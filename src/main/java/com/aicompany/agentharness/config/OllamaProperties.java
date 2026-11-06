package com.aicompany.agentharness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("llm.ollama")
public record OllamaProperties(
		@DefaultValue("http://localhost:11434") String baseUrl,
		@DefaultValue("llama3.2") String model,
		@DefaultValue("120s") Duration timeout) {

	@Override
	public String toString() {
		return "OllamaProperties[baseUrl=" + baseUrl + ", model=" + model + ", timeout=" + timeout + "]";
	}

}
