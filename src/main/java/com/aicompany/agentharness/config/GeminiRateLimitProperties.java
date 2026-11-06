package com.aicompany.agentharness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Gemini API로 보내는 요청 수 한도(sliding window). Retry 시도도 한 번의 요청으로 센다.
 */
@ConfigurationProperties("llm.gemini.rate-limit")
public record GeminiRateLimitProperties(@DefaultValue("5") int maxRequests, @DefaultValue("60s") Duration window) {

}
