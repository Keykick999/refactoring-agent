package com.aicompany.agentharness.analysis;

import com.aicompany.agentharness.llm.LlmErrorCategory;

import java.nio.file.Path;
import java.util.List;

/**
 * 클래스(Java 파일) 하나의 분석 결과. 이슈가 없는 성공과 분석 실패는 상태로 구분한다.
 */
public record ClassAnalysisResult(String className, Path path, AnalysisStatus status, List<CodeIssue> issues, String error,
		LlmErrorCategory errorCategory, String userAction) {

	public ClassAnalysisResult {
		if (className == null || className.isBlank()) {
			throw new IllegalArgumentException("className must not be blank");
		}
		if (path == null) {
			throw new IllegalArgumentException("path must not be null");
		}
		issues = List.copyOf(issues);
		if (status == AnalysisStatus.SUCCEEDED && (error != null || errorCategory != null || userAction != null)) {
			throw new IllegalArgumentException("a succeeded analysis cannot have an error");
		}
		if (status != AnalysisStatus.SUCCEEDED && (error == null || error.isBlank())) {
			throw new IllegalArgumentException("a failed analysis requires an error");
		}
		if (status != AnalysisStatus.SUCCEEDED && !issues.isEmpty()) {
			throw new IllegalArgumentException("a failed analysis cannot contain issues");
		}
	}

	public static ClassAnalysisResult succeeded(String className, Path path, List<CodeIssue> issues) {
		return new ClassAnalysisResult(className, path, AnalysisStatus.SUCCEEDED, issues, null, null, null);
	}

	public static ClassAnalysisResult failed(String className, Path path, String error) {
		return failed(className, path, error, LlmErrorCategory.UNEXPECTED);
	}

	public static ClassAnalysisResult failed(String className, Path path, String error, LlmErrorCategory category) {
		LlmErrorCategory resolved = category == null ? LlmErrorCategory.UNEXPECTED : category;
		return new ClassAnalysisResult(className, path, AnalysisStatus.FAILED, List.of(), error, resolved, resolved.userAction());
	}

	public static ClassAnalysisResult unsupported(String className, Path path, String error) {
		return unsupported(className, path, error, LlmErrorCategory.SOURCE_TOO_LARGE);
	}

	public static ClassAnalysisResult unsupported(String className, Path path, String error, LlmErrorCategory category) {
		LlmErrorCategory resolved = category == null ? LlmErrorCategory.SOURCE_TOO_LARGE : category;
		return new ClassAnalysisResult(className, path, AnalysisStatus.UNSUPPORTED, List.of(), error, resolved, resolved.userAction());
	}

}
