package com.aicompany.agentharness.config;

import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import com.aicompany.agentharness.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ToolPermissionConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	void bindsReadOnlyToolPolicyFromApplicationYml() {
		contextRunner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
			AgentHarnessProperties.Tools tools = context.getBean(AgentHarnessProperties.class).tools();

			assertThat(tools.allowed()).contains("read_text_file", "list_directory").doesNotContain("write_file", "edit_file");
			assertThat(tools.denied()).contains("write_file", "edit_file", "create_directory", "move_file");
		});
	}

	@Test
	void bindsEmptyToolListsWhenToolsAreNotConfigured() {
		contextRunner.withPropertyValues("agent-harness.max-steps=3").run(context -> {
			AgentHarnessProperties.Tools tools = context.getBean(AgentHarnessProperties.class).tools();

			assertThat(tools.allowed()).isEmpty();
			assertThat(tools.denied()).isEmpty();
		});
	}

	@Test
	void createsPolicyWithWarningWhenConfiguredToolIsNotRegistered(CapturedOutput output) {
		ToolRegistry toolRegistry = new ToolRegistry();
		toolRegistry.register(new NoOpTool("read_text_file"));
		toolRegistry.register(new NoOpTool("write_file"));
		AgentHarnessProperties properties = new AgentHarnessProperties(5,
				new AgentHarnessProperties.FilesystemMcpServer(true, List.of(), ".", Duration.ofSeconds(1)),
				new AgentHarnessProperties.Tools(List.of("read_text_file", "not_existing_tool"), List.of("write_file")));

		ToolPermissionPolicy policy = new AgentHarnessConfiguration().toolPermissionPolicy(properties, toolRegistry);

		assertThat(policy.allowedToolDefinitions(toolRegistry.toolDefinitions()))
				.extracting("name")
				.containsExactly("read_text_file");
		assertThat(output).contains("Ignoring configured tools", "not_existing_tool");
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AgentHarnessProperties.class)
	static class PropertiesConfiguration {
	}

	private record NoOpTool(String name) implements Tool {

		@Override
		public String description() {
			return "Does nothing";
		}

		@Override
		public String inputSchema() {
			return "{\"type\":\"object\"}";
		}

		@Override
		public ToolResult execute(Map<String, Object> arguments) {
			return ToolResult.success(name, "");
		}

	}

}
