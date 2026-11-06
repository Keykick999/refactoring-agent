package com.aicompany.agentharness.llm;

/**
 * 실제 LLM 요청에 실리는 메시지와 응답 스키마를 기준으로 예상 토큰 수를 만든다.
 * 소스 코드 길이만 따로 세지 않는다.
 */
public final class LlmRequestSizer {

	private LlmRequestSizer() {
	}

	public static long estimate(TokenEstimator estimator, LlmRequest request) {
		long total = 0;
		for (Message message : request.messages()) {
			total += estimator.estimateTokens(message.role().name());
			total += estimator.estimateTokens(message.content());
		}
		if (request.responseJsonSchema() != null) {
			total += estimator.estimateTokens(request.responseJsonSchema());
		}
		return total;
	}

}
