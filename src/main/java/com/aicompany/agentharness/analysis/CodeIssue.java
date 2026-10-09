package com.aicompany.agentharness.analysis;

/**
 * 클래스 하나 안에서 확인한 코드 품질 문제.
 *
 * @param evidence 문제가 보이는 코드 위치 또는 코드 내용
 */
public record CodeIssue(String title, String description, String evidence, String impact, String recommendation,
		Severity severity) {

	public CodeIssue {
		title = required(title, "title");
		description = required(description, "description");
		evidence = required(evidence, "evidence");
		impact = required(impact, "impact");
		recommendation = required(recommendation, "recommendation");
		if (severity == null) {
			throw new IllegalArgumentException("severity must not be null");
		}
	}

	private static String required(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value.strip();
	}

}
