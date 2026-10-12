(ns prf.canonical.test-runner
  (:require [clojure.test :as test]
            [prf.canonical.codec-test]
            [prf.canonical.reference-test]
            [prf.canonical.compatibility-test]))

(defn -main [& _]
  (let [result (test/run-tests 'prf.canonical.codec-test
                               'prf.canonical.reference-test
                               'prf.canonical.compatibility-test)]
    (System/exit (+ (:fail result) (:error result)))))
