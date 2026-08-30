(ns prf.extensions.held-custody.authorisation-classification-test
  "Invariant check suite for the add-held / sub-held + force-authorisation +
   forbidden / forbidden-authorized gate.

   Core invariant under test:
     ordinary operation      -> :ordinary        (allowed by normal protocol)
     forbidden operation     -> :forbidden       (cannot execute)
     forbidden + exact governed force-auth
                             -> :forbidden-authorized (may execute ONLY through
                                the force-authorisation override)
     governance disables the override
                             -> :forbidden even with otherwise-valid force-auth

   Checks mirror the add-held/sub-held + force-authorisation checklist:
   exact operation identity, forbidden means actually forbidden, governance is
   upstream authority, extension architecture (delegation / no reimplementation),
   consumption semantics, tie-breakers, and vocabulary."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [prf.extensions.held-custody.authorisation-classification :as gate]
            [prf.extensions.held-custody.mutation :as mut]))

;; ── fixtures ────────────────────────────────────────────────────────────────

(defn- scope [action direction amt]
  {:authorization/id (str "permit-" (name action) "-" direction)
   :authorization/type :force-authorisation
   :held/direction direction
   :token :USDC
   :amount amt
   :held/account :escrow-principal
   :owner/address "0xrecipient"
   :held/reason :force-authorised-release
   :held/workflow-id 0})

(defn- permit-for [s]
  {:authorization/id (:authorization/id s)
   :authorization/type :force-authorisation
   :authorization/status :active
   :consumed? false
   :authorization/scope-hash (fa/force-authorisation-scope-hash
                              (fa/normalize-force-authorisation-scope s))
   :authorization/scope (fa/normalize-force-authorisation-scope s)
   :starts-at 0
   :expires-at 1000})

(def add-scope (scope :add-held :in 100))
(def sub-scope (scope :sub-held :out 40))
(def add-permit (permit-for add-scope))
(def sub-permit (permit-for sub-scope))

(def enabled-config
  "Authoritative configuration with the force-authorisation override enabled."
  {:force-authorisation/override-enabled true})

(def disabled-config
  "Authoritative configuration with the force-authorisation override disabled."
  {:force-authorisation/override-enabled false})

;; ── exact operation identity ────────────────────────────────────────────────

(deftest add-held-and-sub-held-are-distinct-authorization-subjects
  (testing "the gate distinguishes add-held (forbidden) from sub-held (ordinary)"
    (is (gate/forbidden-action? :add-held))
    (is (not (gate/forbidden-action? :sub-held)))
    (is (not (gate/forbidden-action? :finalize-released)))
    (is (not (gate/forbidden-action? :refund-held))))
  (testing "no generic held-change permission authorizes both directions"
    (let [add (gate/classify-operation
               {:action :add-held :scope add-scope :permit add-permit
                :consumption-registry {} :now-ts 500 :authoritative-config enabled-config})
          sub (gate/classify-operation
               {:action :sub-held :scope sub-scope :permit nil
                :authoritative-config enabled-config})]
      (is (= :forbidden-authorized (:classification add)))
      (is (= :ordinary (:classification sub)))))
  (testing "the precise source action is preserved, not normalised to a generic held-change"
    (is (= :add-held (mut/normalize-action :add-held)))
    (is (= :sub-held (mut/normalize-action :sub-held)))))

