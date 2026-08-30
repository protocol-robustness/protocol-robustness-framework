(ns prf.extensions.held-custody.core-seam-test
  "Verifies the core-held-admission <-> physical-held-custody seam when the
   physical package is authoritatively selected (Slice B).

   The core boundary resolves the exceptional override implementation from an
   EXPLICIT, ROOTED extension-resolution snapshot (resolve-requested) through the
   package's DECLARED, versioned capability contract — never from the global live
   registry and never through an ad-hoc map key. A merely-registered alternate
   implementation cannot substitute for the provider named by the rooted snapshot."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.extensions.registry :as registry]
            [resolver-sim.extensions.resolution :as resolution]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.assurance.held-admission :as admission]
            [prf.extensions.held-custody.manifest :as manifest]
            [prf.extensions.held-custody.semantic-admission :as semantic-admission]))

(def override-capability-key
  [:assurance/force-authorisation :held-custody/override-admission-v1])

(defn- scope [dir amt]
  {:authorization/id "permit-fa"
   :authorization/type :force-authorisation
   :held/direction dir
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

(defn- override-resolution
  "A rooted extension-resolution snapshot selecting the physical held-custody
   override capability, derived the way production would (resolve-requested over
   an extension-map containing the held-custody package)."
  []
  (let [root (str "sha256:" (apply str (repeat 64 "a")))
        schemas {:prf/held-custody-override-admission-input.v1 root
                 :prf/held-custody-override-admission-result.v1 root
                 :prf/held-custody-override-admission-verification.v1 root}]
    (resolution/resolve-requested
     (registry/register-package (registry/empty-extension-map) manifest/package)
     [override-capability-key]
     {:schemas schemas})))

(defn- current-head []
  (configuration-head/initial-head (str "sha256:" (apply str (repeat 64 "a"))) 1))

(defn- re-root
  "Recompute the resolution-root of a tampered snapshot so it remains root-valid
   (same projection `verify-portable!` uses)."
  [snapshot]
  (let [root (hc/domain-hash
              "EXTENSION_RESOLUTION_V1"
              (dissoc snapshot :extensions/resolution-root))]
    (assoc snapshot :extensions/resolution-root root)))

(defn- base-snapshot []
  (:resolution (override-resolution)))

(deftest core-boundary-resolves-the-rooted-selected-implementation
  (let [resolution-result (override-resolution)]
    (testing "the rooted resolution is valid and selects the override capability"
      (is (true? (:valid? resolution-result)))
      (is (contains? (:extensions/capabilities (:resolution resolution-result))
                     override-capability-key)))
    (testing "the core seam resolves the physical override implementation from the snapshot"
      (let [{:keys [available? implementation reason]}
            (admission/resolve-exceptional-override-implementation resolution-result)]
        (is (true? available?))
        (is (nil? reason))
        (is (= semantic-admission/override-admission implementation))))))

(deftest core-boundary-full-override-proceeds-when-rooted-selected
  (let [s (scope :in 100)
        permit (permit-for s)
        d (admission/admit-held-mutation
           {:operation-id :held-custody/force-auth-mutation
            :scope s :permits [permit]
            :consumption-registry {} :now-ts 500
            :configuration-head (current-head)
            :extension-resolution (override-resolution)})]
    (is (= :proceed-force-authorised (:admission d)))
    (is (= :forbidden-authorized (:classification d)))
    (is (= permit (:permit d))
        "the exact selected permit is carried for accounting consumption"))
  (testing "ordinary ingress still proceeds with no force permit"
    (is (= :proceed-ordinary
           (:admission
            (admission/admit-held-mutation
             {:operation-id :sew/escrow-principal-deposited
              :scope (scope :in 100) :permits []}))))))

(deftest core-boundary-override-disabled-rejects-when-rooted-selected
  (let [s (scope :in 100)
        permit (permit-for s)
        d (admission/admit-held-mutation
           {:operation-id :held-custody/force-auth-mutation
            :scope s :permits [permit]
            :consumption-registry {} :now-ts 500
            :configuration-head {}                       ; invalid head -> override disabled
            :extension-resolution (override-resolution)})]
    (is (= :reject (:admission d)))
    (is (= :forbidden (:classification d)))
    (is (some #{:force-authorisation-override-disabled} (:blocking-reasons d)))))

(deftest registered-alternate-cannot-replace-the-rooted-selected-provider
  (testing "a merely-registered alternate implementation in the global live registry
            cannot substitute for the provider named by the explicit rooted resolution"
    (let [fake-entrypoint 'clojure.core/identity
          fake-capability (assoc manifest/override-admission-capability
                                 :entrypoint fake-entrypoint)
          fake-package (assoc manifest/package
                              :extension/id :prf.extensions/held-custody-fake
                              :extension/capabilities [fake-capability])]
      (registry/register-package! fake-package)
      (try
        (testing "the seam still resolves the REAL provider from the rooted snapshot"
          (let [{:keys [available? implementation]}
                (admission/resolve-exceptional-override-implementation
                 (override-resolution))]
            (is (true? available?))
            (is (= semantic-admission/override-admission implementation)
                "the rooted snapshot names the real override-admission, not the fake")))
        (finally
          (try (registry/unregister-package! fake-package)
               (catch Throwable _ nil)))))))

(deftest absent-resolution-fails-closed
  (testing "no extension-resolution -> override rejects before mutation"
    (let [d (admission/admit-held-mutation
             {:operation-id :held-custody/force-auth-mutation
              :scope (scope :in 100) :permits []
              :extension-resolution nil})]
      (is (= :reject (:admission d)))
      (is (some #{:resolution-invalid} (:blocking-reasons d)))))
  (testing "an invalid resolve-requested result -> reject"
    (let [d (admission/admit-held-mutation
             {:operation-id :held-custody/force-auth-mutation
              :scope (scope :in 100) :permits []
              :extension-resolution {:valid? false :violations []}})]
      (is (= :reject (:admission d)))
      (is (some #{:resolution-invalid} (:blocking-reasons d))))))

(deftest seam-fails-closed-on-selection-defects
  "Slice B fail-closed matrix: a root-valid resolution that does not authoritatively
   select the exact pinned override capability cannot authorize exceptional
   execution."
  (let [s (scope :in 100)
        permit (permit-for s)
        caps (fn [snapshot] (:extensions/capabilities snapshot))
        reject-with (fn [reason extension-resolution]
                      (let [d (admission/admit-held-mutation
                               {:operation-id :held-custody/force-auth-mutation
                                :scope s :permits [permit]
                                :consumption-registry {} :now-ts 500
                                :configuration-head (current-head)
                                :extension-resolution extension-resolution})]
                        (is (= :reject (:admission d)))
                        (is (some #{reason} (:blocking-reasons d)))))]
    (testing "capability absent from a rooted snapshot"
      (reject-with :capability-absent
                   {:valid? true
                    :resolution (re-root (assoc (base-snapshot)
                                                :extensions/capabilities
                                                (dissoc (caps (base-snapshot)) override-capability-key)))}))
    (testing "wrong capability version / contract"
      (reject-with :capability-wrong-identity
                   {:valid? true
                    :resolution (re-root (assoc (base-snapshot)
                                                :extensions/capabilities
                                                (update (caps (base-snapshot))
                                                        override-capability-key
                                                        assoc :capability/version 2)))}))
    (testing "duplicate/ambiguous providers"
      (reject-with :ambiguous-providers
                   {:valid? true
                    :resolution (re-root (assoc (base-snapshot)
                                                :extensions/capability-providers
                                                (update-in (base-snapshot)
                                                           [:extensions/capability-providers override-capability-key :providers]
                                                           conj {:package-root (str "sha256:" (apply str (repeat 64 "z")))})))}))
    (testing "selected provider unavailable (unresolvable entrypoint)"
      (reject-with :provider-unavailable
                   {:valid? true
                    :resolution (re-root (assoc (base-snapshot)
                                                :extensions/capabilities
                                                (update (caps (base-snapshot))
                                                        override-capability-key
                                                        assoc :entrypoint "nonexistent.ns/override-admission")))}))
    (testing "tampered (unrooted) resolution"
      (reject-with :resolution-unrooted
                   {:valid? true
                    :resolution (assoc (base-snapshot)
                                       :extensions/resolution-root
                                       (str "sha256:" (apply str (repeat 64 "b"))))}))
    (testing "the pinned-identity path still succeeds"
      (is (= :proceed-force-authorised
             (:admission
              (admission/admit-held-mutation
               {:operation-id :held-custody/force-auth-mutation
                :scope s :permits [permit]
                :consumption-registry {} :now-ts 500
                :configuration-head (current-head)
                :extension-resolution (override-resolution)})))))))