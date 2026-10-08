# Architecture Decisions

이 문서는 프로젝트의 주요 설계 결정과 그 결정의 이유를 기록한다.

새로운 구조나 기술을 도입하거나 기존 설계를 변경할 때,
그 결정이 장기적으로 영향을 미칠 수 있다면 이 문서에 기록한다.

---

## ADR-001: Agent와 AgentRunner의 책임 분리

### Decision

Agent는 다음 행동을 결정하고,
AgentRunner는 Agent의 결정을 실행하고 전체 실행 흐름을 관리한다.

### Responsibilities

- Agent
  - 현재 AgentState를 기반으로 다음 행동을 결정한다.
  - Tool을 직접 실행하지 않는다.

- AgentRunner
  - Agent를 호출한다.
  - AgentDecision을 확인한다.
  - Tool을 실행한다.
  - ToolResult를 AgentState에 반영한다.
  - Agent가 FINISH를 결정할 때까지 실행을 반복한다.

### Reason

Agent의 업무 판단과 공통 실행 로직을 분리하기 위해서다.

이를 통해 서로 다른 Agent가 동일한 실행 구조를 사용할 수 있도록 한다.

---

## ADR-002: Tool과 Tool 실행 책임 분리

### Decision

Agent는 Tool을 직접 실행하지 않고,
ToolCall을 AgentDecision으로 반환한다.

실제 Tool 실행은 AgentRunner가 담당한다.

### Reason

Agent가 외부 시스템의 실행 세부사항을 알지 않도록 하여
판단과 실행의 책임을 분리한다.

---

## ADR-003: LLM 추상화

### Decision

Agent는 특정 LLM Provider의 구현에 직접 의존하지 않는다.

LLM 호출은 별도의 LLM 인터페이스를 통해 추상화한다.

### Reason

Agent의 핵심 로직과 LLM Provider의 구현을 분리하고,
향후 LLM Provider를 변경할 수 있도록 하기 위해서다.

---

## ADR-004: V1에서 실행 범위 제한

### Decision

Code Quality Agent V1은 코드 품질 분석과 개선에 집중한다.

다음 기능은 V1 범위에서 제외한다.

- 테스트 실행
- 테스트 실패 원인 분석
- 자동 버그 수정
- 배포
- Git 자동 커밋/푸시

### Reason

초기 Agent의 핵심 실행 구조를 단순하게 유지하고
Agent Harness의 기본 구조를 먼저 검증하기 위해서다.

---

## ADR-005: MCP Server를 Tool 제공자로 사용

### Decision

Harness는 파일 시스템 등 외부 작업을 수행하는 Tool을 직접 구현하지 않고,
MCP Server가 제공하는 Tool을 MCP Client를 통해 발견(tools/list)하고 호출(tools/call)한다.

Transport는 MCP 표준 stdio transport를 사용하며,
MCP Server는 Harness가 하위 프로세스로 실행한다.

MCP Client는 외부 SDK 없이 직접 구현하고, JSON 처리를 위해 Jackson만 사용한다.

### Reason

- Tool 구현을 표준 프로토콜 뒤로 분리하여, Harness 코드 변경 없이 MCP Server를 교체하거나 추가할 수 있다.
- 파일 접근 경로 제한 같은 보안 정책을 검증된 MCP Server에 맡기고, Harness에서 중복 구현하지 않는다.
- stdio transport는 로컬 프로세스 하나로 동작하여 MVP에서 가장 단순하다.

### Rejected Alternatives

- 공식 MCP Java SDK: 필요한 기능(initialize, tools/list, tools/call)에 비해 의존성(Reactor 등)이 크다.
- Java File I/O 기반 `ReadFileTool` 유지: Tool마다 Harness가 직접 구현·보안 처리해야 한다. (삭제됨)

### Consequences

- 실행 환경에 MCP Server 실행 수단(Filesystem MCP Server의 경우 Node.js/npx)이 필요하다.
- MCP Server가 접근할 workspace 디렉터리는 서버 실행 인자로 명시적으로 지정한다.

---

## ADR-006: MCP Tool을 공통 Adapter로 Harness Tool에 연결

### Decision

MCP Tool마다 Java 클래스를 만들지 않고,
tools/list 결과의 Tool 정의(name, description, input schema)를 그대로 사용하는
하나의 공통 Adapter(`McpTool`)로 Harness `Tool` 인터페이스를 구현한다.

