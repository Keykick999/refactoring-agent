package com.aicompany.agentharness.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.aicompany.agentharness.tool.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class RequestBudgetLlmTest {

	@Test
	void callsProviderWhenEstimatedTokensAreWithinTheBudget() {
		AtomicInteger calls = new AtomicInteger();
		Llm provider = request -> {
			calls.incrementAndGet();
			return new LlmResponse("ok");
		};
		Llm budget = new RequestBudgetLlm(provider, text -> 1, 6000);

		assertThat(budget.generate(new LlmRequest(List.of(Message.user("Review this.")))).content()).isEqualTo("ok");
		assertThat(calls).hasValue(1);
	}

	@Test
	void doesNotCallProviderWhenEstimatedTokensExceedTheBudget() {
		AtomicInteger calls = new AtomicInteger();
		Llm provider = request -> {
			calls.incrementAndGet();
			return new LlmResponse("ok");
		};
		Llm budget = new RequestBudgetLlm(provider, text -> 4000, 6000);

		assertThatThrownBy(() -> budget.generate(new LlmRequest(List.of(Message.user("Review this.")))))
				.isInstanceOf(LlmException.class)
				.isNotInstanceOf(RetryableLlmException.class)
				.hasMessageContaining("was not sent")
				.extracting(thrown -> ((LlmException) thrown).category())
				.isEqualTo(LlmErrorCategory.REQUEST_BUDGET);
		assertThat(calls).hasValue(0);
	}

	@Test
	void budgetExceededIsNotRetried() {
		AtomicInteger calls = new AtomicInteger();
		List<Duration> sleeps = new ArrayList<>();
		Llm provider = request -> {
			calls.incrementAndGet();
			return new LlmResponse("ok");
		};
		Llm retrying = new RetryingLlm(provider, new LlmRetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0),
				sleeps::add);
		Llm budget = new RequestBudgetLlm(retrying, text -> 4000, 6000);

		assertThatThrownBy(() -> budget.generate(new LlmRequest(List.of(Message.user("Review this.")))))
				.isInstanceOf(LlmException.class)
				.isNotInstanceOf(RetryableLlmException.class);
		assertThat(calls).hasValue(0);
		assertThat(sleeps).isEmpty();
	}

	@Test
	void logsTheContextOfARejectedRequest(CapturedOutput output) {
		Llm budget = new RequestBudgetLlm(request -> new LlmResponse("ok"), text -> 4000, 6000);
		ToolCall toolCall = new ToolCall("call-1", "directory_tree", Map.of("path", "."));
		LlmRequest request = new LlmRequest(List.of(Message.system("Solve one task."),
				Message.assistantToolCall(toolCall), Message.toolResult("call-1", "x".repeat(5000))), "{\"type\":\"object\"}");

		Logger logger = (Logger) LoggerFactory.getLogger(LlmContextLogger.class);
		Level previous = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		try {
			assertThatThrownBy(() -> budget.generate(request)).isInstanceOf(LlmException.class);
		}
		finally {
			logger.setLevel(previous);
		}

		assertThat(output).contains("LLM request context: 3 messages, estimated 28000 tokens (max 6000)")
				.contains("response schema: 4000 tokens")
				.contains("[0] SYSTEM: 8000 tokens, 15 chars")
				.contains("| Solve one task.")
				.contains("tool call: directory_tree {path=.}")
				.contains("[2] TOOL: 8000 tokens, 5000 chars, toolCallId=call-1")
				.contains("... (5000 chars)");
	}

}
