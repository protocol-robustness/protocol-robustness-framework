(ns resolver-sim.assurance.force-authorisation-portability-test
  "Benchmark portability assertion: force-authorisation claim evaluation
   works on canonical evidence records without running the Sew state machine."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.evidence.force-authorisation :as fa-ev]
            [resolver-sim.hash.canonical :as hash]))

(def valid-scope
  {:authorization/id "fa-0"
   :authorization/type :force-authorisation
   :held/direction :out
   :token "USDC"
   :amount 5000
   :held/account :escrow-principal
   :owner/address "0xrecipient"
   :held/reason :force-authorised-release
   :held/workflow-id 0})

(deftest force-authorisation-evidence-evaluates-without-sew
  (testing "Valid scope map passes schema validation"
    (is (fa-ev/valid-scope? valid-scope))
    (is (empty? (remove #(contains? valid-scope %) fa-ev/scope-schema))))

  (testing "Scope map missing keys fails schema validation"
    (is (not (fa-ev/valid-scope? (dissoc valid-scope :token))))
    (is (not (fa-ev/valid-scope? (dissoc valid-scope :amount)))))

  (testing "Authorisation usable check works on data maps (not Sew world)"
    (let [record {:authorization/status :active
                  :authorization/id "fa-0"
                  :authorization/scope-hash (fa/force-authorisation-scope-hash valid-scope)
                  :authorization/scope valid-scope
                  :starts-at 0
                  :expires-at 1000}]
      ;; Before expiry: valid
      (let [result (fa/verify-authorisation-usable record {} valid-scope 500)]
        (is (:valid? result) "active grant within window should be usable"))
      ;; After expiry: invalid
      (let [result (fa/verify-authorisation-usable record {} valid-scope 1500)]
        (is (not (:valid? result)) "expired grant should not be usable")
        (is (some #(= :authorisation-expired (:code %)) (:errors result))
            "error code should be :authorisation-expired"))
      ;; Already consumed: invalid
      (let [consumed {"fa-0" {:consumed-at 500}}
            result (fa/verify-authorisation-usable record consumed valid-scope 600)]
        (is (not (:valid? result)) "consumed grant should not be usable")
        (is (some #(= :authorisation-already-consumed (:code %)) (:errors result))
            "error code should be :authorisation-already-consumed"))))

  (testing "Authorisation lifecycle consistency check works on data maps"
    ;; Consistent: grant and consumption match
    (let [scope valid-scope
          result (fa/verify-authorisation-lifecycle-consistency
                  {"fa-0" {:authorization/status :consumed
                           :authorization/scope-hash (fa/force-authorisation-scope-hash scope)
                           :authorization/scope scope}}
                  {"fa-0" {:consumed-at 500}})]
      (is (:holds? result)))

    ;; Inconsistent: consumption without grant
    (let [result (fa/verify-authorisation-lifecycle-consistency
                  {}
                  {"fa-0" {:consumed-at 500}})]
      (is (not (:holds? result)))
      (is (some #(= :orphan-consumption (:error %)) (:violations result)))))

  (testing "Held custody closed-form checks work on synthetic artifacts"
    (let [adj {:held-adjustment/id "adj-0"
               :held/direction :in
               :token :USDC
               :amount 100
               :held/before 0
               :held/after 100
               :held/reason :force-authorised-release
               :held/action "create-escrow"}
          artifact (custody/build-held-custody-artifact adj)
          checks (custody/held-custody-closed-form-checks [artifact])]
      (is (= 7 (count checks)))
      (is (every? #(= :pass (:status %)) checks)
          "all closed-form checks pass on a valid synthetic artifact")))

  (testing "Authorisation grant-execution ordering verified from evidence envelope"
    (let [envelope {:evidence/kind :force-authorisation
                    :evidence/auth-id "fa-0"
                    :evidence/grant-time 100
                    :evidence/scope-hash "0xhash"
                    :evidence/execution-time 500
                    :evidence/consumption-time 500
                    :evidence/held-adjustment-id "adj-0"}]
      (is (fa-ev/valid-envelope? envelope))
      (is (fa-ev/grant-before-execution? envelope))
      (is (fa-ev/execution-before-consumption? envelope))
      ;; Violation: execution before grant
      (is (not (fa-ev/grant-before-execution?
                (assoc envelope :evidence/grant-time 600 :evidence/execution-time 500))))
      ;; Missing fields: invalid envelope
      (is (not (fa-ev/valid-envelope? (dissoc envelope :evidence/auth-id))))))

  (testing "Scope hash mismatch detection without Sew state"
    (let [hash (fa/force-authorisation-scope-hash valid-scope)]
      (is (string? hash))
      (is (not (fa/scope-hash-mismatch?
                {:authorization/scope-hash hash} valid-scope)))
      (is (fa/scope-hash-mismatch?
           {:authorization/scope-hash "0xdifferent"} valid-scope)))))

;; ── Normalisation tests ────────────────────────────────────────────────────

(deftest normalize-force-authorisation-scope-valid
  (testing "Normalize a keyword-keyed scope map"
    (let [result (fa/normalize-force-authorisation-scope valid-scope)]
      (is (map? result))
      (is (= "fa-0" (:authorization/id result)))
      (is (= :force-authorisation (:authorization/type result)))
      (is (= :out (:held/direction result)))
      (is (= :USDC (:token result)))
      (is (= 5000 (:amount result)))
      (is (= :escrow-principal (:held/account result)))
      (is (= "0xrecipient" (:owner/address result)))
      (is (= :force-authorised-release (:held/reason result)))
      (is (= 0 (:held/workflow-id result))))))

(deftest normalize-force-authorisation-scope-from-json
  (testing "Normalize a scope map with string keys (simulating JSON deserialization)"
    (let [json-input {"authorization/id" "fa-1"
                      "authorization/type" "force-authorisation"
                      "held/direction" "out"
                      "token" "DAI"
                      "amount" "3000"
                      "held/account" "escrow-principal"
                      "owner/address" "0xbob"
                      "held/reason" "force-authorised-release"
                      "held/workflow-id" "2"}
          result (fa/normalize-force-authorisation-scope json-input)]
      (is (map? result))
      (is (= "fa-1" (:authorization/id result)))
      (is (= :force-authorisation (:authorization/type result)))
      (is (= :out (:held/direction result)))
      (is (= :DAI (:token result)))
      (is (= 3000 (:amount result)))
      (is (= "0xbob" (:owner/address result)))
      (is (= 2 (:held/workflow-id result))))))

(deftest normalize-force-authorisation-scope-strips-unknown-keys
  (testing "Normalize strips unknown keys from scope map"
    (let [input (assoc valid-scope :unknown-key "should-be-removed" :extra 42)
          result (fa/normalize-force-authorisation-scope input)]
      (is (not (contains? result :unknown-key)))
      (is (not (contains? result :extra)))
      (is (= 9 (count result)) "only the 9 scope keys should remain"))))

(deftest normalize-force-authorisation-scope-nil
  (testing "nil scope input returns nil"
    (is (nil? (fa/normalize-force-authorisation-scope nil)))))

(deftest normalize-force-authorisation-record-minimal
  (testing "Normalize a minimal record fills defaults"
    (let [result (fa/normalize-force-authorisation-record {:authorization/id "fa-0"})]
      (is (false? (:consumed? result)))
      (is (= :active (:authorization/status result)))
      (is (= :force-authorisation (:authorization/type result)))
      (is (= "fa-0" (:authorization/id result))))))

(deftest normalize-force-authorisation-record-from-json
  (testing "Normalize a record from JSON-style string keys"
    (let [json-input {"authorization/id" "fa-2"
                      "authorization/status" "active"
                      "authorization/type" "force-authorisation"
                      "workflow-id" "5"
                      "starts-at" "1000"
                      "expires-at" "2000"
                      "consumed?" false
                      "authorization/scope" {"authorization/id" "fa-2"
                                             "authorization/type" "force-authorisation"
                                             "held/direction" "out"
                                             "token" "USDC"
                                             "amount" "500"
                                             "held/account" "escrow-principal"
                                             "owner/address" "0xalice"
                                             "held/reason" "force-authorised-release"
                                             "held/workflow-id" "5"}}
          result (fa/normalize-force-authorisation-record json-input)]
      (is (false? (:consumed? result)))
      (is (= :active (:authorization/status result)))
      (is (= :force-authorisation (:authorization/type result)))
      (is (= 5 (:workflow-id result)))
      (is (= 1000 (:starts-at result)))
      (is (= 2000 (:expires-at result)))
      (is (= :USDC (get-in result [:authorization/scope :token]))))))

(deftest normalize-force-authorisation-record-with-execution
  (testing "Normalize a record with execution fields"
    (let [input {:authorization/id "fa-3"
                 :authorization/status :active
                 :consumed? true
                 :workflow-id 1
                 :executed-by "0xexec"
                 :executed-at 1500
                 :nonce "fa-3"}
          result (fa/normalize-force-authorisation-record input)]
      (is (true? (:consumed? result)))
      (is (= 1 (:workflow-id result)))
      (is (= "0xexec" (:executed-by result)))
      (is (= 1500 (:executed-at result)))
      (is (= "fa-3" (:nonce result))))))

(deftest normalize-force-authorisation-records-multiple
  (testing "Normalize a map of multiple records"
    (let [input {"fa-1" {:authorization/id "fa-1"}
                 "fa-2" {:authorization/id "fa-2" :consumed? true}}
          result (fa/normalize-force-authorisation-records input)]
      (is (= 2 (count result)))
      (is (false? (get-in result ["fa-1" :consumed?])))
      (is (true? (get-in result ["fa-2" :consumed?]))))))

(deftest normalize-force-authorisation-consumption-registry-valid
  (testing "Normalize a consumption registry from keyword keys"
    (let [input {"fa-0" {:consumed-at 500
                         :consumed-by "0xgov"
                         :consumed-amount 1000
                         :consumed-token :USDC
                         :held-adjustment-id "ha-1"}}
          result (fa/normalize-force-authorisation-consumption-registry input)]
      (is (= 1 (count result)))
      (is (= 500 (get-in result ["fa-0" :consumed-at])))
      (is (= "0xgov" (get-in result ["fa-0" :consumed-by])))
      (is (= 1000 (get-in result ["fa-0" :consumed-amount])))
      (is (= :USDC (get-in result ["fa-0" :consumed-token]))))))

(deftest normalize-force-authorisation-consumption-registry-from-json
  (testing "Normalize a consumption registry from JSON-style string keys"
    (let [input {"fa-0" {"consumed-at" "500"
                         "consumed-by" "0xgov"
                         "consumed-amount" "1000"
                         "consumed-token" "USDC"
                         "held-adjustment-id" "ha-1"}}
          result (fa/normalize-force-authorisation-consumption-registry input)]
      (is (= 500 (get-in result ["fa-0" :consumed-at])))
      (is (= "0xgov" (get-in result ["fa-0" :consumed-by])))
      (is (= 1000 (get-in result ["fa-0" :consumed-amount])))
      (is (= :USDC (get-in result ["fa-0" :consumed-token])))
      (is (= "ha-1" (get-in result ["fa-0" :held-adjustment-id]))))))

;; ── Versioned force-authorisation evidence file-artifacts ─────────────────

(defn- hash-bound-auth
  "An authorization record whose scope-hash authenticates valid-scope, using the
   canonical normalized scope so it agrees with the evidence builders."
  [id status]
  (let [scope (fa/normalize-force-authorisation-scope valid-scope)]
    {:authorization/id id
     :authorization/status status
     :authorization/type :force-authorisation
     :authorization/scope-hash (fa/force-authorisation-scope-hash scope)
     :authorization/scope scope
     :starts-at 0
     :expires-at 1000}))

(deftest force-auth-add-held-evidence-roundtrip
  (testing "a force-authorised add-held with a matching scope-hash verifies"
    (let [r (fa-ev/build-force-auth-add-held
             {:authorization (hash-bound-auth "fa-0" :active)
              :scope-map valid-scope
              :adjustment {:held-adjustment/id "adj-1"}
              :consumed-at 500
              :consumed-by "0xgov"})]
      (is (= "force-auth-add-held.v1" (:schema-version r)))
      (is (= :force-auth-add-held (:artifact/kind r)))
      (is (true? (:authorization/scope-verifies? r))
          "scope-hash recomputes from the committed scope")
      (is (= "adj-1" (:held/adjustment-id r)))
      (is (= 500 (:held/consumed-at r)))
      (is (true? (fa-ev/valid-force-auth-add-held? r))))))

(deftest force-auth-add-held-evidence-tamper-detected
  (let [r (fa-ev/build-force-auth-add-held
           {:authorization (hash-bound-auth "fa-0" :active)
            :scope-map valid-scope
            :adjustment {:held-adjustment/id "adj-1"}})]
    (is (false? (fa-ev/valid-force-auth-add-held?
                 (assoc r :held/amount 9999))))
    (is (false? (fa-ev/valid-force-auth-add-held?
                 (assoc r :artifact/hash "sha256:forged"))))
    (is (false? (fa-ev/valid-force-auth-add-held?
                 (assoc r :schema-version "wrong.v9"))))
    (is (false? (fa-ev/valid-force-auth-add-held?
                 (dissoc r :artifact/preimage))))))

(deftest force-auth-add-held-evidence-scope-mismatch-is-non-passing
  (testing "a recorded scope-hash that does not recompute is flagged"
    (let [wrong-auth (assoc (hash-bound-auth "fa-0" :active)
                            :authorization/scope-hash "0xdifferent")
          r (fa-ev/build-force-auth-add-held
             {:authorization wrong-auth
              :scope-map valid-scope
              :adjustment {:held-adjustment/id "adj-1"}})]
      (is (false? (:authorization/scope-verifies? r))))))

(deftest force-auth-lifecycle-evidence-roundtrip
  (testing "a consistent lifecycle with an active, usable grant"
    (let [r (fa-ev/build-force-auth-lifecycle
             {:authorisations {"fa-0" (hash-bound-auth "fa-0" :active)}
              :consumption-registry {}})]
      (is (= :force-auth-lifecycle (:artifact/kind r)))
      (is (true? (:lifecycle-consistent? r)))
      (is (true? (get-in r [:authorisation-usable "fa-0"]))
          "an active, un-consumed grant within its window is usable")
      (is (string? (:authorisations-root r)))
      (is (true? (fa-ev/valid-force-auth-lifecycle? r))))))

(deftest force-auth-lifecycle-evidence-orphan-consumption-detected
  (let [r (fa-ev/build-force-auth-lifecycle
           {:authorisations {}
            :consumption-registry {"fa-0" {:consumed-at 500}}})]
    (is (false? (:lifecycle-consistent? r)))
    (is (some #(= :orphan-consumption (:error %))
              (:lifecycle-violations r)))
    (is (true? (fa-ev/valid-force-auth-lifecycle? r))
        "an inconsistent lifecycle still produces a valid artifact")))

(deftest force-auth-lifecycle-evidence-tamper-detected
  (let [r (fa-ev/build-force-auth-lifecycle
           {:authorisations {"fa-0" (hash-bound-auth "fa-0" :consumed)}
            :consumption-registry {"fa-0" {:consumed-at 500}}})]
    (is (false? (fa-ev/valid-force-auth-lifecycle?
                 (assoc r :lifecycle-consistent? false))))
    (is (false? (fa-ev/valid-force-auth-lifecycle?
                 (assoc r :artifact/hash "sha256:forged"))))))

(deftest force-auth-lifecycle-summary-evidence-roundtrip
  (let [r (fa-ev/build-force-auth-lifecycle-summary
           {:authorisations {"fa-0" (hash-bound-auth "fa-0" :consumed)
                             "fa-1" (hash-bound-auth "fa-1" :active)}
            :consumption-registry {"fa-0" {:consumed-at 500}}})]
    (is (= :force-auth-lifecycle-summary (:artifact/kind r)))
    (is (= 2 (:total r)))
    (is (= 1 (:consumed r)))
    (is (= 1 (:created r)))
    (is (= 0 (:orphan-consumptions r)))
    (is (true? (:lifecycle-consistent? r)))
    (is (= "force-auth-lifecycle-summary.v2" (:schema-version r)))
    (is (= {:consumed 1 :active 1} (:counts-by-status r)))
    (is (true? (fa-ev/valid-force-auth-lifecycle-summary? r)))))

(deftest force-auth-lifecycle-summary-evidence-detects-orphans
  (let [r (fa-ev/build-force-auth-lifecycle-summary
           {:authorisations {}
            :consumption-registry {"fa-9" {:consumed-at 500}}})]
    (is (= 1 (:orphan-consumptions r)))
    (is (false? (:lifecycle-consistent? r)))))

(deftest force-auth-lifecycle-summary-evidence-tamper-detected
  (let [r (fa-ev/build-force-auth-lifecycle-summary
           {:authorisations {"fa-0" (hash-bound-auth "fa-0" :consumed)}
            :consumption-registry {"fa-0" {:consumed-at 500}}})]
    (is (false? (fa-ev/valid-force-auth-lifecycle-summary?
                 (assoc r :total 99))))
    (is (false? (fa-ev/valid-force-auth-lifecycle-summary?
                 (assoc r :artifact/hash "sha256:forged"))))))

(deftest force-auth-lifecycle-summary-v2-dimensions
  (testing "lifecycle v2 surfaces outstanding usable, assurance/governance counts, and time range"
    (let [active (assoc (hash-bound-auth "fa-0" :active) :created-at 100)
          consumed (assoc (hash-bound-auth "fa-1" :consumed) :created-at 200 :executed-by "0xgov")
          r (fa-ev/build-force-auth-lifecycle-summary
             {:authorisations {"fa-0" active "fa-1" consumed}
              :consumption-registry {"fa-1" {:consumed-at 500 :consumed-by "0xrelayer"}}
              :now 50
              :assurance {"fa-0" :address-bound "fa-1" :role-declared}
              :governance-mode {"fa-0" :restricted "fa-1" :legacy}
              :creator-provenance {"fa-0" :direct-session}})]
      (is (= 1 (:outstanding-usable r)))
      (is (= 1 (:consumption-count r)))
      (is (= 1 (:conflicting-consumers r)))
      (is (= {:address-bound 1 :role-declared 1} (:assurance-counts r)))
      (is (= {:restricted 1 :legacy 1} (:governance-mode-counts r)))
      (is (= {:direct-session 1} (:creator-provenance-counts r)))
      (is (= 100 (get-in r [:time-range :created-at-earliest])))
      (is (= 200 (get-in r [:time-range :created-at-latest])))
      (is (= 500 (get-in r [:time-range :consumed-at-earliest])))
      (is (true? (fa-ev/valid-force-auth-lifecycle-summary? r))))))

(deftest force-auth-lifecycle-summary-v2-expiry-triage
  (let [expired (assoc (hash-bound-auth "fa-0" :active) :expires-at 100)
        r (fa-ev/build-force-auth-lifecycle-summary
           {:authorisations {"fa-0" expired}
            :consumption-registry {}
            :now 500})]
    (is (= 1 (:expired r)))
    (is (= ["fa-0"] (:expired-after-window (:triage r))))
    (is (true? (fa-ev/valid-force-auth-lifecycle-summary? r)))))

(deftest force-auth-lifecycle-summary-v1-migration-reader
  (testing "a v1-shaped lifecycle summary validates under the v1 reader, v2 does not"
    (let [v1 (fa-ev/build-force-auth-lifecycle-summary-v1
              {:authorisations {"fa-0" (hash-bound-auth "fa-0" :consumed)}
               :consumption-registry {"fa-0" {:consumed-at 500}}})]
      (is (= "force-auth-lifecycle-summary.v1" (:schema-version v1)))
      (is (true? (fa-ev/valid-force-auth-lifecycle-summary-v1? v1)))
      (is (false? (fa-ev/valid-force-auth-lifecycle-summary? v1))
          "a v1 artifact is not a valid v2 artifact"))))

;; ── force-auth-add-held-summary ────────────────────────────────────────────

(defn- sample-add-held
  ([token amount direction]
   (sample-add-held token amount direction "adj-1"))
  ([token amount direction adjustment-id]
   (fa-ev/build-force-auth-add-held
    {:authorization (hash-bound-auth "fa-0" :active)
     :scope-map valid-scope
     :adjustment {:held-adjustment/id adjustment-id
                  :token token
                  :amount amount
                  :held/direction direction}})))

(deftest force-auth-add-held-summary-evidence-roundtrip
  (let [r (fa-ev/build-force-auth-add-held-summary
           {:artifacts [(sample-add-held :USDC 100 :in)
                        (sample-add-held :USDC 200 :in)
                        (sample-add-held :ETH 50 :in)]})]
    (is (= :force-auth-add-held-summary (:artifact/kind r)))
    (is (= 3 (:total r)))
    (is (= 3 (:valid-count r)))
    (is (= 3 (:scope-verified-count r)))
    (is (= 350 (:total-amount r)))
    (is (= 1 (:distinct-adjustment-ids r)))
    (is (= 3 (get-in r [:by-direction :in])))
    (is (= 2 (get-in r [:by-token :USDC])))
    (is (= 1 (get-in r [:by-token :ETH])))
    (is (true? (fa-ev/valid-force-auth-add-held-summary? r)))))

(deftest force-auth-add-held-summary-categories-catalogue
  (testing "sub-category summaries catalogued per account, reason, authorization, consumer, and token×direction"
    (let [a1 (fa-ev/build-force-auth-add-held
              {:authorization (hash-bound-auth "fa-0" :active)
               :scope-map valid-scope
               :adjustment {:held-adjustment/id "adj-1" :token :USDC :amount 100 :held/direction :in}
               :consumed-by "0xgov"})
          a2 (fa-ev/build-force-auth-add-held
              {:authorization (hash-bound-auth "fa-1" :active)
               :scope-map valid-scope
               :adjustment {:held-adjustment/id "adj-2" :token :USDC :amount 50 :held/direction :in}
               :consumed-by "0xrelayer"})
          r (fa-ev/build-force-auth-add-held-summary {:artifacts [a1 a2]})
          cat (:categories r)]
      (is (= {:escrow-principal 2} (:by-account cat)))
      (is (= {:force-authorised-release 2} (:by-reason cat)))
      (is (= {"fa-0" 1 "fa-1" 1} (:by-authorization cat)))
      (is (= {"0xgov" 1 "0xrelayer" 1} (:by-consumed-by cat)))
      (is (= {[:USDC :in] 2} (:by-token-direction cat)))
      (is (true? (fa-ev/valid-force-auth-add-held-summary? r))))))

(deftest force-auth-add-held-summary-categories-token-direction
  (testing "token × direction breakdown distinguishes same-token directions"
    (let [r (fa-ev/build-force-auth-add-held-summary
             {:artifacts [(sample-add-held :USDC 100 :in)
                          (sample-add-held :USDC 50 :out)]})]
      (is (= {[:USDC :in] 1 [:USDC :out] 1}
             (:by-token-direction (:categories r)))))))

(deftest force-auth-add-held-summary-evidence-detects-invalid
  (let [good (sample-add-held :USDC 100 :in)
        bad (assoc (sample-add-held :USDC 100 :in "adj-2") :held/amount 999)
        r (fa-ev/build-force-auth-add-held-summary {:artifacts [good bad]})]
    (is (= 2 (:total r)))
    (is (= 1 (:valid-count r)))
    (is (= 1 (:invalid-count r)))
    (is (= [1] (mapv :index (:invalid-artifacts r))))
    (is (= ["adj-2"] (mapv :adjustment-id (:invalid-artifacts r))))
    (is (true? (fa-ev/valid-force-auth-add-held-summary? r)))))

(deftest force-auth-add-held-summary-amount-sums-and-range
  (testing "amount sums by token/direction/account/owner and min/max range"
    (let [r (fa-ev/build-force-auth-add-held-summary
             {:artifacts [(sample-add-held :USDC 100 :in)
                          (sample-add-held :USDC 250 :in)
                          (sample-add-held :ETH 50 :out)]})]
      (is (= 400 (:total-amount r)))
      (is (= 50 (:min-amount r)))
      (is (= 250 (:max-amount r)))
      (is (= {:USDC 350 :ETH 50} (:amount-by-token r)))
      (is (= {:in 350 :out 50} (:amount-by-direction r)))
      (is (= {:escrow-principal 400} (:amount-by-account r)))
      (is (= {"0xrecipient" 400} (:amount-by-owner r))))))

(deftest force-auth-add-held-summary-owner-and-position-categories
  (let [scope-b (assoc valid-scope :owner/address "0xother")
        a1 (fa-ev/build-force-auth-add-held
            {:authorization (hash-bound-auth "fa-0" :active)
             :scope-map valid-scope
             :adjustment {:held-adjustment/id "adj-1" :token :USDC :amount 100 :held/direction :in
                          :held/position-id "pos-1"}})
        a2 (fa-ev/build-force-auth-add-held
            {:authorization (hash-bound-auth "fa-1" :active)
             :scope-map scope-b
             :adjustment {:held-adjustment/id "adj-2" :token :ETH :amount 50 :held/direction :in
                          :held/position-id "pos-2"}})
        r (fa-ev/build-force-auth-add-held-summary {:artifacts [a1 a2]})
        cat (:categories r)]
    (is (= 2 (:distinct-owners r)))
    (is (= 1 (:distinct-accounts r)))
    (is (= 2 (:distinct-tokens r)))
    (is (= {"0xrecipient" 1 "0xother" 1} (:by-owner cat)))
    (is (= {"pos-1" 1 "pos-2" 1} (:by-position-id cat)))
    (is (= {:force-authorisation 2} (:by-authorization-type cat)))))

(deftest force-auth-add-held-summary-consumed-at-range
  (let [a1 (fa-ev/build-force-auth-add-held
            {:authorization (hash-bound-auth "fa-0" :active)
             :scope-map valid-scope
             :adjustment {:held-adjustment/id "adj-1" :token :USDC :amount 100 :held/direction :in}
             :consumed-at 100})
        a2 (fa-ev/build-force-auth-add-held
            {:authorization (hash-bound-auth "fa-1" :active)
             :scope-map valid-scope
             :adjustment {:held-adjustment/id "adj-2" :token :USDC :amount 50 :held/direction :in}
             :consumed-at 300})
        r (fa-ev/build-force-auth-add-held-summary {:artifacts [a1 a2]})]
    (is (= 100 (:consumed-at-earliest r)))
    (is (= 300 (:consumed-at-latest r)))))

(deftest force-auth-add-held-summary-unverified-auth-triage
  (testing "unverified-authorization-ids surfaces the non-passing scope-hash cases"
    (let [wrong (assoc (sample-add-held :USDC 100 :in "adj-1")
                       :authorization/scope-verifies? false
                       :authorization/id "fa-bad")
          r (fa-ev/build-force-auth-add-held-summary
             {:artifacts [(sample-add-held :USDC 100 :in) wrong]})]
      (is (= 1 (:scope-unverified-count r)))
      (is (= ["fa-bad"] (:unverified-authorization-ids r))))))

(deftest force-auth-add-held-summary-evidence-empty
  (let [r (fa-ev/build-force-auth-add-held-summary {:artifacts []})]
    (is (= 0 (:total r)))
    (is (= 0 (:total-amount r)))
    (is (true? (fa-ev/valid-force-auth-add-held-summary? r)))))

(deftest force-auth-add-held-summary-evidence-tamper-detected
  (let [r (fa-ev/build-force-auth-add-held-summary
           {:artifacts [(sample-add-held :USDC 100 :in)]})]
    (is (false? (fa-ev/valid-force-auth-add-held-summary?
                 (assoc r :total 99))))
    (is (false? (fa-ev/valid-force-auth-add-held-summary?
                 (assoc r :artifact/hash "sha256:forged"))))))

(deftest force-auth-add-held-summary-v2-schema
  (let [r (fa-ev/build-force-auth-add-held-summary
           {:artifacts [(sample-add-held :USDC 100 :in)]})]
    (is (= "force-auth-add-held-summary.v2" (:schema-version r)))
    (is (true? (fa-ev/valid-force-auth-add-held-summary? r)))
    (is (false? (fa-ev/valid-force-auth-add-held-summary-v1? r))
        "a v2 artifact is not a valid v1 artifact")))

(deftest force-auth-add-held-summary-v1-migration-reader
  (testing "a v1-shaped artifact validates under the v1 reader, v2 does not"
    (let [v1 (fa-ev/build-force-auth-add-held-summary-v1
              {:artifacts [(sample-add-held :USDC 100 :in)]})]
      (is (= "force-auth-add-held-summary.v1" (:schema-version v1)))
      (is (true? (fa-ev/valid-force-auth-add-held-summary-v1? v1)))
      (is (false? (fa-ev/valid-force-auth-add-held-summary? v1))
          "a v1 artifact is not a valid v2 artifact"))))
