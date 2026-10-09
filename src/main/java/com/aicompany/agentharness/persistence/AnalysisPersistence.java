package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.analysis.AnalysisIdentity;
import com.aicompany.agentharness.analysis.AnalysisReport;
import com.aicompany.agentharness.analysis.AnalysisStatus;
import com.aicompany.agentharness.analysis.ClassAnalysisResult;
import com.aicompany.agentharness.analysis.ClassProgress;
import com.aicompany.agentharness.analysis.DiscoveredJavaSource;
import com.aicompany.agentharness.analysis.JobStatus;
import com.aicompany.agentharness.analysis.PendingClass;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 결과 저장만 담당한다. 메서드 하나가 짧은 트랜잭션이고, LLM 호출은 여기에 없다.
 */
@Service
public class AnalysisPersistence {

	private final AnalysisJobRepository jobs;

	public AnalysisPersistence(AnalysisJobRepository jobs) {
		this.jobs = jobs;
	}

	@Transactional
	public PreparedAnalysis prepare(AnalysisIdentity identity, List<DiscoveredJavaSource> sources) {
		AnalysisJobEntity job = jobs
				.findByTargetPathAndObjectiveAndSettingsFingerprint(identity.targetPath(), identity.objective(),
						identity.settingsFingerprint())
				.orElseGet(() -> jobs.save(new AnalysisJobEntity(identity.targetPath(), identity.objective(),
						identity.settingsFingerprint(), Instant.now())));

		Map<String, ClassAnalysisEntity> byPath = new LinkedHashMap<>();
		for (ClassAnalysisEntity classAnalysis : job.getClasses()) {
			if (classAnalysis.getStatus() == ClassProgress.RUNNING) {
				classAnalysis.markPending();
			}
			byPath.put(classAnalysis.getFilePath(), classAnalysis);
		}
		for (DiscoveredJavaSource source : deduplicated(sources)) {
			String filePath = filePath(source.path());
			if (!byPath.containsKey(filePath)) {
				ClassAnalysisEntity classAnalysis = new ClassAnalysisEntity(className(source.path()), filePath);
				job.addClass(classAnalysis);
				byPath.put(filePath, classAnalysis);
			}
		}

		List<PendingClass> pending = byPath.values().stream()
				.filter(classAnalysis -> classAnalysis.getStatus().shouldAnalyze(classAnalysis.getErrorCategory()))
				.map(classAnalysis -> new PendingClass(classAnalysis.getClassName(), Path.of(classAnalysis.getFilePath())))
				.sorted(Comparator.comparing(pendingClass -> pendingClass.path().toString()))
				.toList();
		if (!pending.isEmpty()) {
			job.setStatus(JobStatus.RUNNING);
		}
		jobs.saveAndFlush(job);
		return new PreparedAnalysis(job.getId(), pending);
	}

	@Transactional
	public void markRunning(long jobId, String filePath) {
		requireClass(jobId, filePath).markRunning();
	}

	@Transactional
	public void saveResult(long jobId, ClassAnalysisResult result) {
		ClassAnalysisEntity classAnalysis = requireClass(jobId, filePath(result.path()));
		if (result.status() == AnalysisStatus.SUCCEEDED) {
			classAnalysis.replace(ClassProgress.SUCCEEDED, null, null, null,
					result.issues().stream().map(PersistedCodeIssue::new).toList());
			return;
		}
		classAnalysis.replace(ClassProgress.of(result.status()), result.errorCategory(), result.error(), result.userAction(), List.of());
	}

	@Transactional
	public AnalysisReport finish(long jobId, boolean stoppedByAuthentication) {
		AnalysisJobEntity job = requireJob(jobId);
		if (stoppedByAuthentication) {
			job.setStatus(JobStatus.FAILED);
		}
		else if (job.getClasses().stream().allMatch(classAnalysis -> classAnalysis.getStatus() == ClassProgress.SUCCEEDED)) {
			job.setStatus(JobStatus.COMPLETED);
		}
		else {
			job.setStatus(JobStatus.PARTIALLY_COMPLETED);
		}
		return toReport(job);
	}

	@Transactional(readOnly = true)
	public AnalysisReport report(long jobId) {
		return toReport(requireJob(jobId));
	}

	private AnalysisJobEntity requireJob(long jobId) {
		return jobs.findById(jobId).orElseThrow(() -> new IllegalStateException("Analysis job was not found: " + jobId));
	}

	private ClassAnalysisEntity requireClass(long jobId, String filePath) {
		return requireJob(jobId).getClasses().stream()
				.filter(classAnalysis -> classAnalysis.getFilePath().equals(filePath))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("Class analysis was not found: " + filePath));
	}

	private AnalysisReport toReport(AnalysisJobEntity job) {
		List<ClassAnalysisResult> results = new ArrayList<>();
		int pending = 0;
		for (ClassAnalysisEntity classAnalysis : job.getClasses()) {
			classAnalysis.getIssues().size();
			AnalysisStatus status = classAnalysis.getStatus().analysisStatus();
			if (status == null) {
				pending++;
				continue;
			}
			results.add(toResult(classAnalysis, status));
		}
		results.sort(Comparator.comparing(result -> result.path().toString()));
		return new AnalysisReport(results, job.getStatus(), pending);
	}

	private ClassAnalysisResult toResult(ClassAnalysisEntity classAnalysis, AnalysisStatus status) {
		Path path = Path.of(classAnalysis.getFilePath());
		if (status == AnalysisStatus.SUCCEEDED) {
			return ClassAnalysisResult.succeeded(classAnalysis.getClassName(), path,
					classAnalysis.getIssues().stream().map(PersistedCodeIssue::toIssue).toList());
		}
		String error = classAnalysis.getErrorMessage() == null || classAnalysis.getErrorMessage().isBlank()
				? "Class analysis failed."
				: classAnalysis.getErrorMessage();
		if (status == AnalysisStatus.UNSUPPORTED) {
			return ClassAnalysisResult.unsupported(classAnalysis.getClassName(), path, error, classAnalysis.getErrorCategory());
		}
		return ClassAnalysisResult.failed(classAnalysis.getClassName(), path, error, classAnalysis.getErrorCategory());
	}

	private static List<DiscoveredJavaSource> deduplicated(List<DiscoveredJavaSource> sources) {
		Map<String, DiscoveredJavaSource> byPath = new LinkedHashMap<>();
		for (DiscoveredJavaSource source : sources) {
			byPath.putIfAbsent(filePath(source.path()), source);
		}
		return List.copyOf(byPath.values());
	}

	private static String filePath(Path path) {
		return path.toAbsolutePath().normalize().toString();
	}

	private static String className(Path path) {
		String fileName = path.getFileName().toString();
		return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - ".java".length()) : fileName;
	}

}
