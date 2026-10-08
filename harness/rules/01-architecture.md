# Architecture

## Core Components

Agent 시스템은 다음 구성요소로 이루어진다.

- Task
- AgentState
- Agent
- LLM
- AgentDecision
- AgentRunner
- Tool
- ToolCall
- ToolResult
- ToolRegistry
- ToolPermissionPolicy
- AgentResult
- MCP Client
- MCP Tool Adapter

## Responsibility

### Task

작업 목적과 분석 대상을 정의한다.

### AgentState

현재 작업의 상태를 보관한다.

### Agent

현재 상태를 기반으로 다음 행동을 결정한다.

Agent는 Tool을 직접 실행하지 않는다.

### LLM

Agent의 판단과 분석을 지원한다.

LLM은 현재 상태와 사용 가능한 Tool 정보를 바탕으로
판단 또는 분석 결과를 생성한다.

현재 구현체는 `GeminiLlm`이며, Agent는 `Llm` 인터페이스에만 의존한다.

일시적인 호출 실패(429, 5xx, 네트워크 오류)의 Retry는 LLM 계층(`RetryingLlm`)이 담당한다.
Provider 구현체는 오류가 일시적인지만 `RetryableLlmException`으로 알리고,
Agent와 AgentRunner는 최종 성공 또는 최종 실패만 본다.

### AgentDecision

Agent가 다음에 수행할 행동을 표현한다.

- USE_TOOL
- FINISH

### AgentRunner

Agent의 전체 실행 흐름을 관리한다.

1. Agent에 State 전달
2. AgentDecision 획득
3. Tool Permission 확인 후 Tool 실행
4. ToolResult 반영
5. 다시 Agent 호출
6. FINISH까지 반복

### Tool

외부 시스템에 실제 작업을 수행한다.

Tool은 name, description, input schema(JSON Schema)를 가진다.

### ToolResult

Tool 실행 결과를 표현한다.

### ToolRegistry

Tool을 등록하고 이름으로 조회한다.

ToolRegistry는 Tool의 출처(MCP 등)를 알지 않는다.

ToolRegistry는 권한을 판단하지 않는다. MCP Server가 제공하는 모든 Tool을 등록할 수 있다.

### ToolPermissionPolicy

Agent가 사용할 수 있는 Tool을 설정(`agent-harness.tools.allowed/denied`)으로 제한한다.

- 허용 Tool = allowed에 있고 denied에 없는 Tool. denied가 우선한다. allowed가 비어 있으면 모두 거부한다.
- Agent(LLM)에 공개할 Tool 정의를 필터링한다.
- AgentRunner가 Tool 실행 직전에 다시 확인한다. LLM 출력은 신뢰하지 않는다.
- Tool 이름은 코드가 아니라 설정에서만 관리한다.

### AgentResult

Agent 작업이 종료되었을 때 최종 결과를 표현한다.

### MCP Client

MCP Server와 통신한다.

- stdio transport로 MCP Server를 하위 프로세스로 실행한다.
- initialize → notifications/initialized로 연결을 초기화한다.
- tools/list로 서버가 제공하는 Tool 목록을 가져온다.
- tools/call로 Tool을 호출한다.

JSON-RPC와 transport 세부사항은 MCP Client 내부에만 존재한다.

### MCP Tool Adapter

tools/list로 받은 MCP Tool 정의 하나를 Harness Tool 하나로 노출한다.

- 모든 MCP Tool은 하나의 공통 Adapter 클래스(`McpTool`)로 표현한다.
- MCP 결과와 MCP 관련 실패는 Harness `ToolResult`로 변환한다.

## Tool Execution Flow

```text
Agent
  ↓ AgentDecision(USE_TOOL, ToolCall(toolName, arguments))
AgentRunner
  ↓ findByName(toolName)            없으면 → ToolResult.failure("Unknown tool: ...")
ToolRegistry
  ↓ isAllowed(toolName)             거부 → ToolResult.failure("Tool execution denied: ...")
ToolPermissionPolicy
  ↓ Tool.execute(arguments)
McpTool (Adapter)
  ↓ callTool(name, arguments)
McpClient
  ↓ tools/call (JSON-RPC over stdio)
MCP Server
  ↓ MCP Result
McpClient → McpTool
  ↓ Harness ToolResult
AgentRunner
  ↓ AgentState에 반영
Agent
```

## Tool Selection by LLM

어떤 Tool을 사용할지는 Agent 코드가 아니라 LLM이 결정한다.

```text
ToolRegistry.toolDefinitions() → ToolPermissionPolicy.allowedToolDefinitions()
  ↓
AgentState + Task + 허용된 Tool 정의 목록 + Agent 역할/규칙
  ↓ LlmRequest (messages + 응답 JSON Schema)
Llm (GeminiLlm → Gemini API)
  ↓ LlmResponse (구조화된 JSON)
AgentDecisionParser
  ↓ AgentDecision (USE_TOOL / FINISH)
AgentRunner
```

- Agent는 `ToolPermissionPolicy`가 허용한 Tool 정의(name, description, inputSchema)만 받는다.
  Tool을 실행할 수 있는 객체는 받지 않으며, Tool 권한을 직접 판단하지 않는다.
- LLM 응답은 정해진 JSON 형식(`type`, `toolCall`, `result`)을 따라야 한다.
  해석할 수 없는 응답은 Tool을 실행하지 않고 실패한 FINISH로 변환한다.
- Agent는 특정 LLM Provider를 알지 않는다. Provider 구현은 `llm.gemini` 패키지에만 존재한다.

## CLI Entry Point

CLI(`CodeQualityCommandLineRunner`)는 실행 인자를 Task로 변환하고 AgentRunner에 실행을 위임하는 진입점이다.

```text
--target / --objective
  ↓ Task(goal, Target(workspace 기준 경로))
AgentRunner.run(CodeQualityAgent, task)
  ↓ (Tool Selection / Tool Execution Flow)
AgentResult
  ↓ Status(SUCCESS/FAILURE) + Summary 출력, exit code 반환
```

- CLI는 다음 행동을 결정하거나 Tool을 실행하지 않는다. 실행 루프는 AgentRunner에만 존재한다.
- Agent, AgentRunner, Llm은 Spring Bean으로 주입받는다.
- 파일 접근 범위 제한은 CLI가 아니라 MCP Server의 workspace 설정이 담당한다.
