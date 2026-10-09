# Code Quality Agent Requirements

## Goal

V1은 지정된 코드 범위를 분석하고
코드 품질 개선안을 제시하는 Read-only Code Quality Agent다.

소스 코드 파일은 직접 수정하지 않는다.

## Quality Criteria

다음 항목을 중심으로 분석한다.

- Readability
- Maintainability
- Duplication
- Complexity
- Responsibility
- Naming
- Coupling
- Cohesion
- Design

## Workflow

1. 분석 대상 확인
2. 관련 코드 탐색
3. 코드 분석 (Code Quality Analysis)
4. 품질 문제 식별 (Code Quality Issue)
5. 개선 계획 수립 (Improvement Plan)
6. 결과 정리 (Report)

## V1 Scope

- 코드 읽기/탐색
- Code Quality Analysis
- Code Quality Issue 식별
- Improvement Plan 제안
- 최종 분석 결과(Report) 생성

## V1 Excluded

- Code Modification (write_file, edit_file, move_file 등을 통한 파일 변경)
- 코드 자동 수정
- 리팩토링 코드 직접 생성 및 적용
- 테스트 실행
- 테스트 실패 분석
- 버그 자동 수정
- 수정 → 검증 → 재수정 루프
- 배포

## Future Scope (V2 이상)

V2 이상에서는 Code Modification을 다룬다.

```text
Code Modification
  ↓
Test
  ↓
Verification
  ↓
Review
  ↓
Iterative Refinement
```

V2의 구체적인 설계는 아직 확정하지 않는다.
