package com.aicompany.agentharness.plan;

import com.aicompany.agentharness.graph.CodeNode;
import com.aicompany.agentharness.graph.CodeNodeType;
import com.aicompany.agentharness.graph.CodeRelationship;
import com.aicompany.agentharness.graph.CodeRelationshipGraph;
import com.aicompany.agentharness.graph.RelationshipSource;
import com.aicompany.agentharness.graph.RelationshipType;
import com.aicompany.agentharness.harness.AgentDecision;
import com.aicompany.agentharness.harness.AgentResult;
import com.aicompany.agentharness.harness.AgentRunner;
import com.aicompany.agentharness.harness.AgentState;
import com.aicompany.agentharness.harness.Target;
import com.aicompany.agentharness.harness.Task;
import com.aicompany.agentharness.llm.Llm;
import com.aicompany.agentharness.llm.LlmRequest;
import com.aicompany.agentharness.llm.LlmResponse;
import com.aicompany.agentharness.llm.Message;
import com.aicompany.agentharness.llm.MessageRole;
import com.aicompany.agentharness.tool.Tool;
import com.aicompany.agentharness.tool.ToolCall;
import com.aicompany.agentharness.tool.ToolDefinition;
import com.aicompany.agentharness.tool.ToolPermissionPolicy;
import com.aicompany.agentharness.tool.ToolRegistry;
import com.aicompany.agentharness.tool.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSolverAgentTest {

	private static final List<ToolDefinition> AVAILABLE_TOOLS = List.of(
			new ToolDefinition("read_text_file", "Read a text file", "{\"type\":\"object\"}"),
			new ToolDefinition("search_files", "Search files", "{\"type\":\"object\"}"),
			new ToolDefinition("directory_tree", "Show a directory tree", "{\"type\":\"object\"}"),
			new ToolDefinition("list_code_types", "List project types", "{\"type\":\"object\"}"),
			new ToolDefinition("find_related_types", "Find related types", "{\"type\":\"object\"}"));

	private static final String USE_CONTAINER_EXEC = """
			{"type":"USE_TOOL","toolCall":{"toolName":"container.exec","arguments":{"command":"SECRET_ARGUMENT_PAYLOAD"}}}
			""";

	private static final String USE_READ_TEXT_FILE = """
			{"type":"USE_TOOL","toolCall":{"toolName":"read_text_file","arguments":{"path":"CodeNodeExtractor.java"}}}
			""";

	private static final String USE_WEB_SEARCH = """
			{"type":"USE_TOOL","toolCall":{"toolName":"web_search","arguments":{"query":"SECRET_ARGUMENT_PAYLOAD"}}}
			""";

	private final JsonMapper jsonMapper = new JsonMapper();
	private final Task harnessTask = new Task("Analyze class dependencies", new Target(Path.of("graph")));

	@Test
	void doesNotTurnUnavailableToolIntoUseToolOrStoreItsArguments() {
		ScriptedLlm llm = new ScriptedLlm(USE_CONTAINER_EXEC, USE_READ_TEXT_FILE);
		AgentState agentState = new AgentState(harnessTask);

		AgentDecision decision = solver(llm).decide(agentState);

		assertThat(decision.type()).isEqualTo(AgentDecision.Type.USE_TOOL);
		assertThat(decision.toolCall().toolName()).isEqualTo("read_text_file");
		assertThat(agentState.messages()).extracting(Message::content)
				.noneMatch(content -> content.contains("container.exec") || content.contains("SECRET_ARGUMENT_PAYLOAD"));
		assertThat(agentState.toolResults()).isEmpty();
	}

	@Test
	void retriesUnavailableToolOnce() {
		ScriptedLlm llm = new ScriptedLlm(USE_CONTAINER_EXEC, USE_READ_TEXT_FILE);
		solver(llm).decide(new AgentState(harnessTask));

		assertThat(llm.receivedRequests).hasSize(2);
		assertThat(llm.receivedRequests.get(1).messages()).extracting(Message::content)
				.anySatisfy(content -> assertThat(content)
						.isEqualTo("Unavailable tool 'container.exec'. Choose one of: "
								+ "read_text_file, search_files, directory_tree, list_code_types, find_related_types.")
						.doesNotContain("SECRET_ARGUMENT_PAYLOAD")
						.doesNotContain("inputSchema")
						.doesNotContain("Read a text file"));
	}

	@Test
	void acceptsAllowedToolFromTheSingleRetry() {
		ScriptedLlm llm = new ScriptedLlm(USE_CONTAINER_EXEC, USE_READ_TEXT_FILE);
		AgentState agentState = new AgentState(harnessTask);

		AgentDecision decision = solver(llm).decide(agentState);

		assertThat(decision.type()).isEqualTo(AgentDecision.Type.USE_TOOL);
		assertThat(decision.toolCall().toolName()).isEqualTo("read_text_file");
		assertThat(agentState.messages()).containsExactly(Message.assistant(USE_READ_TEXT_FILE));
	}

	@Test
	void failsWithoutExecutingWhenRetryAlsoRequestsUnavailableTool() {
		RecordingTool containerExec = new RecordingTool("container.exec");
		ToolRegistry toolRegistry = new ToolRegistry();
		toolRegistry.register(containerExec);
		ScriptedLlm llm = new ScriptedLlm(USE_CONTAINER_EXEC, USE_WEB_SEARCH);
		AtomicReference<AgentState> capturedState = new AtomicReference<>();
		TaskSolverAgent agent = solver(llm);

		AgentResult agentResult = new AgentRunner(toolRegistry, new ToolPermissionPolicy(List.of("container.exec"), List.of()), 5)
				.run(agentState -> {
					capturedState.set(agentState);
					return agent.decide(agentState);
				}, harnessTask);

		assertThat(agentResult.success()).isFalse();
		assertThat(agentResult.summary()).isEqualTo("Unavailable tool 'web_search'");
		assertThat(agent.finishDecision().outcome().status()).isEqualTo(PlanTaskStatus.FAILED);
		assertThat(agent.finishDecision().outcome().conclusion()).isEqualTo("Unavailable tool 'web_search'");
		assertThat(containerExec.executionCount).isZero();
		assertThat(llm.receivedRequests).hasSize(2);
		assertThat(capturedState.get().toolResults()).isEmpty();
		assertThat(capturedState.get().messages()).extracting(Message::content)
				.noneMatch(content -> content.contains("SECRET_ARGUMENT_PAYLOAD") || content.contains("container.exec")
						|| content.contains("web_search"));
	}

	@Test
	void limitsToolNameEnumToAvailableToolsIncludingGraphTools() {
		ScriptedLlm llm = new ScriptedLlm(USE_READ_TEXT_FILE);

		solver(llm).decide(new AgentState(harnessTask));

		assertThat(toolNameEnum(llm.receivedRequests.get(0).responseJsonSchema())).containsExactly(
				"read_text_file", "search_files", "directory_tree", "list_code_types", "find_related_types");
		assertThat(jsonMapper.readTree(SolverDecisionParser.RESPONSE_JSON_SCHEMA)
				.at("/properties/toolCall/properties/toolName").has("enum")).isFalse();
		ObjectNode dynamicSchema = (ObjectNode) jsonMapper.readTree(llm.receivedRequests.get(0).responseJsonSchema());
		((ObjectNode) dynamicSchema.at("/properties/toolCall/properties/toolName")).remove("enum");
		assertThat(dynamicSchema).isEqualTo(jsonMapper.readTree(SolverDecisionParser.RESPONSE_JSON_SCHEMA));
	}

	@Test
	void runsNativeToolCallThroughTheExistingRunnerAndContinues() {
		RecordingTool searchFiles = new RecordingTool("search_files");
		ToolRegistry toolRegistry = new ToolRegistry();
		toolRegistry.register(searchFiles);
		NativeLlm llm = new NativeLlm(
				new LlmResponse("", List.of(new ToolCall("call_123", "search_files", Map.of("pattern", "**/*.java")))),
				new LlmResponse("""
						{"type":"FINISH","taskDecision":{"status":"COMPLETED","facts":["A depends on B"],"conclusion":"done","evidence":[],"unresolvedQuestions":[],"planChanges":[]}}
						"""));
		TaskSolverAgent agent = solver(llm);

		AgentResult agentResult = new AgentRunner(toolRegistry, new ToolPermissionPolicy(List.of("search_files"), List.of()), 5)
				.run(agent, harnessTask);

		assertThat(searchFiles.executionCount).isEqualTo(1);
		assertThat(agentResult).isEqualTo(AgentResult.success("done"));
		assertThat(llm.receivedRequests).hasSize(2);
		assertThat(llm.receivedRequests.get(0).responseJsonSchema()).isNull();
		assertThat(llm.receivedRequests.get(0).tools()).extracting(ToolDefinition::name)
				.contains("search_files", "list_code_types", "find_related_types");
		assertThat(llm.receivedRequests.get(1).messages())
				.anySatisfy(message -> assertThat(message.toolCalls())
						.containsExactly(new ToolCall("call_123", "search_files", Map.of("pattern", "**/*.java"))));
		assertThat(llm.receivedRequests.get(1).messages())
				.anySatisfy(message -> {
					assertThat(message.role()).isEqualTo(MessageRole.TOOL);
					assertThat(message.toolCallId()).isEqualTo("call_123");
					assertThat(message.content()).contains("executed");
				});
	}

	@Test
	void doesNotExecuteNativeToolMissingFromAvailableTools() {
		RecordingTool containerExec = new RecordingTool("container.exec");
		ToolRegistry toolRegistry = new ToolRegistry();
		toolRegistry.register(containerExec);
		NativeLlm llm = new NativeLlm(
				new LlmResponse("", List.of(new ToolCall("call_x", "container.exec", Map.of("command", "SECRET_ARGUMENT_PAYLOAD")))),
				new LlmResponse("", List.of(new ToolCall("call_y", "container.exec", Map.of("command", "SECRET_ARGUMENT_PAYLOAD")))));
		AtomicReference<AgentState> capturedState = new AtomicReference<>();
		TaskSolverAgent agent = solver(llm);

		AgentResult agentResult = new AgentRunner(toolRegistry, new ToolPermissionPolicy(List.of("container.exec"), List.of()), 5)
				.run(agentState -> {
					capturedState.set(agentState);
					return agent.decide(agentState);
				}, harnessTask);

		assertThat(agentResult.summary()).isEqualTo("Unavailable tool 'container.exec'");
		assertThat(containerExec.executionCount).isZero();
		assertThat(llm.receivedRequests).hasSize(2);
		assertThat(capturedState.get().toolResults()).isEmpty();
		assertThat(capturedState.get().messages()).isEmpty();
		assertThat(llm.receivedRequests.get(1).messages()).extracting(Message::content)
				.noneMatch(content -> content.contains("SECRET_ARGUMENT_PAYLOAD"));
	}

	@Test
	void acceptsNativeFinalAnswerWithoutAToolCall() {
		NativeLlm llm = new NativeLlm(new LlmResponse("""
				{"type":"FINISH","taskDecision":{"status":"COMPLETED","facts":[],"conclusion":"already known","evidence":[],"unresolvedQuestions":[],"planChanges":[]}}
				"""));
		AgentState agentState = new AgentState(harnessTask);

		AgentDecision decision = solver(llm).decide(agentState);

		assertThat(decision.type()).isEqualTo(AgentDecision.Type.FINISH);
		assertThat(decision.result()).isEqualTo(AgentResult.success("already known"));
		assertThat(agentState.toolResults()).isEmpty();
		assertThat(llm.receivedRequests).hasSize(1);
	}

	@Test
	void workingSetListsGraphTypesWithFilePathsAndRelationships() {
		ScriptedLlm llm = new ScriptedLlm("""
				{"type":"FINISH","taskDecision":{"status":"COMPLETED","facts":[],"conclusion":"done","evidence":[],"unresolvedQuestions":[],"planChanges":[]}}
				""");
		Path target = Path.of("sample", "shop").toAbsolutePath();
		CodeNode order = new CodeNode(CodeNodeType.CLASS, "Order", "com.example.shop.Order", target.resolve("Order.java"));
		CodeNode service = new CodeNode(CodeNodeType.CLASS, "OrderService", "com.example.shop.OrderService",
				target.resolve("OrderService.java"));
		CodeNode outside = new CodeNode(CodeNodeType.CLASS, "Customer", "com.example.other.Customer",
				Path.of("sample", "other", "Customer.java").toAbsolutePath());
		CodeRelationshipGraph graph = new CodeRelationshipGraph(List.of(outside, order, service), List.of(
				new CodeRelationship(service, order, RelationshipType.DEPENDS_ON, List.of(RelationshipSource.FIELD))));

		solver(llm, target, graph).decide(new AgentState(harnessTask));

		assertThat(llm.receivedRequests.get(0).messages().get(1).content())
				.contains("## Target\n" + target)
				.contains("2 project types under the target.")
				.contains("""
						- Class: com.example.shop.OrderService
						  File: %s
						  Uses: com.example.shop.Order (DEPENDS_ON)
						  Used by: none
						""".formatted(service.filePath()))
				.contains("""
						- Class: com.example.shop.Order
						  File: %s
						  Uses: none
						  Used by: com.example.shop.OrderService (DEPENDS_ON)
						""".formatted(order.filePath()))
				.doesNotContain("com.example.other.Customer");
	}

	@Test
	void workingSetListsTypesNamedInTheTaskFirst() {
		ScriptedLlm llm = new ScriptedLlm("""
				{"type":"FINISH","taskDecision":{"status":"COMPLETED","facts":[],"conclusion":"done","evidence":[],"unresolvedQuestions":[],"planChanges":[]}}
				""");
		Path target = Path.of("shop").toAbsolutePath();
		CodeRelationshipGraph graph = new CodeRelationshipGraph(List.of(
				new CodeNode(CodeNodeType.CLASS, "Cart", "shop.Cart", target.resolve("Cart.java")),
				new CodeNode(CodeNodeType.CLASS, "Invoice", "shop.Invoice", target.resolve("Invoice.java")),
				new CodeNode(CodeNodeType.CLASS, "InvoiceService", "shop.InvoiceService", target.resolve("InvoiceService.java"))),
				List.of());

		new TaskSolverAgent(llm, AnalysisRequest.of(target, "Find bugs"),
				PlannedTask.pending("analyze-service", "Analyze shop.InvoiceService", List.of()), List.of(), graph, AVAILABLE_TOOLS)
				.decide(new AgentState(harnessTask));

		String content = llm.receivedRequests.get(0).messages().get(1).content();
		assertThat(content.indexOf("Class: shop.InvoiceService")).isLessThan(content.indexOf("Class: shop.Cart"));
		assertThat(content.indexOf("Class: shop.Cart")).isLessThan(content.indexOf("Class: shop.Invoice\n"));
	}

	@Test
	void workingSetLimitsTheListedSourceFiles() {
		ScriptedLlm llm = new ScriptedLlm("""
				{"type":"FINISH","taskDecision":{"status":"COMPLETED","facts":[],"conclusion":"done","evidence":[],"unresolvedQuestions":[],"planChanges":[]}}
				""");
		Path target = Path.of("big").toAbsolutePath();
		List<CodeNode> nodes = new ArrayList<>();
		for (int index = 0; index < 35; index++) {
			nodes.add(new CodeNode(CodeNodeType.CLASS, "Type" + index, "big.Type" + index, target.resolve("Type" + index + ".java")));
		}

		solver(llm, target, new CodeRelationshipGraph(nodes, List.of())).decide(new AgentState(harnessTask));

		assertThat(llm.receivedRequests.get(0).messages().get(1).content())
				.contains("35 project types under the target.")
				.contains("- Class: big.Type29\n")
				.doesNotContain("- Class: big.Type30\n")
				.contains("- ... 5 more types. Use list_code_types or find_related_types to see them.");
	}

	private TaskSolverAgent solver(Llm llm, Path target, CodeRelationshipGraph graph) {
		return new TaskSolverAgent(llm, AnalysisRequest.of(target, "Find bugs"),
				PlannedTask.pending("analyze-order", "Analyze Order", List.of()), List.of(), graph, AVAILABLE_TOOLS);
	}

	private TaskSolverAgent solver(Llm llm) {
		return new TaskSolverAgent(llm, AnalysisRequest.of(Path.of("graph"), "Analyze class dependencies"),
				PlannedTask.pending("list-java-files", "List Java files", List.of()), List.of(),
				new CodeRelationshipGraph(List.of(), List.of()), AVAILABLE_TOOLS);
	}

	private List<String> toolNameEnum(String schema) {
		List<String> names = new ArrayList<>();
		jsonMapper.readTree(schema).at("/properties/toolCall/properties/toolName/enum").forEach(name -> names.add(name.asString()));
		return names;
	}

	private static final class RecordingTool implements Tool {

		private final String name;
		private int executionCount;

		private RecordingTool(String name) {
			this.name = name;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public String description() {
			return "Records executions";
		}

		@Override
		public String inputSchema() {
			return "{\"type\":\"object\"}";
		}

		@Override
		public ToolResult execute(Map<String, Object> arguments) {
			executionCount++;
			return ToolResult.success(name, "executed");
		}

	}

	private static final class NativeLlm implements Llm {

		private final Deque<LlmResponse> scriptedResponses;
		private final List<LlmRequest> receivedRequests = new ArrayList<>();

		private NativeLlm(LlmResponse... scriptedResponses) {
			this.scriptedResponses = new ArrayDeque<>(List.of(scriptedResponses));
		}

		@Override
		public boolean nativeToolCalls() {
			return true;
		}

		@Override
		public LlmResponse generate(LlmRequest request) {
			receivedRequests.add(request);
			return scriptedResponses.removeFirst();
		}

	}

	private static final class ScriptedLlm implements Llm {

		private final Deque<String> scriptedResponses;
		private final List<LlmRequest> receivedRequests = new ArrayList<>();

		private ScriptedLlm(String... scriptedResponses) {
			this.scriptedResponses = new ArrayDeque<>(List.of(scriptedResponses));
		}

		@Override
		public LlmResponse generate(LlmRequest request) {
			receivedRequests.add(request);
			return new LlmResponse(scriptedResponses.removeFirst());
		}

	}

}
