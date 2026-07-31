(ns resolver-sim.deferral-test
  "Tests for the deferral.v1 first-class hashed snapshot artifact and the
   grounded-amount projection contract."
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [resolver-sim.deferral :as d]
            [resolver-sim.hash.canonical :as hash]))

(defn -main [& _]
  (run-tests 'resolver-sim.deferral-test))

(defn- sample-fields
  [& {:keys [value round status] :or {value 1000 round 1 status :active}}]
  {:deferral/id "pos-0"
   :deferral/root-obligation-root "obl-root-0"
   :deferral/source-position-root "pos-root-0"
   :deferral/amount (d/grounded-amount value :usdc :deferred "pos-root-0"
                                       :as-of-root "pos-root-0")
   :deferral/eligibility :later-liquidity
   :deferral/round round
   :deferral/original-priority 7
   :deferral/lineage-root "lineage-genesis"
   :deferral/predecessor-hash "predecessor-0"
   :deferral/status status})

(deftest deferral-artifact-schema
  (let [snap (d/deferral (sample-fields))]
    (is (= "deferral.v1" (:deferral/schema-version snap)))
    (is (= "pos-0" (:deferral/id snap)))
    (is (= :later-liquidity (:deferral/eligibility snap)))
    (is (= 7 (:deferral/original-priority snap)))
    (is (= :active (:deferral/status snap)))
    (is (= :sha256 (:deferral/hash-algorithm snap)))
    (is (= {:amount/value 1000 :amount/token :usdc :amount/basis :deferred
            :amount/source-root "pos-root-0" :amount/as-of-root "pos-root-0"}
           (:deferral/amount snap)))
    (is (some? (:deferral/hash snap)))
    (is (= 64 (count (:deferral/hash snap))))))

(deftest deferral-hash-determinism-and-sensitivity
  (let [a (d/deferral (sample-fields))
        b (d/deferral (sample-fields))]
    (is (= (:deferral/hash a) (:deferral/hash b)) "deterministic")
    (is (not= (:deferral/hash a)
              (:deferral/hash (d/deferral (sample-fields :value 2000))))
        "changes when amount changes")
    (is (not= (:deferral/hash a)
              (:deferral/hash (d/deferral (sample-fields :round 2))))
        "changes when round changes")
    (is (not= (:deferral/hash a)
              (:deferral/hash (d/deferral (sample-fields :status :closed))))
        "changes when status changes")))

(deftest deferral-close-semantics
  (let [active (d/deferral (sample-fields))
        closed (d/deferral-close active)]
    (is (= :closed (:deferral/status closed)))
    (is (= 1000 (:deferral/closed-from-amount closed)))
    (is (= 0 (get-in closed [:deferral/amount :amount/value])))
    (is (not= (:deferral/hash active) (:deferral/hash closed))
        "closing recomputes the snapshot hash")
    (is (= (d/deferral-hash (dissoc closed :deferral/hash))
           (:deferral/hash closed))
        "hash is self-consistent")))

(deftest deferral-successor-lineage
  (let [genesis (d/deferral (assoc (sample-fields)
                                   :deferral/lineage-root "lineage-genesis"
                                   :deferral/predecessor-hash nil))
        [closed next] (d/deferral-successor
                       genesis
                       {:deferral/root-obligation-root "obl-root-0"
                        :deferral/source-position-root "pos-root-0"
                        :deferral/amount (d/grounded-amount 500 :usdc :deferred "pos-root-0")
                        :deferral/eligibility :later-liquidity
                        :deferral/round 2
                        :deferral/original-priority 7})]
    (is (= :closed (:deferral/status closed)))
    (is (= "pos-0" (:deferral/successor-id closed)))
    (is (= :active (:deferral/status next)))
    (is (= "pos-0" (:deferral/id next)) "logical id persists across snapshots")
    (is (= "lineage-genesis" (:deferral/lineage-root next)) "lineage-root inherited")
    (is (= (:deferral/hash genesis) (:deferral/predecessor-hash next))
        "predecessor-hash binds the prior snapshot hash")
    (is (not= (:deferral/hash genesis) (:deferral/hash next)))))

(deftest deferral-hash-domain-separation
  (testing "DEFERRAL_V1 is distinct from adjacent hash domains"
    (let [snap (d/deferral (sample-fields))
          dh (:deferral/hash snap)
          wg-hash (hash/domain-hash "WORKFLOW_GROUP_V1"
                                    {:deferral/id "pos-0" :deferral/round 1})
          member-hash (hash/domain-hash "WORKFLOW_GROUP_MEMBER_V1"
                                        {:deferral/id "pos-0" :deferral/round 1})]
      (is (not= dh wg-hash))
      (is (not= dh member-hash)))))

(deftest deferral-rejects-unsupported-algorithm
  (testing "unsupported hash algorithm is rejected, not silently defaulted"
    (let [snap (d/deferral (sample-fields))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"unsupported hash algorithm"
                            (d/deferral-hash snap :md5)))
      (is (string? (d/deferral-hash snap :sha256))))))

(deftest grounded-amount-contract
  (testing "grounded amount is a projection, not an artifact"
    (is (= {:amount/value 100 :amount/token :usdc :amount/basis :deferred
            :amount/source-root "root"}
           (d/grounded-amount 100 :usdc :deferred "root")))
    (is (= {:amount/value 100 :amount/token :usdc :amount/basis :deferred
            :amount/source-root "root" :amount/as-of-root "state"}
           (d/grounded-amount 100 :usdc :deferred "root" :as-of-root "state")))))
