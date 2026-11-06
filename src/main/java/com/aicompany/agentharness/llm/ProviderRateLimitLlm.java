package com.aicompany.agentharness.llm;

/**
 * Provider가 알려준 남은 토큰이 이번 요청보다 적으면 reset까지 기다린 뒤 다시 확인한다.
 * HTTP 429 재시도는 이 클래스의 책임이 아니다.
 */
public final class ProviderRateLimitLlm implements Llm {

	private final Llm delegate;
	private final ProviderRateLimitStore rateLimits;
	private final String provider;
	private final String model;
	private final TokenEstimator estimator;

	public ProviderRateLimitLlm(Llm delegate, ProviderRateLimitStore rateLimits, String provider, String model,
			TokenEstimator estimator) {
		this.delegate = delegate;
		this.rateLimits = rateLimits;
		this.provider = provider;
		this.model = model;
		this.estimator = estimator;
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
		rateLimits.acquire(provider, model, estimated);
		try {
			return delegate.generate(request);
		}
		finally {
			rateLimits.finish(provider, model);
		}
	}

}
