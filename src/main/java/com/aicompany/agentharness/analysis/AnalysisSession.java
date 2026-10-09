package com.aicompany.agentharness.analysis;

import java.nio.file.Path;
import java.util.List;

/**
 * 한 번 잠금을 잡은 뒤의 분석 작업. 상태 변경은 호출할 때마다 짧게 저장되고, 분석 호출은 포함하지 않는다.
 */
public interface AnalysisSession {

	boolean hasWork();

	List<PendingClass> pending();

	void markRunning(Path path);

	void record(ClassAnalysisResult result);

	AnalysisReport finish(boolean stoppedByAuthentication);

	AnalysisReport report();

}
