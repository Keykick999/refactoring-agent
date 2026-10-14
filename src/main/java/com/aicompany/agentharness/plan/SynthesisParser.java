package com.aicompany.agentharness.plan;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

public final class SynthesisParser {

	public static final String RESPONSE_JSON_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "summary": { "type": "string" },
			    "findings": { "type": "array", "items": { "type": "string" } },
			    "relatedFiles": { "type": "array", "items": { "type": "string" } },
			    "recommendations": { "type": "array", "items": { "type": "string" } },
			    "coverage": { "type": "string" },
			    "complete": { "type": "boolean" }
			  },
			  "required": ["summary", "findings", "complete"]
			}
			""";

	private final JsonMapper jsonMapper = new JsonMapper();

	public FinalAnalysisResult parse(String objective, String response) {
		try {
			JsonNode root = jsonMapper.readTree(response);
			return new FinalAnalysisResult(objective, root.path("summary").asString(""), strings(root.path("findings")),
					strings(root.path("relatedFiles")), strings(root.path("recommendations")),
					root.path("coverage").asString(""), root.path("complete").asBoolean(true));
		}
		catch (JacksonException exception) {
			return new FinalAnalysisResult(objective, "Could not synthesize the final report from stored task results.",
					List.of(), List.of(), List.of(), "", false);
		}
	}

	private static List<String> strings(JsonNode node) {
		List<String> values = new ArrayList<>();
		if (node.isArray()) {
			node.forEach(item -> {
				String value = item.asString("").strip();
				if (!value.isBlank()) {
					values.add(value);
				}
			});
		}
		return values;
	}

}
