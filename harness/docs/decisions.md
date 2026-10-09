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

> Status: ADR-011로 구체화됨. V1은 코드를 수정하지 않는 Read-only Code Quality Agent이며, "개선"은 Improvement Plan 제안을 의미한다.

### Decision

Code Quality Agent V1은 Code Quality Analysis와 개선안(Improvement Plan) 제안에 집중한다.

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
- 기본 workspace는 프로젝트 루트가 아닌 `src`로 둔다. `.git`, `build`, Gradle 설정, `.env` 등
  분석 대상이 아닌 파일을 Agent(와 LLM)에 노출하지 않기 위해서다.

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
- Code Quality Agent V1은 read-only를 기본으로 한다(V1 범위는 ADR-011). `application.yml`은 읽기/탐색 Tool만 allowed에 두고,
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

## ADR-011: V1은 Read-only Code Quality Agent

### Decision

V1은 Read-only Code Quality Agent다.
지정된 코드 범위를 탐색하고 분석하여 Code Quality Issue와 Improvement Plan을 Report로 제시하지만,
소스 코드 파일을 직접 수정하지 않는다.

- V1 범위: 코드 읽기/탐색, Code Quality Analysis, Code Quality Issue 식별, Improvement Plan 제안, Report 생성
- V1 제외: Code Modification(write/edit/move를 통한 파일 변경, 코드 자동 수정, 리팩토링 코드 적용),
  수정 후 테스트 실행, 테스트 실패 분석 후 재수정, 수정 → 검증 → 재수정 루프
- Code Modification은 V2 이상의 범위로 둔다. V2의 구체적인 설계는 아직 확정하지 않는다.

### Reason

- requirements.md와 03-constraints.md는 V1에 코드 수정을 포함하고, ADR-009는 V1을 read-only로 정의하여
  문서 간 V1 범위가 충돌했다. 현재 구현(Tool Permission Policy로 파일 변경 Tool 거부)과 일치하는 read-only 방향을 채택한다.
- 코드 수정은 테스트 실행, 검증, 재수정 루프까지 함께 설계해야 안전하게 제공할 수 있다.
  V1에서는 Harness의 기본 실행 구조와 분석 품질을 먼저 검증한다.

### Consequences

- requirements.md, workflow.md, 03-constraints.md의 V1 범위를 read-only로 통일했다.
- ADR-004의 "개선"은 Improvement Plan 제안을 의미하는 것으로 구체화한다.

---

## ADR-012: Work Unit으로 분석 범위를 나눈다

### Decision

"어떤 코드를 LLM 한 번의 분석으로 묶을 것인가"를 `context` 패키지의 `WorkUnitPlanner`가 담당한다.

```text
Target → (Code Explorer) → List<SourceFile> → WorkUnitPlanner(GroupingStrategy, ContextSizeEstimator) → List<WorkUnit> → (Context Builder) → LLM
```

- V1의 묶는 기준은 파일 위치(`PackageGroupingStrategy`: 같은 디렉터리 = 같은 그룹)와 Context 크기뿐이다.
- 그룹이 최대 Context 크기를 넘으면 경로순으로 여러 Work Unit으로 나눈다.
- 코드는 요약하거나 자르지 않는다. 파일 하나가 한도를 넘으면 단독 Work Unit으로 두고 `exceedsContextLimit`로 표시하며,
  처리 방법(건너뛰기, 사용자 알림 등)은 사용하는 쪽이 결정한다.
- 결과는 입력 순서와 무관하게 결정적이다(그룹은 이름순, 파일은 경로순).
- GroupingStrategy가 파일을 빠뜨리거나 중복시키면 계획 단계에서 실패시켜, 분석되지 않는 코드가 생기지 않게 한다.

### Extension Points

- 묶는 기준: `GroupingStrategy` 구현을 추가한다(Domain, DDD, Dependency, 사용자 정의 등).
  크기 분할과 정렬은 Planner가 공통으로 처리하므로 전략은 묶는 기준만 결정한다.
- 크기 측정: `ContextSizeEstimator`를 교체한다. V1은 문자 수(`CHARACTERS`)이며, Provider의 token 계산으로 바꿀 수 있다.
- 분석 스타일(General, Clean Code, SOLID, DDD, Spring/JPA, Architecture, Performance 등)은 "무엇을 묶을지"가 아니라
  "어떤 관점으로 분석할지"이므로 Work Unit과 분리한다. 향후 Agent의 프롬프트(또는 Context Builder)에 주입하는 방식으로 추가한다.
  필요성이 확인되기 전에는 코드로 만들지 않는다.

### Not Yet Decided

- Code Explorer: Target 디렉터리의 파일 목록과 내용을 MCP Tool로 수집하는 주체.
  AgentRunner 밖에서 Tool을 실행하게 되므로 ADR-002와의 관계를 함께 결정해야 한다.
- Work Unit 실행: Work Unit마다 Agent를 실행하고 결과를 하나의 Report로 합치는 흐름, 최대 Context 크기 설정값.
- 따라서 현재 CLI/Agent 실행 흐름은 이 ADR 시점에는 변경하지 않았다. 클래스별 실행은 ADR-013에서 연결했다.

### Reason

