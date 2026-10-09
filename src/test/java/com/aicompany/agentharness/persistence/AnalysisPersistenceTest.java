package com.aicompany.agentharness.persistence;

import com.aicompany.agentharness.analysis.AnalysisIdentity;
import com.aicompany.agentharness.analysis.AnalysisReport;
import com.aicompany.agentharness.analysis.AnalysisSettings;
import com.aicompany.agentharness.analysis.AnalysisStatus;
import com.aicompany.agentharness.analysis.ClassAnalysisResult;
import com.aicompany.agentharness.analysis.ClassProgress;
import com.aicompany.agentharness.analysis.CodeIssue;
import com.aicompany.agentharness.analysis.DiscoveredJavaSource;
import com.aicompany.agentharness.analysis.JobStatus;
import com.aicompany.agentharness.analysis.Severity;
import com.aicompany.agentharness.harness.Target;
import com.aicompany.agentharness.harness.Task;
import com.aicompany.agentharness.llm.LlmErrorCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(AnalysisPersistence.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisPersistenceTest {

	private static final Path ORDER = Path.of("order/OrderService.java").toAbsolutePath();
	private static final Path PAYMENT = Path.of("payment/PaymentService.java").toAbsolutePath();
	private static final Path LAST = Path.of("zzz/LastService.java").toAbsolutePath();
	private static final AnalysisSettings SETTINGS = new AnalysisSettings(1000, "test-model", "search_files", "read_text_file");

	@Autowired
	private AnalysisPersistence persistence;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private AnalysisJobRepository jobs;

	@Test
	void commitsClassStatusAndIssuesInOneTransaction() {
		long jobId = persistence.prepare(identity("commit status and issues"), List.of(readable(ORDER))).jobId();
		persistence.markRunning(jobId, ORDER.toString());

		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			persistence.saveResult(jobId, ClassAnalysisResult.succeeded(className(ORDER), ORDER, List.of(issue("Rolled back"))));
			throw new IllegalStateException("force rollback");
		})).hasMessage("force rollback");

		StoredClass stored = load(jobId, ORDER);
		assertThat(stored.status()).isEqualTo(ClassProgress.RUNNING);
		assertThat(stored.issueTitles()).isEmpty();

		persistence.saveResult(jobId, ClassAnalysisResult.succeeded(className(ORDER), ORDER, List.of(issue("Kept"))));
		StoredClass committed = load(jobId, ORDER);
		assertThat(committed.status()).isEqualTo(ClassProgress.SUCCEEDED);
		assertThat(committed.issueTitles()).containsExactly("Kept");
	}

	@Test
	void keepsACommittedClassWhenTheNextClassFails() {
		long jobId = persistence.prepare(identity("keep a committed class"), List.of(readable(ORDER), readable(PAYMENT))).jobId();
		persistence.saveResult(jobId, ClassAnalysisResult.succeeded(className(ORDER), ORDER, List.of(issue("Kept"))));

		persistence.saveResult(jobId, ClassAnalysisResult.failed(className(PAYMENT), PAYMENT, "temporary network failure", LlmErrorCategory.NETWORK));

		AnalysisReport report = persistence.report(jobId);
		assertThat(report.results()).extracting(ClassAnalysisResult::className).containsExactly("OrderService", "PaymentService");
		assertThat(report.results().get(0).status()).isEqualTo(AnalysisStatus.SUCCEEDED);
		assertThat(report.results().get(0).issues()).singleElement().extracting(CodeIssue::title).isEqualTo("Kept");
		assertThat(report.results().get(1).errorCategory()).isEqualTo(LlmErrorCategory.NETWORK);
	}

	@Test
	void resumesRunningAndRetryableClassesOnly() {
		AnalysisIdentity identity = identity("resume running and retryable");
		List<DiscoveredJavaSource> sources = List.of(readable(ORDER), readable(PAYMENT), readable(LAST));
		long jobId = persistence.prepare(identity, sources).jobId();
		persistence.markRunning(jobId, ORDER.toString());
		persistence.saveResult(jobId, ClassAnalysisResult.failed(className(PAYMENT), PAYMENT, "HTTP 429", LlmErrorCategory.RATE_LIMIT));
		persistence.saveResult(jobId, ClassAnalysisResult.unsupported(className(LAST), LAST, "too large"));

		PreparedAnalysis resumed = persistence.prepare(identity, sources);

		assertThat(resumed.jobId()).isEqualTo(jobId);
		assertThat(resumed.pending()).extracting(pending -> pending.path().toString())
				.containsExactly(ORDER.toString(), PAYMENT.toString());
		assertThat(load(jobId, ORDER).status()).isEqualTo(ClassProgress.PENDING);
	}

	@Test
	void usesADifferentJobWhenTheObjectiveOrSettingsChange() {
		List<DiscoveredJavaSource> sources = List.of(readable(ORDER));
		long before = jobs.count();
		long original = persistence.prepare(identity("identity original"), sources).jobId();
		long otherObjective = persistence.prepare(identity("identity other objective"), sources).jobId();
		long otherSettings = persistence.prepare(
				AnalysisIdentity.of(new Task("identity original", new Target(Path.of("src").toAbsolutePath())),
						new AnalysisSettings(1000, "other-model", "search_files", "read_text_file")),
				sources).jobId();

		assertThat(otherObjective).isNotEqualTo(original);
		assertThat(otherSettings).isNotEqualTo(original);
		assertThat(jobs.count()).isEqualTo(before + 3);
	}

	@Test
	void finishesCompletedAndPartialJobsFromStoredClasses() {
		long completed = persistence.prepare(identity("complete"), List.of(readable(ORDER))).jobId();
		persistence.saveResult(completed, ClassAnalysisResult.succeeded(className(ORDER), ORDER, List.of()));
		assertThat(persistence.finish(completed, false).jobStatus()).isEqualTo(JobStatus.COMPLETED);

		long partial = persistence.prepare(identity("partial"), List.of(readable(ORDER), readable(PAYMENT))).jobId();
		persistence.saveResult(partial, ClassAnalysisResult.succeeded(className(ORDER), ORDER, List.of()));
		persistence.saveResult(partial, ClassAnalysisResult.failed(className(PAYMENT), PAYMENT, "invalid", LlmErrorCategory.INVALID_RESPONSE));
		AnalysisReport report = persistence.finish(partial, false);
		assertThat(report.jobStatus()).isEqualTo(JobStatus.PARTIALLY_COMPLETED);

		long stopped = persistence.prepare(identity("stopped"), List.of(readable(ORDER), readable(PAYMENT))).jobId();
		persistence.saveResult(stopped, ClassAnalysisResult.succeeded(className(ORDER), ORDER, List.of()));
		persistence.saveResult(stopped, ClassAnalysisResult.failed(className(PAYMENT), PAYMENT, "missing key", LlmErrorCategory.AUTHENTICATION));
		AnalysisReport failed = persistence.finish(stopped, true);
		assertThat(failed.jobStatus()).isEqualTo(JobStatus.FAILED);
		assertThat(failed.succeeded()).isEqualTo(1);
	}

	private StoredClass load(long jobId, Path path) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			ClassAnalysisEntity classAnalysis = jobs.findById(jobId).orElseThrow().getClasses().stream()
					.filter(candidate -> candidate.getFilePath().equals(path.toString()))
					.findFirst()
					.orElseThrow();
			List<String> titles = classAnalysis.getIssues().stream().map(issue -> issue.toIssue().title()).toList();
			return new StoredClass(classAnalysis.getStatus(), titles);
		});
	}

	private record StoredClass(ClassProgress status, List<String> issueTitles) {
	}

	private static AnalysisIdentity identity(String objective) {
		return AnalysisIdentity.of(new Task(objective, new Target(Path.of("src").toAbsolutePath())), SETTINGS);
	}

	private static DiscoveredJavaSource readable(Path path) {
		return DiscoveredJavaSource.readable(path, "class " + className(path) + " {}");
	}

	private static String className(Path path) {
		String fileName = path.getFileName().toString();
		return fileName.substring(0, fileName.length() - ".java".length());
	}

	private static CodeIssue issue(String title) {
		return new CodeIssue(title, "description", "evidence", "impact", "recommendation", Severity.LOW);
	}

}
