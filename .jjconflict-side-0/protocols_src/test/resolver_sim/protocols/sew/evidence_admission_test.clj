(ns resolver-sim.protocols.sew.evidence-admission-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.evidence-admission :as admission]))

(defn- root [x] (str "sha256:" (hc/domain-hash :evidence-record {:x x})))
(def evidence-a (root :a))
(def evidence-b (root :b))
(defn- world []
  {:block-time 1000
   :dispute-levels {7 0}
   :escrow-transfers {7 {:escrow-state :disputed :from "alice" :to "bob" :dispute-resolver "resolver"}}
   :evidence-hashes {7 [evidence-a evidence-b]}
   :evidence-provenance-verified {7 {evidence-a :verified evidence-b :verified}}})

(deftest admission-is-authorized-registered-and-retry-safe
  (let [bad (admission/admit-workflow-evidence! (world) {:workflow-id 7 :caller "mallory" :role :claimant :evidence-ref {:evidence/root evidence-a}})
        unknown (admission/admit-workflow-evidence! (world) {:workflow-id 7 :caller "alice" :role :claimant :evidence-ref {:evidence/root (root :unknown)}})
        admitted (admission/admit-workflow-evidence! (world) {:workflow-id 7 :caller "alice" :role :claimant :evidence-ref {:evidence/root evidence-a}})
        retry (admission/admit-workflow-evidence! (:world admitted) {:workflow-id 7 :caller "alice" :role :claimant :evidence-ref {:evidence/root evidence-a}})
        conflict (admission/admit-workflow-evidence! (:world admitted) {:workflow-id 7 :caller "bob" :role :respondent :evidence-ref {:evidence/root evidence-a}})]
    (is (= :unauthorized-evidence-admission (:error bad)))
    (is (= :unregistered-workflow-evidence (:error unknown)))
    (is (:ok admitted))
    (is (:idempotent? retry))
    (is (= :conflicting-evidence-role (:error conflict)))))

(deftest provenance-close-and-projection-are-fail-closed
  (let [unverified (assoc-in (world) [:evidence-provenance-verified 7 evidence-a] :unverifiable)
        rejected (admission/admit-workflow-evidence! unverified {:workflow-id 7 :caller "alice" :role :claimant :evidence-ref {:evidence/root evidence-a}})
        admitted (admission/admit-workflow-evidence! (world) {:workflow-id 7 :caller "alice" :role :claimant :evidence-ref {:evidence/root evidence-a}})
        closed (admission/close-evidence-admission! (:world admitted) 7 nil "resolver")
        after-close (admission/admit-workflow-evidence! (:world closed) {:workflow-id 7 :caller "alice" :role :claimant :evidence-ref {:evidence/root evidence-b}})
        retry-close (admission/close-evidence-admission! (:world closed) 7 nil "resolver")]
    (is (= :unverified-evidence-provenance (:error rejected)))
    (is (:ok closed))
    (is (:valid? (admission/validate-evidence-set (:evidence-set closed))))
    (is (= :evidence-admission-closed (:error after-close)))
    (is (:idempotent? retry-close))))

(deftest member-order-does-not-change-set-identity
  (let [base (world)
        a (admission/admit-workflow-evidence! base {:workflow-id 7 :caller "alice" :role :claimant :evidence-ref {:evidence/root evidence-a}})
        ab (admission/admit-workflow-evidence! (:world a) {:workflow-id 7 :caller "bob" :role :respondent :evidence-ref {:evidence/root evidence-b}})
        b (admission/admit-workflow-evidence! base {:workflow-id 7 :caller "bob" :role :respondent :evidence-ref {:evidence/root evidence-b}})
        ba (admission/admit-workflow-evidence! (:world b) {:workflow-id 7 :caller "alice" :role :claimant :evidence-ref {:evidence/root evidence-a}})
        close-ab (admission/close-evidence-admission! (:world ab) 7 0 "resolver")
        close-ba (admission/close-evidence-admission! (:world ba) 7 0 "resolver")]
    (is (= (:evidence-set close-ab) (:evidence-set close-ba)))))

(deftest resolver-rotation-does-not-retroactively-control-open-round
  (let [admitted (admission/admit-workflow-evidence! (world) {:workflow-id 7 :caller "alice" :role :claimant :evidence-ref {:evidence/root evidence-a}})
        rotated (assoc-in (:world admitted) [:escrow-transfers 7 :dispute-resolver] "new-resolver")
        old-close (admission/close-evidence-admission! rotated 7 0 "resolver")
        new-close (admission/close-evidence-admission! rotated 7 0 "new-resolver")]
    (is (:ok old-close))
    (is (= :unauthorized-evidence-close (:error new-close)))))

(deftest successor-lineage-is-derived-from-closed-parent
  (let [first (admission/admit-workflow-evidence! (world) {:workflow-id 7 :caller "alice" :role :claimant :evidence-ref {:evidence/root evidence-a}})
        closed (admission/close-evidence-admission! (:world first) 7 0 "resolver")
        successor-world (assoc-in (:world closed) [:dispute-levels 7] 1)
        second (admission/admit-workflow-evidence! successor-world {:workflow-id 7 :caller "bob" :role :respondent :evidence-ref {:evidence/root evidence-b}})
        closed-second (admission/close-evidence-admission! (:world second) 7 1 "resolver")]
    (is (:ok second))
    (is (= (:sew/dispute-evidence-set/root (:evidence-set closed))
           (get-in (:world second) [:dispute-evidence-admissions 7 1 :parent-evidence-set/root])))
    (is (= [evidence-a evidence-b]
           (mapv :evidence/root (:members (:evidence-set closed-second)))))))
