package com.aicompany.agentharness.analysis;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 이번 실행에서 끝난 클래스 분석 결과를 메모리에 모은다. 같은 경로는 한 번만 보관한다.
 */
public class ClassAnalysisCollector {

	private final Map<Path, ClassAnalysisResult> resultsByPath = new LinkedHashMap<>();

	public boolean add(ClassAnalysisResult result) {
		Path path = result.path().toAbsolutePath().normalize();
		if (resultsByPath.containsKey(path)) {
			return false;
		}
		resultsByPath.put(path, result);
		return true;
	}

	public boolean contains(Path path) {
		return resultsByPath.containsKey(path.toAbsolutePath().normalize());
	}

	public List<ClassAnalysisResult> results() {
		return List.copyOf(resultsByPath.values());
	}

}