MCP 응답과 MCP 관련 실패(JSON-RPC 오류, transport 오류)는 Adapter에서 Harness `ToolResult`로 변환한다.
실패 원인은 ToolResult 메시지에 남기고, 예외 stack trace는 로그로 보존한다.

### Reason

- Tool 이름과 schema를 Harness 코드에 하드코딩하지 않기 위해서다.
- 서버가 Tool을 추가하거나 변경해도 Harness 코드를 수정할 필요가 없다.
- AgentRunner와 ToolRegistry가 Tool 출처와 무관하게 동일한 방식으로 동작한다.

---

## ADR-007: Agent는 MCP를 알지 않고, Tool 매핑은 설정으로 주입

> Status: Tool 매핑 부분은 ADR-008로 대체됨. "Agent는 MCP를 알지 않는다"는 원칙은 유지한다.

### Decision

Agent는 `ToolCall(toolName, arguments)`만 사용하며 MCP의 존재를 알지 않는다.

LLM이 Tool을 선택하기 전까지(MVP), Agent가 특정 작업에 사용할 Tool 이름과 인자 이름
(예: Code Quality Agent의 파일 읽기 Tool)은 설정(`application.yml`)에서 주입한다.
설정된 Tool은 애플리케이션 시작 시 tools/list 결과에 존재하는지 검증한다.

### Reason

- Agent의 판단 로직과 Tool 제공자(MCP Server)의 구현 세부사항을 분리하기 위해서다.
- MCP Server의 Tool 이름이 바뀌어도 Agent 코드가 아닌 설정만 변경하면 된다.
- 잘못된 매핑을 Agent 실행 중이 아니라 시작 시점에 발견할 수 있다.

### Consequences

- LLM 기반 동적 Tool 선택을 도입하면 이 매핑은 Tool 목록(name, description, schema)을 LLM에 전달하는 방식으로 대체될 수 있다.

---

## ADR-008: LLM이 구조화된 JSON으로 다음 행동을 결정

### Decision

CodeQualityAgent는 다음 행동(Tool 사용 또는 종료)을 직접 결정하지 않고,
Agent 역할/규칙, Task, AgentState, 사용 가능한 Tool 정의 목록을 LLM에 전달하여 LLM이 결정하게 한다.

- LLM 응답은 `{"type": "USE_TOOL", "toolCall": {...}}` 또는 `{"type": "FINISH", "result": {...}}` 형식의 JSON이다.
- 응답 형식은 `LlmRequest.responseJsonSchema`로 전달하며, Gemini에서는 Structured Output
  (`responseMimeType: application/json` + `responseJsonSchema`)으로 강제한다.
- `AgentDecisionParser`가 응답을 검증하여 AgentDecision으로 변환한다.
  잘못된 JSON, 지원하지 않는 type, 필수 필드 누락은 Tool을 실행하지 않고 실패한 FINISH로 처리한다.
- LLM 호출 실패(`LlmException`)도 Agent가 실패한 FINISH로 변환하여, AgentRunner는 LLM을 알지 않는다.
- Gemini 연동은 `Llm` 인터페이스의 구현체(`GeminiLlm`)로만 추가하며, JDK HttpClient를 사용한다.
- ADR-007의 파일 읽기 Tool 매핑(`ReadFileToolMapping`, `agent-harness.code-quality.*`)은 제거한다.

### Reason

- Tool 이름과 schema를 Agent 코드나 설정에 고정하지 않고, tools/list 결과만으로 동작하게 하기 위해서다.
- 자유 형식 자연어 응답을 행동으로 해석하면 잘못된 Tool이 실행될 수 있으므로, 형식을 강제하고 엄격히 검증한다.
- Agent의 판단(LLM)과 실행(AgentRunner, Tool)의 책임 분리를 유지한다.

### Trade-offs

- LLM 응답에 따라 실행 경로가 달라지므로 실제 LLM을 사용하는 테스트는 결과를 완전히 고정할 수 없다.
  단위 테스트는 Fake Llm으로 결정 변환을 검증하고, 실제 API 테스트는 별도 스위치(`-Pllm.integration=true`)로 분리한다.
