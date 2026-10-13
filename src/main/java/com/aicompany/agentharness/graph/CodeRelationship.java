package com.aicompany.agentharness.graph;

import java.util.List;

/**
 * 두 프로젝트 타입 사이의 구조적 관계. 같은 출발·도착·종류는 하나이고, 발견된 위치만 여러 개일 수 있다.
 */
public record CodeRelationship(CodeNode source, CodeNode target, RelationshipType type, List<RelationshipSource> sourceKinds) {

	public CodeRelationship {
		if (source == null || target == null) {
			throw new IllegalArgumentException("source and target must not be null");
		}
		if (type == null) {
			throw new IllegalArgumentException("type must not be null");
		}
		if (sourceKinds == null || sourceKinds.isEmpty()) {
			throw new IllegalArgumentException("sourceKinds must not be empty");
		}
		sourceKinds = List.copyOf(sourceKinds);
	}

}
