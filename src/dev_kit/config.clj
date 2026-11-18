(ns dev-kit.config
  "kit.edn and kit-defaults.edn: reading, merging and checking the configuration; the text of generated files."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [rewrite-clj.parser :as rewrite-parser]))


(def config-file (base/rel "kit.edn"))


(defn read-defaults [] (edn/read-string (slurp (str (fs/path base/kit-dir "kit-defaults.edn")))))


(defn fail!
  "A problem the person fixes: kit prints the message, without a stack trace, and exits 1."
  [& msg]
  (throw (ex-info (str/join " " msg) {:kit/problem true})))


(defn merge-config
  "kit.edn over the defaults: a key replaces the default, except :board, whose :owner and :number merge one by one
  (#14). :agent replaces whole, so one account's login never pairs with another's email."
  [defaults m]
  (cond-> (merge defaults m)
    (and (map? (:board defaults)) (map? (:board m))) (assoc :board (merge (:board defaults) (:board m)))))


(defn parse-kit-edn
  "The map in a kit.edn's text; label names the file in what fails: its line, and the fix."
  [label s]
  (let [m (try (edn/read-string s)
               (catch Exception e
                 (let [why (or (try (rewrite-parser/parse-string-all s) nil (catch Exception e2 (ex-message e2)))
                               (ex-message e))
                       [_ msg line] (re-find #"(.*) \[at line (\d+), column \d+\]" why)]
                   (fail! (str label
                               (when line
                                 (str " line " line))
                               " cannot be read ("
                               (or msg why)
                               "): fix it by hand")))))]
    (when-not (map? m)
      (fail! (str label " is not a map: make it {:repo \"owner/name\" :showcase \"…\" :modules #{}}")))
    m))


(defn read-config
  "kit.edn over the defaults, or nil when there is none."
  []
  (when-let [s (base/slurp-if config-file)]
    (merge-config (read-defaults) (parse-kit-edn "kit.edn" s))))


(defn read-config-at
  "The kit.edn at the git toplevel of folder, over the defaults, or nil when the folder has none (or is no git
  folder). The flow reads it from the folder it runs in, never from where kit is mounted."
  [folder]
  (when-let [top (base/sh-in folder "git" "rev-parse" "--show-toplevel")]
    (let [file (str (fs/path top "kit.edn"))]
      (when-let [s (base/slurp-if file)]
        (merge-config (read-defaults) (parse-kit-edn file s))))))


(def setting-paths
  "The GHWF_ names of the project's settings and where kit.edn keeps each."
  {"GHWF_OWNER"          [:board :owner]
   "GHWF_PROJECT_NUMBER" [:board :number]
   "GHWF_REPO"           [:repo]
   "GHWF_DEFAULT_BASE"   [:base]
   "GHWF_SHOWCASE"       [:showcase]
   "GHWF_REVIEWERS"      [:owners]})


(defn setting
  "One setting of the flow, as text: the folder's kit.edn (cfg), then the environment (getenv is (fn [variable])),
  then the default. The flag, which beats all three, is the caller's."
  [cfg getenv variable default]
  (let [v (some->> (setting-paths variable)
                   (get-in cfg))]
    (or (not-empty (if (sequential? v)
                     (str/join "," v)
                     (some-> v str)))
        (not-empty (getenv variable))
        default)))


(def known-modules #{:clojure})


(defn config-problems
  "What is wrong with kit.edn over the defaults, each naming the key and the fix (#14)."
  [{:keys [repo showcase modules base owners board agent user-test]}]
  (let [unknown   (when (coll? modules)
                    (remove known-modules modules))
        run       (when (map? user-test)
                    (:run user-test))
        artifacts (when (map? user-test)
                    (:artifacts user-test))]
    (cond-> []
      (not (and (string? repo) (re-matches #"[^/\s]+/[^/\s]+" repo)))
      (conj (str ":repo is "
                 (pr-str repo)
                 ", not \"owner/name\": set it in kit.edn (kit init --repo owner/name in a new project)"))

      (not (and (string? showcase) (not (str/blank? showcase))))
      (conj (str ":showcase is missing: set it in kit.edn to the board's Showcase option for this project"
                 " (kit init --showcase <option> in a new project)"))

      (not (coll? modules)) (conj ":modules must be a set, e.g. #{} or #{:clojure}")

      (seq unknown)
      (conj (str ":modules has " (str/join ", " (map pr-str unknown))
                 "; known modules: "
                 (str/join ", " (map name (sort known-modules)))))

      (not (and (string? base) (not (str/blank? base)))) (conj ":base must name the base branch, e.g. \"main\"")

      (not (and (sequential? owners) (seq owners) (every? string? owners)))
      (conj ":owners must list who reviews every change, e.g. [\"shundeev/owners\"]")

      (not (and (string? (:owner board)) (int? (:number board)))) (conj ":board must be {:owner \"login\" :number N}")

      (not (or (nil? agent) (and (map? agent) (every? #(string? (get agent %)) [:login :email :gh-config-dir]))))
      (conj
       ":agent must be nil, or a map with :login, :email and :gh-config-dir (the agent's account; see kit-defaults.edn)")

      (not (or (nil? user-test) (and (string? run) (not (str/blank? run)))))
      (conj (str ":user-test must be nil, or {:run \"<command>\" :artifacts \"<folder>\"} with :run the command of the"
                 " project's user-level test (#27, docs/user-level-tests.md)"))

      ;; A plain path: upload-artifact reads *, !, ~ and the like as patterns, and "." would be the whole checkout.
      (not (or (nil? artifacts)
               (and (string? artifacts)
                    (re-matches #"[A-Za-z0-9_][A-Za-z0-9._/-]*" artifacts)
                    (not-any? #{".."} (str/split artifacts #"/")))))
      (conj (str ":user-test :artifacts is " (pr-str artifacts)
                 ": it must be a folder inside the project, named with"
                 " letters, digits, . _ - and /, e.g. \"test-results\"")))))


(defn clojure? [cfg] (contains? (set (:modules cfg)) :clojure))


(defn user-test!
  "Runs the project's user-level test, :user-test :run in kit.edn, from the project's root, and exits with its code
  (#27). CI runs it inside the `checks` job, after the other checks."
  []
  (let [cfg (or (read-config) (fail! "no kit.edn: run .shared/bin/kit init first"))
        _ (some->> (seq (config-problems cfg)) (map #(str "kit.edn: " %)) (str/join "; ") fail!)
        run (get-in cfg [:user-test :run])]
    (when-not run
      (fail! "kit.edn has no :user-test: add :user-test {:run \"<the command of the user-level test>\""
             ":artifacts \"<the folder it writes>\"} (docs/user-level-tests.md in dev-kit)"))
    (println (str "user-level test: " run))
    (flush)
    (System/exit (:exit @(p/process {:dir (str base/root) :inherit true} "bash" "-c" run)))))


(def block-begin
  (str "<!-- dev-kit:begin — generated from "
       base/mount
       "/rules by "
       base/mount
       "/bin/kit init. Edit it in dev-kit, not here. -->"))


(def block-end "<!-- dev-kit:end -->")


(defn block-text
  [cfg]
  (str block-begin
       "\n\n"
       (slurp (str (fs/path base/kit-dir "rules" "shared.md")))
       (when (clojure? cfg)
         (str "\n" (slurp (str (fs/path base/kit-dir "rules" "clojure.md")))))
       "\n"
       block-end))


(defn deep-merge
  [a b]
  (cond (and (map? a) (map? b)) (merge-with deep-merge a b)
        (and (sequential? a) (sequential? b)) (vec (distinct (concat a b)))
        (nil? b) a
        :else b))


(defn json-text
  [m]
  (str (json/generate-string m
                             {:pretty {:indentation    2
                                       :indent-arrays? true
                                       :object-field-value-separator ": "}})
       "\n"))


(defn settings-text
  [cfg]
  (let [base (-> (slurp (str (fs/path base/kit-dir "claude" "settings.base.json")))
                 (str/replace "{{kit}}" base/mount)
                 (json/parse-string))
        clj  (when (clojure? cfg)
               {"permissions" {"allow" ["Bash(clj-kondo *)" "Bash(zprint *)"
                                        (str "Bash(" base/mount "/clojure/zprint_check.sh *)")
                                        (str "Bash(bb " base/mount "/clojure/single_caller.clj *)")
                                        (str "Bash(bb " base/mount "/clojure/field_of_same_map.clj *)")]}})]
    (json-text (deep-merge (deep-merge base clj)
                           (json/parse-string (json/generate-string (:claude-settings cfg {})))))))
