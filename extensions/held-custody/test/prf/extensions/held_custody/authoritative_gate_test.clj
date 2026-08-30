(ns prf.extensions.held-custody.authoritative-gate-test
  "Tests for the authoritative held-custody force-authorisation override gate:
   production posture resolution from authoritative configuration + extension
   state, and delegation to the pure classifier.

   Guarantee established: \"given the current authoritative configuration,
   classification is correct end-to-end\" — the override posture is NEVER a
   caller-asserted boolean; it is derived only from a valid self-rooted
   configuration-head-state.v1 AND a valid non-ambiguous extension resolution
   that carries the held-custody force-auth override capability. Every other
   case (disabled, stale/invalid config, missing/ambiguous extension) fails
   closed to :forbidden even with an otherwise-valid permit."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.configuration-head :as configuration-head]
            [prf.extensions.held-custody.authoritative-gate :as auth]
            [prf.extensions.held-custody.authorisation-classification :as gate]))

(defn- scope [action direction amt]
  {:authorization/id (str "permit-" (name action) "-" (name direction))
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
(def add-permit (permit-for add-scope))

(defn- current-head
  "A valid, self-rooted configuration-head-state.v1 (the authoritative current
   head as owned by an AuthorityStateStore)."
  []
  (configuration-head/initial-head
   (str "sha256:" (apply str (repeat 64 "a")))
   1))

(defn- extension-resolution
  "A valid extension resolution that carries the held-custody force-auth override
   capability, shaped like resolver-sim.extensions.resolution/resolve-requested."
  []
  {:valid? true
   :resolution {:extensions/resolution-version 1
                :extensions/packages {}
                :extensions/capabilities
                {[:assurance/force-authorisation :held-custody/override-admission-v1]
                 {:capability/kind :assurance/force-authorisation
                  :capability/id :held-custody/override-admission-v1}}}
   })

(defn- resolution-without-override-capability []
  {:valid? true
   :resolution {:extensions/resolution-version 1
                :extensions/packages {}
                :extensions/capabilities {}}})

(defn- invalid-resolution []
  {:valid? false
   :violations [{:violation/id :extensions/error-ambiguous-provider
                 :details {:capability [:assurance/force-authorisation
                                        :held-custody/override-admission-v1]
                           :package-roots ["sha256:one" "sha256:two"]}}]})

(deftest override-enabled-only-from-valid-config-and-capability
  (testing "enabled: valid head + valid resolution carrying the override capability"
    (is (true? (auth/override-enabled-under-configuration
                (current-head) (extension-resolution)))))
  (testing "disabled: valid resolution but the override capability is absent"
    (is (false? (auth/override-enabled-under-configuration
                 (current-head) (resolution-without-override-capability)))))
  (testing "missing extension: no resolution at all fails closed"
    (is (false? (auth/override-enabled-under-configuration (current-head) nil))))
  (testing "ambiguous extension: an invalid resolution (:valid? false) fails closed"
    (is (false? (auth/override-enabled-under-configuration
                 (current-head) (invalid-resolution)))))
  (testing "stale/forged configuration: an invalid head fails closed"
    (is (false? (auth/override-enabled-under-configuration
                 {} (extension-resolution))))
    (is (false? (auth/override-enabled-under-configuration
                 {:schema-version "configuration-head-state.v1"
                  :configuration/head-root "sha256:not-a-root"}
                 (extension-resolution))))))

(deftest enabled-current-config-exact-permit-succeeds
  (testing "override enabled in authoritative state -> an exact valid permit is
            :forbidden-authorized"
    (let [r (auth/classify-under-current-configuration
             (current-head) (extension-resolution)
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500})]
      (is (= :forbidden-authorized (:classification r)))
      (is (true? (:override-enabled? r)))
      (is (true? (:usable-permit? r))))))

(deftest disabled-current-config-same-permit-fails
  (testing "override not enabled in authoritative state -> the SAME valid permit is
            :forbidden"
    (let [r (auth/classify-under-current-configuration
             (current-head) (resolution-without-override-capability)
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500})]
      (is (= :forbidden (:classification r)))
      (is (false? (:override-enabled? r)))
      (is (some #{:force-authorisation-override-disabled} (:blocking-reasons r))))
    (testing "the permit is itself valid (the failure is the governance posture)"
      (is (true? (:valid? (fa/verify-authorisation-usable add-permit {} add-scope 500)))))))

(deftest stale-config-fails
  (testing "an invalid/stale configuration head fails closed even with an exact permit"
    (let [r (auth/classify-under-current-configuration
             {} (extension-resolution)
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500})]
      (is (= :forbidden (:classification r)))
      (is (false? (:override-enabled? r)))
      (is (some #{:force-authorisation-override-disabled} (:blocking-reasons r))))))

(deftest missing-or-ambiguous-extension-fails
  (testing "missing extension resolution -> :forbidden even with an exact permit"
    (let [r (auth/classify-under-current-configuration
             (current-head) nil
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500})]
      (is (= :forbidden (:classification r)))
      (is (false? (:override-enabled? r)))))
  (testing "ambiguous extension resolution (:valid? false) -> :forbidden"
    (let [r (auth/classify-under-current-configuration
             (current-head) (invalid-resolution)
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500})]
      (is (= :forbidden (:classification r)))
      (is (false? (:override-enabled? r))))))

(deftest ordinary-sub-held-stays-ordinary-under-authoritative-configuration
  (testing "sub-held remains :ordinary; presented force-auth is ignored, not consumed"
    (let [r (auth/classify-under-current-configuration
             (current-head) (extension-resolution)
             {:action :sub-held :scope (scope :sub-held :out 40)
              :permit add-permit :consumption-registry {} :now-ts 500})]
      (is (= :ordinary (:classification r)))
      (is (true? (:force-auth-ignored? r))))))

(deftest pure-classifier-remains-reusable
  (testing "the pure classifier is unchanged and independently reachable"
    (is (fn? gate/classify-operation))
    (is (= :forbidden-authorized
           (:classification
            (gate/classify-operation
             {:action :add-held :scope add-scope :permit add-permit
              :consumption-registry {} :now-ts 500
              :authoritative-config {:force-authorisation/override-enabled true}}))))))