(ns dev-kit.flow.release
  "kit release: frees a held pull request: removes hold, marks it ready, turns auto-merge on (ADR-0007)."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.flow.finish :as finish]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.wait :as wait]))


;; ------------------------------------------------------------------ kit release


(def release-usage
  "Usage: kit release [options]

For a pull request held with the label hold (kit finish --hold), once the PR it waited for has
merged: removes the label, marks the PR ready (GitHub then asks the owners for a review) and
turns on auto-merge (ADR-0007). It also frees a draft that was never held but that nothing marked ready. It refuses,
naming the check and changing nothing, while a required check on the PR's head is not green.

Options:
  --pr <number|url>             The pull request (default: the open PR of the current branch)
  --repo <owner/repo>           Default: the folder's origin on GitHub, then GHWF_REPO
  --help                        Show this help")


(def release-spec
  {"--pr" [:pr :value] "--repo" [:repo :value] "--help" [:help :flag] "-h" [:help :flag]})


(defn- checks-not-green
  "The required checks of a PR that are not green, as \"name (bucket)\"; empty when all are. Dies when GitHub reports none."
  [repo pr]
  (let [checks (wait/required-checks pr repo shell/die)]
    (when (empty? checks)
      (shell/die (str "GitHub reports no required check on " pr
                      ", so none can be called green. Fix: run it again once CI has started, or gh pr ready " pr)))
    (->> checks (remove #(#{"pass" "skipping"} (:bucket %))) (map #(str (:name %) " (" (:bucket %) ")")))))


(defn release!
  [_kit args]
  (let [o      (shell/parse-opts release-spec args)
        _ (when (:help o)
            (println release-usage)
            (System/exit 0))
        repo   (shell/resolve-repo (:repo o))
        branch (shell/git-out "rev-parse" "--abbrev-ref" "HEAD")
        pr     (or (:pr o)
                   (not-empty (shell/gh! "pr" "list"
                                "-R"      repo
                                "--head"  branch
                                "--state" "open"
                                "--json"  "url"
                                "--jq"    ".[0].url // empty"))
                   (shell/die (str "No open pull request for branch '"
                                   branch
                                   "' in "
                                   repo
                                   ". Fix: run it from the held PR's branch, or name it with --pr <number>")))
        {:keys [state isDraft labels url]}
        (json/parse-string (shell/gh! "pr" "view" pr
                             "-R"     repo
                             "--json" "state,isDraft,labels,url")
                           true)
        url    (or url pr)
        held?  (boolean (some #(= "hold" (:name %)) labels))
        _ (when-not (= state "OPEN")
            (shell/die (str url " is " state ", not open: nothing to release.")))
        bad    (checks-not-green repo pr)]
    (when (seq bad)
      (shell/die (str "A required check of " url
                      " is not green: "      (str/join ", " bad)
                      ". Nothing was changed: wait for it "
                      "(kit checks, in the background), then run kit release again; a failed check is fixed first.")))
    (when held?
      (shell/ok! (shell/shell "gh" "pr" "edit" pr
                   "-R"             repo
                   "--remove-label" "hold"))
      (println "Hold removed."))
    (when isDraft
      (shell/ok! (shell/shell "gh" "pr" "ready" pr "-R" repo))
      (println "Marked ready: the owners are asked for a review."))
    (finish/turn-on-auto-merge! url)
    (println (str "Released: " url))))
