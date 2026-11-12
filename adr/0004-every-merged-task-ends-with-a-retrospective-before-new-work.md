# 0004. Every merged task ends with a retrospective, and new work waits for it

- Status: accepted
- Date: 2026-11-12

## Context

ADR-0002 set the stages of a task and ended them at the merge. The owner added a stage after it (#28): a retrospective that turns the task's friction and mistakes into proposals, and the owner decides which of them become work. The stage was written into the delivery skill as text. Of the 13 issues merged after that, 7 got no retrospective: dev-kit #24, #34, #37, #44, #52 and site #7, #10. A rule the agent keeps breaking moves down the stack (ADR-0001).

A retrospective written from the agent's memory also missed what the agent cannot see. The plainest case is the owner's confirmation windows: in one session the owner confirmed 25 commands, and none of it reached a retrospective (#28).

## Options

- **Keep it as text in the skill.** That already failed on 7 of 13 issues.
- **Block the merge until the retrospective exists.** It cannot work: the retrospective needs the merged result, and since #37 auto-merge merges without a session.
- **Block the next piece of work.** Chosen, see below.
- **A scheduled job that posts reminders.** It nags but never stops anything, and it needs a server.

## Decision

- **Stage 11, after the merge:** the agent runs `kit retro <issue>`. It prints the friction that dev-kit's hooks recorded as it happened (#55), and GitHub's data. The agent posts a `## Retrospective` comment on the issue: that table first, then findings, each with a proposal the owner decides on.
- **The gate (#56):** `start_issue_flow.sh` refuses to start new work in a repository while an issue merged there in the last 14 days lacks that comment. The refusal names the issues and the command that collects their data. A session's start names them too.
- **The waiver:** only the owner waives the gate. `--skip-retro` sits on the permission "ask" list, so the owner confirms each use.
- **Supersedes:** nothing. ADR-0002's stages stand, with this one after them.

## Consequences

- An unfinished retrospective stops all new work in that repository, not only the next task of the same kind. That is the point.
- The check needs GitHub. When GitHub cannot be asked, the start script warns and goes on, because the gate guards the process, not data.
- 14 days bounds the search, and with it the cost. A task merged longer ago without a retrospective no longer blocks anything. Its data would be thin anyway: the friction log keeps 100 days, but the owner's memory of the task fades much sooner.
