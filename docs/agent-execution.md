# Agent execution protocol

Owns execution behavior, classification, replanning and completion. Root
[AGENTS.md](../AGENTS.md) owns routing/safety essentials. Domain docs own durable
architecture; [.agent/tasks/](../.agent/tasks/README.md) owns temporary recovery.

**Use the minimum process that keeps expected repair cost acceptably low.**
Planning reduces uncertainty, coordination cost and likely rework; it is not
ceremony. Optimize total cost of correctness, including repair and recovery.

## Core loop

Understand → route context → classify → plan as needed → implement → targeted
validation → review → canonical validation → update durable truth → checkpoint.

Inspect Git status and preserve existing work first. Load root instructions,
applicable scoped deltas, relevant owners and specific symbols/tests in order.
Use topology/config first for unfamiliar work, then targeted symbols and tests;
do not reopen established facts or crawl routine local tasks. Source/config/schema
defines implemented technical facts. Tests are evidence for exercised behavior.

## Modes

Classify by ambiguity, blast radius, coupling, novelty, failure cost and
testability. Choose a lighter mode only when wrong assumptions can be detected
and corrected safely. File count alone is insufficient.

| Mode | Use | Planning and implementation |
| --- | --- | --- |
| PATCH | Localized, understood, low coupling, clear acceptance and straightforward verification | Relevant source/docs/tests; smallest coherent change; focused validation; diff review; canonical gate; durable docs only if truth changed; logical commit when authorized. **No persistent plan file.** |
| FEATURE | Coordinated components/dependent steps, understood architecture | Concise in-context acceptance, components/contracts, sequence, checks and uncertainties. Small testable slices; reassess after meaningful progress. Persist only when sessions, agents, commits, handoff or recovery warrants it. |
| PROJECT | Architecture, contracts, migrations/persistence, trust/security, concurrency/recovery, major integration, substantial uncertainty or tightly coupled phases | Before coding create a [temporary task](../.agent/tasks/_TEMPLATE.md), investigate and record verified facts, define acceptance/preserved behavior/invariants/surfaces/phases/checks, adversarially review, then revise decisions with rationale. Implement/review/validate one phase at a time. |
| BATCH | Independently completable outcomes | Triage once for shared causes, dependencies, conflicts and overlapping ownership. Cluster/order, classify each PATCH/FEATURE/PROJECT and execute separately. Persist only for recovery/handoff. This is orchestration, not complexity. |

For PROJECT review unsupported assumptions, missing contracts/tests,
compatibility, security, state, races/recovery, duplication, sequencing,
scope and unverifiable goals. Maintain continuation state, actual check results
and useful checkpoint IDs. Commit independently sound checkpoints when
authorized. Finish with cumulative/integration and canonical validation, full
diff/commit-series review, owner docs updated and genuine remaining work routed
to a tracked issue/backlog. Mark COMPLETE and delete the task; Git owns history.

## Replan and recovery

Replan when assumptions/contracts prove false, scope grows materially,
unexpected dependencies appear, two repairs fail for the same cause, unrelated
refactors become prerequisites, new durable contracts/migrations emerge,
correctness becomes unclear, or obsolete investigation dominates context.
Preserve verified work, summarize new evidence, adjust mode/plan and continue
from a known-good state. Do not create specifications, proposals, task files or
subagent hierarchies without concrete coordination/recovery value.

Resume using root/scoped instructions, active task, owner docs, current
source/tests and Git status/diff/checkpoints. Verify the recorded continuation
against current code. Missing conversation history must not block recovery;
missing authorization or secrets must not be invented.

## Delegation and validation

Parallelize independent discovery, investigation and review aggressively when
available/authorized. Delegate implementation conservatively with agreed
boundaries; do not concurrently edit coupled contracts/shared state. The
coordinator owns synthesis, architecture, sequencing and final validation.
Subagent output is evidence requiring source checks; delegation is optional.

Choose checks by risk:

- Logic/state: focused tests and affected integration path.
- Contract/API/session: producers, transports, consumers and contract tests.
- Persistence/trust/concurrency/recovery: invariant, failure and actual recovery
  checks where relevant; in-memory DAO tests do not prove migrations.
- UI/platform/external runtime: interaction, appearance, restart, notification,
  real provider/device or integration checks when acceptance requires them.

Use the supported toolchain and [canonical gate](android-development.md).
Unsupported runtime results are non-authoritative; rerun final verification.
Do not broaden/repeat successful checks without new changes/failures/concerns.
The gate is bounded and non-interactive; device checks are separate. Record
actual results and unverified surfaces; timeout/skipped checks are never passes.

## Truth and checkpoints

Update only truth that changed: responsibilities/invariants at the domain owner;
transfer at the contract owner/schema; toolchain/setup at config/operations;
routing at root/scoped instructions. Pure refactors need no doc churn. Reconsider
doc boundaries when actual architecture changes. Check links/routed declarations
with `python3 scripts/verify.py --docs-only`; surface alignment does not replace
semantic review. Comments explain non-obvious rationale and defenses.

Review the whole agent-owned diff against acceptance and preserved behavior.
Never commit secrets, debris or unrelated changes. Stage explicit owned paths
after inspecting status; commit only when authorized. Final report gives
outcome, checks, durable changes, risks and unverified acceptance surfaces
without private reasoning or transcripts.
