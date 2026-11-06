package com.aicompany.agentharness.llm;

import java.time.Instant;

/**
 * Provider가 응답 헤더로 알려준 rate-limit 스냅샷.
 * {@code resetAt}은 그 스냅샷의 토큰 창이 끝난다고 provider가 말한 시각이다. 없으면 대기할 기준이 없다.
 *
 * @param limitTokens     헤더가 없으면 -1
 * @param remainingTokens provider가 알려준 남은 토큰
 */
public record ProviderRateLimitState(long limitTokens, long remainingTokens, Instant resetAt, Instant updatedAt) {
}
