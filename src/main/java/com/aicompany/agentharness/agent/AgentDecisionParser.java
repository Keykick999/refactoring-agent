package com.aicompany.agentharness.agent;

import com.aicompany.agentharness.harness.AgentDecision;
import com.aicompany.agentharness.harness.AgentResult;
import com.aicompany.agentharness.tool.ToolCall;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * LLM의 구조화된 JSON 응답을 AgentDecision으로 변환한다.
 */
public class AgentDecisionParser {

	/**
	 * arguments의 additionalProperties: true가 없으면 Gemini의 constrained decoding이
	 * 항상 빈 객체를 생성하므로(실측), Tool마다 다른 인자를 허용하도록 명시한다.
	 */
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
			    "result": {
			      "type": "object",
			      "properties": {
			        "success": { "type": "boolean" },
			        "summary": { "type": "string" }
			      },
			      "required": ["success", "summary"]
			    }
			  },
			  "required": ["type"]
			}
			""";

	private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {
	};

	private final JsonMapper jsonMapper = new JsonMapper();

	/**
	 * 해석할 수 없는 응답은 잘못된 Tool이 실행되지 않도록 실패한 FINISH로 변환한다.
	 */
	public AgentDecision parse(String llmResponseContent) {
		try {
			return toDecision(jsonMapper.readTree(llmResponseContent));
		}
		catch (JacksonException exception) {
			return invalidDecision("response is not valid JSON");
		}
		catch (IllegalArgumentException exception) {
			return invalidDecision(exception.getMessage());
		}
	}

	private AgentDecision toDecision(JsonNode response) {
		if (!response.isObject()) {
			throw new IllegalArgumentException("response must be a JSON object");
		}
		String decisionType = response.path("type").asString("");
		return switch (decisionType) {
			case "USE_TOOL" -> AgentDecision.useTool(toToolCall(response.path("toolCall")));
			case "FINISH" -> AgentDecision.finish(toAgentResult(response.path("result")));
			default -> throw new IllegalArgumentException("unsupported decision type: '" + decisionType + "'");
		};
	}

	private ToolCall toToolCall(JsonNode toolCall) {
		if (!toolCall.isObject()) {
			throw new IllegalArgumentException("USE_TOOL requires a toolCall object");
		}
		JsonNode toolName = toolCall.path("toolName");
		if (!toolName.isString() || toolName.asString().isBlank()) {
			throw new IllegalArgumentException("toolCall.toolName must be a non-empty string");
		}
		JsonNode arguments = toolCall.path("arguments");
		if (!arguments.isObject()) {
			throw new IllegalArgumentException("toolCall.arguments must be a JSON object");
		}
		Map<String, Object> argumentValues = jsonMapper.convertValue(arguments, ARGUMENTS_TYPE);
		if (argumentValues.containsValue(null)) {
			throw new IllegalArgumentException("toolCall.arguments must not contain null values");
		}
		return new ToolCall(toolName.asString(), argumentValues);
	}

	private AgentResult toAgentResult(JsonNode result) {
		if (!result.isObject()) {
			throw new IllegalArgumentException("FINISH requires a result object");
		}
		if (!result.path("success").isBoolean() || !result.path("summary").isString()) {
			throw new IllegalArgumentException("result requires boolean 'success' and string 'summary'");
		}
		return new AgentResult(result.path("success").asBoolean(), result.path("summary").asString());
	}

	private AgentDecision invalidDecision(String reason) {
		return AgentDecision.finish(AgentResult.failure("Invalid LLM decision: " + reason));
	}

}
