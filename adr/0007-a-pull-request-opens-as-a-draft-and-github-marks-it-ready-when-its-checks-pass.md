# 0007. A pull request opens as a draft and GitHub marks it ready when its checks pass

- Status: accepted
- Date: 2026-11-14

## Context

GitHub asks the owners for a review the moment a PR opens, often while CI still runs or has already failed. An Approve given on red CI is dismissed by the fix's push: an extra round (#3). The owner chose that the review request, and the notification with it, should mean "the checks have passed, your turn" (#104).

Evidence from the sandbox repository (#109, runs 43598214678, 43598321030, 43598464445):
- A PR created as a draft gets no review request. Marking it ready brings the owners' request, from the ruleset's required reviewers.
- A PR opened ready gets the request at once, and converting it to a draft keeps the request. So `kit finish` cannot open a ready PR and take the request back; the PR must open as a draft.
- A workflow on `workflow_run` `completed`, running with `GITHUB_TOKEN` (`pull-requests: write`, `contents: write`), can mark a draft ready and turn auto-merge on. `workflow_run.pull_requests` names the PR for a branch of the same repository.
- Converting a PR to a draft turns auto-merge off, so auto-merge is turned on after the PR is ready.
- A workflow file acts only from the default branch.

## Options

- **A managed workflow marks the PR ready.** Chosen. It needs no agent and works with the session closed.
- **The agent marks it ready** after a background `gh pr checks --watch`. It works only while the session is open; a PR left behind stays a draft. It remains the fallback: `gh pr ready`, then `kit merge`.
- **Leave it as it is.** The review request at open, which is the problem.
- **A server or a personal token** to react to events. A long-lived secret or a public endpoint for a gain of seconds.

## Decision

- **`kit finish` opens the PR as a draft** and neither turns on auto-merge nor requests the owners' review itself (`GHWF_REVIEWERS` applies to ready PRs only), when the repository's base branch already holds `.github/workflows/ready-when-green.yml`. A repository without that file gets a ready PR with auto-merge, as before.
- **The managed workflow `ready-when-green.yml`**, laid by `kit init` and checked by `kit doctor`, runs when the project's CI workflow completes. For each PR of that run that is a draft without the label `hold`, and whose every required check on the head commit passed, it runs `gh pr ready`, then turns auto-merge on. It logs every decision. A repository that does not allow auto-merge is logged, not failed. Its steps are a short inline `gh` and `jq` script: the runner has both and not `bb`, so ADR-0005's Babashka rule cannot apply there.
- **The label `hold`** keeps a PR a draft: a PR that overlaps one already waiting is opened with `kit finish --hold`. When the earlier PR merges, `kit release` removes the label, marks the PR ready and turns auto-merge on, and refuses, naming the check, while a required check is not green.
- **A failed check leaves the draft alone**, and nobody is notified. The agent learns of it from its own background `gh pr checks --watch --fail-fast --required`.
- **Nothing waits for a fixed time.** A wait ends when what it waits for happens, or when that has become impossible, and says which.
- **Supersedes:** nothing. ADR-0002's stage 9 stands, with this opening.

## Consequences

- The owners hear of a PR once, when it can be approved. Nothing changes for a PR opened by hand.
- A project gets this with the dev-kit update PR that brings the file: the workflow acts only from the default branch.
- The review request depends on a GitHub behaviour (a draft gets none). If GitHub changes it, `test/e2e.sh` step 3 shows it in the sandbox.
- A draft that nothing marks ready stays a draft: a required check that comes from outside the named CI workflow, or a CI workflow that is renamed or disabled. The agent sees it (checks passed, still a draft without `hold`) and runs `kit release`, which refuses unless the required checks are green.
