package com.aicompany.agentharness.llm;

/**
 * tokenizer를 쓸 수 없을 때의 fallback. 정확한 토큰 수가 아니다.
 * 문자 2개당 1토큰으로 잡아 영어와 Java를 실제보다 크게 센다.
 */
public final class ApproximateTokenEstimator implements TokenEstimator {

	@Override
	public long estimateTokens(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		return (text.length() + 1L) / 2L;
	}

}
