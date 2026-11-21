(ns dev-kit.flow.watched-pr
  "The steps kit checks and kit review share to settle which pull request they watch and how often they look: the
  repository, the pause between two looks, the branch, the --pr option and the pull request itself. A refusal's text
  names the command, read from the fact :command."
  (:require
   [dev-kit.flow.github :as github]
   [dev-kit.flow.shell :as shell]
   [dev-kit.flow.wait :as wait]
   [dev-kit.problem :as problem]))


(defn read-repo
  "The repository the run works in, named before anything else is asked of GitHub."
  [{:keys [repo]}]
  {:repo (shell/resolve-repo repo)})


(defn read-poll
  "The pause between two looks, in seconds."
  [{:keys [poll-text]}]
  {:poll (wait/poll-seconds poll-text)})


(defn read-branch
  "The branch checked out now."
  [_facts]
  {:branch (github/head-branch)})


(defn check-pr-option
  "--pr names the pull request by its number."
  [{:keys [pr]}]
  (when (and pr (not (re-matches #"\d+" pr)))
    (problem/fail! :pr/bad-number
                   (format "--pr is '%s'" pr)
                   {:fix "give the pull request's number, e.g. --pr 12"})))


(defn find-pr
  "The pull request named by --pr, else the open one of the issue branch. :command is
  the command's name, which goes into the refusals that say what to run instead (kit checks, kit review)."
  [{:keys [command pr repo branch default-base]}]
  {:pr (or
        pr
        (do (when (#{default-base "HEAD"} branch)
              (problem/fail! :branch/not-an-issue-branch
                             (format "'%s' is not an issue branch" branch)
                             {:fix (format "run from the issue branch, or name the pull request: kit %s --pr <number>"
                                           command)}))
            (github/open-pr-url repo branch "open"))
        (problem/fail! :pr/no-open-pr
                       (format "No open pull request for branch '%s' in %s" branch repo)
                       {:fix (format "open it with kit finish, or name it: kit %s --pr <number>" command)}))})


(defn no-result
  "The failure of a read of GitHub that gave no answer: gh's message as it is, exit 2."
  [message fix]
  (problem/fail! :github/no-answer message {:exit 2 :fix fix}))
