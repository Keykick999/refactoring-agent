package com.aicompany.agentharness.config;

import com.aicompany.agentharness.agent.CodeQualityAgent;
import com.aicompany.agentharness.analysis.AnalysisJobStore;
import com.aicompany.agentharness.analysis.AnalysisSettings;
import com.aicompany.agentharness.analysis.ClassAnalysisCoordinator;
import com.aicompany.agentharness.analysis.JavaSourceExplorer;
import com.aicompany.agentharness.analysis.McpJavaSourceExplorer;
import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.plan.PlanStore;
import com.aicompany.agentharness.plan.ProblemSolvingCoordinator;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.mcp.McpClient;
import com.aicompany.agentharness.mcp.McpTool;
import com.aicompany.agentharness.mcp.StdioMcpTransport;
import com.aicompany.agentharness.tool.ToolDefinition;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("agent-harness.filesystem-mcp-server.enabled")
@EnableConfigurationProperties({AgentHarnessProperties.class, ProblemSolvingProperties.class})
public class AgentHarnessConfiguration {

	private static final Logger log = LoggerFactory.getLogger(AgentHarnessConfiguration.class);

	@Bean(destroyMethod = "close")
	public McpClient filesystemMcpClient(AgentHarnessProperties properties) {
		AgentHarnessProperties.FilesystemMcpServer serverProperties = properties.filesystemMcpServer();
		List<String> serverCommand = createServerCommand(serverProperties);
		log.info("Starting filesystem MCP server: {}", serverCommand);

		McpClient mcpClient = new McpClient(StdioMcpTransport.start(serverCommand, serverProperties.timeout()));
		try {
			String protocolVersion = mcpClient.initialize();
			log.info("Connected to filesystem MCP server (protocol {})", protocolVersion);
			return mcpClient;
		}
		catch (RuntimeException exception) {
			mcpClient.close();
			throw exception;
		}
	}

	@Bean
	public ToolRegistry toolRegistry(McpClient filesystemMcpClient) {
		ToolRegistry toolRegistry = new ToolRegistry();
		List<McpTool> mcpTools = McpTool.discoverAll(filesystemMcpClient);
		mcpTools.forEach(toolRegistry::register);
		log.info("Registered MCP tools: {}", mcpTools.stream().map(McpTool::name).toList());
		return toolRegistry;
	}

	/**
	 * 설정에 있지만 등록되지 않은 Tool 이름은 경고만 남긴다.
	 * MCP Server 버전에 따라 Tool 목록이 달라질 수 있어 시작 실패로 처리하지 않는다.
	 */
	@Bean
	public ToolPermissionPolicy toolPermissionPolicy(AgentHarnessProperties properties, ToolRegistry toolRegistry) {
		AgentHarnessProperties.Tools tools = properties.tools();
		ToolPermissionPolicy toolPermissionPolicy = new ToolPermissionPolicy(tools.allowed(), tools.denied());

		List<String> registeredToolNames = toolRegistry.toolDefinitions().stream().map(ToolDefinition::name).toList();
		List<String> unknownToolNames = toolPermissionPolicy.unknownToolNames(registeredToolNames);
		if (!unknownToolNames.isEmpty()) {
			log.warn("Ignoring configured tools not provided by any registered tool provider: {}", unknownToolNames);
		}
		List<String> allowedToolNames = registeredToolNames.stream().filter(toolPermissionPolicy::isAllowed).toList();
		if (allowedToolNames.isEmpty()) {
			log.warn("No tools are allowed. Configure agent-harness.tools.allowed to let the agent use tools.");
		}
		log.info("Allowed tools for agents: {}", allowedToolNames);
		return toolPermissionPolicy;
	}

	@Bean
	public AgentRunner agentRunner(ToolRegistry toolRegistry, ToolPermissionPolicy toolPermissionPolicy,
			AgentHarnessProperties properties) {
		return new AgentRunner(toolRegistry, toolPermissionPolicy, properties.maxSteps());
	}

	@Bean
	public JavaSourceExplorer javaSourceExplorer(ToolRegistry toolRegistry, ToolPermissionPolicy toolPermissionPolicy,
			AgentHarnessProperties properties) {
		return new McpJavaSourceExplorer(toolRegistry, toolPermissionPolicy, properties.classAnalysis());
	}

	@Bean
	public ClassAnalysisCoordinator classAnalysisCoordinator(JavaSourceExplorer javaSourceExplorer, Llm llm,
			AgentRunner agentRunner, AgentHarnessProperties properties,
			@Qualifier("configuredLlmModel") String configuredLlmModel, AnalysisJobStore analysisJobStore) {
		AgentHarnessProperties.ClassAnalysis classAnalysis = properties.classAnalysis();
		AnalysisSettings settings = new AnalysisSettings(classAnalysis.maxSourceCharacters(), configuredLlmModel,
				classAnalysis.searchTool(), classAnalysis.readTool());
		return new ClassAnalysisCoordinator(javaSourceExplorer, llm, agentRunner, settings, analysisJobStore, Path.of("data", "locks"));
	}

	@Bean
	public CodeQualityAgent codeQualityAgent(Llm llm, ToolRegistry toolRegistry, ToolPermissionPolicy toolPermissionPolicy) {
		return new CodeQualityAgent(llm, toolPermissionPolicy.allowedToolDefinitions(toolRegistry.toolDefinitions()));
	}

	@Bean
	public ProblemSolvingCoordinator problemSolvingCoordinator(Llm llm, ToolRegistry toolRegistry,
			AgentHarnessProperties properties, ProblemSolvingProperties problemSolvingProperties, PlanStore planStore) {
		return new ProblemSolvingCoordinator(llm, toolRegistry, properties.tools().allowed(), properties.tools().denied(),
				planStore, Path.of("data", "locks"), problemSolvingProperties.maxSolverSteps(),
				problemSolvingProperties.maxPlanIterations(), problemSolvingProperties.maxFinalCheckRounds());
	}

	private List<String> createServerCommand(AgentHarnessProperties.FilesystemMcpServer serverProperties) {
		List<String> serverCommand = new ArrayList<>();
		// Windows에서 npx 같은 .cmd 스크립트는 프로세스로 직접 실행할 수 없어 cmd를 거쳐야 한다.
		if (System.getProperty("os.name").startsWith("Windows")) {
			serverCommand.addAll(List.of("cmd", "/c"));
		}
		serverCommand.addAll(serverProperties.command());
		serverCommand.add(serverProperties.workspaceDirectory().toString());
		return serverCommand;
	}

}
