package com.aicompany.agentharness.plan;

public record TaskEvidence(String filePath, String className, String methodName, String detail) {

	public TaskEvidence {
		detail = detail == null ? "" : detail.strip();
		filePath = filePath == null ? "" : filePath;
		className = className == null ? "" : className;
		methodName = methodName == null ? "" : methodName;
	}

}
