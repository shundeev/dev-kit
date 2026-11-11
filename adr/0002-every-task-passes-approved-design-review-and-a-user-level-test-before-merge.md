# 0002. Every task passes an approved design, review and a user-level test before it can merge

- Status: accepted
- Date: 2026-11-11

## Context

The owner wants every task to go through the same stages: gather requirements; define new
invariants and apply the existing ones; choose a design and get it approved; execute exactly
what was approved; review and simplify; test the way a person would see it; and only after
review, simplification and tests succeed, approve the merge.

The current flow (`repo-task-delivery`) has the issue, branch, ADR, PR and owner review, but
its approvals are requests to the agent. ADR-0001 says that what must hold is enforced in
L1 (GitHub server) or L2 (CI), with agent-side layers as early feedback, and gives the agent
its own GitHub identity so the server can tell the owner's actions from the agent's.

Research on 2026-11-11 (summary in #16) compared superpowers, Anthropic's feature-dev plugin,
spec-kit, OpenSpec, BMAD and Kiro. Each covers part of the stages; none makes the user-level
test or the merge approval an enforced gate. Users report two things consistently: heavy
spec frameworks are too much for small changes, so ceremony must scale with task size
(BMAD's planning paths, "if a change wouldn't warrant a planning session it needs no spec");
and approvals that live only in prompts get skipped.

## Options

- **Adopt superpowers wholesale.** Closest coverage and cross-agent, but its gates are prompt-
  level and its branch finishing competes with the board and merge scripts.
- **Adopt spec-kit or OpenSpec.** A spec artifact tree per change; still advisory; heavy for the
  small tasks that make up most of the work.
- **Compose the stages on the existing flow, with enforced gates (chosen).** Borrow the stage
  ideas, keep the issue as the single artifact, enforce the two approvals in L1/L2.

## Decision

### Stages and where their output lives

The issue is the one place a task's thinking lives. Its body has five sections.

| Stage | Output | Borrowed from |
|---|---|---|
| 1. Requirements | **Requirements** and **Done when**. The agent asks clarifying questions one at a time, then restates its understanding for the owner to confirm. | superpowers brainstorming, feature-dev discovery |
| 2. Invariants | **Invariants**: ADRs in force and catalogue rows (ADR-0001) that apply, by number; a new invariant is proposed as an ADR (`adr` skill). | spec-kit constitution |
| 3. Design | **Design**: two or three options with trade-offs and the one chosen. A lasting choice also becomes an ADR in the PR. | feature-dev architecture phase |
| 4. Execute | **Plan**: numbered steps; they are the approved scope. A step that turns out wrong stops the work and goes back to the owner; no silent deviation. | superpowers writing/executing-plans |
| 5. Review and simplify | `/code-review` and `/simplify`, plus `smell-reviewer` for Clojure and the verifier agent (#5). Each finding is fixed or answered; the PR section **Review** lists them. | pr-review-toolkit, code-simplifier |
| 6. User-level test | Behaviour checked at the system boundary, as a user meets it: UI in a real browser (tests that assert on what is visible, plus an exploratory pass with screenshots); a service through its requests or CLI; an LLM system through its eval suite. Automated parts run in CI; the PR section **User-level test** holds the evidence. | anthropics/skills webapp-testing, Anthropic's `/verify` |
| 7. Merge approval | The owner approves the PR. | — |

### Gates

**G1 — design approved, before stage 4.** The owner adds the label `design-approved` to the
issue. It counts only if the owner's account added it and the issue body has not been edited
since; a changed plan needs a new approval.

- L2 (backstop): CI fails a PR whose linked issue lacks a valid approval.
- L4 (early feedback): a PreToolUse hook refuses Edit/Write on the issue branch, except in
  `adr/`, while the approval is missing. Writes through Bash slip past it; L2 catches the result.

**G2 — ready to merge.** The PR can merge only when:

- L2: required checks pass — tests including the automated user-level tests, `kit doctor --ci`,
  lint, and a PR-shape check that the sections **Review** and **User-level test** are filled
  and the `ADR:` line is present;
- L1: the owner has approved the PR. The agent's identity cannot approve its own PR.

The PR-shape check proves the sections exist, not that they are true. Their truth is what the
owner's approval is for; that is why G2 ends with the owner.

### Ceremony scales with Size

Size is the board field set when the issue is filed; the owner can change it.

- **XS / S** — Requirements and Done when in one or two lines, Design "obvious", no Plan.
  G1 is not required. Stages 5–7 apply in full.
- **M** — all stages; G1 required.
- **L** — not executed as one task: split into S/M sub-issues, each on its own path.
- Under-sizing is caught in L2: a PR linked to an XS/S issue whose diff exceeds a threshold
  (lines changed, excluding lock and generated files) fails until the issue is resized and G1
  is given. The threshold is set in the implementation.

## Consequences

- The issue template (in `u473t8/.github`) carries the five sections; `repo-task-delivery` is
  rewritten around the stages and the two gates.
- New mechanisms: the G1 approval check in CI, the G1 edit hook, the PR-shape check, the
  size check, and per-project conventions for user-level tests in CI.
- Every M task waits for one owner click before code and one before merge; S tasks only for the
  merge. Agents ask more questions up front.
- G1's "added by the owner" means something only once the agent has its own GitHub identity
  (ADR-0001, choice 1). Until then the check sees only the label, which the agent could set
  itself — a known gap, closed by that implementation.
- Sources: superpowers (github.com/obra/superpowers), feature-dev and pr-review-toolkit
  (github.com/anthropics/claude-plugins-official), spec-kit (github.com/github/spec-kit),
  OpenSpec (github.com/Fission-AI/OpenSpec), BMAD planning paths
  (docs.bmad-method.org/plan/choose-a-planning-path/), Anthropic best practices
  (code.claude.com/docs/en/best-practices).
