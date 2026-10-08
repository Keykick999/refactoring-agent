# Agent Development Rules

## Agent Development Flow

Agent를 개발할 때 다음 순서를 따른다.

1. 요구사항 확인
2. Task 정의
3. State 정의
4. Agent 판단 구조 정의
5. 필요한 Tool 정의
6. 실행 흐름 구현
7. 결과 구조 정의
8. 테스트

## Decision

Agent는 현재 State를 기반으로 다음 행동을 결정한다.

결정 가능한 기본 행동:

- USE_TOOL
- FINISH

## Execution

Agent는 실행보다 판단에 집중한다.

실제 Tool 실행은 AgentRunner가 담당한다.

## Tool Addition

새로운 Tool을 추가할 때 다음을 정의한다.

- Tool name
- Responsibility
- Input
- Output
- Failure behavior

## Changes

기존 Agent 구조를 변경해야 하는 경우:

1. 변경 이유를 확인한다.
2. 기존 책임과 충돌하지 않는지 확인한다.
3. 필요한 최소 범위만 변경한다.