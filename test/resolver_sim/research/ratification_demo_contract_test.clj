(ns resolver-sim.research.ratification-demo-contract-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [scripts.ratification.generate-demo-workspace :as fixture]))

(deftest ratification-demo-golden-is-reproducible
  (let [golden (with-open [reader (io/reader "examples/ratification-v1/ratified-with-dissent.json")]
                 (json/read reader))
        regenerated (fixture/workspace)]
    (is (= regenerated golden)
        "The wire fixture must match independently reconstructed PRF output")
    (is (= "ratified-with-dissent" (get-in golden ["ratification" "status"]))
        "The example retains dissent while meeting the approval threshold")
    (is (false? (get-in golden ["ratification" "operationalAuthority"]))
        "Research ratification does not issue operational authority")
    (is (= 3 (count (get-in golden ["policy" "eligiblePrincipals"]))))
    (is (= 1 (get-in golden ["ratification" "dissentCount"])))))
