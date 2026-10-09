package com.aicompany.agentharness.analysis;

import com.aicompany.agentharness.config.AgentHarnessProperties;
import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import com.aicompany.agentharness.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpJavaSourceExplorerTest {

	private static final AgentHarnessProperties.ClassAnalysis CLASS_ANALYSIS =
			new AgentHarnessProperties.ClassAnalysis(100000, "search_files", "read_text_file");

	@TempDir
	Path directory;

	@Test
	void readsJavaFilesFoundBySearchTool() throws Exception {
		Path order = Files.writeString(directory.resolve("OrderService.java"), "class OrderService {}");
		Path payment = Files.writeString(directory.resolve("PaymentService.java"), "class PaymentService {}");
		ToolRegistry toolRegistry = registry(
				(name, arguments) -> {
					if ("search_files".equals(name)) {
						return ToolResult.success(name, order + "\n" + payment);
					}
					String path = String.valueOf(arguments.get("path"));
					return ToolResult.success(name, path.contains("Payment") ? "class PaymentService {}" : "class OrderService {}");
				});

		List<DiscoveredJavaSource> sources = explorer(toolRegistry, allowed("search_files", "read_text_file")).explore(directory);

		assertThat(sources).extracting(DiscoveredJavaSource::content).containsExactly("class OrderService {}", "class PaymentService {}");
	}

	@Test
	void readsSingleJavaFileWithoutSearching() throws Exception {
		Path order = Files.writeString(directory.resolve("OrderService.java"), "class OrderService {}");
		ToolRegistry toolRegistry = registry((name, arguments) -> {
			assertThat(name).isEqualTo("read_text_file");
			return ToolResult.success(name, "class OrderService {}");
		});

		List<DiscoveredJavaSource> sources = explorer(toolRegistry, allowed("read_text_file")).explore(order);

		assertThat(sources).singleElement().satisfies(source -> {
			assertThat(source.readable()).isTrue();
			assertThat(source.content()).isEqualTo("class OrderService {}");
		});
	}

	@Test
	void reportsNonJavaFileAsUnreadable() throws Exception {
		Path notes = Files.writeString(directory.resolve("notes.txt"), "notes");

		List<DiscoveredJavaSource> sources = explorer(new ToolRegistry(), allowed()).explore(notes);

		assertThat(sources).singleElement().satisfies(source -> {
			assertThat(source.readable()).isFalse();
			assertThat(source.failureReason()).contains("not a Java source");
		});
	}

	@Test
	void keepsReadFailureAsUnreadableSource() throws Exception {
		Path order = Files.writeString(directory.resolve("OrderService.java"), "class OrderService {}");
		ToolRegistry toolRegistry = registry((name, arguments) -> "read_text_file".equals(name)
				? ToolResult.failure(name, "Access denied")
				: ToolResult.success(name, order.toString()));

		List<DiscoveredJavaSource> sources = explorer(toolRegistry, allowed("search_files", "read_text_file")).explore(directory);

		assertThat(sources).singleElement().satisfies(source -> {
			assertThat(source.path()).isEqualTo(order.toAbsolutePath().normalize());
			assertThat(source.failureReason()).contains("Access denied");
		});
	}

	@Test
	void failsDiscoveryWhenSearchToolIsDenied() {
		ToolRegistry toolRegistry = registry((name, arguments) -> ToolResult.success(name, ""));

		assertThatThrownBy(() -> explorer(toolRegistry, allowed("read_text_file")).explore(directory))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Tool execution denied: search_files");
	}

	private McpJavaSourceExplorer explorer(ToolRegistry toolRegistry, ToolPermissionPolicy policy) {
		return new McpJavaSourceExplorer(toolRegistry, policy, CLASS_ANALYSIS);
	}

	private static ToolPermissionPolicy allowed(String... toolNames) {
		return new ToolPermissionPolicy(List.of(toolNames), List.of());
	}

	private static ToolRegistry registry(ToolCall toolCall) {
		ToolRegistry toolRegistry = new ToolRegistry();
		toolRegistry.register(fakeTool("search_files", toolCall));
		toolRegistry.register(fakeTool("read_text_file", toolCall));
		return toolRegistry;
	}

	private static Tool fakeTool(String name, ToolCall toolCall) {
		return new Tool() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public String description() {
				return name;
			}

			@Override
			public String inputSchema() {
				return "{\"type\":\"object\"}";
			}

			@Override
			public ToolResult execute(Map<String, Object> arguments) {
				return toolCall.call(name, arguments);
			}
		};
	}

	private interface ToolCall {
		ToolResult call(String name, Map<String, Object> arguments);
	}

}
