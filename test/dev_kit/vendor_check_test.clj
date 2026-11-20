(ns dev-kit.vendor-check-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev-kit.vendor-check :as vendor-check]))


(def release {"dist/bin/a.js" "a" "package.json" "{}"})


(defn- results
  [overrides]
  (map (juxt :ok? :message)
       (vendor-check/verdicts (merge {:version  "1.2.3"
                                      :integrity-pinned "sha512-x"
                                      :integrity-actual "sha512-x"
                                      :vendored (assoc release "PIN" "p" "README.md" "r")
                                      :released release}
                                     overrides))))


(deftest a_copy_equal_to_the_release_passes_both_checks
  (is (= [[true "the npm release 1.2.3 has the pinned sha512"]
          [true "the vendored files are the release's, byte for byte"]]
         (results {}))))


(deftest a_pin_that_npm_does_not_confirm_is_named
  (is (= [false true] (map first (results {:integrity-actual "sha512-y"})))))


(deftest an_edited_or_extra_vendored_file_is_listed
  (testing "edited and extra files differ; PIN and README.md are ours"
    (let [vendored  (assoc release "dist/bin/a.js" "edited" "dist/bin/extra.js" "x" "PIN" "p")
          [_ files] (vendor-check/verdicts {:vendored vendored :released release})]
      (is (= ["dist/bin/a.js" "dist/bin/extra.js"] (vendor-check/differing-files vendored release)))
      (is (false? (:ok? files))))))


(deftest integrity_is_the_npm_sha512_string
  (is (= "sha512-z4PhNX7vuL3xVChQ1m2AB9Yg5AULVxXcg/SpIdNs6c5H0NE8XYXysP+DGNKHfuwvY7kxvUdBeoGlODJ6+SfaPg=="
         (vendor-check/integrity (.getBytes "" "UTF-8")))))
