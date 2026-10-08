package com.aicompany.agentharness.config;

import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmException;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.llm.RetryingLlm;
import com.aicompany.agentharness.llm.gemini.FakeGeminiServer;
import com.aicompany.agentharness.llm.gemini.GeminiLlm;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

import static com.aicompany.agentharness.llm.gemini.FakeGeminiServer.error;
import static com.aicompany.agentharness.llm.gemini.FakeGeminiServer.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRetryConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	void bindsRetrySettingsFromApplicationYml() {
		contextRunner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
			LlmRetryProperties retry = context.getBean(LlmRetryProperties.class);

			assertThat(retry.enabled()).isTrue();
			assertThat(retry.maxAttempts()).isEqualTo(3);
			assertThat(retry.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
			assertThat(retry.multiplier()).isEqualTo(2.0);
			assertThat(retry.maxBackoff()).isEqualTo(Duration.ofSeconds(10));
		});
	}

	@Test
	void bindsCustomRetrySettings() {
		contextRunner.withPropertyValues(
				"llm.retry.enabled=false",
				"llm.retry.max-attempts=5",
				"llm.retry.initial-backoff=500ms",
				"llm.retry.multiplier=3.0",
				"llm.retry.max-backoff=30s",
				"llm.retry.jitter=0").run(context -> {
			LlmRetryProperties retry = context.getBean(LlmRetryProperties.class);

			assertThat(retry).isEqualTo(new LlmRetryProperties(false, 5, Duration.ofMillis(500), 3.0, Duration.ofSeconds(30), 0));
		});
	}

	@Test
	void usesDefaultRetrySettingsWhenNotConfigured() {
		contextRunner.run(context -> assertThat(context.getBean(LlmRetryProperties.class))
				.isEqualTo(new LlmRetryProperties(true, 3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0.1)));
	}

	@Test
	void failsImmediatelyOnRateLimitWhenRetryIsDisabled() {
		try (FakeGeminiServer server = FakeGeminiServer.start(error(429, "quota exceeded"), text("ok"))) {
			LlmRetryProperties disabled = new LlmRetryProperties(false, 3, Duration.ofMillis(1), 2.0, Duration.ofMillis(1), 0);

			Llm llm = new AgentHarnessConfiguration().llm(geminiProperties(server), disabled);

			assertThat(llm).isInstanceOf(GeminiLlm.class);
			assertThatThrownBy(() -> llm.generate(new LlmRequest(List.of(Message.user("Hi")))))
					.isInstanceOf(LlmException.class)
					.hasMessageContaining("HTTP 429");
			assertThat(server.requestCount()).isEqualTo(1);
		}
	}

	@Test
	void retriesThroughConfiguredLlmWhenRetryIsEnabled() {
		try (FakeGeminiServer server = FakeGeminiServer.start(error(429, "quota exceeded"), text("ok"))) {
			LlmRetryProperties enabled = new LlmRetryProperties(true, 3, Duration.ofMillis(1), 2.0, Duration.ofMillis(5), 0);

			Llm llm = new AgentHarnessConfiguration().llm(geminiProperties(server), enabled);

			assertThat(llm).isInstanceOf(RetryingLlm.class);
			assertThat(llm.generate(new LlmRequest(List.of(Message.user("Hi")))).content()).isEqualTo("ok");
			assertThat(server.requestCount()).isEqualTo(2);
		}
	}

	private static GeminiProperties geminiProperties(FakeGeminiServer server) {
		return new GeminiProperties("test-api-key", "test-model", server.baseUrl(), Duration.ofSeconds(5));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(LlmRetryProperties.class)
	static class PropertiesConfiguration {
	}

}
