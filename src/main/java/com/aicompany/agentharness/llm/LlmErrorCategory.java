package com.aicompany.agentharness.llm;

/**
 * LLM 호출이 실패한 원인. 이번 실행의 재시도와, 작업을 다시 시작할 때의 재개를 구분한다.
 */
public enum LlmErrorCategory {

	RATE_LIMIT,
	SERVER,
	NETWORK,
	TOKEN_LIMIT,
	AUTHENTICATION,
	INVALID_RESPONSE,
	SOURCE_UNREADABLE,
	SOURCE_TOO_LARGE,
	PERSISTENCE,
	UNEXPECTED;

	public boolean retryOnResume() {
		return this == RATE_LIMIT || this == SERVER || this == NETWORK || this == AUTHENTICATION || this == PERSISTENCE;
	}

	public boolean stopsTheJob() {
		return this == AUTHENTICATION;
	}

	public String userAction() {
		return switch (this) {
			case RATE_LIMIT, SERVER, NETWORK -> "The failure looks temporary. Run the same target again to resume this class.";
			case AUTHENTICATION -> "Set GEMINI_API_KEY and run the same target again. Completed classes are kept.";
			case TOKEN_LIMIT, SOURCE_TOO_LARGE -> "The source was not truncated. Split the file or raise the source size limit.";
			case PERSISTENCE -> "The analysis result was not saved. Run the same target again.";
			case SOURCE_UNREADABLE -> "The source file could not be read. This class will not be retried automatically.";
			case INVALID_RESPONSE, UNEXPECTED -> "This class will not be retried automatically.";
		};
	}

}
