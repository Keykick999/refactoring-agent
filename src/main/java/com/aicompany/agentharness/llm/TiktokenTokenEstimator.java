package com.aicompany.agentharness.llm;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI tiktoken {@code o200k_base}로 센다.
 * Groq의 gpt-oss 계열은 이 인코딩에 harmony 특수 토큰을 더한 tokenizer를 쓴다.
 * 일반 텍스트, Java, JSON에서는 그 특수 토큰이 거의 없어 o200k_base 개수와 가깝다.
 * Gemini SentencePiece나 Ollama 모델 tokenizer와는 다를 수 있다.
 * 인코딩에 실패하면 {@link ApproximateTokenEstimator}로 넘어간다.
 */
public final class TiktokenTokenEstimator implements TokenEstimator {

	private static final Logger log = LoggerFactory.getLogger(TiktokenTokenEstimator.class);

	private final Encoding encoding;
	private final TokenEstimator fallback;

	public TiktokenTokenEstimator(Encoding encoding, TokenEstimator fallback) {
		this.encoding = encoding;
		this.fallback = fallback;
	}

	public static TiktokenTokenEstimator create() {
		Encoding encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
		return new TiktokenTokenEstimator(encoding, new ApproximateTokenEstimator());
	}

	@Override
	public long estimateTokens(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		try {
			return encoding.countTokens(text);
		}
		catch (RuntimeException exception) {
			log.warn("o200k_base tokenization failed ({}); using the character fallback", exception.getClass().getSimpleName());
			return fallback.estimateTokens(text);
		}
	}

}
