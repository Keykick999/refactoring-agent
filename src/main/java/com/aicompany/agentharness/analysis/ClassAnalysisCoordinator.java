package com.aicompany.agentharness.analysis;

import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.harness.Target;
import com.aicompany.agentharness.harness.Task;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmErrorCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Java 파일을 하나씩 AgentRunner로 분석하고, 끝난 결과를 저장한 뒤 하나의 보고서로 모은다.
 *
 * <p>한 클래스의 실패는 그 클래스의 결과로만 남기고 다음 클래스를 계속 분석한다.
 * 클래스마다 AgentState를 새로 만들므로 이전 클래스의 원문은 다음 요청에 들어가지 않는다.
 * 완료된 클래스와 다시 시도하지 않을 실패는 저장된 결과를 그대로 쓴다.
 */
public class ClassAnalysisCoordinator {

	private static final Logger log = LoggerFactory.getLogger(ClassAnalysisCoordinator.class);

	private final JavaSourceExplorer explorer;
	private final Llm llm;
	private final AgentRunner agentRunner;
	private final AnalysisSettings settings;
	private final AnalysisJobStore store;
	private final Path lockDirectory;

	public ClassAnalysisCoordinator(JavaSourceExplorer explorer, Llm llm, AgentRunner agentRunner, AnalysisSettings settings,
			AnalysisJobStore store, Path lockDirectory) {
		this.explorer = Objects.requireNonNull(explorer, "explorer must not be null");
		this.llm = Objects.requireNonNull(llm, "llm must not be null");
		this.agentRunner = Objects.requireNonNull(agentRunner, "agentRunner must not be null");
		this.settings = Objects.requireNonNull(settings, "settings must not be null");
		this.store = Objects.requireNonNull(store, "store must not be null");
		this.lockDirectory = Objects.requireNonNull(lockDirectory, "lockDirectory must not be null");
	}

	public AnalysisReport analyze(Task task) {
		List<DiscoveredJavaSource> sources = explorer.explore(task.target().path()).stream()
				.map(this::normalized)
				.sorted(Comparator.comparing(source -> source.path().toString()))
				.toList();
		if (sources.isEmpty()) {
			log.info("Class analysis finished: no Java source files");
			return new AnalysisReport(List.of());
		}

		AnalysisIdentity identity = AnalysisIdentity.of(task, settings);
		try (JobFileLock ignored = JobFileLock.acquire(lockDirectory, identity.lockName())) {
			AnalysisSession session = store.begin(identity, sources);
			if (!session.hasWork()) {
				AnalysisReport report = session.report();
				log.info("Class analysis reused the stored report: {} targets, {} succeeded, {} failed",
						report.total(), report.succeeded(), report.failed());
				return report;
			}

			Map<Path, DiscoveredJavaSource> byPath = new LinkedHashMap<>();
			for (DiscoveredJavaSource source : sources) {
				byPath.putIfAbsent(source.path(), source);
			}
			boolean stoppedByAuthentication = false;
			for (PendingClass pending : session.pending()) {
				session.markRunning(pending.path());
				ClassAnalysisResult result = analyzeOne(task, pending, byPath.get(pending.path()));
				record(session, pending, result);
				if (result.errorCategory() == LlmErrorCategory.AUTHENTICATION) {
					stoppedByAuthentication = true;
					log.warn("Class analysis stopped after an authentication failure at {}", pending.path());
					break;
				}
			}
			AnalysisReport report = session.finish(stoppedByAuthentication);
			log.info("Class analysis finished: {} targets, {} succeeded, {} failed, job {}",
					report.total(), report.succeeded(), report.failed(), report.jobStatus());
			return report;
		}
	}

	private void record(AnalysisSession session, PendingClass pending, ClassAnalysisResult result) {
		try {
			session.record(result);
		}
		catch (RuntimeException exception) {
			log.warn("Could not save the analysis result for {}", pending.path(), exception);
			session.record(ClassAnalysisResult.failed(pending.className(), pending.path(),
					"Could not save the analysis result: " + exception.getMessage(), LlmErrorCategory.PERSISTENCE));
		}
	}

	private ClassAnalysisResult analyzeOne(Task task, PendingClass pending, DiscoveredJavaSource source) {
		if (source == null) {
			return ClassAnalysisResult.failed(pending.className(), pending.path(),
					"Java source file was not found.", LlmErrorCategory.SOURCE_UNREADABLE);
		}
		String className = ClassCodeQualityAgent.classNameOf(source.path());
		if (!source.readable()) {
			return ClassAnalysisResult.failed(className, source.path(), source.failureReason(), LlmErrorCategory.SOURCE_UNREADABLE);
		}
		if (source.content().length() > settings.maxSourceCharacters()) {
			return ClassAnalysisResult.unsupported(className, source.path(),
					"Source has " + source.content().length() + " characters, which exceeds the limit of "
							+ settings.maxSourceCharacters() + ". The source was not truncated or sent to the LLM.");
		}
		ClassAnalysisCollector collector = new ClassAnalysisCollector();
		try {
			agentRunner.run(new ClassCodeQualityAgent(llm, source, task.goal(), collector),
					new Task(task.goal(), new Target(source.path())));
		}
		catch (RuntimeException exception) {
			log.warn("Class analysis failed unexpectedly for {}", source.path(), exception);
			if (!collector.contains(source.path())) {
				collector.add(ClassAnalysisResult.failed(className, source.path(),
						"Class analysis failed unexpectedly: " + exception.getMessage()));
			}
		}
		if (collector.results().isEmpty()) {
			return ClassAnalysisResult.failed(className, source.path(), "Class analysis produced no result.");
		}
		return collector.results().get(0);
	}

	private DiscoveredJavaSource normalized(DiscoveredJavaSource source) {
		Path path = source.path().toAbsolutePath().normalize();
		if (source.readable()) {
			return DiscoveredJavaSource.readable(path, source.content());
		}
		return DiscoveredJavaSource.unreadable(path, source.failureReason());
	}

}
