(ns resolver-sim.research.sew.adversarial.liveness-participation-test
  "Tests for the Phase R liveness consumer's display-path propagation of the
   queue-saturation fields.

   Phase R is exploratory and display-only; these tests assert that the
   saturation evidence added to latency-sensitivity actually reaches the
   reduced result maps, and that the propagated fields remain internally
   consistent under the canonical test-time verifier."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.research.sew.adversarial.liveness-participation :as phase-r]
            [resolver-sim.stochastic.liveness-failures :as liveness]))

(deftest latency-sensitivity-propagates-saturation-fields
  (testing "every reduced latency result carries the four saturation fields"
    (let [results (phase-r/test-latency-sensitivity)]
      (is (seq results))
      (doseq [m results]
        (is (= liveness/saturation-queue-days (:saturation-queue-days m)))
        (is (contains? #{:liveness/queue-saturated :liveness/queue-unsaturated}
                       (:saturation-queue m)))
        (is (boolean? (:saturation-satisfied m)))
        (is (boolean? (:liveness/wait-capped? m)))))))

(deftest propagated-saturation-fields-internally-consistent
  (testing "the propagated saturation fields satisfy the canonical test-time invariant"
    (doseq [m (phase-r/test-latency-sensitivity)]
      (let [;; :wait-days is the display name for the queue-wait value; alias it
            ;; so the shared verifier can operate on the propagated artifact.
            artifact (assoc m :queue-wait-days (:wait-days m))
            result (liveness/check-saturation-invariant artifact)]
        (is (:holds? result)
            (str "propagated saturation fields inconsistent: "
                 (select-keys m [:volume :wait-days :saturation-queue
                                 :saturation-queue-days :liveness/wait-capped?
                                 :saturation-satisfied])))
        (is (empty? (:violations result)))))))
