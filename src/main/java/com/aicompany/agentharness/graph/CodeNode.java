package com.aicompany.agentharness.graph;

import java.nio.file.Path;

/**
 * 프로젝트 안의 클래스 또는 인터페이스 하나. 같은 qualifiedName은 그래프에서 한 번만 둔다.
 */
public record CodeNode(CodeNodeType type, String simpleName, String qualifiedName, Path filePath) {

	public CodeNode {
		if (type == null) {
			throw new IllegalArgumentException("type must not be null");
		}
		if (simpleName == null || simpleName.isBlank()) {
			throw new IllegalArgumentException("simpleName must not be blank");
		}
		if (qualifiedName == null || qualifiedName.isBlank()) {
			throw new IllegalArgumentException("qualifiedName must not be blank");
		}
		if (filePath == null) {
			throw new IllegalArgumentException("filePath must not be null");
		}
	}

}
