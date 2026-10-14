package com.aicompany.agentharness.plan;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

public final class FinalCheckParser {

	public static final String RESPONSE_JSON_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "needsMoreWork": { "type": "boolean" },
			    "reason": { "type": "string" },
			    "newTasks": {
			      "type": "array",
			      "items": {
			        "type": "object",
			        "properties": {
			          "id": { "type": "string" },
			          "objective": { "type": "string" },
			          "dependsOn": { "type": "array", "items": { "type": "string" } }
			        },
			        "required": ["id", "objective", "dependsOn"]
			      }
			    }
			  },
			  "required": ["needsMoreWork", "reason"]
			}
			""";

	private final JsonMapper jsonMapper = new JsonMapper();

	public FinalCheckDecision parse(String response) {
		try {
			JsonNode root = jsonMapper.readTree(response);
			List<TaskDraft> tasks = new ArrayList<>();
			for (JsonNode task : root.path("newTasks")) {
				try {
					tasks.add(new TaskDraft(task.path("id").asString(""), task.path("objective").asString(""),
							strings(task.path("dependsOn"))));
				}
				catch (IllegalArgumentException ignored) {
					// skip incomplete new task objects
				}
			}
			return new FinalCheckDecision(root.path("needsMoreWork").asBoolean(false), root.path("reason").asString(""), tasks);
		}
		catch (JacksonException | IllegalArgumentException exception) {
			return new FinalCheckDecision(false, "Invalid final-check response: " + exception.getMessage(), List.of());
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
