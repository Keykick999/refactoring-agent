package com.aicompany.agentharness.graph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * target 디렉터리 아래 Java 파일만 노드로 만들어 인메모리 관계 그래프를 만든다.
 * target은 source root여도 되고 그 아래 package 디렉터리여도 된다.
 * 분석 실행, LLM 호출, 저장된 분석 결과와는 연결되어 있지 않다.
 */
public final class CodeRelationshipGraphBuilder {

	private final JavaSourceParser parser = new JavaSourceParser();
	private final CodeNodeExtractor nodeExtractor = new CodeNodeExtractor();
	private final CodeRelationshipExtractor relationshipExtractor = new CodeRelationshipExtractor();

	public CodeRelationshipGraph build(Path target) {
		List<ParsedJavaSource> sources = parser.parse(target);
		Map<String, CodeNode> nodes = new LinkedHashMap<>();
		for (ParsedJavaSource source : sources) {
			for (CodeNode node : nodeExtractor.extract(source)) {
				nodes.putIfAbsent(node.qualifiedName(), node);
			}
		}

		Map<RelationshipKey, EnumSet<RelationshipSource>> kindsByRelationship = new LinkedHashMap<>();
		for (ParsedJavaSource source : sources) {
			for (CodeRelationshipDraft draft : relationshipExtractor.extract(source, nodes)) {
				RelationshipKey key = new RelationshipKey(draft.sourceQualifiedName(), draft.targetQualifiedName(), draft.type());
				kindsByRelationship.computeIfAbsent(key, ignored -> EnumSet.noneOf(RelationshipSource.class)).add(draft.sourceKind());
			}
		}

		List<CodeRelationship> relationships = new ArrayList<>();
		for (Map.Entry<RelationshipKey, EnumSet<RelationshipSource>> entry : kindsByRelationship.entrySet()) {
			RelationshipKey key = entry.getKey();
			CodeNode source = nodes.get(key.sourceQualifiedName());
			CodeNode destination = nodes.get(key.targetQualifiedName());
			if (source == null || destination == null) {
				continue;
			}
			relationships.add(new CodeRelationship(source, destination, key.type(), List.copyOf(entry.getValue())));
		}
		return new CodeRelationshipGraph(CodeRelationshipGraph.sortedNodes(nodes),
				CodeRelationshipGraph.sortedRelationships(relationships));
	}

	private record RelationshipKey(String sourceQualifiedName, String targetQualifiedName, RelationshipType type) {
	}

}
