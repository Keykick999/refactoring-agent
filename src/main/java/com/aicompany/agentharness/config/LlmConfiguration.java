package com.aicompany.agentharness.config;

import com.aicompany.agentharness.llm.InMemoryLlmRateLimiter;
import com.aicompany.agentharness.llm.TiktokenTokenEstimator;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRateLimiter;
import com.aicompany.agentharness.llm.ProviderRateLimitLlm;
import com.aicompany.agentharness.llm.ProviderRateLimitStore;
import com.aicompany.agentharness.llm.RequestBudgetLlm;
import com.aicompany.agentharness.llm.RetrySleeper;
import com.aicompany.agentharness.llm.RetryingLlm;
import com.aicompany.agentharness.llm.TokenEstimator;
import com.aicompany.agentharness.llm.gemini.GeminiLlm;
import com.aicompany.agentharness.llm.groq.GroqLlm;
import com.aicompany.agentharness.llm.ollama.OllamaLlm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * LLM Provider를 설정으로 고르고, 공통 Retry 데코레이터로 감싼다.
 * Agent/Plan 계층은 이 구성이 제공하는 {@link Llm}만 사용한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GeminiProperties.class, GeminiRateLimitProperties.class, GroqProperties.class,
		OllamaProperties.class, LlmRetryProperties.class, RequestTokenBudgetProperties.class})
public class LlmConfiguration {

	private static final Logger log = LoggerFactory.getLogger(LlmConfiguration.class);

	@Bean
	@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini", matchIfMissing = true)
	public LlmRateLimiter geminiRateLimiter(GeminiRateLimitProperties rateLimit) {
		log.info("Gemini rate limit: {} requests per {}", rateLimit.maxRequests(), rateLimit.window());
		return new InMemoryLlmRateLimiter("Gemini", rateLimit.maxRequests(), rateLimit.window(), Clock.systemUTC(),
				RetrySleeper.THREAD_SLEEP);
	}

	@Bean(name = "llmDelegate")
	@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini", matchIfMissing = true)
	public Llm geminiLlm(GeminiProperties geminiProperties, LlmRateLimiter geminiRateLimiter) {
		log.info("Using Gemini LLM: {}", geminiProperties);
		return new GeminiLlm(geminiProperties.url(), geminiProperties.model(), geminiProperties.apiKey(), geminiProperties.timeout(),
				geminiRateLimiter);
	}

	@Bean(name = "llmDelegate")
	@ConditionalOnProperty(name = "llm.provider", havingValue = "groq")
	public Llm groqLlm(GroqProperties groqProperties, ProviderRateLimitStore rateLimits) {
		log.info("Using Groq LLM: {}", groqProperties);
		return new GroqLlm(groqProperties.url(), groqProperties.model(), groqProperties.apiKey(), groqProperties.timeout(),
				rateLimits, Clock.systemUTC());
	}

	@Bean(name = "llmDelegate")
	@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama")
	public Llm ollamaLlm(OllamaProperties ollamaProperties) {
		log.info("Using Ollama LLM: {}", ollamaProperties);
		return new OllamaLlm(ollamaProperties.baseUrl(), ollamaProperties.model(), ollamaProperties.timeout());
	}

	@Bean(name = "configuredLlmModel")
	@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini", matchIfMissing = true)
	public String geminiModel(GeminiProperties geminiProperties) {
		return geminiProperties.model();
	}

	@Bean(name = "configuredLlmModel")
	@ConditionalOnProperty(name = "llm.provider", havingValue = "groq")
	public String groqModel(GroqProperties groqProperties) {
		return groqProperties.model();
	}

	@Bean(name = "configuredLlmModel")
	@ConditionalOnProperty(name = "llm.provider", havingValue = "ollama")
	public String ollamaModel(OllamaProperties ollamaProperties) {
		return ollamaProperties.model();
	}

	@Bean
	public TokenEstimator tokenEstimator() {
		log.info("Token estimator: o200k_base via jtokkit; character fallback if tokenization fails");
		return TiktokenTokenEstimator.create();
	}

	@Bean
	public ProviderRateLimitStore providerRateLimitStore() {
		return new ProviderRateLimitStore(Clock.systemUTC(), RetrySleeper.THREAD_SLEEP);
	}

	/**
	 * API Key가 없어도 애플리케이션은 시작되도록 하고, 실제 호출 시점에 명확한 오류를 낸다.
	 * 요청 크기 검사는 retry 바깥에서 한 번만 한다. Groq rate-limit 대기도 retry 루프 바깥에 둔다.
	 */
	@Bean
	@Primary
	public Llm llm(@Qualifier("llmDelegate") Llm llmDelegate, LlmRetryProperties retryProperties, TokenEstimator tokenEstimator,
			RequestTokenBudgetProperties tokenBudget, ProviderRateLimitStore rateLimits) {
		Llm guarded = withRetry(llmDelegate, retryProperties);
		if (llmDelegate instanceof GroqLlm groqLlm) {
			guarded = new ProviderRateLimitLlm(guarded, rateLimits, "groq", groqLlm.model(), tokenEstimator);
		}
		log.info("LLM request token budget: max {} estimated tokens", tokenBudget.maxRequestTokens());
		return new RequestBudgetLlm(guarded, tokenEstimator, tokenBudget.maxRequestTokens());
	}

	static Llm withRetry(Llm delegate, LlmRetryProperties retryProperties) {
		if (!retryProperties.enabled()) {
			log.info("LLM retry is disabled");
			return delegate;
		}
		log.info("LLM retry is enabled: {}", retryProperties);
		return new RetryingLlm(delegate, retryProperties.toPolicy(), RetrySleeper.THREAD_SLEEP);
	}

}
