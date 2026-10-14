package com.aicompany.agentharness.plan;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

public final class DecompositionParser {

	public static final String RESPONSE_JSON_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "tasks": {
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
			  "required": ["tasks"]
			}
			""";

	private final JsonMapper jsonMapper = new JsonMapper();
	private final PlanValidator validator = new PlanValidator();

	public List<PlannedTask> parse(String response) {
		try {
			JsonNode root = jsonMapper.readTree(response);
			if (!root.isObject() || !root.path("tasks").isArray()) {
				throw new InvalidPlanException("Decomposition requires a tasks array.");
			}
			List<TaskDraft> drafts = new ArrayList<>();
			for (JsonNode task : root.path("tasks")) {
				drafts.add(new TaskDraft(required(task, "id"), required(task, "objective"), strings(task.path("dependsOn"))));
			}
			return validator.validateDrafts(drafts);
		}
		catch (JacksonException exception) {
			throw new InvalidPlanException("Decomposition response is not valid JSON.");
		}
		catch (IllegalArgumentException exception) {
			throw new InvalidPlanException(exception.getMessage());
		}
	}

	private static String required(JsonNode node, String field) {
		String value = node.path(field).asString("").strip();
		if (value.isBlank()) {
			throw new InvalidPlanException("Task " + field + " must not be blank.");
		}
		return value;
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
