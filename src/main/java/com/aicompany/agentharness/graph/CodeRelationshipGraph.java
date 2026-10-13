package com.aicompany.agentharness.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 프로젝트 내부 타입의 구조적 관계를 담는 인메모리 그래프.
 */
public final class CodeRelationshipGraph {

	private final List<CodeNode> nodes;
	private final List<CodeRelationship> relationships;
	private final Map<String, CodeNode> nodeByQualifiedName;

	public CodeRelationshipGraph(List<CodeNode> nodes, List<CodeRelationship> relationships) {
		this.nodes = List.copyOf(nodes);
		this.relationships = List.copyOf(relationships);
		Map<String, CodeNode> index = new LinkedHashMap<>();
		for (CodeNode node : this.nodes) {
			index.putIfAbsent(node.qualifiedName(), node);
		}
		this.nodeByQualifiedName = Map.copyOf(index);
	}

	public List<CodeNode> getNodes() {
		return nodes;
	}

	public List<CodeRelationship> getRelationships() {
		return relationships;
	}

	public Optional<CodeNode> findNode(String qualifiedName) {
		return Optional.ofNullable(nodeByQualifiedName.get(qualifiedName));
	}

	public List<CodeNode> findRelatedNodes(String qualifiedName) {
		Map<String, CodeNode> related = new LinkedHashMap<>();
		for (CodeRelationship relationship : relationships) {
			if (relationship.source().qualifiedName().equals(qualifiedName)) {
				related.putIfAbsent(relationship.target().qualifiedName(), relationship.target());
			}
			else if (relationship.target().qualifiedName().equals(qualifiedName)) {
				related.putIfAbsent(relationship.source().qualifiedName(), relationship.source());
			}
		}
		return List.copyOf(related.values());
	}

	public List<CodeRelationship> findRelationshipsFrom(String qualifiedName) {
		return relationships.stream()
				.filter(relationship -> relationship.source().qualifiedName().equals(qualifiedName))
				.toList();
	}

	public List<CodeRelationship> findRelationshipsTo(String qualifiedName) {
		return relationships.stream()
				.filter(relationship -> relationship.target().qualifiedName().equals(qualifiedName))
				.toList();
	}

	@Override
	public String toString() {
		return new CodeRelationshipGraphText().render(this);
	}

	static Comparator<CodeRelationship> relationshipOrder() {
		return Comparator.comparing((CodeRelationship relationship) -> relationship.source().qualifiedName())
				.thenComparing(relationship -> relationship.type().name())
				.thenComparing(relationship -> relationship.target().qualifiedName());
	}

	static List<CodeNode> sortedNodes(Map<String, CodeNode> nodes) {
		return nodes.values().stream().sorted(Comparator.comparing(CodeNode::qualifiedName)).toList();
	}

	static List<CodeRelationship> sortedRelationships(List<CodeRelationship> relationships) {
		List<CodeRelationship> sorted = new ArrayList<>(relationships);
		sorted.sort(relationshipOrder());
		return List.copyOf(sorted);
	}

}
