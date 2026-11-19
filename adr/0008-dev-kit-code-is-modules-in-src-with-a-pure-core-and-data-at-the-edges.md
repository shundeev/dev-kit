# 0008. dev-kit's code is modules in `src/` with a pure core and data at the edges

- Status: accepted, amended 2026-11-19 (locality)
- Date: 2026-11-17

## Context

ADR-0005 settled the language: logic in Babashka. It said nothing about the shape of the code, and the code grew as scripts. Measured on 88d3be2:

- `bin/kit` is 2056 lines and `bin/flow.clj` 1468. kit pulls flow and retro in with `load-file`, and flow calls kit back as a subprocess.
- `start!` is 245 lines. 60 of them are five functions declared inside its `let` before the story starts, 29 are gh and git arguments one per line, 24 are error texts. The steps of the command cannot be read off it.
- The configuration is read wherever it is needed, through 18 `GHWF_*` variables that each project carries as a copy of its `kit.edn`. A dev-kit command run from another project's session takes that project's values (#139).
- A problem is a printed line and an exit, and the lines CI, skills and Claude Code read are held only by the selftest.
- The selftest has 1103 checks and runs 3 minutes. It is the only test level, so every case lands there, and many of its checks compare exact gh call strings: a reordered call fails a correct change.
- `kit gate --hook` runs on every file edit and loads all of `bin/kit` (55 ms, ADR-0005).

The owner reviewed the whole proposal and approved it on 2026-11-17 (#139). The error style below was checked on a prototype of `kit start`, three variants against the same five stories on an in-memory GitHub.

## Options

- **Keep scripts, tidy them in place.** No test level below the selftest, and every module still loads every line.
- **Modules, and every step returns a result map** (`{:fail problem}` or facts). Checked: 19 hand-written error checks in one command, and removing one let a board refusal pass silently.
- **Modules, and one function per command that throws.** Short, but the result does not say what was done before the failure.
- **Modules in `src/`; a command is a list of named steps; a failing call throws a problem the dispatcher turns into a result map.** Chosen, see below.

How a step reaches GitHub and git (2026-11-19, after the six commands were recipes):

- **Named operations in a `ctx` map, called by keyword** (`(github :read-pr …)`), the pilot's way (#146). Tests hand an in-memory map. Measured: to read the step `read-repo` the owner visited three places (the step, the `ctx` built in the edge, `shell.clj`); 78 calls by keyword, on which neither "go to definition" nor "find references" works.
- **A protocol** with a real and an in-memory record. The call leads to the method's declaration, the implementation is a third place.
- **Effects as data with an interpreter at the end** (Elm, re-frame, free monads). kit's effects depend on each other's answers (the issue, then its URL, then the board), and the interpreter takes an effect's meaning away from its step.
- **Direct calls, functions next to their step, tests substitute by var.** Chosen, see below.

## Decision

- **Code lives in `src/dev_kit/`**, one namespace per concern. `bin/kit`, `bin/flow.clj`, `bin/retro.clj` and `claude/hooks/guard.clj` are thin entries that keep their paths. Each finds `src/` through a `bb.edn` next to it, because bb reads `bb.edn` only from the invoked script's folder or the current folder. dev-kit's own tasks (`lint`, `test`, `selftest`, `ci`) live in the root `bb.edn`, and CI calls the same tasks.
- **A pure core between two edges.** On the way in, configuration, arguments, gh and git output and hook input become maps, each read in one place. On the way out, problems and verdicts are maps that carry a `:fix`. Text and exit codes appear only in `main`.
- **A command is a list of named steps run by a dispatcher.** Inside a step, a failing call throws a problem. The dispatcher stops there and returns a map that names the steps done and the step it stopped at.
- **Locality: a step reaches the world by direct calls.** A gh or git call one command needs is a function right above its step, in the command's file; what several commands share is a plain function in one namespace, called directly. A step is `(fn [facts])`: no `ctx`, no calls by keyword, no step factories. Tests substitute those functions by var (`with-redefs-fn`), and the real gh and git fail a story that reaches them (#139, step 7b).
- **A check lives at the lowest level that sees its behaviour.** `bb test` (clojure.test, seconds) for pure functions, written as stories a person would tell: the test's name says what the owner sees, and it asserts the state of the world afterwards, not the calls made. The selftest keeps the end-to-end paths. No selftest case leaves before the same behaviour is covered lower and that test passes.
- **bb 1.13.229 or newer.** `kit doctor` names an older one, with the command that updates it.
- **Supersedes:** nothing. It extends ADR-0005: the language stays, this adds the shape.

## Consequences

- Each later change touches one concern, and a hook loads only the modules it needs.
- A person reads a command as its list of steps. The details sit one level down, behind names, and one "go to definition" away.
- Tests substitute by var, which is global while a story runs: stories run one after another.
- The move into `src/` touches every file once (#141). It conflicts with any open dev-kit PR, so it runs when none is open.
- Projects need bb 1.13.229 or newer. The command tree of `babashka.cli` fails on 1.12.218.
- A new command or hook is written as steps over maps, with its stories in `bb test`. A new selftest case is an end-to-end path, not a variant.
