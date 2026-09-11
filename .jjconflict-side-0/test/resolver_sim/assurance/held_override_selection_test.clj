(ns resolver-sim.assurance.held-override-selection-test
  "Slice C: production authoritative extension selection.

   Proves:
     1. extension installed but not selected -> exceptional mutation rejects;
     2. extension selected by a STALE configuration -> rejects;
     3. current configuration selects the exact provider -> succeeds;
     4. current config selects provider A while provider B is also installed
        -> A is used, never B;
     5. a configuration transition that removes/disables the selection makes the
        same otherwise-valid permit unusable;
     6. ordinary ingress remains independent of extension installation/selection.

   installed != selected, and selected-but-stale != current."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.assurance.held-override-selection :as selection]
            [prf.extensions.held-custody.manifest :as manifest]))

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

(def override-key [:assurance/force-authorisation :held-custody/override-admission-v1])

(defn- head-at [n]
  (configuration-head/initial-head
   (str "sha256:" (apply str (repeat 64 (str n)))) 1))

(def h1 (head-at 1))
(def h2 (head-at 2))

(def installed-packages
  {:prf.extensions/held-custody manifest/package})

(defn- selected-selection [config-head]
  (selection/build-selection override-key
                             {:package/id :prf.extensions/held-custody
                              :package-root "sha256:held-custody-pkg"}
                             (:configuration-head-state/root config-head)))

(defn- op-opts [permit config-head]
  {:operation-id :held-custody/force-auth-mutation
   :scope (scope :in 100) :permits [permit]
   :consumption-registry {} :now-ts 500
   :configuration-head config-head})

(deftest installed-but-not-selected-rejects
  (testing "an installed package that is NOT selected cannot authorize exceptional
            execution (no committed selection)"
    (let [s (scope :in 100)
          permit (permit-for s)
          d (selection/classify-under-authoritative-selection
             installed-packages h1 nil (op-opts permit h1))]
      (is (= :reject (:admission d)))
      (is (some #{:selection-stale-or-absent} (:blocking-reasons d))))))

(deftest selected-but-stale-rejects
  (testing "a selection committed against an OLDER head-root is stale and rejects"
    (let [s (scope :in 100)
          permit (permit-for s)
          stale-selection (selected-selection h1)   ; committed against h1
          d (selection/classify-under-authoritative-selection
             installed-packages h2 stale-selection (op-opts permit h2))]
      (is (= :reject (:admission d)))
      (is (some #{:selection-stale-or-absent} (:blocking-reasons d))))))

(deftest current-config-selects-exact-provider-succeeds
  (testing "current configuration commits the selection -> exact permit proceeds"
    (let [s (scope :in 100)
          permit (permit-for s)
          current-selection (selected-selection h1)
          d (selection/classify-under-authoritative-selection
             installed-packages h1 current-selection (op-opts permit h1))]
      (is (= :proceed-force-authorised (:admission d)))
      (is (= :forbidden-authorized (:classification d)))
      (is (= permit (:permit d))))))

(deftest selected-provider-a-used-not-b
  (testing "config selects provider A while provider B is ALSO installed -> A is used,
            never B"
    (let [s (scope :in 100)
          permit (permit-for s)
          current-selection (selected-selection h1)
          fake-package (assoc manifest/package
                              :extension/id :prf.extensions/held-custody-fake
                              :extension/capabilities
                              [(assoc manifest/override-admission-capability
                                      :entrypoint 'clojure.core/identity)])
          both-installed (assoc installed-packages
                                :prf.extensions/held-custody-fake fake-package)]
      (testing "the selected (real) provider's package is used for derivation"
        (let [{:keys [valid? resolution]}
              (selection/derive-selected-resolution both-installed h1 current-selection)]
          (is (true? valid?))
          (is (contains? (:extensions/capabilities resolution) override-key))))
      (testing "classification proceeds via the real selected provider"
        (let [d (selection/classify-under-authoritative-selection
                 both-installed h1 current-selection (op-opts permit h1))]
          (is (= :proceed-force-authorised (:admission d)))
          (is (= permit (:permit d))))))))

(deftest transition-removing-selection-makes-permit-unusable
  (testing "a configuration transition that removes the selection makes the same
            otherwise-valid permit unusable"
    (let [s (scope :in 100)
          permit (permit-for s)
          _ (is (true? (:valid? (fa/verify-authorisation-usable permit {} s 500)))
                "the permit is itself valid")
          without-selection nil
          d (selection/classify-under-authoritative-selection
             installed-packages h2 without-selection (op-opts permit h2))]
      (is (= :reject (:admission d)))
      (is (some #{:selection-stale-or-absent} (:blocking-reasons d))))))

(deftest ordinary-ingress-independent-of-extension-selection
  (testing "ordinary ingress proceeds regardless of extension installation/selection"
    (doseq [[cfg sel] [[h1 nil] [h1 (selected-selection h1)] [nil nil]]]
      (let [d (selection/classify-under-authoritative-selection
               installed-packages cfg sel
               {:operation-id :sew/escrow-principal-deposited
                :scope (scope :in 100) :permits []})]
        (is (= :proceed-ordinary (:admission d)))))))