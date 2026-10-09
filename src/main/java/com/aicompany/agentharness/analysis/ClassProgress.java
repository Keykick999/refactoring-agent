package com.aicompany.agentharness.analysis;

import com.aicompany.agentharness.llm.LlmErrorCategory;

/**
 * 클래스 하나의 저장 상태. 분석 결과 상태와, 아직 끝나지 않은 상태를 함께 둔다.
 */
public enum ClassProgress {

	PENDING,
	RUNNING,
	SUCCEEDED,
	FAILED,
	UNSUPPORTED;

	public boolean shouldAnalyze(LlmErrorCategory category) {
		return switch (this) {
			case PENDING, RUNNING -> true;
			case FAILED -> category != null && category.retryOnResume();
			case SUCCEEDED, UNSUPPORTED -> false;
		};
	}

	public AnalysisStatus analysisStatus() {
		return switch (this) {
			case SUCCEEDED -> AnalysisStatus.SUCCEEDED;
			case FAILED -> AnalysisStatus.FAILED;
			case UNSUPPORTED -> AnalysisStatus.UNSUPPORTED;
			case PENDING, RUNNING -> null;
		};
	}

	public static ClassProgress of(AnalysisStatus status) {
		return switch (status) {
			case SUCCEEDED -> SUCCEEDED;
			case FAILED -> FAILED;
			case UNSUPPORTED -> UNSUPPORTED;
		};
	}

}
