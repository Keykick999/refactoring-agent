package com.aicompany.agentharness.llm;

/**
 * 텍스트의 토큰 수를 추정한다.
 * 구현은 모델 tokenizer를 대체할 수 있으며, 반환값은 정확한 토큰 수가 아닐 수 있다.
 */
public interface TokenEstimator {

	long estimateTokens(String text);

}
