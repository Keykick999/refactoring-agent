package com.aicompany.agentharness.llm;

/**
 * 한 번의 요청이 설정된 예상 토큰 수를 넘으면 provider를 호출하지 않는다.
 * 이 실패는 네트워크 오류가 아니므로 {@link RetryingLlm}의 HTTP 재시도 대상이 아니다.
 */
public final class RequestBudgetLlm implements Llm {

	private final Llm delegate;
	private final TokenEstimator estimator;
	private final long maxRequestTokens;

	public RequestBudgetLlm(Llm delegate, TokenEstimator estimator, long maxRequestTokens) {
		if (maxRequestTokens < 1) {
			throw new IllegalArgumentException("maxRequestTokens must be at least 1: " + maxRequestTokens);
		}
		this.delegate = delegate;
		this.estimator = estimator;
		this.maxRequestTokens = maxRequestTokens;
	}

	public Llm delegate() {
		return delegate;
	}

	@Override
	public boolean nativeToolCalls() {
		return delegate.nativeToolCalls();
	}

	@Override
	public LlmResponse generate(LlmRequest request) {
		long estimated = LlmRequestSizer.estimate(estimator, request);
		LlmContextLogger.log(estimator, request, estimated, maxRequestTokens);
		if (estimated > maxRequestTokens) {
			throw new LlmException(
					"LLM request exceeds the token budget: estimated %d tokens, max %d. The request was not sent."
							.formatted(estimated, maxRequestTokens),
					LlmErrorCategory.REQUEST_BUDGET);
		}
		return delegate.generate(request);
	}

}
