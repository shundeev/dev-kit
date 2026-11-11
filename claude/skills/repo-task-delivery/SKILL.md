---
name: repo-task-delivery
description: "Use when the user reports a bug, a warning, a regression, or proposes a feature, improvement, refactor, docs or config change that is likely to end in a committed edit to this repository. Drives the task through the stages of ADR-0002: size, requirements, invariants, design and the owner's approval (G1), plan and execution, review and simplification, user-level test, pull request, merge on the owner's Approve, retrospective."
---

# Repo Task Delivery

Every task passes the same stages (ADR-0002); Size decides how much of each. The issue is the
one place a task's thinking lives: its sections, from the form `.github/ISSUE_TEMPLATE/task.yml`,
are Requirements, Invariants, Design, Plan and Done when. The pull request body follows
`.github/pull_request_template.md`.

`size → requirements → invariants → design → G1 → plan → execute → review → user-level test → PR → merge → retrospective`

## When to use

- A new problem or a proposed change that should end as a committed edit.
- Not for: questions, reading or measuring only, continuing an issue that is already active,
  or when the owner says to skip GitHub. A narrow sub-step ("open the PR") uses
  `gh-project-workflow` directly.
- "It is small" is not an exception: small changes take the light path, not no path.

## 1. Size sets the ceremony

Find the issue or file it first. Search the open issues, since one issue may host several PRs.
File through `gh-project-workflow` (the start script), never `gh issue create`, with Priority
and Size; starting work means Status **In progress**. No file is edited before the issue and its
branch exist, XS included.

| Size | Ceremony | Example |
|---|---|---|
| XS / S | Requirements and Done when in a line or two, Design "obvious", no Plan, no G1. Stages 7–11 in full. | The PR type taken from the branch's commits (#44). |
| M | Every section, and no code before G1. | The agent's own GitHub identity (#18). |
| L | Not one task: split it into S and M sub-issues (native sub-issues), each on its own path. | Moving the repositories into an organization (#22). |

Name the Size in the first reply, with a reason, before anything else: "Size M: a new flag with
a choice of what the version is." An S that grows past a screenful of logic or into a second
component is an M: stop, resize it and ask for G1.

## 2. Requirements

- Ask clarifying questions one at a time, each with two or three named options, the
  recommended one first. A question about what a person does or sees always goes to the owner.
- Then restate the understanding in a few lines and let the owner confirm it.
- Write **Requirements** (what is true for a person afterwards, and why) and **Done when**
  (checks a person can see, including the user-level test) into the issue.

## 3. Invariants

- List by number what must keep holding: the ADRs in force (walk the `Supersedes:` lines), the
  ADR-0001 catalogue rows that apply, the rules in `AGENTS.md`, and behaviour that must not change.
- A new invariant this work introduces is proposed as an ADR (`adr` skill), committed first on
  the branch.

## 4. Design

- Two or three real options with their trade-offs, and the chosen one. A choice that is
  expensive to reverse also becomes an ADR in the same pull request.
- Check the design against:
  - a new machine, a new repository, a new worktree, offline, and a second person (#18, #22);
  - every claim a guarantee rests on: test it, do not take it from docs or forums (#22). Known
    facts live in `docs/facts.md` once it exists (#28);
  - each manual step for the owner: automate it, or plan it as numbered steps in the PR.
- For M, post the design on the issue and name what the owner decides.

## 5. G1 — the owner approves the design (M)

- No code until the owner adds the label `design-approved` to the issue; `adr/` is the
  exception. The agent never sets the label. A design changed after approval needs a new one.
- Today this is a rule; #26 backs it with CI and a hook.

## 6. Plan and execute

- The Plan's numbered steps are the approved scope. A step that turns out wrong stops the work
  and goes to the owner: no silent deviation.
- Tests first: show each new case failing on the old code, including cases added after the
  first green run.
- Stay inside the issue's scope. A finding outside it is a comment on the issue, or a new issue
  when a PR could close it.
- New screen or visual redesign: invoke `frontend-design:frontend-design` before the first markup.

## 7. Review and simplify

- Self-review the diff (`git diff --stat` first). For M also run `/code-review` and `/simplify`;
  for Clojure, the `smell-reviewer` agent.
- Fix or answer every finding, and list them under **Review** in the PR.

## 8. User-level test

- Check the behaviour where a person meets it: a UI in a real browser, a service through its
  requests or CLI, a script through a real run, an LLM system through its eval suite.
- The evidence goes under **User-level test**. A check that can only run after the merge
  (GitHub reads templates and rulesets from `main`) is named as such, and its result goes on
  the issue.
- Under **Verified** and **Not verified**, say exactly what was proven and what was not.

## 9. Pull request

- Write the body from `.github/pull_request_template.md`, then run `finish_issue_flow.sh`
  (`gh-project-workflow`). It commits, pushes, opens the PR, turns on auto-merge and moves the
  card to **In review**.
- Anything the owner must do by hand goes into a PR comment as numbered steps: where to click,
  what to type, what the result looks like, what to do if it fails. The report gives the link.

## 10. Review round and merge on the owner's Approve

- The owner reviews in the diff. For each thread:
  - agreed: fix it, fold the fix into the commit it belongs to (`git commit --fixup <sha>`,
    then `GIT_SEQUENCE_EDITOR=: git rebase --autosquash origin/main`), push with
    `git push --force-with-lease`, reply with the new hash and resolve the thread;
  - disagreed: reply with the argument and leave the thread open. An open thread blocks the merge.
- The owner's Approve of the latest commit is the word to merge. Auto-merge merges once the
  checks pass and the branch contains the latest `main`. Then run `merge_issue_flow.sh` for the
  closeout; it also updates a branch that fell behind, and merging `main` in keeps the approval.
- The agent never approves a pull request.
- An owner step still open after the merge: reopen the issue and keep its card **In progress**.
  A branch from `gh issue develop` closes its issue on merge, whatever the PR body says.

## 11. Retrospective

- After the merge, post a `## Retrospective` comment on the issue (#28). It covers the flow,
  code rules, determinism, friction (the owner's manual steps, waiting, prompts, corrections),
  mistakes and wrong assumptions, facts learned, and what worked. XS and S may say "nothing to
  change" in one line.
- Every finding ends with a proposal, and the owner decides which become issues. A lesson that
  holds for every project goes to dev-kit, not to the agent's memory.
- Follow-up work is a new task, except obvious closeout caused by the merged change.
