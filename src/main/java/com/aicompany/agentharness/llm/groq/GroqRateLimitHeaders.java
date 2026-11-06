package com.aicompany.agentharness.llm.groq;

import com.aicompany.agentharness.llm.ProviderRateLimitState;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.Optional;

/**
 * Groq rate-limit 헤더를 스냅샷으로 읽는다.
 * 토큰 헤더는 Groq 문서상 TPM이다. TPD remaining 헤더가 없으면 TPD는 만들지 않는다.
 */
public final class GroqRateLimitHeaders {

	private GroqRateLimitHeaders() {
	}

	public static Optional<ProviderRateLimitState> parse(HttpHeaders headers, Instant observedAt) {
		return parse(
				headers.firstValue("x-ratelimit-limit-tokens").orElse(null),
				headers.firstValue("x-ratelimit-remaining-tokens").orElse(null),
				headers.firstValue("x-ratelimit-reset-tokens").orElse(null),
				observedAt);
	}

	static Optional<ProviderRateLimitState> parse(String limit, String remaining, String reset, Instant observedAt) {
		Long remainingTokens = longOrNull(remaining);
		if (remainingTokens == null) {
			return Optional.empty();
		}
		Long limitTokens = longOrNull(limit);
		Instant resetAt = resetAt(reset, observedAt);
		return Optional.of(new ProviderRateLimitState(limitTokens == null ? -1 : limitTokens, remainingTokens, resetAt, observedAt));
	}

	private static Instant resetAt(String reset, Instant observedAt) {
		return parseResetMillis(reset).map(observedAt::plusMillis).orElse(null);
	}

	static Optional<Long> parseResetMillis(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		String value = raw.trim().toLowerCase();
		if (value.endsWith("ms")) {
			return wholeMillis(value.substring(0, value.length() - 2));
		}
		long millis = 0;
		boolean matched = false;
		int hours = value.indexOf('h');
		int minutes = value.indexOf('m');
		int seconds = value.indexOf('s');
		if (hours >= 0) {
			Long hoursValue = longOrNull(value.substring(0, hours));
			if (hoursValue == null) {
				return Optional.empty();
			}
			millis += Math.multiplyExact(hoursValue, 3_600_000L);
			matched = true;
			value = value.substring(hours + 1);
			minutes = value.indexOf('m');
			seconds = value.indexOf('s');
		}
		if (minutes >= 0) {
			Long minutesValue = longOrNull(value.substring(0, minutes));
			if (minutesValue == null) {
				return Optional.empty();
			}
			millis += Math.multiplyExact(minutesValue, 60_000L);
			matched = true;
			value = value.substring(minutes + 1);
			seconds = value.indexOf('s');
		}
		if (seconds >= 0) {
			Long secondsMillis = fractionalMillis(value.substring(0, seconds));
			if (secondsMillis == null) {
				return Optional.empty();
			}
			millis += secondsMillis;
			matched = true;
		}
		return matched ? Optional.of(millis) : Optional.empty();
	}

	private static Optional<Long> wholeMillis(String number) {
		try {
			return Optional.of(Math.round(Double.parseDouble(number.trim())));
		}
		catch (NumberFormatException exception) {
			return Optional.empty();
		}
	}

	private static Long fractionalMillis(String number) {
		String trimmed = number.trim();
		int dot = trimmed.indexOf('.');
		try {
			if (dot < 0) {
				return Long.parseLong(trimmed) * 1000L;
			}
			long seconds = trimmed.substring(0, dot).isEmpty() ? 0 : Long.parseLong(trimmed.substring(0, dot));
			String fraction = (trimmed.substring(dot + 1) + "000").substring(0, 3);
			return seconds * 1000L + Long.parseLong(fraction);
		}
		catch (NumberFormatException exception) {
			return null;
		}
	}

	private static Long longOrNull(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Long.parseLong(raw.trim());
		}
		catch (NumberFormatException exception) {
			return null;
		}
	}

}
