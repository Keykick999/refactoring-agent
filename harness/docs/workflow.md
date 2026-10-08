# Agent Workflow

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
Need improvement?
  ├─ NO → Summarize result → END
  │
  └─ YES
       ↓
    Modify code
       ↓
    Summarize result
       ↓
      END