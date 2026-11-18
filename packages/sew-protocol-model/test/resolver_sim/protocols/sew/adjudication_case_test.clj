(ns resolver-sim.protocols.sew.adjudication-case-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.adjudication-case :as c]
            [resolver-sim.protocols.sew.evidence-admission :as e]))

(defn- root [x] (str "sha256:" (hc/domain-hash :evidence-record {:x x})))
(defn- evidence-set [wf round](ns resolver-sim.protocols.sew.adjudication-case-test)
  (let [r (root [wf round])
        w {:block-time 1 :dispute-levels {wf round}
           :escrow-transfers {wf {:escrow-state :disputed :from "a" :to "b" :dispute-resolver "r"}}
           :evidence-hashes {wf [r]}
           :evidence-provenance-verified {wf {r :verified}}
           :dispute-evidence-admissions {wf {round {:status :closed :resolver "r" :parent-round nil :parent-evidence-set/root nil :members [{:evidence/root r :evidence/role :claimant}]}}}}]
    (e/reconstruct-evidence-set w wf round)))

(deftest case-reconstruction-rejects-cross-component-substitution
  (let [q (c/build-dispute-question {:workflow-id 1 :round-id 0 :subject-root (root :subject)})
        rs (c/build-ruling-schema)
        p (c/build-adjudication-policy)
        d (c/build-case-definition q rs p)
        es (evidence-set 1 0)
        case (c/build-adjudication-case d es)]
    (is (c/valid-adjudication-case? case d q rs p es))
    (is (not (c/valid-adjudication-case? case d q rs p (evidence-set 2 0))))
    (is (not (c/valid-adjudication-case? case d q rs p (evidence-set 1 1))))
    (is (not (c/valid-dispute-question? (assoc q :ruling/outcome-mapping {:refuse :refund-to-sender}))))))
