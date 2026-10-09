package com.aicompany.agentharness.context;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 분석 대상 파일 하나. content는 원문 그대로이며, Context 크기를 줄이기 위해 요약하거나 자르지 않는다.
 */
public record SourceFile(Path path, String content) {

	public SourceFile {
		Objects.requireNonNull(path, "path must not be null");
		Objects.requireNonNull(content, "content must not be null");
	}

}
