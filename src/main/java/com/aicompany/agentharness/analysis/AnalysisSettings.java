package com.aicompany.agentharness.analysis;

/**
 * 같은 분석 작업인지를 구분하는 설정. 이 값이 다르면 다른 작업이다.
 */
public record AnalysisSettings(int maxSourceCharacters, String model, String searchTool, String readTool) {

	public AnalysisSettings {
		if (maxSourceCharacters < 1) {
			throw new IllegalArgumentException("maxSourceCharacters must be at least 1: " + maxSourceCharacters);
		}
		if (model == null || model.isBlank() || searchTool == null || searchTool.isBlank() || readTool == null || readTool.isBlank()) {
			throw new IllegalArgumentException("analysis settings must not be blank");
		}
	}

	public String fingerprint() {
		return maxSourceCharacters + "\n" + model + "\n" + searchTool + "\n" + readTool;
	}

}
