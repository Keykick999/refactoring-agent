# Agent Harness

LLM과 Tool을 활용하는 AI Agent를 실행하기 위한 **Agent Harness**를 개발하는 프로젝트입니다.

Harness는 Agent가 LLM을 호출하고, 필요한 Tool을 사용하며, 그 실행 흐름과 결과를 일관되게 관리할 수 있도록 하는 실행 기반을 제공하는 것을 목표로 합니다.

첫 번째 Agent로는 코드의 품질을 점검하고 개선점을 제안하는 **Code Quality Agent**를 구현하고 있습니다.

> 현재는 클래스 단위 Code Quality 분석, AST 기반 코드 관계 그래프,
> 목표를 Task로 나눠 저장하고 이어서 수행하는 계획 기반 실행까지 구현되어 있습니다.

## 범위

저장소 이름(`refactoring-agent`)은 장기 목표인 자동 리팩토링을 나타냅니다. 현재 V1이 수행하는 기능은 Code Quality Analysis입니다.

- **V1: Read-only Code Quality Agent (Code Quality Analysis)**
  - 지정된 코드를 탐색·분석하여 Code Quality Issue와 Improvement Plan을 Report로 제시합니다.
  - 기본 CLI(`--mode=class`)는 Java 파일을 하나씩 분석합니다.
  - `--mode=plan`은 목표를 Task로 분해하고, Graph/MCP로 필요한 정보만 모은 뒤 작업 결과를 저장하면서 이어서 수행합니다.
  - 소스 코드 파일은 수정하지 않습니다(파일을 변경하는 Tool은 Tool Permission Policy로 거부).
- **V2 이상: Code Modification (Automated Code Refactoring)**
  - 코드 수정 → 테스트 → 검증 → 리뷰 → 반복 개선. 구체적인 설계는 아직 확정하지 않았습니다.

자세한 범위는 [`harness/docs/requirements.md`](harness/docs/requirements.md), ADR-011, ADR-015를 참고하세요.

## 기술 스택

- Java 17
- Spring Boot 4.1
- Gradle 9 (Gradle Wrapper 포함)
- JUnit 5
- Jackson 3 (MCP JSON-RPC, Gemini API, LLM 응답 JSON 처리)
- Gemini API (JDK HttpClient로 호출)
- H2와 Spring Data JPA (클래스 분석 결과와 계획 기반 Task 저장)
- JavaParser Symbol Solver (프로젝트 내부 타입의 구조적 관계 추출)

## 패키지 구조

```
com.aicompany.agentharness
├── agent    # 개별 Agent (예: Code Quality Agent)
├── harness  # Agent 실행 흐름 관리
├── analysis # 클래스별 분석, 작업 저장, 재개
├── graph    # Java AST에서 추출한 클래스 관계 그래프
├── plan     # 계획 기반 문제 분해와 작업 실행
├── persistence # H2에 클래스 분석 결과와 계획/Task 결과를 저장
├── context  # 분석 작업 단위(Work Unit) 계획 (아직 실행 흐름에 연결되지 않음)
├── llm      # LLM 연동 추상화
│   └── gemini  # Gemini 구현체 (GeminiLlm)
├── tool     # Harness Tool 추상화와 ToolRegistry
├── mcp      # MCP Client와 MCP Tool Adapter
├── cli      # 터미널 실행 진입점 (인자 → 클래스 분석 또는 계획 기반 실행)
└── config   # Spring 설정 (MCP Server 실행, Tool 등록, LLM/Agent 구성)
```

설계 문서는 [`harness/`](harness/) 디렉터리에 있습니다.

## 빌드 및 실행

JDK 17이 로컬에 없으면 Gradle toolchain이 자동으로 내려받습니다.

```bash
# 빌드 + 테스트 (Fake MCP Server 사용, Node.js 불필요)
./gradlew build

# Code Quality Agent 실행 (아래 "CLI 실행" 참고). target은 workspace(src) 기준 경로
./gradlew bootRun --args="--target=main/java/com/aicompany/agentharness/harness/Task.java"
```

Windows에서는 `./gradlew` 대신 `gradlew.bat`을 사용합니다.

## CLI 실행

실행 인자를 Task로 변환해 분석을 돌리고, 최종 결과를 출력합니다.
Node.js(Filesystem MCP Server)와 `GEMINI_API_KEY`가 필요합니다.

기본 `--mode=class`는 Java 파일을 하나씩 AgentRunner로 분석합니다.
`--mode=plan`은 목표를 Task로 분해한 뒤, 실행 가능한 작업을 하나씩 처리하고 필요할 때 계획을 고칩니다.

| 인자 | 필수 | 설명 |
| --- | --- | --- |
| `--target` | O | 분석할 Java 파일 또는 디렉터리. 상대 경로는 MCP workspace(기본 `src`) 기준 |
| `--objective` | X | 작업 목적. 생략하면 기본 코드 품질 분석 목적 사용 |
| `--mode` | X | `class`(기본)는 파일마다 분석한다. `plan`은 목표를 작업으로 나누어 저장하고 이어서 수행한다 |
| `--style` | X | `--mode=plan`일 때 분석 관점. `GENERAL`, `SOLID`, `LAYERED_ARCHITECTURE` |

```bash
# 클래스 단위 분석 (기본)
./gradlew bootRun --args="--target=main/java/com/aicompany/agentharness/harness/Task.java --objective='Review the code quality'"

# 계획 기반 분석. style은 작업 분해 가이드이며, SOLID 규칙을 코드로 검사하지는 않는다
./gradlew bootRun --args="--target=main/java --mode=plan --style=SOLID --objective='Analyze whether this code follows SOLID'"

# 또는 jar로 실행 (workspace `src`는 실행 디렉터리 기준이므로 프로젝트 루트에서 실행)
./gradlew bootJar
java -jar build/libs/agent-harness-0.0.1-SNAPSHOT.jar --target=main/java/com/aicompany/agentharness/harness/Task.java
```

