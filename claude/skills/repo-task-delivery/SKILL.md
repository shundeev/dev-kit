---
name: repo-task-delivery
description: "Use when the user reports a bug, a warning, a regression, or proposes a feature, improvement, refactor, docs or config change that is likely to end in a committed edit to this repository. Drives the full flow: issue on the board, branch, ADR when a lasting decision is made, implementation, verification, pull request, review round, merge after approval, closeout."
---

# Repo Task Delivery

The default entry point for new tracked work in this repository:

`issue on the board -> branch -> [ADR] -> implement -> verify -> PR (In review) -> owner review -> merge (Done)`

## When to use

- A new problem or a proposed change that should end as a committed edit.
- Not for: questions, reading or measuring only, continuing an issue that is already active,
  or when the owner says to skip GitHub. A narrow sub-step ("open the PR") uses
  `gh-project-workflow` directly.
- "It is small" is not an exception.

## 1. Find or file the issue

- Search open issues first; one issue may host several PRs. Continue an existing one rather than filing a twin.
- File through `gh-project-workflow` (start script), never `gh issue create`. Pick the Priority
  (Urgent/High/Medium/Low) and Size deliberately; the script refuses an empty Priority.
- Starting work means Status **In progress**. Filed but not started stays **Backlog**.
- Dependencies: native blocked-by (`gh issue edit <child> --add-blocked-by <blocker>`).
- The issue body states the goal and the done criterion. Open questions about what a person
  does or sees go to the owner first: one line, two named options (AGENTS.md, "Decisions").

## 2. Decide whether this work records an ADR

The test: does this work choose between alternatives that are expensive to reverse —
storage, architecture, a protocol or data format, a framework or library, a boundary between
components? And will it constrain future changes?

- Yes → invoke the `adr` skill and commit the ADR first on the branch, before the code.
- No → the PR body says `ADR: none`.
- Process changes (agent rules, hooks, scripts, CI) record no ADR; their rationale goes in the
  PR body.

## 3. Implement

- Code edits start only after the issue and its branch exist.
- New screen or a visual redesign: invoke `frontend-design:frontend-design` before the first
  markup. A point layout fix does not need it.
- Stay inside the issue's scope; a finding outside it is a comment on the issue, or a new
  issue when a PR could close it.

## 4. Verify

- Run the project's checks (tests, lint, format) on what changed.
- UI or browser behaviour: prove it in a real browser, not by reading the code.
- Clojure changes: run the `smell-reviewer` agent on the branch diff and fix or answer each finding.
- Write down what was proven and what was not. "Tests pass, not checked in the browser" is a
  complete, honest result; "should work" is not.

## 5. Open the pull request

Write the PR body to a file:

```markdown
## What changed
- ...

## Verified
- <command or check> → <result>

## Not verified
- ... (or "nothing left")

ADR: none
```

Then run the finish script (`gh-project-workflow`, step 1). Commit subjects follow
`type(GH-<issue>): summary`. The card moves to **In review** — the owner's signal.

## 6. Review round

The owner reviews in the diff and submits the review. A pending review is invisible to the
API: if the owner says they reviewed and nothing shows, ask whether they submitted.

When the owner says the round is done, process every thread:

- Fetch: `gh api repos/<owner>/<repo>/pulls/<n>/comments` (`path`, `line`, `body`, `in_reply_to_id`).
- Agreed: fix, fold the fix into the commit it belongs to (`git commit --fixup <sha>`,
  `GIT_SEQUENCE_EDITOR=: git rebase --autosquash main`), `git push --force-with-lease` to this
  issue's branch only. Reply in the thread naming the new hash — commit first, reference
  after — and resolve the thread (GraphQL `resolveReviewThread`).
- Disagreed: reply with the argument and leave the thread open; the owner decides.
  An open thread blocks the merge by ruleset.
- Report the split: resolved with commit / answered and open.

## 7. Merge — only on the owner's word

`merge_issue_flow.sh` (gh-project-workflow, "Merge after approval"); it asks the owner for
confirmation. It deletes the branch only after GitHub reports `MERGED`; never delete a
branch before that.

## 8. Close the task

After the merge, follow-up work is a new task, except obvious closeout (a deploy the owner
asked for, a fix caused by the merged change).
