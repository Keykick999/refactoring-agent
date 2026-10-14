package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.tool.ToolCall;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SolverDecisionParser {

	public static final String RESPONSE_JSON_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "type": { "type": "string", "enum": ["USE_TOOL", "FINISH"] },
			    "toolCall": {
			      "type": "object",
			      "properties": {
			        "toolName": { "type": "string" },
			        "arguments": { "type": "object", "additionalProperties": true }
			      },
			      "required": ["toolName", "arguments"]
			    },
			    "taskDecision": {
			      "type": "object",
			      "properties": {
			        "status": { "type": "string", "enum": ["COMPLETED", "BLOCKED", "FAILED"] },
			        "facts": { "type": "array", "items": { "type": "string" } },
			        "conclusion": { "type": "string" },
			        "evidence": {
			          "type": "array",
			          "items": {
			            "type": "object",
			            "properties": {
			              "filePath": { "type": "string" },
			              "className": { "type": "string" },
			              "methodName": { "type": "string" },
			              "detail": { "type": "string" }
			            }
			          }
			        },
			        "unresolvedQuestions": { "type": "array", "items": { "type": "string" } },
			        "planChanges": {
			          "type": "array",
			          "items": {
			            "type": "object",
			            "properties": {
			              "type": { "type": "string", "enum": ["ADD_TASK", "CANCEL_TASK", "UPDATE_DEPENDENCY"] },
			              "taskId": { "type": "string" },
			              "objective": { "type": "string" },
			              "dependsOn": { "type": "array", "items": { "type": "string" } }
			            },
			            "required": ["type", "taskId"]
			          }
			        }
			      },
			      "required": ["status", "conclusion"]
			    }
			  },
			  "required": ["type"]
			}
			""";

	private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {
	};

	private final JsonMapper jsonMapper = new JsonMapper();

	public SolverDecision parse(String response) {
		try {
			JsonNode root = jsonMapper.readTree(response);
			if (!root.isObject()) {
				throw new IllegalArgumentException("response must be a JSON object");
			}
			return switch (root.path("type").asString("")) {
				case "USE_TOOL" -> SolverDecision.useTool(toToolCall(root.path("toolCall")));
				case "FINISH" -> toFinish(root.path("taskDecision"));
				default -> throw new IllegalArgumentException("unsupported decision type");
			};
		}
		catch (JacksonException | IllegalArgumentException exception) {
			return SolverDecision.finish(TaskOutcome.failed("Invalid solver response: " + exception.getMessage()), List.of());
		}
	}

	private ToolCall toToolCall(JsonNode toolCall) {
		String toolName = toolCall.path("toolName").asString("").strip();
		if (toolName.isBlank()) {
			throw new IllegalArgumentException("toolName must not be blank");
		}
		Map<String, Object> arguments = jsonMapper.convertValue(toolCall.path("arguments"), ARGUMENTS_TYPE);
		return new ToolCall(toolName, arguments == null ? Map.of() : arguments);
	}

	private SolverDecision toFinish(JsonNode decision) {
		PlanTaskStatus status = parseStatus(decision.path("status").asString(""));
		TaskOutcome outcome = new TaskOutcome(status, strings(decision.path("facts")), decision.path("conclusion").asString(""),
				evidence(decision.path("evidence")), strings(decision.path("unresolvedQuestions")));
		return SolverDecision.finish(outcome, planChanges(decision.path("planChanges")));
	}

	private static PlanTaskStatus parseStatus(String status) {
		return switch (status) {
			case "COMPLETED" -> PlanTaskStatus.COMPLETED;
			case "BLOCKED" -> PlanTaskStatus.BLOCKED;
			case "FAILED" -> PlanTaskStatus.FAILED;
			default -> throw new IllegalArgumentException("unsupported task status: " + status);
		};
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

	private static List<TaskEvidence> evidence(JsonNode node) {
		List<TaskEvidence> values = new ArrayList<>();
		if (node.isArray()) {
			node.forEach(item -> values.add(new TaskEvidence(
					item.path("filePath").asString(""),
					item.path("className").asString(""),
					item.path("methodName").asString(""),
					item.path("detail").asString(""))));
		}
		return values;
	}

	private static List<PlanChange> planChanges(JsonNode node) {
		List<PlanChange> changes = new ArrayList<>();
		if (!node.isArray()) {
			return changes;
		}
		for (JsonNode item : node) {
			PlanChangeType type = PlanChangeType.valueOf(item.path("type").asString(""));
			changes.add(new PlanChange(type, item.path("taskId").asString(""), item.path("objective").asString(""),
					strings(item.path("dependsOn"))));
		}
		return changes;
	}

}
