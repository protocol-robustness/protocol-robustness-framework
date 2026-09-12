(ns resolver-sim.pro-rata.publication-test
  "Acceptance gate for the economic application publication binding (DS9).

   Establishes that an applied economic realization (applied-effect-receipt R
   and protocol-transaction-realization P) gains authoritative lineage only
   through a publication ordering T (transaction-ordering.v3) committed by an
   authoritative successor head, and that the conservation invariant rejects
   transplant/substitution. The four-layer distinction is pinned:

     valid artifact
     ≠ retained artifact
     ≠ transaction-committed artifact
     ≠ authoritatively published application"
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.publication :as sut]
            [resolver-sim.transaction.ordering :as ordering]))

(defn- root [n]
  (str "sha256:" (format "%064x" (bit-and 0xFFFFFFFF (.hashCode (str n))))))

(def q (root "1"))

(defn- build-receipt
  [{:keys [canonical effect-set-root auth-root executed-effect-set-root]}]
  (let [sb (:state-before/root canonical)
        sa (:state-after/root canonical)
        effect-set (or effect-set-root (root "e"))
        executed (or executed-effect-set-root effect-set)
        base {:schema-version "applied-effect-receipt.v1"
              :authorization/root (or auth-root (root "auth"))
              :protocol-effect-set/root effect-set
              :executed-effect-set/root executed
              :applied-adjustment-refinement/root (root "ref")
              :applied-adjustments/root (root "adj")
              :state-before/root sb
              :state-after/root sa
              :ledger-before/root (root "lb")
              :ledger-after/root (root "la")
              :application/status :applied}]
    (assoc base :applied-effect-receipt/root
           (hc/domain-hash :applied-effect-receipt base))))

(defn- build-realization
  [canonical]
  (let [base {:schema-version "protocol-transaction-realization.v1"
              :canonical-transition/root (:canonical-effect-transition/root canonical)
              :transition-binding/root (root "tb")
              :protocol-effect-realization/root (root "per")
              :binding/mode :effect-exact}]
    (assoc base :protocol-transaction-realization/root
           (hc/domain-hash :protocol-transaction-realization base))))

(defn- valid-application
  []
  (let [canonical (effects/transition {q 10} [(effects/delta q -5)])]
    {:canonical canonical
     :receipt (build-receipt {:canonical canonical})
     :realization (build-realization canonical)}))

(defn- pub-input
  [{:keys [canonical receipt realization commit-index previous-transaction-hash]
    :or {commit-index 1 previous-transaction-hash nil}}]
  {:receipt receipt
   :realization realization
   :canonical-transition canonical
   :action :prf.economic/publish
   :scope :economic-application
   :conflict-key [:economic-application]
   :commit-index commit-index
   :previous-transaction-hash previous-transaction-hash
   :input-root (root "in")})

;; ── happy path ────────────────────────────────────────────────────────────

(deftest happy-path-publication-and-recovery
  (let [{:keys [canonical receipt realization]} (valid-application)
        R (:applied-effect-receipt/root receipt)
        P (:protocol-transaction-realization/root realization)
        T (sut/build-publication-ordering (pub-input {:canonical canonical
                                                      :receipt receipt
                                                      :realization realization}))
        store (sut/new-head-store)
        result (sut/publish! store T nil)]
    (testing "v3 economic publication ordering commits exact R + P + state"
      (is (= ordering/ordering-v3-schema (:transaction-ordering/schema T)))
      (is (= (ref/sha256-ref R) (:transaction/application-receipt-root T)))
      (is (= (ref/sha256-ref P) (:transaction/realization-root T)))
      (is (= (ref/sha256-ref (:state-before/root canonical)) (:transaction/state-before-root T)))
      (is (= (ref/sha256-ref (:state-after/root canonical)) (:transaction/state-after-root T)))
      (is (= (ref/sha256-ref (:effects/root canonical)) (:transaction/effects-root T))))
    (testing "T verifies as a sound ordering"
      (is (:valid? (ordering/verify-ordering T))))
    (testing "publish commits T to the authoritative head; recoverable from head alone"
      (is (= :committed (:status result)))
      (is (sut/published? store T))
      (is (= (:transaction-ordering/hash T) (sut/published-ordering-root store)))
      (is (nil? (:transaction/previous-transaction-hash T))))))

(deftest chained-publication-verifies-as-a-lineage
  (let [a1 (valid-application)
        T1 (sut/build-publication-ordering (pub-input a1))
        store (sut/new-head-store)
        _ (sut/publish! store T1 nil)
        ;; successor application must begin at T1's committed state-after
        canonical2 (effects/transition {q 5} [(effects/delta q -2)])
        a2 {:canonical canonical2
            :receipt (build-receipt {:canonical canonical2})
            :realization (build-realization canonical2)}
        T2 (sut/build-publication-ordering
            (pub-input (assoc a2
                              :previous-transaction-hash (:transaction-ordering/hash T1)
                              :commit-index 2)))
        result (sut/publish! store T2 nil)]
    (is (= (:state-after/root (:canonical a1)) (:state-before/root canonical2))
        "T2's state-before is the prior-state fixed point of T1")
    (is (= :committed (:status result)))
    (is (sut/published? store T2))
    (is (:valid? (ordering/verify-ordering-chain [T1 T2])))))

