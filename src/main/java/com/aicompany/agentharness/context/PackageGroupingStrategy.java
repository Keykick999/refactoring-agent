package com.aicompany.agentharness.context;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 같은 디렉터리(패키지)에 있는 파일을 하나의 그룹으로 묶는다.
 * 의미 기반 분석 없이, 패키지 구조를 프로젝트의 자연스러운 코드 그룹으로 간주한다.
 */
public class PackageGroupingStrategy implements GroupingStrategy {

	@Override
	public Map<String, List<SourceFile>> group(List<SourceFile> sourceFiles) {
		return sourceFiles.stream().collect(Collectors.groupingBy(PackageGroupingStrategy::directoryOf));
	}

	private static String directoryOf(SourceFile sourceFile) {
		Path directory = sourceFile.path().getParent();
		return directory == null ? "" : directory.toString();
	}

}
