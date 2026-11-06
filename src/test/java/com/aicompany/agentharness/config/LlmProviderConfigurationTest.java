package com.aicompany.agentharness.config;

import com.aicompany.agentharness.llm.InMemoryLlmRateLimiter;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRateLimiter;
import com.aicompany.agentharness.llm.ProviderRateLimitLlm;
import com.aicompany.agentharness.llm.RequestBudgetLlm;
import com.aicompany.agentharness.llm.RetryingLlm;
import com.aicompany.agentharness.llm.gemini.GeminiLlm;
import com.aicompany.agentharness.llm.groq.GroqLlm;
import com.aicompany.agentharness.llm.ollama.OllamaLlm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(LlmConfiguration.class)
			.withPropertyValues(
					"llm.gemini.api-key=test-key",
					"llm.gemini.model=gemini-3.5-flash",
					"llm.gemini.url=https://example.test/v1beta",
					"llm.gemini.timeout=5s");

	@Test
	void selectsGeminiByDefault() {
		contextRunner.run(context -> {
			assertThat(context.getBean("llmDelegate")).isInstanceOf(GeminiLlm.class);
			assertThat(context.getBean(Llm.class)).isInstanceOf(RequestBudgetLlm.class);
			assertThat(((RequestBudgetLlm) context.getBean(Llm.class)).delegate()).isInstanceOf(RetryingLlm.class);
			assertThat(context.getBean(Llm.class).nativeToolCalls()).isFalse();
			assertThat(context.getBean("configuredLlmModel", String.class)).isEqualTo("gemini-3.5-flash");
			assertThat(context.getBeansOfType(Llm.class).values()).noneMatch(OllamaLlm.class::isInstance);
		});
	}

	@Test
	void selectsGeminiWhenProviderIsGemini() {
		contextRunner.withPropertyValues("llm.provider=gemini").run(context -> {
			assertThat(context.getBean("llmDelegate")).isInstanceOf(GeminiLlm.class);
			assertThat(context.getBean("configuredLlmModel", String.class)).isEqualTo("gemini-3.5-flash");
		});
	}

	@Test
	void selectsGeminiFromApplicationYmlWhenProviderIsOmitted() {
		new ApplicationContextRunner()
				.withUserConfiguration(LlmConfiguration.class)
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.run(context -> {
					assertThat(context.getBean("llmDelegate")).isInstanceOf(GeminiLlm.class);
					assertThat(context.getBean("configuredLlmModel", String.class)).isEqualTo("gemini-3.5-flash");
				});
	}

	@Test
	void selectsOllamaWhenProviderIsOllama() {
		contextRunner.withPropertyValues(
				"llm.provider=ollama",
				"llm.ollama.base-url=http://127.0.0.1:11434",
				"llm.ollama.model=llama3.2",
				"llm.ollama.timeout=30s").run(context -> {
			assertThat(context.getBean("llmDelegate")).isInstanceOf(OllamaLlm.class);
			assertThat(context.getBean(Llm.class)).isInstanceOf(RequestBudgetLlm.class);
			assertThat(((RequestBudgetLlm) context.getBean(Llm.class)).delegate()).isInstanceOf(RetryingLlm.class);
			assertThat(context.getBean("configuredLlmModel", String.class)).isEqualTo("llama3.2");
			assertThat(context.getBeansOfType(Llm.class).values()).noneMatch(GeminiLlm.class::isInstance);
		});
	}

	@Test
	void selectsGroqWhenProviderIsGroq() {
		contextRunner.withPropertyValues("llm.provider=groq", "llm.groq.api-key=test-groq-key").run(context -> {
			assertThat(context.getBean("llmDelegate")).isInstanceOf(GroqLlm.class);
			RequestBudgetLlm budget = (RequestBudgetLlm) context.getBean(Llm.class);
			assertThat(budget.delegate()).isInstanceOf(ProviderRateLimitLlm.class);
			assertThat(budget.nativeToolCalls()).isTrue();
			assertThat(context.getBean("configuredLlmModel", String.class)).isEqualTo("openai/gpt-oss-20b");
			assertThat(context.getBean(GroqProperties.class).toString()).doesNotContain("test-groq-key").contains("<masked>");
			assertThat(context.getBeansOfType(Llm.class).values()).noneMatch(GeminiLlm.class::isInstance);
		});
	}

	@Test
	void usesOllamaDefaultsWhenOnlyProviderIsSet() {
		contextRunner.withPropertyValues("llm.provider=ollama").run(context -> {
			OllamaProperties ollama = context.getBean(OllamaProperties.class);
			assertThat(ollama.baseUrl()).isEqualTo("http://localhost:11434");
			assertThat(ollama.model()).isEqualTo("llama3.2");
			assertThat(context.getBean("llmDelegate")).isInstanceOf(OllamaLlm.class);
		});
	}

	@Test
	void createsOneGeminiRateLimiterOnlyForGemini() {
		contextRunner.run(context -> {
			assertThat(context.getBeansOfType(LlmRateLimiter.class)).hasSize(1);
			assertThat(context.getBean(LlmRateLimiter.class)).isInstanceOf(InMemoryLlmRateLimiter.class);
			GeminiRateLimitProperties rateLimit = context.getBean(GeminiRateLimitProperties.class);
			assertThat(rateLimit.maxRequests()).isEqualTo(5);
			assertThat(rateLimit.window()).isEqualTo(Duration.ofSeconds(60));
		});
		contextRunner.withPropertyValues("llm.provider=groq", "llm.groq.api-key=test-groq-key")
				.run(context -> assertThat(context.getBeansOfType(LlmRateLimiter.class)).isEmpty());
		contextRunner.withPropertyValues("llm.provider=ollama")
				.run(context -> assertThat(context.getBeansOfType(LlmRateLimiter.class)).isEmpty());
	}

	@Test
	void failsToCreateLlmWhenProviderIsUnknown() {
		contextRunner.withPropertyValues("llm.provider=unknown").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
					.hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class);
		});
	}

	@Test
	void wrapsNeitherProviderInRetryWhenRetryIsDisabled() {
		contextRunner.withPropertyValues("llm.retry.enabled=false").run(context -> {
			RequestBudgetLlm budget = (RequestBudgetLlm) context.getBean(Llm.class);
			assertThat(budget.delegate()).isInstanceOf(GeminiLlm.class);
		});
		contextRunner.withPropertyValues("llm.provider=ollama", "llm.retry.enabled=false").run(context -> {
			RequestBudgetLlm budget = (RequestBudgetLlm) context.getBean(Llm.class);
			assertThat(budget.delegate()).isInstanceOf(OllamaLlm.class);
		});
		contextRunner.withPropertyValues("llm.provider=groq", "llm.retry.enabled=false").run(context -> {
			RequestBudgetLlm budget = (RequestBudgetLlm) context.getBean(Llm.class);
			assertThat(budget.delegate()).isInstanceOf(ProviderRateLimitLlm.class);
			assertThat(context.getBean("llmDelegate")).isInstanceOf(GroqLlm.class);
		});
	}

}
