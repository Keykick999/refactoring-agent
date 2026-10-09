package com.aicompany.agentharness.analysis;

import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.harness.Target;
import com.aicompany.agentharness.harness.Task;
import com.aicompany.agentharness.llm.LlmErrorCategory;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClassAnalysisRecoveryTest {

	private static final Path ORDER = Path.of("order/OrderService.java").toAbsolutePath();
	private static final Path PAYMENT = Path.of("payment/PaymentService.java").toAbsolutePath();
	private static final Path LAST = Path.of("zzz/LastService.java").toAbsolutePath();
	private static final String NO_ISSUES = "{\"issues\": []}";

	private final List<String> prompts = new ArrayList<>();
	private final InMemoryAnalysisJobStore store = new InMemoryAnalysisJobStore();

	@TempDir
	Path lockDirectory;

	@Test
	void reusesCompletedReportWithoutCallingTheLlmAgain() {
		List<DiscoveredJavaSource> sources = List.of(readable(ORDER, "class OrderService {}"));
		ClassAnalysisCoordinator coordinator = coordinator(request -> new LlmResponse(issue("OrderService")), sources);

		AnalysisReport first = coordinator.analyze(task());
		AnalysisReport second = coordinator.analyze(task());

		assertThat(first.jobStatus()).isEqualTo(JobStatus.COMPLETED);
		assertThat(second.jobStatus()).isEqualTo(JobStatus.COMPLETED);
		assertThat(second.results()).singleElement().extracting(result -> result.issues().get(0).title()).isEqualTo("Issue in OrderService");
		assertThat(prompts).hasSize(1);
	}

	@Test
	void resumesOnlyRetryableFailuresAndKeepsSavedResults() {
		List<DiscoveredJavaSource> sources = List.of(
				readable(ORDER, "class OrderService {}"),
				readable(PAYMENT, "class PaymentService {}"),
				DiscoveredJavaSource.unreadable(LAST, "cannot read file"));
		AtomicBoolean failOrder = new AtomicBoolean(true);
		ClassAnalysisCoordinator coordinator = coordinator(request -> {
			if (failOrder.get() && request.messages().get(1).content().contains("OrderService")) {
				throw new LlmException("temporary network failure", LlmErrorCategory.NETWORK);
			}
			if (request.messages().get(1).content().contains("PaymentService")) {
				return new LlmResponse("not json");
			}
			return new LlmResponse(NO_ISSUES);
		}, sources);

		AnalysisReport first = coordinator.analyze(task());
		assertThat(first.jobStatus()).isEqualTo(JobStatus.PARTIALLY_COMPLETED);
		assertThat(result(first, "OrderService").errorCategory()).isEqualTo(LlmErrorCategory.NETWORK);
		assertThat(result(first, "PaymentService").errorCategory()).isEqualTo(LlmErrorCategory.INVALID_RESPONSE);
		assertThat(result(first, "LastService").errorCategory()).isEqualTo(LlmErrorCategory.SOURCE_UNREADABLE);

		failOrder.set(false);
		prompts.clear();
		AnalysisReport second = coordinator.analyze(task());

		assertThat(prompts).singleElement().asString().contains("OrderService");
		assertThat(result(second, "OrderService").status()).isEqualTo(AnalysisStatus.SUCCEEDED);
		assertThat(result(second, "PaymentService").errorCategory()).isEqualTo(LlmErrorCategory.INVALID_RESPONSE);
		assertThat(result(second, "LastService").errorCategory()).isEqualTo(LlmErrorCategory.SOURCE_UNREADABLE);
		assertThat(second.jobStatus()).isEqualTo(JobStatus.PARTIALLY_COMPLETED);
	}

	@Test
	void doesNotRetryUnsupportedClasses() {
		String oversized = "a".repeat(11);
		ClassAnalysisCoordinator coordinator = coordinator(request -> {
			throw new AssertionError("LLM must not be called");
		}, List.of(readable(ORDER, oversized)), settings(10));

		AnalysisReport first = coordinator.analyze(task());
		AnalysisReport second = coordinator.analyze(task());

		assertThat(first.results()).singleElement().extracting(ClassAnalysisResult::status).isEqualTo(AnalysisStatus.UNSUPPORTED);
		assertThat(second.results()).singleElement().extracting(ClassAnalysisResult::errorCategory).isEqualTo(LlmErrorCategory.SOURCE_TOO_LARGE);
		assertThat(second.jobStatus()).isEqualTo(JobStatus.PARTIALLY_COMPLETED);
	}

	@Test
	void stopsAfterAuthenticationAndResumesTheRest() {
		List<DiscoveredJavaSource> sources = List.of(
				readable(ORDER, "class OrderService {}"),
				readable(PAYMENT, "class PaymentService {}"),
				readable(LAST, "class LastService {}"));
		AtomicBoolean rejectAuthentication = new AtomicBoolean(true);
		ClassAnalysisCoordinator coordinator = coordinator(request -> {
			if (rejectAuthentication.get() && request.messages().get(1).content().contains("PaymentService")) {
				throw new LlmException("Gemini API key is not configured", LlmErrorCategory.AUTHENTICATION);
			}
			return new LlmResponse(NO_ISSUES);
		}, sources);

		AnalysisReport stopped = coordinator.analyze(task());

		assertThat(stopped.jobStatus()).isEqualTo(JobStatus.FAILED);
		assertThat(stopped.succeeded()).isEqualTo(1);
		assertThat(stopped.pending()).isEqualTo(1);
		assertThat(result(stopped, "OrderService").status()).isEqualTo(AnalysisStatus.SUCCEEDED);
		assertThat(result(stopped, "PaymentService").errorCategory()).isEqualTo(LlmErrorCategory.AUTHENTICATION);
		assertThat(prompts).extracting(prompt -> prompt.contains("LastService")).doesNotContain(true);

		rejectAuthentication.set(false);
		prompts.clear();
		AnalysisReport resumed = coordinator.analyze(task());

		assertThat(prompts).hasSize(2);
		assertThat(prompts).noneMatch(prompt -> prompt.contains("class OrderService"));
		assertThat(resumed.succeeded()).isEqualTo(3);
		assertThat(resumed.jobStatus()).isEqualTo(JobStatus.COMPLETED);
		assertThat(resumed.pending()).isZero();
	}

	@Test
	void reanalyzesAClassLeftRunningByACrash() {
		AtomicBoolean crash = new AtomicBoolean(true);
		ClassAnalysisCoordinator coordinator = coordinator(request -> {
			if (crash.get()) {
				throw new Crash("analysis process stopped");
			}
			return new LlmResponse(NO_ISSUES);
		}, List.of(readable(ORDER, "class OrderService {}")));

		assertThatThrownBy(() -> coordinator.analyze(task())).isInstanceOf(Crash.class);

		crash.set(false);
		AnalysisReport resumed = coordinator.analyze(task());

		assertThat(prompts).hasSize(2);
		assertThat(resumed.jobStatus()).isEqualTo(JobStatus.COMPLETED);
		assertThat(resumed.succeeded()).isEqualTo(1);
	}

	@Test
	void keepsEarlierClassWhenALaterSaveFailsAndRetriesTheUnsavedClass() {
		AnalysisJobStore flakyStore = new SaveOnceThenFailStore(store);
		ClassAnalysisCoordinator coordinator = coordinator(request -> new LlmResponse(issue("OrderService")),
				List.of(readable(ORDER, "class OrderService {}"), readable(PAYMENT, "class PaymentService {}")),
				settings(1000), flakyStore);

		AnalysisReport first = coordinator.analyze(task());

		assertThat(result(first, "OrderService").status()).isEqualTo(AnalysisStatus.SUCCEEDED);
		assertThat(result(first, "OrderService").issues()).singleElement().extracting(CodeIssue::title).isEqualTo("Issue in OrderService");
		assertThat(result(first, "PaymentService").errorCategory()).isEqualTo(LlmErrorCategory.PERSISTENCE);
		assertThat(first.jobStatus()).isEqualTo(JobStatus.PARTIALLY_COMPLETED);

		prompts.clear();
		AnalysisReport second = coordinator.analyze(task());

		assertThat(prompts).singleElement().asString().contains("PaymentService");
		assertThat(result(second, "OrderService").issues()).singleElement().extracting(CodeIssue::title).isEqualTo("Issue in OrderService");
		assertThat(result(second, "PaymentService").status()).isEqualTo(AnalysisStatus.SUCCEEDED);
		assertThat(second.jobStatus()).isEqualTo(JobStatus.COMPLETED);
	}

	@Test
	void startsANewJobWhenObjectiveOrSettingsChange() {
		List<DiscoveredJavaSource> sources = List.of(readable(ORDER, "class OrderService {}"));
		ClassAnalysisCoordinator coordinator = coordinator(request -> new LlmResponse(NO_ISSUES), sources);
		coordinator.analyze(task());

		coordinator(request -> new LlmResponse(NO_ISSUES), sources).analyze(new Task("Review names only", new Target(ORDER)));
		coordinator(request -> new LlmResponse(NO_ISSUES), sources, new AnalysisSettings(1000, "other-model", "search_files", "read_text_file"))
				.analyze(task());

		assertThat(prompts).hasSize(3);
	}

	@Test
	void doesNotStartAnalysisWhenTheJobLockIsAlreadyHeld() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ClassAnalysisCoordinator running = coordinator(request -> {
			entered.countDown();
			await(release);
			return new LlmResponse(NO_ISSUES);
		}, List.of(readable(ORDER, "class OrderService {}")));
		ClassAnalysisCoordinator duplicate = coordinator(request -> {
			throw new AssertionError("duplicate run must not call the LLM");
		}, List.of(readable(ORDER, "class OrderService {}")));

		Thread worker = new Thread(() -> running.analyze(task()));
		worker.start();
		try {
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> duplicate.analyze(task())).isInstanceOf(JobAlreadyRunningException.class);
		}
		finally {
			release.countDown();
			worker.join(5000);
		}
		assertThat(worker.isAlive()).isFalse();
		assertThat(prompts).hasSize(1);
	}

	private ClassAnalysisCoordinator coordinator(Function<LlmRequest, LlmResponse> responses, List<DiscoveredJavaSource> sources) {
		return coordinator(responses, sources, settings(1000), store);
	}

	private ClassAnalysisCoordinator coordinator(Function<LlmRequest, LlmResponse> responses, List<DiscoveredJavaSource> sources,
			AnalysisSettings settings) {
		return coordinator(responses, sources, settings, store);
	}

	private ClassAnalysisCoordinator coordinator(Function<LlmRequest, LlmResponse> responses, List<DiscoveredJavaSource> sources,
			AnalysisSettings settings, AnalysisJobStore jobStore) {
		return new ClassAnalysisCoordinator(target -> sources, request -> {
			prompts.add(request.messages().get(1).content());
			return responses.apply(request);
		}, new AgentRunner(new ToolRegistry(), new ToolPermissionPolicy(List.of(), List.of()), 5), settings, jobStore, lockDirectory);
	}

	private static AnalysisSettings settings(int maxSourceCharacters) {
		return new AnalysisSettings(maxSourceCharacters, "test-model", "search_files", "read_text_file");
	}

	private static Task task() {
		return new Task("Analyze code quality", new Target(Path.of("src").toAbsolutePath()));
	}

	private static DiscoveredJavaSource readable(Path path, String content) {
		return DiscoveredJavaSource.readable(path, content);
	}

	private static ClassAnalysisResult result(AnalysisReport report, String className) {
		return report.results().stream()
				.filter(result -> result.className().equals(className))
				.findFirst()
				.orElseThrow();
	}

	private static String issue(String className) {
		return """
				{"issues": [{
				  "title": "Issue in %s",
				  "description": "Nested conditions are hard to read.",
				  "evidence": "if (a > 0)",
				  "impact": "The method is hard to test.",
				  "recommendation": "Extract a method.",
				  "severity": "MEDIUM"
				}]}
				""".formatted(className);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting for the other analysis");
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted", exception);
		}
	}

	private static final class Crash extends Error {
		private Crash(String message) {
			super(message);
		}
	}

	/**
	 * 첫 성공 저장은 커밋하고, 그 다음 성공 저장만 실패시킨다.
	 */
	private static final class SaveOnceThenFailStore implements AnalysisJobStore {

		private final AnalysisJobStore delegate;
		private int successes;

		private SaveOnceThenFailStore(AnalysisJobStore delegate) {
			this.delegate = delegate;
		}

		@Override
		public AnalysisSession begin(AnalysisIdentity identity, List<DiscoveredJavaSource> sources) {
			AnalysisSession session = delegate.begin(identity, sources);
			return new AnalysisSession() {
				@Override
				public boolean hasWork() {
					return session.hasWork();
				}

				@Override
				public List<PendingClass> pending() {
					return session.pending();
				}

				@Override
				public void markRunning(Path path) {
					session.markRunning(path);
				}

				@Override
				public void record(ClassAnalysisResult result) {
					if (result.status() == AnalysisStatus.SUCCEEDED && ++successes == 2) {
						throw new IllegalStateException("database unavailable");
					}
					session.record(result);
				}

				@Override
				public AnalysisReport finish(boolean stoppedByAuthentication) {
					return session.finish(stoppedByAuthentication);
				}

				@Override
				public AnalysisReport report() {
					return session.report();
				}
			};
		}
	}

}
