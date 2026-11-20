(ns dev-kit.docs-test
  "What the owner relies on in dev-kit's written files, told as stories over tables of phrases, sections and rules:
  the rules block, the delivery skill, the issue form and the PR template, the workflows, the permission lists. The
  files are read as they are in this checkout; nothing runs and nothing is installed (#139, step 8). A failure names
  the file and what it lacks. The selftest keeps what needs a real run: that init lays these files and that doctor
  finds them edited."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clj-yaml.core :as yaml]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))


(defn slurp-file
  [path]
  (slurp (str path)))


(defn lines-of
  [path]
  (str/split-lines (slurp-file path)))


(defn lacking
  "The phrases of the table that the file does not contain."
  [path phrases]
  (let [text (slurp-file path)]
    (remove #(str/includes? text %) phrases)))


(defn settings-list
  "One permission list of a settings file: allow, ask or deny."
  [path list-name]
  (get-in (json/parse-string (slurp-file path) true) [:permissions list-name]))


(def base-settings "claude/settings.base.json")


(def own-settings ".claude/settings.json")


;; The rules block


(def rules-block "rules/shared.md")


(def standing-rules
  "Phrases of the rules the owner relies on every agent to follow."
  ["what changed for them and what is needed from them" "ends with the pull request's link" "as numbered steps"
   "says what to do" "not only into an agent's memory" "What can be done in one task is done in one"])


(deftest the-rules-block-stays-within-100-lines
  (is (<= (count (lines-of rules-block)) 100) (str rules-block " is over 100 lines")))


(deftest the-rules-block-names-the-owners-standing-rules
  (is (empty? (lacking rules-block standing-rules)) (str rules-block " lacks these phrases")))


;; The delivery skill


(def skill "claude/skills/repo-task-delivery/SKILL.md")


(def stages
  "The stages of ADR-0002, as the words of their headings."
  ["Size sets the ceremony" "Requirements" "Invariants" "Design" "G1" "Plan and execute" "Review and simplify"
   "User-level test" "Pull request" "Merge" "Retrospective"])


(def skill-phrases
  "What the skill must say, by the story it belongs to."
  {"stage 9 asks for the background alarm, the hold, the release and the Approve once ready (ADR-0007)"
   ["kit checks" "in the background" "kit finish --hold" "kit release"
    "Ask the owner for the Approve once the PR is ready"]
   "stage 4: a repeated pattern is shown by one real step followed with go to definition"
   ["go to definition" "the locality rule"]
   "a brief to a subagent asks for no report on a wake-up where nothing changed"
   ["no report on a wake-up where nothing changed"]
   "stage 9: a pass means ready unless held, and a stuck draft is released" ["unless the PR is held"
                                                                             "still a draft without `hold`"]})


(deftest the-delivery-skill-names-itself
  (is (= "name: repo-task-delivery" (second (lines-of skill)))))


(deftest the-delivery-skill-walks-every-stage-of-adr-0002
  (let [headings (filter #(str/starts-with? % "## ") (lines-of skill))]
    (doseq [stage stages]
      (is (some #(re-find (re-pattern (str "(?i)^## [0-9]+\\. .*" stage)) %) headings)
          (str skill " lacks the stage " stage)))))


(deftest the-delivery-skill-tells-the-parts-the-owner-relies-on
  (doseq [[story phrases] skill-phrases]
    (testing story (is (empty? (lacking skill phrases)) (str skill " lacks these phrases")))))


;; The issue form and the PR template


(def issue-form ".github/ISSUE_TEMPLATE/task.yml")


(def pr-template ".github/pull_request_template.md")


(defn form-labels
  "The label of each field of the issue form, with (required) where the form requires it."
  [path]
  (for [field (:body (yaml/parse-string (slurp-file path)))
        :let  [label (get-in field [:attributes :label])]
        :when label]
    (str label (when (get-in field [:validations :required]) " (required)"))))


(deftest the-issue-form-has-the-five-adr-0002-sections
  (is (= ["Requirements (required)" "Invariants" "Design" "Plan" "Done when (required)"] (form-labels issue-form))))


(deftest the-pr-template-has-the-sections-the-owner-reviews
  (let [headings (set (lines-of pr-template))]
    (doseq [section ["## What changed" "## Why" "## Root causes" "## Review" "## User-level test" "## Verified"
                     "## Not verified" "## Owner steps"]]
      (is (contains? headings section) (str pr-template " lacks " section)))))


(deftest the-pr-template-passes-the-finish-scripts-adr-check
  (is (some #(re-find #"^ADR: +\S" %) (lines-of pr-template))))


(deftest the-pr-template-leaves-the-closing-keyword-to-the-finish-script
  (is (not (re-find #"(?i)(close[sd]?|fix(e[sd])?|resolve[sd]?) #" (slurp-file pr-template)))))


;; The workflows


(def workflows
  "Every workflow file and action that dev-kit ships or lays in a project."
  (concat ["managed/ci.yml" "ci/action.yml"] (map str (fs/glob ".github/workflows" "*.yml"))))


(def ready-workflow ".github/workflows/ready-when-green.yml")


(deftest every-workflow-pins-its-actions-to-a-commit
  (doseq [path  workflows
          line  (lines-of path)
          :when (re-find #"^\s*-?\s*uses:\s*[^.\s]" line)]
    (is (re-find #"@[0-9a-f]{40}(\s|$)" line) (str path " does not pin to a commit: " (str/trim line)))))


(deftest the-ready-workflow-waits-for-the-ci-workflow-by-its-name
  (is (str/includes? (slurp-file ready-workflow) "workflows: [\"CI\"]"))
  (doseq [path ["managed/ci.yml" ".github/workflows/selftest.yml"]]
    (is (some #{"name: CI"} (lines-of path)) (str path " is not named CI"))))


(deftest the-ready-workflow-holds-the-permissions-its-script-needs
  (doseq [permission ["checks: read" "statuses: read" "pull-requests: write" "contents: write"]]
    (is (boolean (some #(str/starts-with? % (str "  " permission)) (lines-of ready-workflow)))
        (str ready-workflow " lacks the permission " permission))))


(deftest a-workflow-reruns-when-the-title-or-body-is-edited
  (doseq [path ["managed/ci.yml" ".github/workflows/selftest.yml"]]
    (is (some #(re-find #"^ +types: \[opened, synchronize, reopened, edited\]" %) (lines-of path))
        (str path " misses the edited type"))))


(deftest ci-gives-the-selftest-no-time-limit
  (is (not (str/includes? (slurp-file ".github/workflows/selftest.yml") "timeout-minutes"))
      "a hang is made impossible, not cut off: remove timeout-minutes from .github/workflows/selftest.yml"))


;; The code owner


(deftest dev-kit-itself-has-the-owners-team-as-code-owner
  (is (some #{"* @shundeev/owners"} (lines-of ".github/CODEOWNERS"))))


;; The permission lists


(def never-allowed
  "Blanket rules that would let the agent past the guard."
  ["Bash(git *)" "Bash(gh *)" "Bash(bb *)"])


(def decided-by-the-safety-guard
  "The guard refuses these outright; an ask rule would never be reached (#20, #74)."
  ["Bash(git reset --hard *)" "Bash(git clean *)" "Bash(git branch -D *)" "Bash(git checkout -- *)"
   "Bash(git restore *)" "Bash(gh issue close *)"])


(def denied
  ["Bash(git push --force *)" "Bash(git push * --force *)" "Bash(git push * --force)" "Bash(git push -f *)"
   "Bash(git push * -f)" "Bash(git push * +*)" "Bash(gh repo delete *)"])


(deftest the-base-settings-allow-no-blanket-command
  (let [allowed (set (settings-list base-settings :allow))]
    (doseq [rule never-allowed]
      (is (not (contains? allowed rule)) (str "blanket allow: " rule)))))


(deftest kit-merge-runs-without-a-prompt
  (let [allowed (set (settings-list base-settings :allow))]
    (is (contains? allowed "Bash({{kit}}/bin/kit *)"))
    (is (contains? allowed "Bash(bb {{kit}}/bin/kit *)"))
    (is (not-any? #(str/includes? % "kit merge") (settings-list base-settings :ask)))))


(deftest the-base-settings-ask-before-a-merge-by-hand
  (is (some #{"Bash(gh pr merge *)"} (settings-list base-settings :ask))))


(deftest the-safety-guard-decides-what-it-refuses-without-an-ask-rule
  (let [asked (set (settings-list base-settings :ask))]
    (doseq [rule decided-by-the-safety-guard]
      (is (not (contains? asked rule)) (str "still asks: " rule)))))


(deftest the-base-settings-deny-forced-pushes-and-repository-deletion
  (let [denied-rules (set (settings-list base-settings :deny))]
    (doseq [rule denied]
      (is (contains? denied-rules rule) (str "does not deny: " rule)))))


(deftest dev-kits-own-ask-and-deny-rules-match-the-base
  (doseq [list-name [:ask :deny]]
    (is (= (settings-list base-settings list-name) (settings-list own-settings list-name))
        (str "dev-kit's own " (name list-name) " rules drifted from the base"))))


;; The marketplace


(deftest every-marketplace-entry-matches-its-plugin
  (let [marketplace (json/parse-string (slurp-file ".claude-plugin/marketplace.json") true)]
    (is (seq (:plugins marketplace)))
    (doseq [{:keys [source] plugin-name :name} (:plugins marketplace)]
      (is (= plugin-name (:name (json/parse-string (slurp-file (str source "/.claude-plugin/plugin.json")) true)))
          (str "the marketplace entry " plugin-name " does not match its plugin.json")))))
