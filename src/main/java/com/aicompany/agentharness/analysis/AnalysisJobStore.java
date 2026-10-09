package com.aicompany.agentharness.analysis;

import java.util.List;

/**
 * 분석 작업과 클래스별 결과를 저장한다. 구현은 LLM을 호출하지 않는다.
 */
public interface AnalysisJobStore {

	AnalysisSession begin(AnalysisIdentity identity, List<DiscoveredJavaSource> sources);

}
