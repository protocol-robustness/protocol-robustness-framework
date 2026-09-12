(ns resolver-sim.tools.stability-checker-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.tools.stability-checker :as checker]))

(deftest stability-manifest-self-hash-covers-the-whole-manifest
  (let [self-entry {:stability/id :stability/stability-manifest
                    :stability/surface "Stability manifest itself"
                    :stability/files ["STABILITY_MANIFEST.edn"
                                      "src/resolver_sim/tools/stability_checker.clj"
                                      "src/resolver_sim/hash/canonical.clj"]
                    :stability/hash "ignored"}
        base-manifest {:stability/entries
                       [self-entry
                        {:stability/id :stability/example
                         :stability/surface "Example surface"
                         :stability/files ["src/resolver_sim/hash/canonical.clj"]
                         :stability/hash "abc"}]}
        changed-manifest (assoc-in base-manifest
                                   [:stability/entries 1 :stability/surface]
                                   "Changed surface label")
        normalized-base (#'checker/normalize-manifest-for-self-hash base-manifest)
        normalized-changed (#'checker/normalize-manifest-for-self-hash changed-manifest)]
    (is (not= (pr-str normalized-base)
              (pr-str normalized-changed)))))
