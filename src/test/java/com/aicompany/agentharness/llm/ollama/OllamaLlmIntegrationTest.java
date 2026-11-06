package com.aicompany.agentharness.llm.ollama;

import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로컬 Ollama가 떠 있을 때만 실행한다.
 * {@code gradlew test -Pollama.integration=true}
 */
@EnabledIfSystemProperty(named = "ollama.integration", matches = "true")
class OllamaLlmIntegrationTest {

	@Test
	void generatesTextFromLocalOllama() {
		String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434");
		String model = System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.2");
		OllamaLlm llm = new OllamaLlm(baseUrl, model, Duration.ofMinutes(2));

		LlmResponse response = llm.generate(new LlmRequest(List.of(Message.user("Reply with the single word ok."))));

		assertThat(response.content()).isNotBlank();
	}

}