`--target` 경로 규칙:

- 상대 경로는 **MCP workspace(기본 `src`) 기준**으로만 해석합니다. 프로젝트 루트 기준으로 해석하지 않습니다.
- 절대 경로는 workspace 안에 있을 때만 허용합니다.
- 다음 경우에는 Agent(LLM)를 실행하지 않고 오류 메시지와 함께 종료 코드 `2`로 끝납니다.
  - workspace 밖을 가리키는 경로(`../build.gradle`, workspace 밖 절대 경로, workspace 밖을 가리키는 symlink)
  - 존재하지 않는 경로
- `--target=src/main/...`처럼 프로젝트 루트 기준으로 쓰면 `src/src/main/...`으로 해석되어 존재하지 않으므로 거부되며,
  앞의 `src/`를 빼라는 안내가 출력됩니다. 두 기준을 섞어 자동으로 보정하지 않습니다.
- CLI의 검사는 잘못된 입력을 미리 알리기 위한 것이며, 파일 접근 제한의 최종 책임은 MCP Server에 있습니다.

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

종료 코드: `class` 모드는 클래스 분석이 하나라도 성공하면 `0`, 대상이 없거나 모두 실패하면 `1`. `plan` 모드는 전체 계획이 완료되면 `0`, 막히거나 실패하면 `1`. 인자 오류(잘못된 `--target` / `--mode` 포함)는 `2`.
`class` 모드에서 일부 클래스만 실패해도 나머지는 계속 분석하며, 실패 사유는 같은 보고서에 남습니다.
같은 대상 경로, 같은 `--objective`, 같은 분석 설정으로 다시 실행하면 이미 끝난 클래스는 저장된 보고서를 쓰고, 아직 끝나지 않았거나 일시적으로 실패한 클래스만 이어서 분석합니다. 인증 오류로 작업이 멈춰도 그 전에 성공한 클래스는 남습니다. 완료된 작업을 처음부터 다시 분석하는 옵션은 없습니다.
분석 결과는 실행 디렉터리의 `data/`에 저장됩니다. 이 디렉터리는 Git에 포함하지 않습니다. 같은 작업의 중복 실행은 `data/locks`의 파일 잠금으로 막습니다.
`bootRun`은 0이 아닌 종료 코드를 Gradle 빌드 실패로 표시합니다.
대상 파일 접근 범위는 MCP Server의 workspace 설정으로 제한됩니다.

### 계획 기반 분석 (`--mode=plan`)

목표는 한 번의 LLM 호출로 끝내지 않습니다. Decomposer가 Task로 나누고, 선행 조건이 끝난 Task만 Solver가 처리합니다. Solver는 코드 관계 그래프로 관련 타입을 찾고, MCP로 필요한 원문만 읽습니다. 작업 결과(facts, conclusion, evidence, unresolved questions)는 H2에 저장되며, 필요할 때 Task를 추가하거나 취소할 수 있습니다. 실행할 Task가 없으면 Final Check가 추가 작업 필요 여부를 보고, 더 할 일이 없으면 저장된 결과만 모아 최종 보고서를 만듭니다.

- `--style`은 분해와 판단의 가이드입니다. SOLID/계층 규칙을 if문으로 검사하지 않습니다.
- 같은 대상, 같은 objective, 같은 style로 다시 실행하면 완료된 계획은 저장된 보고서를 쓰고, 중간에 끊긴 `IN_PROGRESS` Task는 `PENDING`으로 되돌린 뒤 이어서 수행합니다.
- Graph는 프로젝트 내부 클래스/인터페이스의 `DEPENDS_ON` / `EXTENDS` / `IMPLEMENTS`만 담습니다. 외부 라이브러리 타입은 넣지 않습니다.

```yaml
agent-harness:
  problem-solving:
    max-solver-steps: 8        # 한 Task 안에서 Tool을 쓰는 최대 횟수
    max-plan-iterations: 20    # 전체 루프에서 Task를 처리하는 최대 횟수
    max-final-check-rounds: 3  # 추가 작업이 없다고 볼 때까지 Final Check 횟수
```

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
    workspace: src    # 서버가 접근을 허용할 유일한 디렉터리
    timeout: 30s
```

- Harness는 `command` 뒤에 `workspace`의 절대 경로를 붙여 서버를 실행합니다.
  서버는 이 디렉터리 밖의 경로 접근을 거부합니다.
  상대 경로는 애플리케이션 실행 디렉터리 기준입니다.
- 기본 workspace는 프로젝트의 `src`입니다. `.git`, `build`, `gradle`, `build.gradle`, `.env` 등
  프로젝트 루트의 파일은 Agent가 MCP Tool로 읽을 수 없습니다.
- API Key 같은 민감한 정보는 파일이 아닌 환경변수(`GEMINI_API_KEY`)로만 주입합니다.
  workspace(`src`) 안에 비밀 값이 담긴 파일을 두지 마세요.
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

클래스 분석과 계획 기반 Solver 모두 Gemini에 구조화된 JSON 응답을 요청합니다.
클래스 분석은 파일 하나의 이슈 목록을 받고, 계획 기반 실행은 작업 분해 / Tool 사용 / Task 결과 / 종료 판단 / 최종 종합을 역할별로 요청합니다.

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
