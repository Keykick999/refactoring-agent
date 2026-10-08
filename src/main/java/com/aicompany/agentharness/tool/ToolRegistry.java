package com.aicompany.agentharness.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ToolRegistry {

	private final Map<String, Tool> toolsByName = new LinkedHashMap<>();

	public void register(Tool tool) {
		if (toolsByName.containsKey(tool.name())) {
			throw new IllegalArgumentException("Tool already registered: " + tool.name());
		}
		toolsByName.put(tool.name(), tool);
	}

	public Optional<Tool> findByName(String toolName) {
		return Optional.ofNullable(toolsByName.get(toolName));
	}

	public List<ToolDefinition> toolDefinitions() {
		return toolsByName.values().stream()
				.map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.inputSchema()))
				.toList();
	}

}