- 쓰기 Tool(write_file 등)도 LLM에 노출된다. 현재는 프롬프트 규칙으로만 수정을 제한하며,
  Tool 권한 정책은 이후 단계에서 다룬다. (ADR-009에서 해결)

---

## ADR-009: Tool Permission Policy로 Agent가 사용할 Tool을 제한

### Decision

MCP Server가 제공하는 모든 Tool은 그대로 ToolRegistry에 등록하고,
Agent가 볼 수 있는 Tool과 실제로 실행할 수 있는 Tool은 `ToolPermissionPolicy`로 제한한다.

- 허용 Tool = `agent-harness.tools.allowed`에 있고 `agent-harness.tools.denied`에 없는 Tool. denied가 allowed보다 우선한다.
- Agent(LLM)에는 정책을 통과한 Tool 정의만 전달한다.
- AgentRunner는 Tool 실행 직전에 정책을 다시 확인한다.
  거부된 Tool은 실행하지 않고 `ToolResult.failure(toolName, "Tool execution denied: <toolName>")`를 AgentState에 남긴다.
- 등록되지 않은 Tool 호출은 기존처럼 `Unknown tool`로 처리한다(존재 여부를 먼저 확인).
- 설정에 있지만 등록되지 않은 Tool 이름은 시작 시 경고 로그만 남기고 무시한다.
- Code Quality Agent V1은 read-only를 기본으로 한다. `application.yml`은 읽기/탐색 Tool만 allowed에 두고,
  파일을 변경하는 Tool(write_file, edit_file, create_directory, move_file)은 denied에 둔다.
- allowed가 비어 있거나 설정되지 않으면 어떤 Tool도 허용하지 않는다(fail-closed).

### Reason

- MCP Server는 Agent의 목적과 무관하게 모든 Tool을 제공할 수 있다. 어떤 Tool을 쓸지는 Harness가 결정해야 한다.
- LLM 출력은 신뢰할 수 없는 입력이다. Tool 목록에서 숨겨도 LLM이 이름을 추측하거나 지어낼 수 있으므로,
  노출 단계와 실행 단계 모두에서 검사한다.
- 실수로 allowed에 들어간 위험한 Tool을 denied로 확실히 막을 수 있도록 denied를 우선한다.
- 거부를 예외가 아닌 실패한 ToolResult로 처리하면 기존 Unknown tool 처리와 일관되고,
  Agent가 거부 사유를 보고 다음 행동을 결정할 수 있다.

### Alternatives

- allowed가 비어 있을 때 Java 코드에 내장한 read-only Tool 목록을 기본값으로 사용:
  특정 MCP Server의 Tool 이름을 코드에 하드코딩하게 되어 ADR-006/008의 원칙과 충돌한다.
  대신 read-only 기본 목록은 `application.yml`에 두고, 설정이 없으면 모두 거부한다.
- MCP Tool annotation(`readOnlyHint`)으로 자동 판별: 서버가 제공하는 힌트일 뿐 보장이 아니고,
  모든 MCP Server가 제공하지 않으므로 이번 단계에서는 명시적 이름 목록을 사용한다.
- 정책을 ToolRegistry에 넣어 등록 단계에서 제외: Registry의 책임(등록/조회)과 권한 판단이 섞이고,
  Agent마다 다른 정책을 적용하기 어려워진다.

### Consequences

- 새 Tool을 Agent에게 허용하려면 설정의 allowed에 추가해야 한다.
- AgentRunner 생성자에 `ToolPermissionPolicy`가 추가되었다. 실행 루프는 변경하지 않았다.

---

## ADR-010: LLM Retry Policy

### Problem

Gemini API는 rate limit/quota(HTTP 429)나 일시적인 서버 오류(5xx)를 반환할 수 있다.
지금까지는 첫 실패가 곧바로 Agent의 실패한 FINISH가 되어, 잠시 후 다시 호출하면 성공할 요청도 실패했다.

### Decision

LLM 호출 계층에 Retry를 둔다. AgentRunner와 Agent는 Retry를 알지 않는다.

```text
AgentRunner → CodeQualityAgent → Llm(RetryingLlm) → GeminiLlm → Gemini API
```

- `GeminiLlm`은 오류가 일시적인지만 판단하여 `RetryableLlmException`(LlmException 하위 타입)을 던진다.
  HTTP 상태와 헤더는 Provider 구현체만 알기 때문이다.
