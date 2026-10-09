package com.aicompany.agentharness.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 분석 대상 파일을 LLM 한 번에 전달할 수 있는 Work Unit으로 나눈다.
 *
 * <p>묶는 기준은 {@link GroupingStrategy}에 맡기고, 이 클래스는 모든 기준에 공통인 규칙만 적용한다.
 * <ul>
 *   <li>그룹 하나가 최대 Context 크기를 넘으면 파일 순서대로 여러 Work Unit으로 나눈다.</li>
 *   <li>파일 내용은 자르거나 요약하지 않는다. 파일 하나가 한도를 넘으면 단독 Work Unit으로 두고 표시한다.</li>
 *   <li>입력 순서와 관계없이 같은 결과를 만든다(그룹은 이름순, 파일은 경로순).</li>
 * </ul>
 */
public class WorkUnitPlanner {

	private static final Comparator<SourceFile> BY_PATH = Comparator.comparing(SourceFile::path);

	private final GroupingStrategy groupingStrategy;
	private final ContextSizeEstimator contextSizeEstimator;
	private final int maxContextSize;

	public WorkUnitPlanner(GroupingStrategy groupingStrategy, ContextSizeEstimator contextSizeEstimator, int maxContextSize) {
		if (maxContextSize < 1) {
			throw new IllegalArgumentException("maxContextSize must be at least 1: " + maxContextSize);
		}
		this.groupingStrategy = groupingStrategy;
		this.contextSizeEstimator = contextSizeEstimator;
		this.maxContextSize = maxContextSize;
	}

	public List<WorkUnit> plan(List<SourceFile> sourceFiles) {
		Map<String, List<SourceFile>> groups = new TreeMap<>(groupingStrategy.group(sourceFiles));
		verifyEveryFileIsGroupedOnce(sourceFiles, groups);

		List<WorkUnit> workUnits = new ArrayList<>();
		groups.forEach((groupKey, files) -> workUnits.addAll(splitByContextSize(groupKey, files)));
		return List.copyOf(workUnits);
	}

	private List<WorkUnit> splitByContextSize(String groupKey, List<SourceFile> files) {
		List<WorkUnit> workUnits = new ArrayList<>();
		List<SourceFile> currentFiles = new ArrayList<>();
		int currentSize = 0;

		for (SourceFile file : files.stream().sorted(BY_PATH).toList()) {
			int fileSize = contextSizeEstimator.estimate(file);
			if (!currentFiles.isEmpty() && (fileSize > maxContextSize || currentSize + fileSize > maxContextSize)) {
				workUnits.add(new WorkUnit(groupKey, currentFiles, currentSize, false));
				currentFiles = new ArrayList<>();
				currentSize = 0;
			}
			if (fileSize > maxContextSize) {
				workUnits.add(new WorkUnit(groupKey, List.of(file), fileSize, true));
				continue;
			}
			currentFiles.add(file);
			currentSize += fileSize;
		}
		if (!currentFiles.isEmpty()) {
			workUnits.add(new WorkUnit(groupKey, currentFiles, currentSize, false));
		}
		return workUnits;
	}

	/**
	 * 잘못된 GroupingStrategy가 파일을 빠뜨리거나 중복시키면 분석되지 않는 코드가 생기므로 계획 단계에서 막는다.
	 */
	private void verifyEveryFileIsGroupedOnce(List<SourceFile> sourceFiles, Map<String, List<SourceFile>> groups) {
		List<SourceFile> groupedFiles = groups.values().stream().flatMap(List::stream).sorted(BY_PATH).toList();
		if (!groupedFiles.equals(sourceFiles.stream().sorted(BY_PATH).toList())) {
			throw new IllegalStateException("Grouping strategy must place every source file in exactly one group");
		}
	}

}
