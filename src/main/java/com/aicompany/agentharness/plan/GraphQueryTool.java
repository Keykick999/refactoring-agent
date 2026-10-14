package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.graph.CodeNode;
import com.aicompany.agentharness.graph.CodeRelationship;
import com.aicompany.agentharness.graph.CodeRelationshipGraph;
import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 그래프에서 관련 타입만 찾는다. 원문은 읽지 않는다.
 */
public final class GraphQueryTool implements Tool {

	public static final String NAME = "find_related_types";

	private final CodeRelationshipGraph graph;

	public GraphQueryTool(CodeRelationshipGraph graph) {
		this.graph = graph;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String description() {
		return "Find structural relationships for a project type. Pass simpleName or qualifiedName as typeName.";
	}

	@Override
	public String inputSchema() {
		return """
				{"type":"object","properties":{"typeName":{"type":"string"}},"required":["typeName"]}
				""";
	}

	@Override
	public ToolResult execute(Map<String, Object> arguments) {
		String typeName = String.valueOf(arguments.getOrDefault("typeName", "")).strip();
		if (typeName.isBlank() || "null".equals(typeName)) {
			return ToolResult.failure(NAME, "typeName is required");
		}
		List<CodeNode> matches = matches(typeName);
		if (matches.isEmpty()) {
			return ToolResult.failure(NAME, "No project type named " + typeName);
		}
		if (matches.size() > 1) {
			return ToolResult.success(NAME, "Multiple types match. Use a qualifiedName:\n"
					+ matches.stream().map(CodeNode::qualifiedName).collect(Collectors.joining("\n")));
		}
		CodeNode node = matches.get(0);
		StringBuilder text = new StringBuilder();
		text.append(node.type()).append(' ').append(node.qualifiedName()).append('\n');
		text.append("File: ").append(node.filePath()).append('\n');
		append("From", graph.findRelationshipsFrom(node.qualifiedName()), text);
		append("To", graph.findRelationshipsTo(node.qualifiedName()), text);
		return ToolResult.success(NAME, text.toString());
	}

	private List<CodeNode> matches(String typeName) {
		return graph.getNodes().stream()
				.filter(node -> node.qualifiedName().equals(typeName) || node.simpleName().equals(typeName))
				.toList();
	}

	private static void append(String title, List<CodeRelationship> relationships, StringBuilder text) {
		text.append(title).append(":\n");
		if (relationships.isEmpty()) {
			text.append("- none\n");
			return;
		}
		for (CodeRelationship relationship : relationships) {
			text.append("- ")
					.append(relationship.source().simpleName())
					.append(" --")
					.append(relationship.type())
					.append("--> ")
					.append(relationship.target().simpleName())
					.append('\n');
		}
	}

}
