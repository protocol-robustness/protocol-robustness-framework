(ns resolver-sim.claim-bindings-test
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(deftest public-claim-bindings-are-ci-valid
  (let [{:keys [exit out err]}
        (shell/sh "clojure" "-M:with-sew" "-m" "scripts.validate-claim-bindings")
        report (json/read-str (slurp "target/claim-binding-report.json"))]
    (is (zero? exit) (str out err))
    (is (= "passed" (get report "status")))
    (is (= 3 (get report "claims_total")))
    (is (vector? (get report "errors")))))
