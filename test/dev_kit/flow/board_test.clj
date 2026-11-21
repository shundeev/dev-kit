(ns dev-kit.flow.board-test
  "The board as the owner meets it when GitHub answers badly (#41): what is retried, and what a refusal says. The calls
  to gh are answered one by one; the board's own code runs as it is."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [dev-kit.flow.board :as board]
   [dev-kit.flow.shell :as shell]))


(def ^:private fields
  "{\"fields\":[{\"name\":\"Size\",\"id\":\"F1\",\"options\":[{\"name\":\"S\",\"id\":\"o1\"},{\"name\":\"M\",\"id\":\"o2\"}]}]}")


(defn- board-answering
  "Sets Size on a card with gh answered by `answer`: a function of the call's arguments, {:exit :out :err}. Returns what
  board-set-field said and the calls made."
  [option answer]
  (let [calls (atom [])]
    (reset! @#'board/board-cache {})
    (with-redefs [shell/run (fn [_gh & args]
                              (swap! calls conj (vec args))
                              (answer (vec args)))]
      {:said  (board/board-set-field 3 "u473t8" "https://github.com/o/r/issues/1" "Size" option)
       :calls @calls})))


(defn- gh-works
  [args]
  (let [[_ verb] args]
    {:exit 0
     :out  (case verb
             "item-add"   "PVTI_1\n"
             "view"       "PVT_1\n"
             "field-list" fields
             "item-edit"  "")
     :err  ""}))


(defn- edits [calls] (count (filter #(= "item-edit" (second %)) calls)))


(deftest a-card-field-is-set-and-nothing-is-said
  (let [{:keys [said calls]} (board-answering "M" gh-works)]
    (is (nil? said))
    (is (= 1 (edits calls)))))


(deftest an-option-the-board-lacks-names-the-ones-it-has
  (let [{:keys [said calls]} (board-answering "XXL" gh-works)]
    (is (str/includes? said "its options: S, M"))
    (is (zero? (edits calls)) "nothing is edited")))


(deftest a-passing-github-error-is-retried-once-and-the-run-goes-on
  (let [failed (atom false)
        {:keys [said calls]} (board-answering
                              "M"
                              (fn [args]
                                (if (and (= "item-edit" (second args)) (not @failed))
                                  (do (reset! failed true) {:exit 1 :out "" :err "HTTP 502: Bad Gateway"})
                                  (gh-works args))))]
    (is (nil? said) "the second call went through")
    (is (= 2 (edits calls)) "one call more")))


(deftest a-refusal-names-gh-s-reason-and-the-way-out
  (doseq [[story err fix]
          [["a token without the project scope"
            "GraphQL: Your token has not been granted the required scopes (INSUFFICIENT_SCOPES)"
            "gh auth refresh -h github.com -s project"]
           ["no access to the board" "Could not resolve to a ProjectV2 with the number 3." "no access to the board"]]]
    (let [{:keys [said calls]} (board-answering "M"
                                                (fn [args]
                                                  (if (= "item-add" (second args))
                                                    {:exit 1 :out "" :err err}
                                                    (gh-works args))))]
      (is (str/includes? (board/board-reason said) err) (format "%s: gh's reason" story))
      (is (str/includes? (board/board-reason said) fix) (format "%s: the fix" story))
      (is (= 1 (count calls)) (format "%s: not retried" story)))))
