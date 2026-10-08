package com.aicompany.agentharness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("llm.gemini")
public record GeminiProperties(String apiKey, String model, String url, Duration timeout) {

	@Override
	public String toString() {
		return "GeminiProperties[apiKey=" + (apiKey == null || apiKey.isBlank() ? "<not set>" : "<masked>")
				+ ", model=" + model + ", url=" + url + ", timeout=" + timeout + "]";
	}

}
