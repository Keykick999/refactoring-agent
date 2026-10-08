# Agent Harness

LLM과 Tool을 활용하는 AI Agent를 실행하기 위한 **Agent Harness**를 개발하는 프로젝트입니다.

Harness는 Agent가 LLM을 호출하고, 필요한 Tool을 사용하며, 그 실행 흐름과 결과를 일관되게 관리할 수 있도록 하는 실행 기반을 제공하는 것을 목표로 합니다.

첫 번째 Agent로는 코드의 품질을 점검하고 개선점을 제안하는 **Code Quality Agent**를 구현하고 있습니다.

> 현재 MVP는 Agent 실행 루프, MCP Server를 통한 Tool 실행,
> Gemini LLM이 Tool 목록을 보고 다음 행동을 결정하는 흐름까지 구현되어 있습니다.

## 기술 스택

- Java 17
- Spring Boot 4.1
- Gradle 9 (Gradle Wrapper 포함)
- JUnit 5
- Jackson 3 (MCP JSON-RPC, Gemini API, LLM 응답 JSON 처리)
- Gemini API (JDK HttpClient로 호출)

## 패키지 구조

```
com.aicompany.agentharness
├── agent    # 개별 Agent (예: Code Quality Agent)
├── harness  # Agent 실행 흐름 관리
├── llm      # LLM 연동 추상화
│   └── gemini  # Gemini 구현체 (GeminiLlm)
├── tool     # Harness Tool 추상화와 ToolRegistry
├── mcp      # MCP Client와 MCP Tool Adapter
├── cli      # 터미널 실행 진입점 (인자 → Task → AgentRunner)
└── config   # Spring 설정 (MCP Server 실행, Tool 등록, LLM/Agent 구성)
```

설계 문서는 [`harness/`](harness/) 디렉터리에 있습니다.

## 빌드 및 실행

JDK 17이 로컬에 없으면 Gradle toolchain이 자동으로 내려받습니다.

```bash
# 빌드 + 테스트 (Fake MCP Server 사용, Node.js 불필요)
./gradlew build

# Code Quality Agent 실행 (아래 "CLI 실행" 참고)
./gradlew bootRun --args="--target=src/main/java/com/aicompany/agentharness/harness/Task.java"
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`을 사용합니다.

## CLI 실행

실행 인자를 Task로 변환해 AgentRunner로 CodeQualityAgent를 실행하고, 최종 결과를 출력합니다.
Node.js(Filesystem MCP Server)와 `GEMINI_API_KEY`가 필요합니다.

| 인자 | 필수 | 설명 |
| --- | --- | --- |
| `--target` | O | 분석할 파일. 상대 경로는 MCP workspace 기준 |
| `--objective` | X | 작업 목적. 생략하면 기본 코드 품질 분석 목적 사용 |

```bash
./gradlew bootRun --args="--target=src/main/java/com/aicompany/agentharness/harness/Task.java --objective='Review the code quality'"

# 또는 jar로 실행
./gradlew bootJar
java -jar build/libs/agent-harness-0.0.1-SNAPSHOT.jar --target=src/Main.java
```

출력 예:

```
========================================
Code Quality Agent
========================================

Objective: Review the code quality
Target: /path/to/agent-harness/src/main/java/com/aicompany/agentharness/harness/Task.java

Status: SUCCESS

Summary:
...
========================================
```

종료 코드: `0` 성공, `1` Agent 실패(FINISH 실패, 최대 step 초과, 실행 중 오류), `2` 인자 오류.
`bootRun`은 0이 아닌 종료 코드를 Gradle 빌드 실패로 표시합니다.
대상 파일 접근 범위는 MCP Server의 workspace 설정으로 제한됩니다.

## Filesystem MCP Server

