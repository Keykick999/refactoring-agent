package com.aicompany.agentharness.context;

import java.util.List;

/**
 * LLM 한 번의 분석에 함께 전달할 파일 묶음.
 *
 * @param groupKey            파일을 묶은 기준(예: 패키지 디렉터리). 같은 그룹이 크기 때문에 나뉘면 같은 값을 가진다.
 * @param files               원문 그대로의 파일 목록
 * @param contextSize         {@link ContextSizeEstimator}로 계산한 크기 합계
 * @param exceedsContextLimit 파일 하나가 최대 Context 크기보다 커서 단독으로도 한도를 넘는 경우 true.
 *                            파일을 자르지 않으므로, 이 Work Unit을 어떻게 처리할지는 사용하는 쪽이 결정한다.
 */
public record WorkUnit(String groupKey, List<SourceFile> files, int contextSize, boolean exceedsContextLimit) {

	public WorkUnit {
		files = List.copyOf(files);
		if (files.isEmpty()) {
			throw new IllegalArgumentException("A work unit must contain at least one file");
		}
	}

}
