package com.aicompany.agentharness.analysis;

import com.aicompany.agentharness.llm.LlmErrorCategory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 클래스 분석 LLM 응답을 검증해 {@link ClassAnalysisResult}로 변환한다.
 * 형식이 잘못된 응답은 성공으로 만들지 않는다.
 */
public class ClassAnalysisParser {

	/**
	 * 이슈 필드는 모두 문자열이므로 열린 객체가 필요 없다. severity만 정해진 값으로 제한한다.
	 */
	public static final String RESPONSE_JSON_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "issues": {
			      "type": "array",
			      "items": {
			        "type": "object",
			        "properties": {
			          "title": { "type": "string" },
			          "description": { "type": "string" },
			          "evidence": { "type": "string" },
			          "impact": { "type": "string" },
			          "recommendation": { "type": "string" },
			          "severity": { "type": "string", "enum": ["LOW", "MEDIUM", "HIGH"] }
			        },
			        "required": ["title", "description", "evidence", "impact", "recommendation", "severity"]
			      }
			    }
			  },
			  "required": ["issues"]
			}
			""";

	private final JsonMapper jsonMapper = new JsonMapper();

	public ClassAnalysisResult parse(String className, Path path, String response) {
		try {
			return ClassAnalysisResult.succeeded(className, path, deduplicate(toIssues(jsonMapper.readTree(response))));
		}
		catch (JacksonException exception) {
			return ClassAnalysisResult.failed(className, path, "Invalid analysis response: response is not valid JSON",
					LlmErrorCategory.INVALID_RESPONSE);
		}
		catch (IllegalArgumentException exception) {
			return ClassAnalysisResult.failed(className, path, "Invalid analysis response: " + exception.getMessage(),
					LlmErrorCategory.INVALID_RESPONSE);
		}
	}

	private List<CodeIssue> toIssues(JsonNode response) {
		if (!response.isObject() || !response.path("issues").isArray()) {
			throw new IllegalArgumentException("response requires an issues array");
		}
		List<CodeIssue> issues = new ArrayList<>();
		for (JsonNode item : response.path("issues")) {
			issues.add(toIssue(item));
		}
		return issues;
	}

	private CodeIssue toIssue(JsonNode item) {
		if (!item.isObject()) {
			throw new IllegalArgumentException("each issue must be an object");
		}
		String severity = item.path("severity").asString("");
		try {
			return new CodeIssue(
					text(item, "title"),
					text(item, "description"),
					text(item, "evidence"),
					text(item, "impact"),
					text(item, "recommendation"),
					Severity.valueOf(severity));
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("issue is missing a required field or has an unsupported severity");
		}
	}

	private String text(JsonNode item, String field) {
		JsonNode value = item.path(field);
		if (!value.isString()) {
			throw new IllegalArgumentException(field + " must be a string");
		}
		return value.asString();
	}

	/**
	 * 같은 클래스 안에서 제목과 근거가 같은 이슈만 한 번 남긴다. 다른 클래스의 이슈는 여기서 합치지 않는다.
	 */
	private List<CodeIssue> deduplicate(List<CodeIssue> issues) {
		Map<String, CodeIssue> uniqueIssues = new LinkedHashMap<>();
		for (CodeIssue issue : issues) {
			uniqueIssues.putIfAbsent(issue.title() + "\n" + issue.evidence(), issue);
		}
		return List.copyOf(uniqueIssues.values());
	}

}
