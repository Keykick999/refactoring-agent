package com.aicompany.agentharness.graph;

import java.util.stream.Collectors;

/**
 * 그래프를 개발자가 읽을 수 있는 텍스트로 출력한다. 분석 CLI나 LLM 호출과는 연결되어 있지 않다.
 */
public final class CodeRelationshipGraphText {

	public String render(CodeRelationshipGraph graph) {
		StringBuilder text = new StringBuilder();
		text.append("Nodes:\n");
		for (CodeNode node : graph.getNodes()) {
			text.append("- ").append(node.type().name()).append(' ').append(node.qualifiedName()).append('\n');
		}
		text.append("\nRelationships:\n");
		if (graph.getRelationships().isEmpty()) {
			text.append("- none\n");
			return text.toString();
		}
		for (CodeRelationship relationship : graph.getRelationships()) {
			text.append("- ")
					.append(relationship.source().simpleName())
					.append(" --")
					.append(relationship.type().name())
					.append("--> ")
					.append(relationship.target().simpleName());
			if (relationship.type() == RelationshipType.DEPENDS_ON) {
				text.append(" [")
						.append(relationship.sourceKinds().stream().map(Enum::name).collect(Collectors.joining(", ")))
						.append(']');
			}
			text.append('\n');
		}
		return text.toString();
	}

}
