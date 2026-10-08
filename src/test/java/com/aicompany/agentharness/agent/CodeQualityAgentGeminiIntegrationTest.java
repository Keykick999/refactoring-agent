package com.aicompany.agentharness.agent;

import com.aicompany.agentharness.harness.Agent;
import com.aicompany.agentharness.harness.AgentResult;
import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.harness.AgentState;
import com.aicompany.agentharness.harness.Target;
import com.aicompany.agentharness.harness.Task;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실제 Gemini API와 실제 Filesystem MCP Server(npx)를 사용한다.
 * 기본 빌드에서는 실행하지 않고 {@code gradlew test -Pllm.integration=true}로 실행하며,
 * GEMINI_API_KEY 환경변수가 필요하다.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "llm.integration", matches = "true")
class CodeQualityAgentGeminiIntegrationTest {

	private static final Path WORKSPACE = createTempDirectory("gemini-workspace");
	private static final String SOURCE_CODE = """
			public class OrderService {
			    public double calc(int a, int b, int c) {
			        double x = 0;
			        if (a > 0) { if (b > 0) { if (c > 0) { x = a * b * c * 1.1; } } }
			        return x;
			    }
			}
			""";

	@Autowired
	private Llm llm;

	@Autowired
	private CodeQualityAgent codeQualityAgent;

	@Autowired
	private AgentRunner agentRunner;

	@DynamicPropertySource
	static void harnessProperties(DynamicPropertyRegistry registry) {
		registry.add("agent-harness.filesystem-mcp-server.enabled", () -> "true");
		registry.add("agent-harness.filesystem-mcp-server.workspace", WORKSPACE::toString);
	}

	@BeforeAll
	static void requireApiKey() {
		String apiKey = System.getenv("GEMINI_API_KEY");
		assumeTrue(apiKey != null && !apiKey.isBlank(), "GEMINI_API_KEY is not set");
	}

	@Test
	void geminiReturnsDecisionThatMatchesRequestedSchema() {
		LlmRequest request = new LlmRequest(List.of(
				Message.system("Decide the next action for a code review task."),
				Message.user("The review is complete. Return FINISH with success=true and summary 'done'.")),
				AgentDecisionParser.RESPONSE_JSON_SCHEMA);

		String content = llm.generate(request).content();

		assertThat(new AgentDecisionParser().parse(content).result().success()).isTrue();
	}

	@Test
	void geminiSelectsMcpToolToReadTargetAndFinishesWithAnalysis() throws IOException {
		Path sourceFile = Files.writeString(WORKSPACE.resolve("OrderService.java"), SOURCE_CODE);
		AtomicReference<AgentState> capturedState = new AtomicReference<>();
		Agent stateCapturingAgent = agentState -> {
			capturedState.set(agentState);
			return codeQualityAgent.decide(agentState);
		};

		AgentResult agentResult = agentRunner.run(stateCapturingAgent,
				new Task("Analyze the code quality of the target file and propose improvements. Do not modify any file.",
						new Target(sourceFile)));

		assertThat(agentResult.success()).as("agent result: %s", agentResult.summary()).isTrue();
		assertThat(agentResult.summary()).isNotBlank();
		assertThat(capturedState.get().toolResults())
				.as("Gemini should have read the target through an MCP tool")
				.anyMatch(toolResult -> toolResult.success() && toolResult.output().contains("class OrderService"));
		assertThat(capturedState.get().analysisResults()).containsExactly(agentResult.summary());
	}

	private static Path createTempDirectory(String prefix) {
		try {
			return Files.createTempDirectory(prefix).toRealPath();
		}
		catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

}
