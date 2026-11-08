(ns resolver-sim.protocols.sew.terminal-state-snapshot-contract-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.terminal-state-snapshot :as snapshot]))

(def empty-world
  {:held-adjustments []
   :held-artifacts {}
   :held-ledger/index {:by-token {}
                       :by-position {}
                       :by-account {}
                       :by-owner {}
                       :by-workflow {}}
   :total-held {}
   :held/positions {}
   :force-authorisations {}
   :force-authorisations/consumed {}
   :force-authorisations/consumption-records []
   :params {:held-adjustments/complete? true
            :held-ledger/origin :zero}})

(deftest terminal-snapshot-round-trips-an-empty-zero-origin-state
  (let [built (snapshot/build-terminal-state-snapshot empty-world)]
    (is (snapshot/valid-terminal-state-snapshot? built))
    (is (:valid? (snapshot/validate-terminal-state-snapshot built))
        "an emitted terminal snapshot satisfies its own verification contract")
    (is (= {:adjustment nil
            :artifact nil
            :effect-present? false
            :artifact-present? false
            :artifact-hash-matches? false}
           (snapshot/resolve-held-effect built
                                         {:held-adjustment/id "missing"
                                          :held-custody/artifact-hash "sha256:missing"})))
    (is (not (snapshot/valid-terminal-state-snapshot?
              (assoc built :snapshot/hash "sha256:tampered"))))))
