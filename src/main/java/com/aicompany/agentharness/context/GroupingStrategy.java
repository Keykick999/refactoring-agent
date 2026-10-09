package com.aicompany.agentharness.context;

import java.util.List;
import java.util.Map;

/**
 * 어떤 파일들을 함께 분석할지 묶는 기준.
 *
 * <p>V1은 {@link PackageGroupingStrategy}만 제공한다. Domain, Dependency 기반 등 다른 기준은 이 인터페이스의 구현으로 추가한다.
 * 크기 제한에 따른 분할과 결과 순서는 {@link WorkUnitPlanner}가 공통으로 처리하므로, 구현체는 묶는 기준만 결정한다.
 */
@FunctionalInterface
public interface GroupingStrategy {

	/**
	 * @return 그룹 이름별 파일 목록. 모든 입력 파일은 정확히 하나의 그룹에 속해야 한다.
	 */
	Map<String, List<SourceFile>> group(List<SourceFile> sourceFiles);

}
