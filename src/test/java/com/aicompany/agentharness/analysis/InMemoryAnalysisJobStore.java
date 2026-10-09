package com.aicompany.agentharness.analysis;

import com.aicompany.agentharness.llm.LlmErrorCategory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 테스트용 저장소. 데이터베이스 없이 작업 식별과 재개 규칙을 그대로 따른다.
 */
public final class InMemoryAnalysisJobStore implements AnalysisJobStore {

	private final Map<AnalysisIdentity, Job> jobs = new LinkedHashMap<>();

	@Override
	public synchronized AnalysisSession begin(AnalysisIdentity identity, List<DiscoveredJavaSource> sources) {
		Job job = jobs.computeIfAbsent(identity, key -> new Job());
		Map<String, StoredClass> byPath = job.classes;
		for (StoredClass stored : byPath.values()) {
			if (stored.status == ClassProgress.RUNNING) {
				stored.status = ClassProgress.PENDING;
			}
		}
		for (DiscoveredJavaSource source : sources) {
			String filePath = filePath(source.path());
			byPath.computeIfAbsent(filePath, key -> new StoredClass(ClassCodeQualityAgent.classNameOf(source.path()), source.path().toAbsolutePath().normalize()));
		}
		List<PendingClass> pending = byPath.values().stream()
				.filter(stored -> stored.status.shouldAnalyze(stored.category))
				.map(stored -> new PendingClass(stored.className, stored.path))
				.sorted(Comparator.comparing(item -> item.path().toString()))
				.toList();
		if (!pending.isEmpty()) {
			job.status = JobStatus.RUNNING;
		}
		return new Session(job, pending);
	}

	private final class Session implements AnalysisSession {

		private final Job job;
		private final List<PendingClass> pending;

		private Session(Job job, List<PendingClass> pending) {
			this.job = job;
			this.pending = pending;
		}

		@Override
		public boolean hasWork() {
			return !pending.isEmpty();
		}

		@Override
		public List<PendingClass> pending() {
			return pending;
		}

		@Override
		public void markRunning(Path path) {
			synchronized (InMemoryAnalysisJobStore.this) {
				require(path).status = ClassProgress.RUNNING;
			}
		}

		@Override
		public void record(ClassAnalysisResult result) {
			synchronized (InMemoryAnalysisJobStore.this) {
				StoredClass stored = require(result.path());
				stored.status = ClassProgress.of(result.status());
				if (result.status() == AnalysisStatus.SUCCEEDED) {
					stored.issues = List.copyOf(result.issues());
					stored.error = null;
					stored.category = null;
					stored.userAction = null;
					return;
				}
				stored.issues = List.of();
				stored.error = result.error();
				stored.category = result.errorCategory();
				stored.userAction = result.userAction();
			}
		}

		@Override
		public AnalysisReport finish(boolean stoppedByAuthentication) {
			synchronized (InMemoryAnalysisJobStore.this) {
				if (stoppedByAuthentication) {
					job.status = JobStatus.FAILED;
				}
				else if (job.classes.values().stream().allMatch(stored -> stored.status == ClassProgress.SUCCEEDED)) {
					job.status = JobStatus.COMPLETED;
				}
				else {
					job.status = JobStatus.PARTIALLY_COMPLETED;
				}
				return toReport(job);
			}
		}

		@Override
		public AnalysisReport report() {
			synchronized (InMemoryAnalysisJobStore.this) {
				return toReport(job);
			}
		}

		private StoredClass require(Path path) {
			StoredClass stored = job.classes.get(filePath(path));
			if (stored == null) {
				throw new IllegalStateException("Class analysis was not found: " + path);
			}
			return stored;
		}

	}

	private static AnalysisReport toReport(Job job) {
		List<ClassAnalysisResult> results = new ArrayList<>();
		int pending = 0;
		for (StoredClass stored : job.classes.values()) {
			AnalysisStatus status = stored.status.analysisStatus();
			if (status == null) {
				pending++;
				continue;
			}
			results.add(toResult(stored, status));
		}
		results.sort(Comparator.comparing(result -> result.path().toString()));
		return new AnalysisReport(results, job.status, pending);
	}

	private static ClassAnalysisResult toResult(StoredClass stored, AnalysisStatus status) {
		if (status == AnalysisStatus.SUCCEEDED) {
			return ClassAnalysisResult.succeeded(stored.className, stored.path, stored.issues);
		}
		String error = stored.error == null || stored.error.isBlank() ? "Class analysis failed." : stored.error;
		if (status == AnalysisStatus.UNSUPPORTED) {
			return ClassAnalysisResult.unsupported(stored.className, stored.path, error, stored.category);
		}
		return ClassAnalysisResult.failed(stored.className, stored.path, error, stored.category);
	}

	private static String filePath(Path path) {
		return path.toAbsolutePath().normalize().toString();
	}

	private static final class Job {
		private final Map<String, StoredClass> classes = new LinkedHashMap<>();
		private JobStatus status = JobStatus.RUNNING;
	}

	private static final class StoredClass {
		private final String className;
		private final Path path;
		private ClassProgress status = ClassProgress.PENDING;
		private List<CodeIssue> issues = List.of();
		private String error;
		private LlmErrorCategory category;
		private String userAction;

		private StoredClass(String className, Path path) {
			this.className = className;
			this.path = path;
		}
	}

}
