(ns dev-kit.flow.board
  "The project board (#22, #41): fields read and set by name, and kit board set."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.string :as str]
   [dev-kit.base :as base]
   [dev-kit.flow.shell :as shell]
   [dev-kit.problem :as problem]))


;; ------------------------------------------------------------------ board (#22, #41)


(defn board-hint
  "The likely cause of a board error and its fix, or nil."
  [msg]
  (cond
    (re-find #"INSUFFICIENT_SCOPES|required scopes|missing required scope" msg)
    (str "the gh login in use lacks the project scope: in your own terminal, GH_CONFIG_DIR="
         (shell/env "GH_CONFIG_DIR" "~/.config/gh")
         " gh auth refresh -h github.com -s project")

    (re-find #"Could not resolve to a ProjectV2|Could not resolve to a User|NOT_FOUND" msg)
    "no access to the board: check that it exists and that this gh account can write to it (the board's Settings > Manage access)"

    (shell/passing-github-error? msg)
    "GitHub had an error: run the command again"))


(defn board-reason [msg] (str msg (some->> (board-hint msg) (str "; "))))


(defn board-gh!
  "gh, run once more after a passing GitHub error (#41). Its trimmed stdout, or an exception with its message."
  [& args]
  (let [once #(apply shell/run "gh" args)
        r    (once)
        r    (if (and (not (zero? (:exit r))) (shell/passing-github-error? (:err r)))
               (once)
               r)]
    (if (zero? (:exit r))
      (str/trim (:out r))
      (throw (ex-info (str/trim (:err r)) {})))))


(def ^:private board-cache (atom {}))


(defn board-set-field
  "Sets a single-select field of an issue's card. The card is found through the project: `item-add` returns the card
  an issue already has and adds a missing one. gh's by-URL mode found it through the issue, and an organization's
  issue does not list a user's board (#22). nil, or why it failed."
  [number owner url field option]
  (try
    (let [number (str number)
          item   (board-gh! "project" "item-add" number
                   "--owner"  owner
                   "--url"    url
                   "--format" "json"
                   "--jq"     ".id")
          pid    (or (:pid @board-cache)
                     (:pid (swap! board-cache assoc
                             :pid
                             (board-gh! "project" "view" number
                               "--owner"  owner
                               "--format" "json"
                               "--jq"     ".id"))))
          fields (or (:fields @board-cache)
                     (:fields (swap! board-cache assoc
                                :fields
                                (json/parse-string (board-gh! "project" "field-list" number
                                                     "--owner"  owner
                                                     "--format" "json"
                                                     "--limit"  "100")
                                                   true))))
          f      (some #(when (= field (:name %)) %) (:fields fields))
          o      (some #(when (= option (:name %)) %) (:options f))]
      (cond
        (nil? f) (str "board "
                      number
                      " has no field '"
                      field
                      "'; its fields: "
                      (str/join ", " (map :name (:fields fields)))
                      ". Use one of them, or add the field on the board")
        (nil? o) (str "the field '"
                      field
                      "' of board "
                      number
                      " has no option '"
                      option
                      "'; its options: "
                      (str/join ", " (map :name (:options f)))
                      ". Use one of them, or add the option on the board")
        :else    (do (board-gh! "project" "item-edit"
                       "--id" item
                       "--project-id" pid
                       "--field-id" (:id f)
                       "--single-select-option-id" (:id o))
                     nil)))
    (catch clojure.lang.ExceptionInfo e (ex-message e))))


(def kit-script
  "The path of bin/kit, which a printed command runs."
  (str (fs/path base/kit-dir "bin" "kit")))


(defn board-set-command
  "The command that sets one field alone, as an error prints it."
  [number owner url field option]
  (str/join " " (map shell/shell-quote ["bb" kit-script "board" "set" (str number) owner url field option])))


(defn- show!
  "The folder's board: its kit.edn, then the environment."
  []
  (let [owner  (shell/env "GHWF_OWNER" nil)
        number (shell/env "GHWF_PROJECT_NUMBER" nil)]
    (if (and owner number)
      (println (json/generate-string {:owner owner :number number}))
      (problem/fail! :board/none
                     "This folder's kit.edn and the environment name no board"
                     {:fix "add :board to kit.edn (run kit init), or set GHWF_OWNER and GHWF_PROJECT_NUMBER"}))))


(defn- set-field!
  [[_ number owner url field option]]
  (when-let [e (board-set-field number owner url field option)]
    (problem/fail! :board/refused e {:fix "read the reason above; kit board set can be run again alone"}))
  (println (str field ": " option)))


(defn board!
  "kit board show | set: the folder's board as JSON, or one field of one card. A refusal is thrown for the flow's edge."
  [args]
  (cond
    (= ["show"] args) (show!)
    (and (= "set" (first args)) (= 6 (count args))) (set-field! args)
    :else
    (problem/fail!
     :board/usage
     "Usage: kit board set <project-number> <owner> <issue-url> <field> <option>\n       kit board show   the folder's board as JSON, {\"owner\": ..., \"number\": ...}"
     {:exit 2 :fix "give the arguments as shown"})))
