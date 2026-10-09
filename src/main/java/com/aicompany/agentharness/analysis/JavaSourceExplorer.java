package com.aicompany.agentharness.analysis;

import java.nio.file.Path;
import java.util.List;

/**
 * 분석 대상 경로에서 Java 파일과 그 원문을 찾는다.
 */
public interface JavaSourceExplorer {

	List<DiscoveredJavaSource> explore(Path target);

}
