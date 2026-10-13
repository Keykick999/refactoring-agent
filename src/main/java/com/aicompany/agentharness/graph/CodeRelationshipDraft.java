package com.aicompany.agentharness.graph;

record CodeRelationshipDraft(String sourceQualifiedName, String targetQualifiedName, RelationshipType type,
		RelationshipSource sourceKind) {
}
