# Agent Workflow

## V1: Read-only Code Quality Agent

```text
START
  ↓
Specify analysis scope
  ↓
Explore target code
  ↓
Analyze code
  ↓
Identify quality issues
  ↓
Create improvement plan
  ↓
Generate report
  ↓
FINISH
```

V1 workflow는 코드를 수정하지 않는다.
write, edit, test, fix, verification loop 단계를 포함하지 않는다.

## Future (V2 이상)

Code Modification을 포함한 workflow는 V2 이상에서 다룬다.

```text
Code Modification → Test → Verification → Review → Iterative Refinement
```

구체적인 workflow는 아직 확정하지 않는다.