;; ── conservation rejections ───────────────────────────────────────────────

(deftest rejects-receipt-substitution
  (let [{:keys [canonical realization]} (valid-application)
        ;; A substituted receipt R' whose executed effect set disagrees with its
        ;; own declared protocol effect set is an incoherent application receipt.
        substituted (build-receipt {:canonical canonical
                                    :effect-set-root (root "x")
                                    :executed-effect-set-root (root "y")})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/build-publication-ordering
                  (pub-input {:canonical canonical :receipt substituted :realization realization}))))))

(deftest rejects-realization-substitution
  (let [{:keys [canonical receipt]} (valid-application)
        other (effects/transition {q 10} [(effects/delta q -3)])
        substitution (build-realization other)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/build-publication-ordering
                  (pub-input {:canonical canonical :receipt receipt :realization substitution}))))))

(deftest rejects-state-after-transplant
  (let [{:keys [receipt]} (valid-application)
        ;; same state-before, different state-after
        other (effects/transition {q 10} [(effects/delta q -3)])
        other-realization (build-realization other)]
    (is (not= (:state-after/root receipt) (:state-after/root other)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/build-publication-ordering
                  (pub-input {:canonical other :receipt receipt :realization other-realization}))))))

(deftest rejects-effects-transplant
  (let [{:keys [receipt]} (valid-application)
        ;; A transition with a different effect commitment (different effects
        ;; root) cannot be realized under the receipt's published application.
        other (effects/transition {q 10} [(effects/delta q -4)])
        other-realization (build-realization other)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/build-publication-ordering
                  (pub-input {:canonical other :receipt receipt :realization other-realization}))))))

(deftest rejects-v2-receipt-output-binding-substitution
  (let [{:keys [canonical realization]} (valid-application)
        receipt-base {:schema-version "applied-effect-receipt.v2"
                      :effect-compilation-binding/root (root "E1")
                      :state-before/root (:state-before/root canonical)
                      :state-after/root (:state-after/root canonical)
                      :protocol-effect-set/root (:effects/root canonical)
                      :executed-effect-set/root (:effects/root canonical)}
        receipt (assoc receipt-base :applied-effect-receipt/root
                       (hc/domain-hash :applied-effect-receipt receipt-base))
        output {:pro-rata-output/schema "pro-rata-capability-output.v2"
                :effect-compilation-binding/root (root "E2")}
        violations (sut/conservation-violations receipt realization canonical
                                                {} {} output (fn [_] nil))]
    (is (some #(= :publication/binding-root-mismatch (:violation/id %)) violations)
        "individually named but different receipt/output bases cannot conserve")))

;; ── authoritative publication vs weaker states ────────────────────────────

(deftest floating-receipt-is-not-authoritative
  (let [{:keys [canonical receipt realization]} (valid-application)]
    (is (sut/conservation-holds? receipt realization canonical))
    (is (nil? (sut/published-ordering-root (sut/new-head-store)))
        "a valid R + P with no publication ordering is not authoritative")))

(deftest retention-only-is-not-authoritative
  (let [{:keys [canonical receipt realization]} (valid-application)
        T (sut/build-publication-ordering (pub-input {:canonical canonical
                                                      :receipt receipt
                                                      :realization realization}))
        store (sut/new-head-store)]
    ;; R/P/T bodies exist, but no committed root: not authoritative.
    (is (nil? (sut/published-ordering-root store)))
    (is (not (sut/published? store T)))))

(deftest ordering-only-is-not-authoritative
  (let [{:keys [canonical receipt realization]} (valid-application)
        T (sut/build-publication-ordering (pub-input {:canonical canonical
                                                      :receipt receipt
                                                      :realization realization}))
        store (sut/new-head-store)]
    ;; A valid T built in memory but never published to a successor head is not
    ;; an authoritative application.
    (is (:valid? (ordering/verify-ordering T)))
    (is (not (sut/published? store T)))))

(deftest publish-marks-the-exact-ordering-authoritative
  (let [{:keys [canonical receipt realization]} (valid-application)
        T (sut/build-publication-ordering (pub-input {:canonical canonical
                                                      :receipt receipt
                                                      :realization realization}))
        store (sut/new-head-store)
        _ (sut/publish! store T nil)]
    (is (sut/published? store T))
    ;; a different, valid ordering is not the published one
    (is (not (sut/published? store (sut/build-publication-ordering
                                    (pub-input {:canonical canonical
                                                :receipt receipt
                                                :realization realization
                                                :commit-index 2})))))))
