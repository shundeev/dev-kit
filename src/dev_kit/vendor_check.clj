(ns dev-kit.vendor-check
  "bb vendor-check: the vendored cc-safety-net is the npm release pinned in its PIN file, byte for byte. It
  needs the network, so it runs in CI only when a pull request changes vendor/, and on main; selftest.sh
  checks the wiring around the copy, not the copy."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [babashka.process :as process]
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)
   (java.util Base64)))


(def vendor-dir "vendor/cc-safety-net")


;; files that are ours, not the release's
(def not-from-release #{"PIN" "README.md"})


(defn parse-pin
  "PIN's `key=value` lines as a map."
  [text]
  (into {}
        (for [line  (str/split-lines text)
              :let  [[_ k v] (re-matches #"([^=\s]+)=(.*)" line)]
              :when k]
          [k v])))


(defn integrity
  "The npm integrity string of a tarball's bytes."
  [^bytes tarball]
  (let [digest (.digest (MessageDigest/getInstance "SHA-512") tarball)]
    (str "sha512-" (.encodeToString (Base64/getEncoder) digest))))


(defn differing-files
  "Relative paths of the vendored files that the release lacks or has with other bytes. Both arguments map a
  path to its content as a string (one char per byte)."
  [vendored released]
  (->> vendored
       (remove (fn [[path _]] (not-from-release path)))
       (remove (fn [[path content]] (= content (get released path))))
       (map key)
       sort
       vec))


(defn verdicts
  "The checks' results as a vector of {:ok? :message :detail}, from the PIN, the tarball's integrity and the
  two file maps."
  [{:keys [version integrity-pinned integrity-actual vendored released]}]
  (let [differ (differing-files vendored released)]
    [(if (= integrity-pinned integrity-actual)
       {:ok? true :message (str "the npm release " version " has the pinned sha512")}
       {:ok?     false
        :message "the release's sha512 differs from PIN"
        :detail  (str "PIN says "
                      integrity-pinned
                      ", npm serves "
                      integrity-actual
                      ". Check the PIN against `npm view cc-safety-net@"
                      version
                      " dist.integrity`.")})
     (if (empty? differ)
       {:ok? true :message "the vendored files are the release's, byte for byte"}
       {:ok?     false
        :message "vendored files differ from the release"
        :detail  (str (str/join "\n" differ)
                      "\nReplace them from the release tarball (vendor/cc-safety-net/README.md, To update).")})]))


(defn- file-contents
  "Map of path relative to `root` to its content, one char per byte, for every file under `root`."
  [root]
  (into {}
        (for [f     (fs/glob root "**" {:hidden true})
              :when (fs/regular-file? f)]
          [(str (fs/relativize root f)) (String. (fs/read-all-bytes f) "ISO-8859-1")])))


(defn check!
  "Downloads the pinned release and returns the verdicts; throws on a download that fails."
  []
  (let [pin     (parse-pin (slurp (str vendor-dir "/PIN")))
        version (get pin "version")
        url     (str "https://registry.npmjs.org/cc-safety-net/-/cc-safety-net-" version ".tgz")
        tarball (:body (http/get url {:as :bytes}))
        work    (fs/create-temp-dir)]
    (try
      (let [tgz (str (fs/path work "release.tgz"))]
        (fs/write-bytes tgz tarball)
        (process/shell "tar"
          "-xzf" tgz
          "-C"   (str work))
        (verdicts {:version  version
                   :integrity-pinned (get pin "integrity")
                   :integrity-actual (integrity tarball)
                   :vendored (file-contents vendor-dir)
                   :released (file-contents (fs/path work "package"))}))
      (finally (fs/delete-tree work)))))


(defn -main
  [& _]
  (let [results (try
                  (check!)
                  (catch Exception e
                    [{:ok? false
                      :message
                      "the npm release of cc-safety-net could not be downloaded, so the vendored copy is unchecked"
                      :detail (str (ex-message e) "\nRun `bb vendor-check` again once the network is back.")}]))]
    (doseq [{:keys [ok? message detail]} results]
      (println (if ok? "  ok   " "  FAIL ") message)
      (when detail
        (println detail)))
    (when-not (every? :ok? results)
      (System/exit 1))))
