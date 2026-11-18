(ns dev-kit.gate
  "The gate (ADR-0002, #26, #21): a pull request's shape, its size and the design approval, in CI and as the edit hook."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [dev-kit.config :as config]
   [dev-kit.identity :as ident]))


;; The pull request gate, in CI: `kit gate --branch … --pr …`. In order:
;; - shape (#21): the branch is `<n>-<slug>` of an open issue, the title `type(GH-n): summary`, the
;;   body has an `ADR:` line and filled **Review** and **User-level test** sections; the finish
;;   script runs the same rules offline (`kit gate --shape`) before it opens the PR;
;; - accepted ADRs (`adr/NNNN-*.md` on the base) are not modified, removed or renamed;
;; - G1: an M or L issue, or one without a `size:*` label, needs `design-approved`, added after the
;;   issue body and its `## Design` comments last changed, by someone with write access who is not
;;   the agent. An XS/S pull request stays within a size threshold.
;; The PreToolUse hook runs `kit gate --hook` before Edit/Write.


(def size-threshold
  "Lines changed in an XS/S pull request, lock and kit-managed files not counted. Merged before the
  gate (#26): S PRs 0–160 lines (dev-kit#43 the largest), M PRs 53–467. 200 sits above every S so
  far: it catches a clearly under-sized S, not every M."
  200)


(def lock-file #"(^|/)(package-lock\.json|yarn\.lock|pnpm-lock\.yaml|[^/]+\.lock)$")


(def commit-types ["build" "chore" "ci" "docs" "feat" "fix" "perf" "refactor" "test"])


(defn branch-issue
  "The issue number of a `<n>-<slug>` branch, or nil."
  [branch]
  (second (re-find #"^(\d+)-" (str branch))))


(defn report!
  "Prints [level message] lines as `ok`/`FAIL`; exits 1 when one failed."
  [verdicts]
  (doseq [[level msg] verdicts]
    (println (str (if (= level :ok) "  ok    " "  FAIL  ") msg)))
  (when (some #(not= :ok (first %)) verdicts) (System/exit 1)))


(defn section-text
  "The text under `## <heading>` up to the next heading or the ADR line, without the template's
  `<!-- … -->` hints; nil when the body has no such heading."
  [body heading]
  (when-let [[_ text] (re-find (re-pattern (str "(?ms)^## "
                                                (java.util.regex.Pattern/quote heading)
                                                "[ \\t]*$(.*?)(?=^## |^ADR:|\\z)"))
                               body)]
    (str/trim (str/replace text #"(?s)<!--.*?-->" ""))))


(defn shape-problems
  "What is wrong with a pull request's branch, title and body, each with its fix (#21). Offline, so
  the finish script checks before it opens the PR what CI checks after. A nil body is not checked."
  [branch title body pr]
  (if-let [n (branch-issue branch)]
    (cond-> []
      (not (re-matches (re-pattern (str "(?:" (str/join "|" commit-types) ")\\(GH-" n "\\): \\S.*")) (str title)))
      (conj (str "the title '" title
                 "' is not '<type>(GH-" n
                 "): <summary>' with a type from " (str/join ", " commit-types)
                 ": " (if pr
                        (str "rename it: gh pr edit " pr " --title '<type>(GH-" n "): <summary>'")
                        "pass --type from that list, or fix the first line of the commit message")))

      (and body (not (re-find #"(?m)^ADR: +(?:none|adr/\d{4}-\S+\.md)\b" body)))
      (conj "the body has no 'ADR: none' or 'ADR: adr/NNNN-….md' line: add one (adr skill); a reason may follow it")

      body
      (into
       (for [heading ["Review" "User-level test"]
             :let    [text (section-text body heading)]
             :when   (str/blank? text)]
         (if (nil? text)
           (str "the body has no line '## " heading "' (exactly that heading): add the section from the PR template")
           (str "'## "
                heading
                "' is empty, or holds only the template's hint: fill in ## "
                heading
                "; CI reruns when the body is edited")))))
    [(str "branch '" branch "' is not <issue>-<slug>: open the issue with kit start and move the work to its branch")]))


(defn adr-problems
  "Accepted ADRs, `adr/NNNN-*.md` that exist on the base, which the pull request's files change."
  [files]
  (for [{:strs [filename status previous_filename]} files
        :let  [f (if (= status "renamed") previous_filename filename)]
        :when (and (#{"modified" "changed" "removed" "renamed"} status) (re-matches #"adr/\d{4}-[^/]+\.md" (str f)))]
    (str f
         " was "
         status
         ", but accepted ADRs are immutable: revert it, and record the change as a new ADR with "
         "'Supersedes: ADR-"
         (subs f 4 8)
         "' (adr skill)")))


(def issue-query
  (str
   "query($o:String!,$n:String!,$i:Int!){repository(owner:$o,name:$n){issue(number:$i){"
   "state lastEditedAt labels(first:50){nodes{name}}"
   " timelineItems(itemTypes:[LABELED_EVENT],last:50){nodes{... on LabeledEvent{actor{login} label{name} createdAt}}}"
   " comments(last:100){nodes{author{login} body createdAt lastEditedAt}}}}}"))


(defn fetch-issue
  [repo n]
  (let [[o r] (str/split repo #"/")]
    (get-in (json/parse-string (base/gh! "api" "graphql"
                                 "-f" (str "query=" issue-query)
                                 "-f" (str "o=" o)
                                 "-f" (str "n=" r)
                                 "-F" (str "i=" n)))
            ["data" "repository" "issue"])))


(defn issue-size
  "The issue's size label (\"XS\" … \"XL\"), or nil."
  [issue]
  (some #(second (re-matches #"size:(XS|S|M|L|XL)" (get % "name"))) (get-in issue ["labels" "nodes"])))


(defn g1-verdict
  "[:ok msg] or [:fail reason-with-fix] for the design approval of issue n."
  [repo n issue agent]
  (let [labels  (set (map #(get % "name") (get-in issue ["labels" "nodes"])))
        event   (->> (get-in issue ["timelineItems" "nodes"])
                     (filter #(= "design-approved" (get-in % ["label" "name"])))
                     (sort-by #(get % "createdAt"))
                     last)
        by      (get-in event ["actor" "login"])
        at      (get event "createdAt")
        later?  (fn [t] (and t at (pos? (compare t at))))
        redo    (str "; the owner approves again: remove `design-approved` from #" n " and add it back")
        changed (some #(and (str/starts-with? (str (get % "body")) "## Design")
                            (or (later? (get % "createdAt")) (later? (get % "lastEditedAt"))))
                      (get-in issue ["comments" "nodes"]))]
    (cond
      (not (labels "design-approved"))
      [:fail (str "#" n " has no `design-approved`: code waits until the owner approves the design by adding it")]

      (nil? event) [:fail (str "#" n " carries `design-approved` but GitHub shows no event for it" redo)]

      (= by agent)
      [:fail
       (str "`design-approved` on #" n " was set by the agent (" by "); only a person with write access approves" redo)]

      (not (#{"write" "maintain" "admin"}
            (str/trim (base/gh! "api" (str "repos/" repo "/collaborators/" by "/permission") "--jq" ".permission"))))
      [:fail (str "`design-approved` on #" n " was set by " by ", who has no write access" redo)]

      (later? (get issue "lastEditedAt"))
      [:fail (str "the body of #" n " was edited after the approval (" at ")" redo)]

      changed [:fail (str "a `## Design` comment on #" n " changed after the approval (" at ")" redo)]

      :else [:ok (str "G1: design of #" n " approved by " by " at " at)])))


(defn gate-config [] (merge (config/read-defaults) (or (config/read-config) {})))


(defn gate-repo
  "The repository gate and retro act on: --repo, CI's, the current folder's origin, then the session's
  GHWF_REPO and kit.edn. The folder comes before the session (#81)."
  [{:keys [repo]} cfg]
  (or
   repo
   (not-empty (System/getenv "GITHUB_REPOSITORY"))
   (base/origin-repo ".")
   (not-empty (System/getenv "GHWF_REPO"))
   (:repo cfg)
   (base/die
    "no repository: this folder has no GitHub origin and GHWF_REPO is not set; run inside the clone, or pass --repo owner/name")))


(defn managed-paths
  "Files kit generates in this project: not counted towards a PR's size."
  [cfg]
  (if (config/read-config)
    (into #{base/mount} (keep #(when (#{:file :symlink} (:kind %)) (:path %)) (ident/managed-items cfg)))
    #{}))


(defn pr-files
  "Every file of the pull request, all pages: one JSON object per line from gh."
  [repo pr]
  (->> (base/gh! "api"
                 (str "repos/" repo "/pulls/" pr "/files?per_page=100")
                 "--paginate"
                 "--jq"
                 ".[] | {filename, status, previous_filename, additions, deletions, patch}")
       str/split-lines
       (remove str/blank?)
       (map json/parse-string)))


(defn pr-lines
  [files excluded]
  (->> files
       (remove #(let [f (get % "filename")] (or (re-find lock-file f) (excluded f))))
       (map #(+ (get % "additions" 0) (get % "deletions" 0)))
       (reduce + 0)))


(defn issue-verdict
  "[level message] for the issue behind the pull request: open, and its design approval or size."
  [repo n issue files cfg]
  (let [size (issue-size issue)]
    (cond
      (or (nil? issue) (get issue "error"))
      [:fail
       (str "#"
            n
            " is not an issue of "
            repo
            (some->> (get issue "error") (str ": "))
            "; the branch must start with the number of the PR's issue")]

      (not= "OPEN" (get issue "state"))
      [:fail (str "#" n " is closed: reopen it, or move the work to the branch of an open issue")]

      (#{"XS" "S"} size)
      (let [lines (pr-lines files (managed-paths cfg))]
        (if (<= lines size-threshold)
          [:ok
           (str "size "
                size
                ": "
                lines
                " lines changed, within "
                size-threshold
                " (lock and kit-managed files not counted); G1 not required")]
          [:fail
           (str "#"
                n
                " is size:"
                size
                " but the PR changes "
                lines
                " lines (over "
                size-threshold
                "): resize #"
                n
                " to size:M on the board and its label, post the design, and the owner approves it")]))

      :else
      (let [[l m] (g1-verdict repo n issue (get-in cfg [:agent :login]))]
        [l
         (str (if size
                (str "size " size)
                "no size label, so M")
              "; "
              m)]))))


(defn size-problems
  "An M pull request (an issue that is not XS or S) states its size in numbers before and after the simplify pass (#117, #132)."
  [issue body]
  (when (and (not (#{"XS" "S"} (issue-size issue))) (not (re-find #"(?m)^Size: +\d+ +(?:→|->) +\d+\b" body)))
    ["an M pull request has no 'Size: <lines before> → <lines after>' line with numbers: add it, e.g. 'Size: 164 → 150 lines after simplify'"]))


(defn rule-problems
  "Each non-blank line the pull request removes from `rules/shared.md` that its body does not quote (#117).
  Whitespace is normalised, since a rule line may wrap."
  [files body]
  (let [norm    #(str/join " " (str/split (str/trim (str %)) #"\\s+"))
        quoted  (norm body)
        removed (for [f     files
                      :when (= "rules/shared.md" (get f "filename"))
                      l     (str/split-lines (str (get f "patch")))
                      :when (re-matches #"-.*\S.*" l)]
                  (norm (subs l 1)))]
    (for [l     removed
          :when (not (str/includes? quoted l))]
      (str "the pull request removes this rule line from rules/shared.md and its body does not quote it: '"
           l
           "'. Quote the old line in the PR body, next to the new one"))))


(defn gate-ci!
  [{:keys [branch pr] :as opts}]
  (let [cfg    (gate-config)
        repo   (gate-repo opts cfg)
        branch (or branch (not-empty (System/getenv "GITHUB_HEAD_REF")) (base/die "gate: pass --branch"))
        pr     (or pr (base/die "gate: pass --pr"))
        n      (branch-issue branch)
        read!  #(try (%)
                     (catch Exception e
                       (report! [[:fail
                                  (str "could not read PR #"
                                       pr
                                       " of "
                                       repo
                                       " from GitHub ("
                                       (ex-message e)
                                       "); rerun the job")]])))
        {:strs [title body]} (read! #(json/parse-string (base/gh! "api"  (str "repos/" repo "/pulls/" pr)
                                                                  "--jq" "{title, body}")))
        files  (read! #(doall (pr-files repo pr)))
        issue  (when n (try (fetch-issue repo n) (catch Exception e {"error" (ex-message e)})))]
    (report! (concat (for [p (concat (shape-problems branch title (str body) pr)
                                     (size-problems issue (str body))
                                     (adr-problems files)
                                     (rule-problems files (str body)))]
                       [:fail p])
                     (when n [(issue-verdict repo n issue files cfg)])))))


(defn gate-shape!
  "The shape rules alone, offline, for the finish script before it commits and opens the PR."
  [{:keys [branch title body-file]}]
  (report! (for [p (shape-problems branch title (some-> body-file slurp) nil)] [:fail p])))


(defn gate-g1!
  "The design approval of one issue, as the hook and a person need it. :cfg in opts is the config of the
  repository the hook judges, instead of this kit's own project."
  [n opts]
  (let [cfg   (or (:cfg opts) (gate-config))
        repo  (gate-repo opts cfg)
        issue (fetch-issue repo n)
        size  (issue-size issue)]
    (if (#{"XS" "S"} size)
      [:ok (str "#" n " is size " size "; G1 not required")]
      (g1-verdict repo n issue (get-in cfg [:agent :login])))))


(defn edited-file-repo
  "Where an edit lands (#99, ADR-0006): {:top :branch :path :dir} of the git repository holding file, from the
  real path of its nearest existing part (a symlink is followed, a directory not yet made is walked past), with
  :path the file's path from that root. nil when the file is in no repository."
  [file]
  (let [full         (fs/absolutize file)
        existing     (first (filter fs/exists? (take-while some? (iterate fs/parent full))))
        real         (when existing (fs/real-path existing))
        dir          (when real
                       (if (fs/directory? real)
                         real
                         (fs/parent real)))
        [top branch] (when dir
                       (some-> (base/sh-in dir "git" "rev-parse" "--show-toplevel" "--abbrev-ref" "HEAD")
                               str/split-lines))]
    (when (and top branch)
      {:top    top
       :branch branch
       :dir    (str dir)
       :path   (str (fs/relativize top (fs/path real (fs/relativize existing full))))})))


(defn on-dev-kit?
  "A repository whose root holds kit.edn (a project) or kit-defaults.edn (dev-kit itself)."
  [top]
  (or (fs/regular-file? (fs/path top "kit.edn")) (fs/regular-file? (fs/path top "kit-defaults.edn"))))


(defn repo-config
  "The gate's config for the repository at top: its kit.edn over the defaults."
  [top]
  (config/merge-config (config/read-defaults)
                       (or (some-> (base/slurp-if (str (fs/path top "kit.edn"))) edn/read-string) {})))


(defn gate-hook!
  "PreToolUse for Edit/Write/NotebookEdit (ADR-0006): judges the repository and branch of the edited file, not
  the session's folder; an input with no path is judged by the session's folder (the input's cwd, else .). In a
  repository on dev-kit, on an issue branch, outside adr/, refuses an edit while the design approval is missing.
  The repository asked is the file's own (its origin, else :repo of its kit.edn). Verdicts are cached for 10
  minutes in that repository; GitHub unreachable means refused."
  [opts]
  (let [input (try (json/parse-string (slurp *in*)) (catch Exception _ nil))
        file  (or (get-in input ["tool_input" "file_path"]) (get-in input ["tool_input" "notebook_path"]))
        {:keys [top branch path dir]} (edited-file-repo (or file (get input "cwd") "."))
        path  (when file path)
        n     (branch-issue branch)
        deny! (fn [reason]
                (println (json/generate-string
                          {:hookSpecificOutput {:hookEventName      "PreToolUse"
                                                :permissionDecision "deny"
                                                :permissionDecisionReason (str "Refused: " reason)}})))]
    (when (and n (on-dev-kit? top) (not (some-> path (str/starts-with? "adr/"))))
      (let [cfg   (try (repo-config top) (catch Exception _ nil))
            repo  (when cfg (or (base/origin-repo top) (:repo cfg)))
            cache (str (fs/absolutize (fs/path dir (base/sh-in dir "git" "rev-parse" "--git-path" (str "kit/g1-" n)))))
            now   (quot (System/currentTimeMillis) 1000)
            [t level reason] (some-> (base/slurp-if cache) (str/split #" " 3))]
        (cond
          (nil? cfg)
          (deny! (str "kit.edn of " top " cannot be read: fix it by hand, then retry"))

          (nil? repo)
          (deny! (str "cannot tell which GitHub repository holds #" n
                      " for " top
                      ": set :repo in that repository's kit.edn, "
                      "or add its GitHub origin (git remote add origin https://github.com/<owner>/<name>.git)"))

          :else
          (let [[level reason]
                ;; 600 s: the cache saves a GitHub call on every edit, and a removed approval is seen within 10 minutes.
                ;; No event tells a local hook that the label changed, so this is a lifetime, not a wait (#109).
                (if (and t (< (- now (parse-long t)) 600))
                  [(keyword level) reason]
                  (try (let [[l r] (gate-g1! n (assoc opts :cfg cfg :repo repo))]
                         (fs/create-dirs (fs/parent cache))
                         (spit cache (str now " " (name l) " " r))
                         [l r])
                       (catch Exception e
                         [:error
                          (str "could not check the design approval of #" n
                               " (" (ex-message e)
                               "); retry, or check by hand: kit gate --g1 " n)])))]
            (when (not= level :ok) (deny! reason))))))))


(defn gate!
  [{:keys [g1 hook shape] :as opts}]
  (cond
    hook  (gate-hook! opts)
    shape (gate-shape! opts)
    g1    (report! [(try (gate-g1! g1 opts) (catch Exception e [:fail (ex-message e)]))])
    :else (gate-ci! opts)))
