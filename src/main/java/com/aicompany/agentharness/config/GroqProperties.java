package com.aicompany.agentharness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("llm.groq")
public record GroqProperties(
		String apiKey,
		@DefaultValue("openai/gpt-oss-20b") String model,
		@DefaultValue("https://api.groq.com/openai/v1") String url,
		@DefaultValue("60s") Duration timeout) {

	@Override
	public String toString() {
		return "GroqProperties[apiKey=" + (apiKey == null || apiKey.isBlank() ? "<not set>" : "<masked>")
				+ ", model=" + model + ", url=" + url + ", timeout=" + timeout + "]";
	}

}