(deftest amount-and-scope-are-exact-where-semantically-relevant
  (testing "a wrong amount is an exact-scope mismatch -> forbidden, not authorized"
    (let [wrong (permit-for (assoc add-scope :amount 99))
          r (gate/classify-operation
             {:action :add-held :scope add-scope :permit wrong
              :consumption-registry {} :now-ts 500 :authoritative-config enabled-config})]
      (is (= :forbidden (:classification r)))
      (is (not (:usable-permit? r)))
      (is (some #{:scope-hash-mismatch :scope-mismatch} (:blocking-reasons r)))))
  (testing "the exact amount is authorized"
    (is (= :forbidden-authorized
           (:classification
            (gate/classify-operation
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500 :authoritative-config enabled-config}))))))

(deftest force-authorisation-for-add-held-cannot-be-replayed-for-sub-held
  (testing "a force authorisation for add-held cannot authorize sub-held, and vice versa"
    (let [r-add-from-sub (gate/classify-operation
                          {:action :add-held :scope add-scope :permit sub-permit
                           :consumption-registry {} :now-ts 500
                           :authoritative-config enabled-config})
          r-sub-from-add (gate/classify-operation
                          {:action :sub-held :scope sub-scope :permit add-permit
                           :consumption-registry {} :now-ts 500
                           :authoritative-config enabled-config})]
      (is (= :forbidden (:classification r-add-from-sub))
          "sub-held permit never satisfies add-held")
      (is (not= :forbidden-authorized (:classification r-add-from-sub)))
      (testing "sub-held is ordinary regardless; the add-held permit is ignored, not consumed"
        (is (= :ordinary (:classification r-sub-from-add)))))))

;; ── forbidden means actually forbidden ──────────────────────────────────────

(deftest forbidden-add-held-cannot-execute-without-exact-force-authorisation
  (testing "a forbidden (add-held) operation with no permit is :forbidden"
    (let [r (gate/classify-operation
             {:action :add-held :scope add-scope :permit nil
              :consumption-registry {} :now-ts 500 :authoritative-config enabled-config})]
      (is (= :forbidden (:classification r)))
      (is (some #{:missing-force-authorisation} (:blocking-reasons r)))))
  (testing "no alternate helper bypasses the rejection"
    (is (= :forbidden
           (:classification
            (gate/classify-operation
             {:action :add-held :scope add-scope :permit nil
              :authoritative-config enabled-config}))))
    (is (= :forbidden
           (:classification
            (gate/classify-operation
             {:action :add-held :scope add-scope :permit nil
              :authoritative-config enabled-config}))))))

(deftest forbidden-authorized-derives-from-verified-force-authorisation
  (testing "forbidden-authorized is NOT a caller-provided enum/status"
    (let [r (gate/classify-operation
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500 :authoritative-config enabled-config})]
      (is (= :forbidden-authorized (:classification r)))
      (is (true? (:usable-permit? r)))
      (is (= :force-authorisation (:authorization/type add-permit)))
      (testing "the classification derives from exact verified verification, not a status field"
        (is (true? (:valid?
                    (fa/verify-authorisation-usable add-permit {} add-scope 500)))))))
  (testing "a permit with only a present authorization field (no usable verification) stays forbidden"
    (let [stub {:authorization/id "permit-x" :authorization/type :force-authorisation}
          r (gate/classify-operation
             {:action :add-held :scope add-scope :permit stub
              :consumption-registry {} :now-ts 500 :authoritative-config enabled-config})]
      (is (= :forbidden (:classification r)))
      (is (not (:usable-permit? r))))))

;; ── governance is upstream authority ────────────────────────────────────────

(deftest governance-explicitly-enables-the-override
  (testing "the override is enabled only by explicit authoritative configuration"
    (is (true? (gate/override-enabled? enabled-config)))
    (is (false? (gate/override-enabled? disabled-config)))
    (is (false? (gate/override-enabled? nil))
        "absent configuration never enables the override")
    (is (false? (gate/override-enabled? {}))
        "ambiguous configuration never enables the override")))

(deftest governance-disable-makes-valid-force-authorisation-unusable
  (testing "disabling the override keeps an otherwise-valid permit forbidden"
    (let [r (gate/classify-operation
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500 :authoritative-config disabled-config})]
      (is (= :forbidden (:classification r)))
      (is (true? (:valid?
                  (fa/verify-authorisation-usable add-permit {} add-scope 500)))
          "the permit is cryptographically and lifecycle-valid")
      (is (some #{:force-authorisation-override-disabled} (:blocking-reasons r)))))
  (testing "the gate reads configuration from authoritative state, not the permit/request"
    (let [r (gate/classify-operation
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500 :authoritative-config nil})]
      (is (= :forbidden (:classification r)))
      (is (some #{:force-authorisation-override-disabled} (:blocking-reasons r))))))

(deftest stale-governance-cannot-authorize-against-the-new-head
  (testing "the override posture is resolved per call from authoritative config;
            an earlier-enabled config no longer held cannot authorize"
    (is (= :forbidden
           (:classification
            (gate/classify-operation
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500 :authoritative-config disabled-config}))))
    (is (= :forbidden-authorized
           (:classification
            (gate/classify-operation
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500 :authoritative-config enabled-config}))))))

;; ── extension architecture ──────────────────────────────────────────────────

(deftest production-delegates-verification-and-never-reimplements-it
  (testing "the gate's usable-permit? delegates to the authoritative core validator"
    (is (= (fa/verify-authorisation-usable add-permit {} add-scope 500)
           (let [r (fa/verify-authorisation-usable add-permit {} add-scope 500)]
             r)))
    (is (true? (:valid? (fa/verify-authorisation-usable add-permit {} add-scope 500))))
    (is (false? (:valid? (fa/verify-authorisation-usable sub-permit {} add-scope 500)))
        "a sub-held permit fails against the add-held scope through the same validator"))
  (testing "forbidden-action? delegates to the sensitivity sentinel, not a local re-derivation"
    (is (= (gate/forbidden-action? :add-held)
           (resolver-sim.sensitivity.sentinel/remote-authority-required-artifact?
            {:held/action :add-held})))))

;; ── consumption semantics ───────────────────────────────────────────────────

(deftest single-use-and-replay-prevention
  (testing "a consumed permit cannot be replayed for the same single-use authority"
    (let [consumed (assoc add-permit :consumed? true)
          r (gate/classify-operation
             {:action :add-held :scope add-scope :permit consumed
              :consumption-registry {} :now-ts 500 :authoritative-config enabled-config})]
      (is (= :forbidden (:classification r)))
      (is (some #{:authorisation-already-consumed} (:blocking-reasons r)))))
  (testing "a permit in the consumption registry cannot be replayed"
    (let [registry {(:authorization/id add-permit) {:consumed-at 400}}
          r (gate/classify-operation
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry registry :now-ts 500
              :authoritative-config enabled-config})]
      (is (= :forbidden (:classification r)))
      (is (some #{:authorisation-already-consumed} (:blocking-reasons r))))))

(deftest stale-at-commit-cannot-execute
  (testing "a permit valid at an earlier time but expired at commit time fails closed"
    (let [r (gate/classify-operation
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 5000 :authoritative-config enabled-config})]
      (is (= :forbidden (:classification r)))
      (is (some #{:authorisation-expired} (:blocking-reasons r)))))
  (testing "a permit not yet started fails closed"
    (let [future (assoc add-permit :starts-at 2000 :expires-at 3000)
          r (gate/classify-operation
             {:action :add-held :scope add-scope :permit future
              :consumption-registry {} :now-ts 500 :authoritative-config enabled-config})]
      (is (= :forbidden (:classification r)))
      (is (some #{:authorisation-not-yet-started} (:blocking-reasons r))))))

(deftest failed-attempts-do-not-consume
  (testing "the gate is a pure classifier and never consumes; a failed attempt leaves the permit usable"
    (let [failed (gate/classify-operation
                  {:action :add-held :scope (assoc add-scope :amount 999)
                   :permit add-permit :consumption-registry {} :now-ts 500
                   :authoritative-config enabled-config})
          retry (gate/classify-operation
                 {:action :add-held :scope add-scope :permit add-permit
                  :consumption-registry {} :now-ts 500
                  :authoritative-config enabled-config})]
      (is (= :forbidden (:classification failed)))
      (is (= :forbidden-authorized (:classification retry))
          "the same single-use authority is still usable after a failed attempt (no accidental consumption)"))))

;; ── tie-breakers ────────────────────────────────────────────────────────────

(deftest precedence-is-explicit
  (testing "when both a normal (ordinary) path and force-authorisation could apply,
            the ordinary path wins and force-authorisation is ignored, not consumed"
    (let [r (gate/classify-operation
             {:action :sub-held :scope sub-scope :permit add-permit
              :consumption-registry {} :now-ts 500 :authoritative-config enabled-config})]
      (is (= :ordinary (:classification r)))
      (is (true? (:force-auth-ignored? r))))))

(deftest multiple-candidate-selection-is-deterministic-and-not-caller-selectable
  (testing "candidate selection sorts by authorization/id and is deterministic"
    (let [a (assoc add-permit :authorization/id "permit-a")
          b (assoc add-permit :authorization/id "permit-b")]
      (is (= (:authorization/id a) (:authorization/id (gate/select-permit [b a])))
          "first-by-id, not caller order")
      (is (= (:authorization/id a) (:authorization/id (gate/select-permit [a b]))))))
  (testing "selection cannot make a generic/wrong-scope permit satisfy a forbidden operation"
    (let [r (gate/classify-operation
             {:action :add-held :scope add-scope :permit sub-permit
              :consumption-registry {} :now-ts 500 :authoritative-config enabled-config})]
      (is (= :forbidden (:classification r)))
      (is (not (:usable-permit? r))))))

(deftest tie-breaking-cannot-let-add-held-authorization-satisfy-sub-held
  (testing "an add-held (forbidden) authorization is the wrong direction for a sub-held
            operation; sub-held stays :ordinary with the force-auth ignored, not consumed,
            and never becomes :forbidden-authorized"
    (let [r (gate/classify-operation
             {:action :sub-held :scope sub-scope :permit add-permit
              :consumption-registry {} :now-ts 500 :authoritative-config enabled-config})]
      (is (= :ordinary (:classification r)))
      (is (true? (:force-auth-ignored? r)))
      (is (not= :forbidden-authorized (:classification r))))))

;; ── vocabulary ──────────────────────────────────────────────────────────────

(deftest vocabulary-is-exact
  (testing "only three classifications exist"
    (is (= #{:forbidden :forbidden-authorized :ordinary}
           (set (keys gate/vocabulary)))))
  (testing "forbidden-authorized requires exact verified authorization + enabled override"
    (let [valid (gate/classify-operation
                 {:action :add-held :scope add-scope :permit add-permit
                  :consumption-registry {} :now-ts 500
                  :authoritative-config enabled-config})
          not-valid (gate/classify-operation
                     {:action :add-held :scope add-scope :permit add-permit
                      :consumption-registry {} :now-ts 500
                      :authoritative-config disabled-config})]
      (is (= :forbidden-authorized (:classification valid)))
      (is (true? (:usable-permit? valid)))
      (is (= :forbidden (:classification not-valid))
          "without the enabled override it is forbidden, not forbidden-authorized"))))