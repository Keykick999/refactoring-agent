package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.graph.CodeNode;
import com.aicompany.agentharness.graph.CodeRelationshipGraph;
import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolResult;

import java.util.Map;

public final class GraphListTool implements Tool {

	public static final String NAME = "list_code_types";

	private final CodeRelationshipGraph graph;

	public GraphListTool(CodeRelationshipGraph graph) {
		this.graph = graph;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String description() {
		return "List project class and interface names in the code relationship graph. Does not return source code.";
	}

	@Override
	public String inputSchema() {
		return """
				{"type":"object","properties":{}}
				""";
	}

	@Override
	public ToolResult execute(Map<String, Object> arguments) {
		StringBuilder text = new StringBuilder();
		for (CodeNode node : graph.getNodes()) {
			text.append(node.type()).append(' ').append(node.qualifiedName()).append('\n');
		}
		if (text.isEmpty()) {
			return ToolResult.success(NAME, "No project types were found.");
		}
		return ToolResult.success(NAME, text.toString());
	}

}
