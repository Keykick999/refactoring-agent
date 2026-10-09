# Constraints

## General

- 요구사항에 없는 기능을 임의로 추가하지 않는다.
- 기존 구조를 이유 없이 변경하지 않는다.
- 필요 이상의 라이브러리를 추가하지 않는다.
- 복잡한 설계를 단순한 문제에 적용하지 않는다.

## Agent

- Agent가 Tool을 직접 실행하지 않는다.
- Tool이 Agent의 판단을 대신하지 않는다.
- AgentRunner에 특정 Agent의 업무 로직을 넣지 않는다.
- AgentRunner가 특정 Tool을 하드코딩하지 않는다.
- Agent와 Harness의 책임을 혼합하지 않는다.

## LLM

- LLM 호출 자체를 비즈니스 로직과 강하게 결합하지 않는다.
- 특정 LLM provider에 의존하는 구현을 핵심 Agent 로직에 직접 넣지 않는다.

## Tool

- Tool 내부에 Agent의 의사결정 로직을 넣지 않는다.
- Tool은 자신의 책임에 필요한 작업만 수행한다.

## Scope

V1은 Read-only Code Quality Agent다. V1에서는 다음 범위를 유지한다.

- 코드 읽기/탐색
- Code Quality Analysis
- Code Quality Issue 식별
- Improvement Plan 제안
- Report 생성

V1에서는 다음을 지킨다.

- 소스 코드를 직접 수정하지 않는다.
- 파일을 변경하는 Tool(write/edit/move)을 사용하지 않는다.
- 분석 결과를 실제 파일에 반영하지 않는다.
- 분석에 필요한 파일은 read-only 방식으로 접근한다.

실제 Tool 허용/거부는 Tool Permission Policy(ADR-009)가 담당한다.

다음 기능은 V1 범위에서 제외한다.

- Code Modification (코드 자동 수정, 리팩토링 코드 적용)
- 테스트 실행
- 테스트 실패 원인 분석
- 자동 버그 수정
- 수정 → 검증 → 재수정 루프
- 배포
- Git 자동 커밋/푸시

Code Modification은 V2 이상의 범위로 둔다.