- `RetryingLlm`은 `Llm`을 감싸는 Provider 독립 데코레이터로, `RetryableLlmException`만 재시도한다.
- 모든 시도가 실패하면 `LlmException("LLM request failed after N attempts: ...")`을 던진다.
  CodeQualityAgent의 기존 처리(실패한 FINISH)와 CLI 출력(`Status: FAILURE`)은 그대로다.

### Retry 대상

- HTTP 429
- HTTP 500, 502, 503, 504
- 네트워크 오류: JDK HttpClient가 던지는 `IOException`(연결 거부, connect/request timeout, 연결 끊김 등).
  단 `SSLException`(인증서/프로토콜 문제)은 제외한다.

### Retry하지 않는 오류

- 그 외 4xx(400, 401, 403, 404 등)와 501: 잘못된 API Key, 요청, 모델은 다시 호출해도 실패한다.
- API Key 미설정, 응답 파싱 실패, candidates 없음, 인터럽트.

### Backoff 정책

- `max-attempts`(기본 3)는 첫 호출을 포함한 최대 호출 횟수다. 기본값에서는 1s, 2s 대기 후 최대 2번 재시도한다.
- 대기 시간 = `initial-backoff × multiplier^(실패 횟수 - 1)`, `max-backoff`(기본 10s)를 넘지 않는다.
- `jitter`(기본 0.1)만큼 ±비율로 대기 시간을 흔들어 여러 Agent의 동시 재시도를 분산한다.
- 설정은 기존 LLM 설정 위치와 형식을 따라 `llm.retry.*`에 두고, 시간은 `Duration`(`1s`)으로 쓴다.
- `llm.retry.enabled=false`이면 RetryingLlm을 사용하지 않아 기존과 동일하게 첫 실패를 전달한다.

### Retry-After 처리

- Provider가 대기 시간을 주면 exponential backoff 대신 그 값을 사용하되 `max-backoff`를 넘지 않는다.
- `Retry-After` 헤더(초 단위)를 우선하고, 없으면 Gemini 오류 본문의 `RetryInfo.retryDelay`(예: `"37s"`)를 사용한다.
  Gemini는 quota 초과 시 주로 본문으로 대기 시간을 알려준다.
- HTTP-date 형식의 Retry-After는 지원하지 않는다(Clock 의존 추가 대비 이득이 작음). 이 경우 exponential backoff를 쓴다.

### AgentRunner와 분리한 이유

- Retry는 외부 호출의 일시적 실패를 흡수하는 인프라 관심사이며, Agent 실행 흐름(ADR-001)과 무관하다.
- AgentRunner에 두면 LLM 호출 실패와 Agent의 실패한 판단을 구분해야 하고, AgentRunner가 LLM을 알게 된다.
- 데코레이터로 두면 다른 Provider를 추가해도 같은 Retry를 재사용할 수 있고, Agent 코드는 바뀌지 않는다.

### Alternatives

- Spring Framework 7 core retry(`RetryTemplate`, 의존성 추가 불필요): 대기를 내부에서 `Thread.sleep`으로 직접 수행해
  테스트에서 대기를 대체할 수 없고, BackOff가 예외를 보지 않아 Retry-After를 반영할 수 없어 사용하지 않았다.
- Spring Retry, Resilience4j: 새 의존성이 필요하고, 필요한 기능에 비해 크다.

### Test Strategy

- 대기는 `RetrySleeper`로 분리하여 테스트에서 실제로 기다리지 않고 요청된 대기 시간만 기록·검증한다.
- Fake Gemini HTTP 서버로 응답 순서(429 → 200 등)를 고정하여, 실제 `GeminiLlm`의 오류 분류와 `RetryingLlm`의 재시도를 함께 검증한다.
- jitter는 테스트에서 0으로 두고, jitter 범위는 별도 테스트로 경계만 검증한다.
- 실제 Gemini 통합 테스트는 기존 스위치(`-Pllm.integration=true`)를 그대로 사용한다.

---

## ADR 작성 규칙

새로운 설계 결정을 기록할 때 다음 형식을 사용한다.

### Decision

무엇을 결정했는지 명확하게 작성한다.

### Reason

왜 해당 결정을 했는지 작성한다.

필요한 경우 다음 항목을 추가할 수 있다.

- Alternatives
- Trade-offs
- Consequences
- Rejected Alternatives