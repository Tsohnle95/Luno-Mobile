# Temporary task memory

Owns recovery instructions for active work; completed history belongs in Git.
See [execution modes](../../docs/agent-execution.md). Keep this README and
[_TEMPLATE.md](_TEMPLATE.md); create one named Markdown file per active project.

- PATCH: no task file. FEATURE: normally an in-context plan.
- PROJECT: copy the template before coding, record verified evidence, review the
  plan, then proceed through DRAFT → REVIEWED → ACTIVE → COMPLETE → DELETE.
- BATCH: triage once; persist only when recovery or handoff warrants it.

Keep acceptance, decisions, phase status, actual validation results, risks, and
useful checkpoint IDs current. Link canonical owners instead of copying prose.
Do not store secrets, transcripts, or speculative architecture here.

To resume: read root/scoped instructions, the active file, relevant canonical
docs, Git status/diff and referenced source/tests. Verify assumptions against
current code; preserve unrelated changes. Continue at the first incomplete
phase. If no checkpoint exists, say so; never invent a commit or passing check.
At completion update canonical docs, move genuine remaining work to an issue
or an explicitly tracked backlog, and delete the completed task file. Do not
delete another task merely because it appears old.
