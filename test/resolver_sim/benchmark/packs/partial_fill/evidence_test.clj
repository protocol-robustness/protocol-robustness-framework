(ns resolver-sim.benchmark.packs.partial-fill.evidence-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.packs.partial-fill.evidence :as pfev]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.yield.modules.liquid-lending :as ll]))

(def test-mod
  (ll/make-liquid-lending-module :test-mod))

(def base-world
  {:yield/indices {:test-mod {"USDC" 1.0}}
   :yield/rates   {:test-mod {"USDC" 0.05}}
   :yield/risk    {:test-mod {"USDC" {:liquidity-mode :available
                                      :loss-mode :none}}}
   :yield/held-balances {"USDC" 1000000}
   :yield/module-status {:test-mod :active}
   :block-time 1000
   :run/id "test-run"
   :execution/id "test-execution"
   :params {:scenario-id "test-scenario"}})

(defn- real-world []
  (-> (ll/deposit base-world test-mod {:owner/id "alice" :amount 100 :token "USDC"})
      (assoc-in [:total-held :USDC] 30)
      (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(defn- two-decision-world []
  (-> (real-world)
      (assoc-in [:total-held :USDC] 30)
      (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(defn- latest-decision [world]
  (last (vals (:yield/partial-fill-decisions world))))

(def sample-final-world
  {:yield/withdrawn {:USDC {"alice" 15}}
   :yield/positions {"alice"
                     {:principal 100
                      :deferred-position
                      {:position/id "alice/deferred/2"
                       :position/current-amount 15
                       :position/status :active}
                      :cumulative-fulfilled 15}}})

(def final-world-pos-hash
  (str "sha256:" (hc/domain-hash :state-projection
                                 (get-in sample-final-world [:yield/positions "alice"]))))

(def sample-application
  {:propagation-id "prop-1"
   :participants
   [{:participant-id "alice"
     :position-before {:deferred-position
                       {:position/id "alice/deferred/1"
                        :position/current-amount 20
                        :position/status :active}}
     :position-before-hash "sha256:before"
     :position-after {:deferred-position
                      {:position/id "alice/deferred/2"
                       :position/current-amount 15
                       :position/status :active}}
     :position-after-hash final-world-pos-hash
     :withdrawn {:token :USDC :before 10 :delta 5 :after 15}
     :obligation {:before 20 :fulfilled 5 :deferred 15 :after 15}
     :cumulative-fulfilled {:before 10 :delta 5 :after 15}}]})

(deftest derive-state-write-back-verified
  (let [wb (pfev/derive-state-write-back sample-application sample-final-world)]
    (is (seq wb))
    (let [alice (first wb)]
      (is (= "alice" (:participant/id alice)))
      (is (true? (get-in alice [:withdrawn :verified?])))
      (is (true? (get-in alice [:position :verified?])))
      (is (true? (get-in alice [:deferred-position :verified?]))))))

(deftest derive-state-write-back-values
  (let [wb (pfev/derive-state-write-back sample-application sample-final-world)
        alice (first wb)]
    (is (= 10 (get-in alice [:withdrawn :before])))
    (is (= 5 (get-in alice [:withdrawn :delta])))
    (is (= 15 (get-in alice [:withdrawn :after])))
    (is (= 15 (get-in alice [:withdrawn :final-world-value])))
    (is (= 15 (get-in alice [:deferred-position :successor-current-amount])))
    (is (= 15 (get-in alice [:deferred-position :final-world-current-amount])))))

(deftest derive-state-write-back-mismatch-detected
  (let [bad-world (assoc-in sample-final-world
                            [:yield/withdrawn :USDC "alice"] 99)
        wb (pfev/derive-state-write-back sample-application bad-world)
        alice (first wb)]
    (is (false? (get-in alice [:withdrawn :verified?])))))

(deftest derive-state-write-back-nil-for-no-participants
  (let [app {:participants []}
        wb (pfev/derive-state-write-back app {})]
    (is (nil? wb))))

(deftest collect-application-refs
  (let [world {:yield/applied-pro-rata-propagations
               {"prop-1" {:propagation-id "prop-1"
                          :application/hash "sha256:app-hash"
                          :calculation-id "calc-1"
                          :outcome-hash "sha256:outcome"
                          :application-order {:step 1 :event-id 0}}}}]
    (is (= 1 (count (pfev/collect-application-refs world))))
    (is (= "prop-1" (:propagation/id (first (pfev/collect-application-refs world)))))))

(deftest semantic-commitments-empty
  (is (nil? (pfev/semantic-commitments {}))))

(deftest semantic-commitments-with-decisions
  (let [world {:yield/partial-fill-decisions
               {"d1" {:decision/id "d1" :decision/hash "sha256:d1"}}}]
    (let [commitments (pfev/semantic-commitments world)]
      (is (some? commitments))
      (is (map? (:semantic/economic-application commitments)))
      (is (some? (get-in commitments [:semantic/economic-application
                                      :partial-fill-decisions-root]))))))

(deftest application-evidence-ladder-basic
  (let [world {:yield/pro-rata-propagations
               {"prop-1" {:schema-version "pro-rata-propagation.v2"
                          :propagation/id "prop-1"
                          :calculation-ref "calc-1"
                          :accounting-entry-set-hash "sha256:entries"
                          :accounting-entries [{:delta 5}]
                          :applications [{:participant-id "alice"}]}}
               :yield/applied-pro-rata-propagations
               {"prop-1" sample-application}
               :yield/partial-fill-decisions
               {"calc-1" {:decision/id "calc-1" :decision/hash "sha256:d1"}}}
        ladder (pfev/application-evidence-ladder world)]
    (is (= 1 (count ladder)))
    (let [entry (first ladder)]
      (is (= "prop-1" (:propagation/id entry)))
      (is (= 6 (count (:levels entry)))))))

(deftest application-ladder-next-precondition-not-observed
  (let [world {:yield/pro-rata-propagations
               {"prop-1" {:schema-version "pro-rata-propagation.v2"
                          :propagation/id "prop-1"
                          :calculation-ref "calc-1"
                          :accounting-entry-set-hash "sha256:entries"
                          :accounting-entries [{:delta 5}]
                          :applications [{:participant-id "alice"}]}}
               :yield/applied-pro-rata-propagations
               {"prop-1" sample-application}
               :yield/partial-fill-decisions
               {"calc-1" {:decision/id "calc-1" :decision/hash "sha256:d1"}}}
        ladder (pfev/application-evidence-ladder world)
        entry (first ladder)
        continuity (nth (:levels entry) 4)]
    (is (= :continuity-consumed (:level continuity)))
    (is (= "not-observed" (:status continuity)))))

(deftest application-evidence-ladder-levels-verified
  (let [world {:yield/pro-rata-propagations
               {"prop-1" {:schema-version "pro-rata-propagation.v2"
                          :propagation/id "prop-1"
                          :calculation-ref "calc-1"
                          :accounting-entry-set-hash "sha256:entries"
                          :accounting-entries [{:delta 5}]
                          :applications [{:participant-id "alice"}]}}
               :yield/applied-pro-rata-propagations
               {"prop-1" sample-application}
               :yield/partial-fill-decisions
               {"calc-1" {:decision/id "calc-1" :decision/hash "sha256:d1"}}}
        ladder (pfev/application-evidence-ladder world)
        entry (first ladder)
        levels (:levels entry)]
    (is (= "verified" (:status (nth levels 0))))
    (is (= "verified" (:status (nth levels 1))))
    (is (= "verified" (:status (nth levels 2))))
    (is (= "not-observed" (:status (nth levels 3))))
    (is (= "not-observed" (:status (nth levels 4))))
    (is (= "not-observed" (:status (nth levels 5))))))

;; ── partial-fill-decisions-root verification ─────────────────────────────

(defn- claim-key [d]
  (first (keys (:requested d))))

(defn- full-fill-world []
  (-> (ll/deposit base-world test-mod {:owner/id "alice" :amount 100 :token "USDC"})
      (assoc-in [:total-held :USDC] 100)
      (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(defn- three-claim-world []
  (-> (reduce (fn [w o]
                (ll/deposit w test-mod {:owner/id o :amount 100 :token "USDC"}))
              base-world
              ["a1" "a2" "a3"])
      (assoc-in [:total-held :USDC] 30)
      (ll/withdraw-shared test-mod {:owner-ids ["a1" "a2" "a3"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(defn- tamper-decision
  "Set a field (at path) inside a decision of a world."
  [w d path value]
  (assoc-in w (into [:yield/partial-fill-decisions (:decision/id d)] path) value))

;; -- baseline / integrity ------------------------------------------------

(deftest verify-partial-fill-decisions-passes-on-real-world
  (let [result (pfev/verify-partial-fill-decisions (real-world))]
    (is (= :evaluated-pass (:classification result)))
    (is (= 1 (:decision-count result)))
    (is (some? (:root-recomputed result)))
    (is (true? (:decision-integrity? result)))
    (is (= :isolated-exact (:expected-fill-mode result)))
    (is (= 0 (:aggregate-drift result)))
    (is (empty? (:violations result)))))

(deftest verify-partial-fill-decisions-not-evaluated-when-none
  (let [result (pfev/verify-partial-fill-decisions {})]
    (is (= :not-evaluated (:classification result)))
    (is (false? (:decision-integrity? result)))))

(deftest verify-two-round-lineage-is-a-valid-bundle
  (testing "multi-round repetition of a claim is legitimate by default"
    (let [result (pfev/verify-partial-fill-decisions (two-decision-world))]
      (is (= :evaluated-pass (:classification result)))
      (is (= 0 (:aggregate-drift result))))))

;; -- decision integrity (renamed from decidability) ----------------------

(deftest verify-tampered-decision-hash-fails-integrity
  (let [w (real-world)
        d (latest-decision w)
        tampered (tamper-decision w d [:decision/hash] "sha256:forged")
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (false? (:decision-integrity? result)))
    (is (some #(= :invalid-decision-hash (:code %)) (:violations result)))))

(deftest verify-root-mismatch-fails
  (let [w (real-world)
        result (pfev/verify-partial-fill-decisions w
                                                   :committed-root "sha256:wrong")]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :root-mismatch (:code %)) (:violations result)))))

(deftest verify-scope-bound-root-passes
  (let [w (real-world)
        scope-root (pfev/partial-fill-decisions-root w {:scope {:case "C1" :run "r"}})
        result (pfev/verify-partial-fill-decisions w
                                                   :case-scope {:case "C1" :run "r"}
                                                   :committed-root scope-root)]
    (is (= :evaluated-pass (:classification result)))
    (is (true? (:decision-integrity? result)))))

(deftest verify-replayed-under-other-scope-fails
  (testing "a decision bundle committed under one case/run scope cannot be
            replayed under another"
    (let [w (real-world)
          scope-a (pfev/partial-fill-decisions-root w {:scope {:case "C1" :run "r"}})
          result (pfev/verify-partial-fill-decisions w
                                                     :case-scope {:case "C2" :run "r2"}
                                                     :committed-root scope-a)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :scope-root-mismatch (:code %)) (:violations result))))))

;; -- membership binding ---------------------------------------------------

(deftest verify-missing-claim-fails
  (let [result (pfev/verify-partial-fill-decisions (real-world)
                                                   :expected-claims #{"alice" "bob"})]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(and (= :missing-claim (:code %))
                    (= ["bob"] (:claims %)))
              (:violations result)))))

(deftest verify-unexpected-claim-fails
  (let [result (pfev/verify-partial-fill-decisions (real-world)
                                                   :expected-claims #{})]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :unexpected-claim (:code %)) (:violations result)))))

(deftest verify-decision-count-mismatch-fails
  (let [result (pfev/verify-partial-fill-decisions (real-world)
                                                   :expected-count 2)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :decision-count-mismatch (:code %)) (:violations result)))))

(deftest verify-duplicate-claim-fails-under-unique-contract
  (testing "a single-settlement membership contract rejects a repeated claim"
    (let [result (pfev/verify-partial-fill-decisions (two-decision-world)
                                                     :unique-claims? true)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :duplicate-claim (:code %)) (:violations result))))))

;; -- derived amounts, not trusted redundant fields -----------------------

(deftest verify-negative-amount-fails
  (let [w (real-world)
        d (latest-decision w)
        tampered (tamper-decision w d [:filled (claim-key d)] -1)
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :negative-amount (:code %)) (:violations result)))))

(deftest verify-non-integral-amount-fails
  (let [w (real-world)
        d (latest-decision w)
        tampered (tamper-decision w d [:requested (claim-key d)] 100.5)
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :non-integral-amount (:code %)) (:violations result)))))

(deftest verify-bounds-violation-fails
  (let [w (real-world)
        d (latest-decision w)
        tampered (tamper-decision w d [:deferred (claim-key d)] 200)
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :bounds-violation (:code %)) (:violations result)))))

(deftest verify-inconsistent-shortage-fails
  (testing "a supplied positive shortage is not authoritative"
    (let [w (real-world)
          d (latest-decision w)
          tampered (tamper-decision w d [:evidence :shortage] 999)
          result (pfev/verify-partial-fill-decisions tampered)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :shortage-inconsistent (:code %)) (:violations result))))))

(deftest verify-over-capacity-fill-fails
  (let [w (real-world)
        d (latest-decision w)
        tampered (tamper-decision w d [:evidence :available-liquidity] 10)
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :over-capacity-fill (:code %)) (:violations result)))))

;; -- reconciliation and expected-fill -------------------------------------

(deftest verify-per-claim-inexact-fails
  (let [w (real-world)
        d (latest-decision w)
        tampered (assoc-in w
                           [:yield/partial-fill-decisions (:decision/id d)
                            :evidence :allocation-rows 0 :filled] 28)
        result (pfev/verify-partial-fill-decisions tampered)]
    (is (= :evaluated-fail (:classification result)))
    (is (some #(= :per-claim-inexact (:code %)) (:violations result)))))

(deftest verify-aggregate-drift-fails
  (testing "one-unit errors on many claims cannot accumulate undetected"
    (let [w (three-claim-world)
          d (latest-decision w)
          tampered (reduce (fn [w k]
                             (update-in w
                                        [:yield/partial-fill-decisions
                                         (:decision/id d) :filled k]
                                        inc))
                           w
                           (keys (:filled d)))
          result (pfev/verify-partial-fill-decisions tampered)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :aggregate-drift (:code %)) (:violations result))))))

(deftest verify-underfill-despite-capacity-fails
  (testing "a reconciled-but-deliberately-underfilled decision despite capacity"
    (let [w (full-fill-world)
          d (latest-decision w)
          k (claim-key d)
          tampered (-> w
                       (assoc-in [:yield/partial-fill-decisions (:decision/id d)
                                  :filled k] 30)
                       (assoc-in [:yield/partial-fill-decisions (:decision/id d)
                                  :deferred k] 70)
                       (assoc-in [:yield/partial-fill-decisions (:decision/id d)
                                  :evidence :shortage] 70))
          result (pfev/verify-partial-fill-decisions tampered)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :expected-fill-mismatch (:code %)) (:violations result))))))

(deftest verify-classification-mismatch-fails
  (testing "derived classification must agree with settlement-mode"
    (let [w (real-world)
          d (latest-decision w)
          tampered (tamper-decision w d [:settlement-mode] :full-fill)
          result (pfev/verify-partial-fill-decisions tampered)]
      (is (= :evaluated-fail (:classification result)))
      (is (some #(= :classification-mismatch (:code %)) (:violations result))))))

;; ── partial-fill verification report file-artifact ───────────────────────

(deftest partial-fill-verification-report-roundtrip
  (let [r (pfev/build-partial-fill-verification-report (real-world))]
    (is (= "partial-fill-decisions-verification.v1" (:schema-version r)))
    (is (= :partial-fill-decisions-verification (:artifact/kind r)))
    (is (= :evaluated-pass (:classification r)))
    (is (string? (:report/hash r)))
    (is (true? (pfev/valid-partial-fill-verification-report? r)))))

(deftest partial-fill-verification-report-tamper-classification
  (let [r (pfev/build-partial-fill-verification-report (real-world))]
    (is (false? (pfev/valid-partial-fill-verification-report?
                 (assoc r :classification :evaluated-fail))))))

(deftest partial-fill-verification-report-tamper-hash
  (let [r (pfev/build-partial-fill-verification-report (real-world))]
    (is (false? (pfev/valid-partial-fill-verification-report?
                 (assoc r :report/hash "sha256:forged"))))))

(deftest partial-fill-verification-report-wrong-schema
  (let [r (pfev/build-partial-fill-verification-report (real-world))]
    (is (false? (pfev/valid-partial-fill-verification-report?
                 (assoc r :schema-version "wrong.v9"))))))

(deftest partial-fill-verification-report-missing-preimage
  (let [r (pfev/build-partial-fill-verification-report (real-world))]
    (is (false? (pfev/valid-partial-fill-verification-report?
                 (dissoc r :report/preimage))))))
