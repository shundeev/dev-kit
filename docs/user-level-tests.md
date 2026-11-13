# User-level tests

A user-level test checks a change the way a person meets it (ADR-0002, stage 8). The agent runs it
before the pull request and reports it under **User-level test**. When the project declares it in
`kit.edn`, CI runs it again on every pull request, inside the required `checks` job, so a
regression in what a person sees blocks the merge like any failing check (#27).

## By kind of project

- **UI (a page or an app):** a browser loads it as a visitor would. The test asserts on what is
  visible: text, a link that leads somewhere, the layout at phone width (390 px). Screenshots at
  desktop and phone width are kept as artifacts. GitHub's `ubuntu-latest` runner has Google Chrome
  installed ([runner image](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)),
  so Playwright can drive it with `channel: 'chrome'`, and CI downloads no browser. Set
  `screenshot: 'only-on-failure'` as well: a failing check stops before its own screenshot, and
  the page it failed on is the one worth seeing. The site's `tests/visitor.spec.js` and
  `playwright.config.js` are a working example.
- **Service:** requests against the running service, from outside, asserting on the answers.
- **CLI or script:** a run of the real command in a scratch setup, asserting on the output and on
  the files it leaves.
- **LLM system:** its eval suite, with the scores kept as an artifact.

Tests of single functions are not user-level tests; they are the project's own checks.

## Declaring it

In `kit.edn`:

```clojure
:user-test {:run       "npm ci && npx playwright test"  ; run by bash from the project's root
            :artifacts "test-results"}                   ; optional: a folder inside the project
```

- `.shared/bin/kit user-test` runs `:run` from the project's root and exits with its code. The
  agent runs the same command locally.
- `kit doctor` fails on a `:user-test` without `:run`, and on `:artifacts` outside the project.

## In CI

`ci/action.yml` runs `kit user-test` after the other checks when `:user-test` is declared. It then
uploads `:artifacts` as the artifact `user-test` with `actions/upload-artifact`, also when the test
failed: a failure's screenshots are the ones worth seeing. The artifact is listed on the run's page
(Actions → the run → Artifacts) and kept for the repository's retention period, 90 days by default.
Hidden files are not uploaded.

Anyone who can read the repository can download its artifacts
([docs](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts)),
so in a public repository anyone can. An artifact never holds a secret or personal data.

A project without `:user-test` gets the same CI as before.

## In the pull request

Under **User-level test**: what ran, against what, and what it showed. For a UI, the link to the
CI run whose artifact holds the screenshots.
