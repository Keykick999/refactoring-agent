package com.aicompany.agentharness.analysis;

import com.aicompany.agentharness.config.AgentHarnessProperties;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import com.aicompany.agentharness.tool.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 등록된 MCP Tool로 Java 파일을 찾고 원문을 읽는다.
 *
 * <p>파일을 고르는 일은 LLM 판단이 아니므로 AgentRunner 루프 밖에서 실행한다.
 * 실행 전에는 AgentRunner와 같은 ToolPermissionPolicy를 확인한다.
 */
public class McpJavaSourceExplorer implements JavaSourceExplorer {

	private static final String JAVA_PATTERN = "**/*.java";

	private final ToolRegistry toolRegistry;
	private final ToolPermissionPolicy toolPermissionPolicy;
	private final String searchToolName;
	private final String readToolName;

	public McpJavaSourceExplorer(ToolRegistry toolRegistry, ToolPermissionPolicy toolPermissionPolicy,
			AgentHarnessProperties.ClassAnalysis classAnalysis) {
		this.toolRegistry = toolRegistry;
		this.toolPermissionPolicy = toolPermissionPolicy;
		this.searchToolName = classAnalysis.searchTool();
		this.readToolName = classAnalysis.readTool();
	}

	@Override
	public List<DiscoveredJavaSource> explore(Path target) {
		if (Files.isRegularFile(target)) {
			return List.of(exploreFile(target));
		}
		ToolResult searchResult = call(searchToolName, Map.of("path", target.toString(), "pattern", JAVA_PATTERN));
		if (!searchResult.success()) {
			throw new IllegalStateException("Failed to discover Java sources: " + searchResult.output());
		}
		List<DiscoveredJavaSource> sources = new ArrayList<>();
		for (Path file : javaFilesIn(target, searchResult.output())) {
			sources.add(read(file));
		}
		return List.copyOf(sources);
	}

	private DiscoveredJavaSource exploreFile(Path target) {
		if (!target.getFileName().toString().endsWith(".java")) {
			return DiscoveredJavaSource.unreadable(target, "Target is not a Java source file");
		}
		return read(target);
	}

	private DiscoveredJavaSource read(Path file) {
		ToolResult toolResult = call(readToolName, Map.of("path", file.toString()));
		if (!toolResult.success()) {
			return DiscoveredJavaSource.unreadable(file, toolResult.output());
		}
		return DiscoveredJavaSource.readable(file, toolResult.output());
	}

	private List<Path> javaFilesIn(Path directory, String searchOutput) {
		Set<Path> files = new LinkedHashSet<>();
		for (String line : searchOutput.lines().map(String::strip).toList()) {
			if (!line.endsWith(".java")) {
				continue;
			}
			Path file = Path.of(line);
			if (!file.isAbsolute()) {
				file = directory.resolve(file);
			}
			file = file.toAbsolutePath().normalize();
			if (file.startsWith(directory.toAbsolutePath().normalize())) {
				files.add(file);
			}
		}
		return List.copyOf(files);
	}

	private ToolResult call(String toolName, Map<String, Object> arguments) {
		if (!toolPermissionPolicy.isAllowed(toolName)) {
			return ToolResult.failure(toolName, "Tool execution denied: " + toolName);
		}
		return toolRegistry.findByName(toolName)
				.map(tool -> tool.execute(arguments))
				.orElseGet(() -> ToolResult.failure(toolName, "Unknown tool: " + toolName));
	}

}
