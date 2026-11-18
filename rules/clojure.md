## Clojure

- Format: zprint with `.zprint.edn` (shared from dev-kit). Check: `.shared/clojure/zprint_check.sh`;
  fix: `.shared/clojure/zprint_check.sh --write`. The pre-commit hook checks staged files.
  The config lays out a CLI call (`gh!`, `sh`, `run`, …) with two or more flag–value pairs one
  pair per line, values in a column; one pair stays on the call's line. `if`, `if-not`, `when`
  and `when-not` put a branch that is a call, vector or map on its own line; a plain value stays
  inline. zprint keeps the source's line breaks: write such a call on one line and let it lay
  it out.
- A command whose output is parsed runs in a `let`; the parsing reads the bound value.
- Conditional arguments: `cond->` with `:always` for the unconditional part, never `true`.
- `apply` starts its own line under the call it feeds.
- A message built from text and values is a `format` with its template on one line; never a
  `str` broken over lines.
- One flat `cond` where a nested one says the same: combine the tests instead of a branch that is
  itself a `cond`.
- Names are words (`statement`, `words`, `dir`), not `st`, `ws`, `wd`; a single letter only for a
  loop index or a short `fn`'s argument.
- Lint: `clj-kondo --lint src test`. Shared strict linters come from dev-kit through
  `:config-paths` in `.clj-kondo/config.edn`; project-only settings live in that file.
- Navigate Clojure symbols with LSP (clojure-lsp) first; grep only for plain text.
- Before opening a PR that changes Clojure, run the `smell-reviewer` agent on the branch diff.

## Clojure design

Before adding a function or a map key, run LSP `findReferences` / `incomingCalls` on what it
wraps or carries and answer: who creates the value, who reads it. If one place creates it and
the next line reads it, the new name is not needed.

Smells to name when you see them:

- **Feature envy / tell, don't ask** — a function reads fields of another value to decide for it.
  Move the logic to where the data lives, or pass the whole value.
- **Tramp data** — an argument travels through functions that only hand it on. Give it to the one that reads it.
- **Middle man** — a function that only forwards to another. Call the other one.
- **Speculative generality** — an option, argument or layer for a case that has not happened. Remove it.
- **Wandering data** — a value assembled and taken apart by field right away
  (`(let [m {:a a :b b}] (f (:a m) (:b m)))`). Pass the parts or the owner.
- **Single call site** — a private helper or `def` with one caller is inlined.
  `bb .shared/clojure/single_caller.clj [path ...]` lists them.
- **An object handed its own field** — `(f m (:k m))`: pass `m`, read `:k` inside `f`.
  `bb .shared/clojure/field_of_same_map.clj [path ...]` lists them.
- **Structured text matched by a pattern** — YAML, EDN or JSON read with a regex. Parse it (clj-yaml,
  `clojure.edn`, cheshire) and take the value by its path: a quoted or reformatted value slips past a pattern (#144).
