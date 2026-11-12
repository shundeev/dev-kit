---
name: adr
description: "Record an Architecture Decision Record in adr/ when work chooses between alternatives that are expensive to reverse (storage, architecture, protocol, data format, library, component boundary). Also use to find which decisions are currently in force before designing a change. Invoked from repo-task-delivery step 2."
---

# ADR

An ADR is a short, permanent note: what was decided, why, and what it costs. ADRs live in
`adr/` at the repository root, one file each: `adr/NNNN-kebab-title.md`.

## Iron rule: accepted ADRs are immutable

Never edit an ADR that is on `main` — not its status, not its body, not its date. To change a
decision, write a new ADR with `Status: accepted, supersedes ADR-NNNN` and a
`Supersedes: ADR-NNNN` line, and explain in its Context why the earlier decision is revisited.
What is in force is found by walking the Supersedes links. CI's pull request gate refuses a PR that
modifies, removes or renames an accepted ADR (#21).

## When an ADR is due

All three:

1. It is a lasting commitment — a pattern, technology, boundary or contract — not a tactical
   implementation detail.
2. It will shape future changes beyond this one.
3. No ADR in force covers it, or this work deliberately departs from one (then supersede it).

Nothing qualifies → no ADR; the PR body says `ADR: none`. Do not invent ADRs.

## Writing one

1. Read every file in `adr/`. Build the supersession graph from the `Supersedes:` lines and
   note the highest number in use.
2. Create `adr/NNNN-kebab-title.md` with the next number (4 digits, never reused), from
   `template.md` next to this file. Title the decision as a statement:
   `0003-events-are-written-before-they-are-processed.md`.
3. Status is `accepted` (or `accepted, supersedes ADR-NNNN`). The ADR travels in the same pull
   request as the change; merging the PR is accepting it, a closed PR takes the ADR with it.
4. Commit the ADR as the first commit on the issue branch, before the code it governs.
5. The PR body names it: `ADR: adr/NNNN-title.md`.

## Measurements

When a decision rests on a measurement, link the experiment record in Context
(`docs/experiments/NNNN-*.md`): the experiment says what was measured, the ADR what was
decided because of it.