Harness는 파일 접근을 직접 구현하지 않고, 외부 [Filesystem MCP Server](https://www.npmjs.com/package/@modelcontextprotocol/server-filesystem)를
stdio transport로 실행해 사용합니다.

### 요구사항

- Node.js (npx 포함). 처음 실행할 때 npx가 서버 패키지를 내려받습니다.

### 설정 (`src/main/resources/application.yml`)

```yaml
agent-harness:
  filesystem-mcp-server:
    enabled: true
    command: [npx, -y, "@modelcontextprotocol/server-filesystem@2026.8.31"]
    workspace: .      # 서버가 접근을 허용할 유일한 디렉터리
    timeout: 30s
```

- Harness는 `command` 뒤에 `workspace`의 절대 경로를 붙여 서버를 실행합니다.
  서버는 이 디렉터리 밖의 경로 접근을 거부합니다.
  상대 경로는 애플리케이션 실행 디렉터리 기준입니다.
- Windows에서는 `npx`가 `.cmd` 스크립트이므로 Harness가 `cmd /c`를 앞에 붙여 실행합니다.
- 다른 디렉터리를 분석하려면 실행 시 workspace를 지정합니다.

```bash
./gradlew bootRun --args="--agent-harness.filesystem-mcp-server.workspace=/path/to/project"
```

### Tool Permission Policy

MCP Server가 제공하는 모든 Tool은 등록되지만, Agent(LLM)에 보여주고 실행을 허용하는 Tool은 설정으로 제한합니다.

```yaml
agent-harness:
  tools:
    allowed: [read_text_file, read_multiple_files, list_directory, list_directory_with_sizes,
              directory_tree, search_files, get_file_info, list_allowed_directories]
    denied: [write_file, edit_file, create_directory, move_file]
```

- 허용 Tool = allowed에 있고 denied에 없는 Tool입니다(denied 우선). allowed가 비어 있으면 어떤 Tool도 허용하지 않습니다.
- LLM에는 허용된 Tool 정의만 전달되며, LLM이 다른 Tool을 요청해도 AgentRunner가 실행 직전에 거부합니다
  (`Tool execution denied: <name>`이 Tool 결과로 Agent에 전달됨).
- 서버가 제공하지 않는 Tool 이름은 시작 시 경고 로그만 남기고 무시합니다.
- 기본 설정은 Code Quality Agent V1에 맞춘 read-only 정책입니다.

### 실제 MCP Server 통합 테스트

기본 빌드는 Node.js 없이 동작하도록 테스트 전용 Fake MCP Server만 사용합니다.
실제 Filesystem MCP Server로 전체 흐름을 검증하려면 다음을 실행합니다.

```bash
./gradlew test -Pmcp.integration=true
```

PowerShell에서는 인자를 따옴표로 감싸야 합니다: `.\gradlew.bat test "-Pmcp.integration=true"`

## Gemini LLM

CodeQualityAgent는 Agent 역할/규칙, Task, 현재 AgentState, MCP Server에서 발견한 Tool 정의 목록을
Gemini에 전달하고, Gemini가 구조화된 JSON(USE_TOOL 또는 FINISH)으로 다음 행동을 결정합니다.

### API Key

API Key는 **환경변수 `GEMINI_API_KEY`로만** 주입합니다. 코드나 설정 파일에 값을 쓰지 않습니다.

```bash
export GEMINI_API_KEY=<your-api-key>         # macOS / Linux
$env:GEMINI_API_KEY = "<your-api-key>"       # PowerShell (현재 세션)
```

Key가 없어도 애플리케이션은 시작되며, LLM을 처음 호출할 때 명확한 오류로 실패합니다.

### 설정 (`src/main/resources/application.yml`)

```yaml
llm:
  gemini:
    api-key: ${GEMINI_API_KEY:}
    model: gemini-3.5-flash
    url: https://generativelanguage.googleapis.com/v1beta
    timeout: 60s
```

### LLM Retry

일시적인 오류(HTTP 429, 500/502/503/504, 네트워크 오류)는 exponential backoff로 재시도하고,
그 외 4xx(잘못된 API Key, 요청, 모델)는 즉시 실패합니다.

```yaml
llm:
  retry:
    enabled: true        # false이면 첫 실패를 그대로 전달
    max-attempts: 3      # 첫 호출 포함 (기본값: 1s, 2s 대기 후 최대 2번 재시도)
    initial-backoff: 1s
    multiplier: 2.0
    max-backoff: 10s     # Provider가 요청한 대기 시간(Retry-After 등)에도 적용
    jitter: 0.1          # ±10%
```

모든 시도가 실패하면 Agent는 기존처럼 실패로 종료합니다(CLI: `Status: FAILURE`).

### 실제 Gemini API 통합 테스트

기본 빌드는 실제 Gemini API를 호출하지 않습니다(Fake Llm, 로컬 Fake HTTP 서버 사용).
실제 Gemini와 실제 Filesystem MCP Server로 전체 흐름을 검증하려면 다음을 실행합니다(Node.js와 `GEMINI_API_KEY` 필요).

```bash
./gradlew test -Pllm.integration=true
```

PowerShell: `.\gradlew.bat test "-Pllm.integration=true"`