- 분석 범위 결정은 Agent의 판단(다음 행동 결정)이나 LLM 호출과 다른 책임이며, 크기 한도 때문에 반드시 필요해진다.
- 코드를 잘라 전달하면 LLM이 존재하지 않는 문제를 지적하거나 실제 문제를 놓칠 수 있으므로, 원문을 유지하고 묶는 방식만 조정한다.
- 묶는 기준은 프로젝트와 분석 목적에 따라 달라질 수 있으므로 교체 지점을 두되, V1은 가장 단순한 기준(패키지)만 구현한다.

---

## ADR-013: 클래스 하나씩 분석하고 Java 객체로 모은다

### Decision

CLI의 Code Quality 분석은 Java 파일 하나를 한 번의 LLM 요청으로 분석한다.

```text
CLI
  → McpJavaSourceExplorer (search_files / read_text_file, ToolPermissionPolicy 확인)
  → ClassAnalysisCoordinator
       → 파일마다 AgentRunner.run(ClassCodeQualityAgent)
            → Llm 한 번 (원문 전체, Tool 선택 없음)
            → ClassAnalysisParser → ClassAnalysisResult
       → ClassAnalysisCollector
  → AnalysisReport (LLM 호출 없이 텍스트로 렌더링)
```

- 파일 탐색은 LLM 판단이 아니므로 AgentRunner의 Tool 루프 밖에서 기존 MCP Tool로 수행한다. 실행 전 권한 검사는 AgentRunner와 같은 정책을 쓴다.
- 분석 요청에는 그 클래스의 원문만 넣는다. 이전 클래스의 원문과 Tool 정의는 넣지 않는다. 코드는 자르거나 요약하지 않는다.
- 설정된 문자 수를 넘는 파일은 LLM에 보내지 않고 UNSUPPORTED로 기록한다.
- 한 클래스의 실패(LLM 오류, 잘못된 JSON, 파일 읽기 실패)는 그 결과만 FAILED로 남기고 다음 클래스를 계속한다.
- 같은 경로의 파일은 한 번만 분석한다. 같은 클래스 안에서 제목과 근거가 같은 이슈만 한 번 남긴다.
- 하나 이상 성공하면 CLI는 성공으로 끝낸다. 대상이 없거나 모두 실패하면 실패로 끝낸다. 어느 쪽이든 보고서는 출력한다.
- 기존 CodeQualityAgent의 Tool 선택 루프는 그대로 둔다. 이 흐름은 클래스 분석 전용 Agent를 AgentRunner에 넣어 재사용한다.
- 실행 사이의 저장과 재개는 ADR-014를 따른다. 한 클래스 안에서는 여전히 Collector에 모은 뒤 저장한다.

### Reason

- 분석 단위를 클래스로 고정하면 호출마다 맥락이 커지지 않고, 결과도 클래스별로 남길 수 있다.
- 파일 목록을 LLM이 고르게 하면 같은 파일을 반복해서 읽거나 빠뜨릴 수 있다.
- 보고서 문장은 수집된 객체에서 결정적으로 만들 수 있으므로 마지막에 LLM을 한 번 더 부르지 않는다.

---

## ADR-014: 클래스 분석 결과를 저장하고 이어서 재개한다

### Decision

클래스별 분석 결과는 H2 파일 데이터베이스에 저장한다. 실행 중 저장소는 `./data/agent-harness`이고, 테스트는 메모리 데이터베이스를 쓴다.

작업의 신원은 정규화한 대상 경로, objective 문자열, 설정 지문(원문 길이 한도, 모델 이름, 검색 Tool 이름, 읽기 Tool 이름)이다. 셋 중 하나라도 다르면 새 작업이다.

- 클래스 `SUCCEEDED`는 다시 분석하지 않는다. `UNSUPPORTED`와 재개할 수 없는 `FAILED`도 다시 분석하지 않는다.
- 프로세스가 죽어서 남은 `RUNNING`은 다음 실행에서 `PENDING`으로 되돌린 뒤 분석한다.
- 네트워크, 429, 5xx, 인증, 저장 실패는 같은 작업을 다시 실행할 때 그 클래스만 재개한다. 이번 호출 안의 재시도는 기존 RetryingLlm이 담당한다.
- 인증 오류는 남은 클래스를 진행하지 않고 작업을 `FAILED`로 둔다. 이미 저장된 성공 결과는 유지한다.
- 모든 클래스가 성공하면 작업은 `COMPLETED`다. 실행이 끝났지만 실패하거나 지원하지 않는 클래스가 있으면 `PARTIALLY_COMPLETED`다. 저장된 보고서는 다시 분석하지 않고 출력한다.
- 클래스 결과와 성공 상태 변경은 하나의 짧은 트랜잭션이다. LLM 호출과 재시도 대기는 트랜잭션 밖에 있다.
- 원문과 API 키는 저장하지 않는다. 재개할 때는 MCP로 원문을 다시 읽는다.
- 같은 작업의 중복 실행은 데이터베이스 트랜잭션 밖에서 운영체제 파일 잠금으로 막는다. 잠금을 얻지 못하면 분석을 시작하지 않는다.

### Reason

- 인증 오류나 프로세스 중단 뒤에 성공한 클래스를 다시 분석하지 않아야 한다.
- 작업 신원에 설정이 들어가야 모델이나 길이 한도가 바뀌었을 때 이전 결과와 섞이지 않는다.
- 분석 호출을 트랜잭션 안에 두면 연결을 오래 붙잡고, 재시도 대기 동안 잠금이 유지된다.

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