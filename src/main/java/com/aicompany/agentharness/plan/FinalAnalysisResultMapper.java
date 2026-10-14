package com.aicompany.agentharness.plan;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

public final class FinalAnalysisResultMapper {

	private final JsonMapper jsonMapper = new JsonMapper();

	public String write(FinalAnalysisResult result) {
		ObjectNode node = jsonMapper.createObjectNode();
		node.put("objective", result.objective());
		node.put("summary", result.summary());
		node.put("coverage", result.coverage());
		node.put("complete", result.complete());
		putStrings(node, "findings", result.findings());
		putStrings(node, "relatedFiles", result.relatedFiles());
		putStrings(node, "recommendations", result.recommendations());
		return jsonMapper.writeValueAsString(node);
	}

	public FinalAnalysisResult read(String json, String fallbackObjective) {
		if (json == null || json.isBlank()) {
			return null;
		}
		JsonNode node = jsonMapper.readTree(json);
		return new FinalAnalysisResult(
				text(node, "objective", fallbackObjective),
				text(node, "summary", ""),
				strings(node.path("findings")),
				strings(node.path("relatedFiles")),
				strings(node.path("recommendations")),
				text(node, "coverage", ""),
				node.path("complete").asBoolean(false));
	}

	private void putStrings(ObjectNode node, String field, List<String> values) {
		ArrayNode array = node.putArray(field);
		values.forEach(array::add);
	}

	private static String text(JsonNode node, String field, String fallback) {
		String value = node.path(field).asString("");
		return value.isBlank() ? fallback : value;
	}

	private static List<String> strings(JsonNode node) {
		List<String> values = new ArrayList<>();
		if (node.isArray()) {
			node.forEach(item -> values.add(item.asString("")));
		}
		return values;
	}

}
