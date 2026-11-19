# 0009. A step reaches GitHub and git by direct calls, written next to it

- Status: accepted
- Date: 2026-11-19

## Context

ADR-0008 made each flow command a list of named steps over facts. It did not say how a step reaches GitHub and git. The pilot (#146) passed a `ctx` map of named operations into every step, called by keyword — `(github :read-pr …)` — so that tests could hand an in-memory GitHub; the six commands followed it (#147–#153).

Reviewing #153, the owner followed the step `read-repo` to its code and had to visit three places: the step took a function out of `ctx`, `ctx` was built in the `checks!` edge, the function lived in `shell.clj`. 78 calls dispatched on a keyword, and neither "go to definition" nor "find references" works on them; `:read-pr` occurred 11 times, as step names, operations and their in-memory twins. The owner's rule: to understand a fragment, a reader needs only the code next to it.

## Options

- **Named operations in a `ctx` map, called by keyword**, the pilot's way. Three hops from a step to its code, and no navigation on keyword calls.
- **A protocol** with a real and an in-memory record. The call leads to the method's declaration; the implementation is a third place.
- **Effects as data with an interpreter at the end** (Elm, re-frame, free monads). kit's effects depend on each other's answers — the issue, then its URL, then the board — and an interpreter takes an effect's meaning away from its step.
- **Direct calls to functions written next to their step; tests substitute them by var.** Chosen.

## Decision

- **A step is `(fn [facts])`** and reaches the world by direct calls. No `ctx`, no calls by keyword, no step factories, no keyword built at run time.
- **A gh or git call one command needs is a function right above its step**, in the command's file. **What several commands share is a plain function in one namespace named for what it is** (`dev-kit.flow.github` for what they ask of GitHub, `dev-kit.flow.watched-pr` for the steps kit checks and kit review share, `board`, `shell`), called directly and holding the code itself, not a forwarder: "go to definition" lands on the code.
- **Tests substitute those functions by var** (`with-redefs-fn` over a table keyed `#'ns/name`, one wrapper `fake/with-github`). While a story runs, the real gh and git throw an `AssertionError` naming the call, so a missing substitute fails the story and no `catch Exception` on the way can swallow it.
- **Supersedes:** nothing. It extends ADR-0008: the steps stay, this says how they reach the world.

## Consequences

- From any step to its code is one jump, and "find references" on a function lists its calls and its substitute.
- Substitution is global while a story runs, so stories run one after another, as they already did.
- A new command's gh or git call is written above the step that makes it; it moves to a shared namespace only when a second command needs it.
- Effects as data remain open where the plan itself is worth showing before it runs; `kit init`, which already builds its list of files as data, is the candidate.
