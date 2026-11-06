package com.aicompany.agentharness.llm;

import com.aicompany.agentharness.tool.ToolCall;
import com.aicompany.agentharness.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * LLM에 보내는 컨텍스트를 메시지 단위로 DEBUG 로그에 남긴다. 어떤 메시지가 토큰을 차지하는지 보기 위한 용도다.
 * 메시지 본문은 {@link #MAX_CONTENT_LENGTH}자까지만 남긴다.
 */
public final class LlmContextLogger {

	private static final Logger log = LoggerFactory.getLogger(LlmContextLogger.class);

	static final int MAX_CONTENT_LENGTH = 4000;

	private LlmContextLogger() {
	}

	public static void log(TokenEstimator estimator, LlmRequest request, long estimatedTotal, long maxRequestTokens) {
		if (!log.isDebugEnabled()) {
			return;
		}
		StringBuilder text = new StringBuilder();
		text.append("LLM request context: ").append(request.messages().size()).append(" messages, estimated ")
				.append(estimatedTotal).append(" tokens (max ").append(maxRequestTokens).append(')');
		if (request.responseJsonSchema() != null) {
			text.append("\n  response schema: ").append(estimator.estimateTokens(request.responseJsonSchema()))
					.append(" tokens");
		}
		if (!request.tools().isEmpty()) {
			text.append("\n  native tools (not counted): ").append(request.tools().size()).append(" tools, ")
					.append(toolTokens(estimator, request.tools())).append(" tokens");
		}
		List<Message> messages = request.messages();
		for (int index = 0; index < messages.size(); index++) {
			appendMessage(text, estimator, index, messages.get(index));
		}
		log.debug(text.toString());
	}

	private static void appendMessage(StringBuilder text, TokenEstimator estimator, int index, Message message) {
		String content = message.content();
		text.append("\n  [").append(index).append("] ").append(message.role()).append(": ")
				.append(estimator.estimateTokens(message.role().name()) + estimator.estimateTokens(content))
				.append(" tokens, ").append(content.length()).append(" chars");
		if (message.toolCallId() != null) {
			text.append(", toolCallId=").append(message.toolCallId());
		}
		for (ToolCall toolCall : message.toolCalls()) {
			text.append("\n      tool call: ").append(toolCall.toolName()).append(' ').append(toolCall.arguments());
		}
		if (!content.isEmpty()) {
			text.append('\n').append(indent(truncate(content)));
		}
	}

	private static long toolTokens(TokenEstimator estimator, List<ToolDefinition> tools) {
		long total = 0;
		for (ToolDefinition tool : tools) {
			total += estimator.estimateTokens(tool.name());
			total += estimator.estimateTokens(tool.description());
			total += estimator.estimateTokens(tool.inputSchema());
		}
		return total;
	}

	private static String truncate(String content) {
		if (content.length() <= MAX_CONTENT_LENGTH) {
			return content;
		}
		return content.substring(0, MAX_CONTENT_LENGTH) + "... (" + content.length() + " chars)";
	}

	private static String indent(String content) {
		return "      | " + content.replace("\n", "\n      | ");
	}

}
