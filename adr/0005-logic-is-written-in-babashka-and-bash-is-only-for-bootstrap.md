# 0005. Logic is written in Babashka; bash is only for the bootstrap

- Status: accepted
- Date: 2026-11-13

## Context

dev-kit grew in two languages without a rule. The flow scripts and the delivery guard came from Sprecha in bash. They read gh's JSON through jq and parse commands with awk and sed. `bin/kit` was written in Babashka. A fix lands in whichever language its file happens to use.

The cost shows in the guard. Its header lists what heuristic parsing misses: quoted text read as flags, heredoc bodies cut by a pattern, commands inside `bash -c` unseen. The flow scripts carry 980 lines of bash, and a board query in jq that broke more than once (#12, #41).

Measured on 2026-11-13, 50 runs averaged:
- the bash guard takes 16 ms per call;
- an empty `bb` 6 ms;
- `bb` reading the hook's JSON 8 ms;
- `bb` loading all of `bin/kit` 55 ms.

## Options

- **Keep both, no rule.** The state that produced the gaps.
- **Everything in bash.** jq, awk and sed for data, and no shared code with `kit`.
- **Logic in Babashka, bash only where bb cannot be assumed.** Chosen, see below.

## Decision

- **Logic is Babashka:** data as data structures, JSON without jq, one language with `bin/kit`.
- **bash only for the bootstrap that must run when dev-kit or bb is missing:**
  - `managed/pretooluse.sh` keeps the repair path open when `.shared` is empty, and refuses when bb is missing;
  - `claude/hooks/session-start.sh` reports a missing bb;
  - `githooks/pre-commit`.
- **The outside drivers stay bash:** `test/selftest.sh` and `test/e2e.sh` call the commands as a person would, from outside.
- **A hook that runs before every call is a small bb file of its own**, not a `kit` subcommand. Loading `kit` costs 55 ms per call.
- **Supersedes:** nothing.

## Consequences

- bb becomes required wherever the guard runs. It already was for edits (the design approval gate), and `kit doctor` checks it. Without bb, the entry refuses with the repair.
- jq leaves the guard. It stays in the selftest, and in the flow scripts until they move (#35, part 2).
- A new hook or command is written in bb. A new bash file needs a reason this ADR names.
