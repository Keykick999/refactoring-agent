package com.aicompany.agentharness.context;

/**
 * 파일 하나가 LLM Context에서 차지하는 크기를 추정한다.
 * 실제 한도는 token 단위이므로, Provider의 token 계산 방식으로 교체할 수 있도록 분리한다.
 */
@FunctionalInterface
public interface ContextSizeEstimator {

	/**
	 * 문자 수를 크기로 사용한다. 이 경우 최대 Context 크기도 문자 수로 지정한다.
	 */
	ContextSizeEstimator CHARACTERS = sourceFile -> sourceFile.content().length();

	int estimate(SourceFile sourceFile);

}
