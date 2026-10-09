package com.aicompany.agentharness.analysis;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 분석 대상으로 찾은 Java 파일. 원문을 읽지 못하면 content는 null이고 failureReason이 있다.
 */
public record DiscoveredJavaSource(Path path, String content, String failureReason) {

	public DiscoveredJavaSource {
		Objects.requireNonNull(path, "path must not be null");
		if (failureReason == null && content == null) {
			throw new IllegalArgumentException("a readable source requires content");
		}
	}

	public static DiscoveredJavaSource readable(Path path, String content) {
		return new DiscoveredJavaSource(path, content, null);
	}

	public static DiscoveredJavaSource unreadable(Path path, String failureReason) {
		return new DiscoveredJavaSource(path, null, failureReason);
	}

	public boolean readable() {
		return failureReason == null;
	}

}
