(ns resolver-sim.protocols.sew.held-egress-migration-test
  "P1 egress semantic migration.

   Proves force-authorised release/refund egress is semantic, not primitive-driven:
   `sub-held :out` has no inherent authorization meaning — the SEMANTIC OPERATION
   decides whether ordinary authority or an exact governed override is required.

     ordinary release/refund/settlement -> sub-held :out -> :ordinary
       (incidental FA ignored, no consumption, no lineage)
     force-authorised release/refund    -> same sub-held :out -> exact :out permit
       required, consumed once, lineage retained, audit-from-successor succeeds

   Adversarial cases (all reject at admission with NO mutation/adjustment/
   consumption/lineage): release permit -> refund scope, refund permit -> release
   scope, :in permit for egress, wrong amount / account, consumed / stale permit,
   absent extension, ambiguous permits."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.accounting.held-adjustment :as held-adjustment]
            [resolver-sim.accounting.held-position-policy :as held-policy]
            [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.extensions.registry :as registry]
            [resolver-sim.extensions.resolution :as resolution]
            [resolver-sim.assurance.held-override-selection :as selection]
            [resolver-sim.assurance.held-override-publication :as publication]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew.held-mutation-admission :as admission]
            [prf.extensions.held-custody.manifest :as manifest]))

(def override-key [:assurance/force-authorisation :held-custody/override-admission-v1])
(def ordinary-reason :held/unspecified)     ; policy-exempt, NOT exceptional
(def release-reason :force-authorised-release)
(def refund-reason :force-authorised-refund)
(def token :USDC)
(def amount 40)
(def extra {:owner/address "0xr" :held/workflow-id 0})

(defn- current-head []
  (configuration-head/initial-head (str "sha256:" (apply str (repeat 64 "a"))) 1))

(defn- historical-selection []
  (selection/build-selection override-key
                             {:package/id :prf.extensions/held-custody
                              :package-root "sha256:held-custody-pkg"}
                             (:configuration-head-state/root (current-head))))

(defn- extension-resolution []
  (let [root (str "sha256:" (apply str (repeat 64 "a")))
        schemas {:prf/held-custody-override-admission-input.v1 root
                 :prf/held-custody-override-admission-result.v1 root
                 :prf/held-custody-override-admission-verification.v1 root}]
    (resolution/resolve-requested
     (registry/register-package (registry/empty-extension-map) manifest/package)
     [override-key]
     {:schemas schemas})))

(defn- scope-map
  "The exact :authorization/scope accounting derives for a force-auth egress of
   the given reason (direction :out), so a permit can reconcile at execution."
  [id reason]
  (let [components (held-policy/position-components token reason extra)
        scope-fields (merge {:authorization/id id
                             :authorization/type :force-authorisation
                             :held/direction :out
                             :token token
                             :amount amount
                             :held/account (:held/account components)
                             :held/position-id (:held/position-id components)
                             :owner/address (:owner/address components)
                             :held/reason reason}
                            (select-keys extra [:held/workflow-id]))]
    (held-adjustment/project-held-adjustment-scope
     (dissoc scope-fields :held/position-id))))

(defn- force-auth-permit [id reason]
  (let [sm (scope-map id reason)]
    {:authorization/id id
     :authorization/type :force-authorisation
     :authorization/status :active
     :consumed? false
     :authorization/scope-hash (fa/force-authorisation-scope-hash sm)
     :authorization/scope sm
     :starts-at 0
     :expires-at 1000}))

(defn- position-held-for
  "Seed the position balance so a position-bearing sub-held (release/refund) does
   not underflow during accounting execution."
  [reason]
  (let [components (held-policy/position-components token reason extra)]
    {:position-id (:held/position-id components)
     :account (:held/account components)}))

(defn- egress-world [permit reason]
  (let [{:keys [position-id account]} (position-held-for reason)]
    (cond-> (assoc (types/empty-world)
                   :total-held {token amount}
                   :held-ledger/index {:by-token {token amount}
                                       :by-position {}
                                       :by-account {}
                                       :by-owner {}
                                       :by-workflow {}}
                   :force-authorisations {(:authorization/id permit) permit}
                   :force-authorisations/consumed {})
      (some? position-id) (assoc-in [:held/positions position-id] amount)
      (some? position-id) (assoc-in [:held-ledger/index :by-position position-id] amount)
      (some? account) (assoc-in [:held-ledger/index :by-account account] amount))))

(defn- with-package-registered [f]
  (registry/register-package! manifest/package)
  (try (f)
       (finally (try (registry/unregister-package! manifest/package)
                     (catch Throwable _ nil)))))

(defn- override-context
  "Authoritative override context with the given requested scope and permits."
  [scope permits & [extra-opts]]
  (merge
   {:configuration-head (current-head)
    :extension-resolution (extension-resolution)
    :held-override/selection (historical-selection)
    :held-override/provider-root "sha256:held-custody-pkg"
    :held-override/capability-key override-key
    :held-override/capability-version 1
    :consumption-registry {}
    :now-ts 500
    :scope scope
    :permits permits}
   (or extra-opts {})))

(defn- run-egress [op reason permit context]
  (admission/admit-and-sub-held!
   (egress-world permit reason) token amount
   {:action "sub-held" :reason reason :extra extra}
   (merge context {:operation-id op})))

(defn- world-effects
  "The surface that must be unchanged on rejection."
  [permit reason]
  (select-keys (egress-world permit reason) [:held-adjustments :force-authorisations/consumed
                                             :total-held :held-override/lineage-root
                                             :held-override/bodies]))

(defn- assert-rejected [op reason permit context]
  (let [before (world-effects permit reason)
        threw? (atom false)
        ex (try (run-egress op reason permit context)
                (catch clojure.lang.ExceptionInfo e (reset! threw? true) e))]
    (is (true? @threw?) (str "rejected: " op))
    (is (= :held-custody/admission-rejected (:type (ex-data ex))))
    (is (= before (world-effects permit reason))
        "no mutation, no adjustment, no consumption, no lineage on rejection")))

(deftest ordinary-egress-ignores-incidental-fa
  (with-package-registered
    (fn []
      (doseq [op [:sew/ordinary-release :sew/ordinary-refund :sew/ordinary-settlement]]
        (let [permit (force-auth-permit "permit-ignored" release-reason)
              world' (run-egress op ordinary-reason permit {:permits [permit]})]
          (testing (str "ordinary " op " -> sub-held :out, FA ignored")
            (is (= 0 (get-in world' [:total-held token])) "full sub-held")
            (is (empty? (:force-authorisations/consumed world')))
            (is (not (contains? world' :held-override/lineage-root)))))))))

(deftest force-authorised-release-requires-exact-out-permit
  (with-package-registered
    (fn []
      (let [permit (force-auth-permit "permit-release" release-reason)
            context (override-context (scope-map "permit-release" release-reason)
                                      [permit])
            world' (run-egress :held-custody/force-authorised-release release-reason
                               permit context)]
        (is (= #{"permit-release"} (set (keys (:force-authorisations/consumed world')))))
        (is (= 0 (get-in world' [:total-held token])))
        (is (string? (:held-override/lineage-root world')))
        (is (true? (:audited? (publication/audit-from-successor world'))))))))

(deftest force-authorised-refund-requires-exact-out-permit
  (with-package-registered
    (fn []
      (let [permit (force-auth-permit "permit-refund" refund-reason)
            context (override-context (scope-map "permit-refund" refund-reason)
                                      [permit])
            world' (run-egress :held-custody/force-authorised-refund refund-reason
                               permit context)]
        (is (= #{"permit-refund"} (set (keys (:force-authorisations/consumed world')))))
        (is (= 0 (get-in world' [:total-held token])))
        (is (true? (:audited? (publication/audit-from-successor world'))))))))

(deftest adversarial-egress-rejections
  (with-package-registered
    (fn []
      (let [release-permit (force-auth-permit "p-release" release-reason)
            refund-permit (force-auth-permit "p-refund" refund-reason)
            in-permit (force-auth-permit "p-in" release-reason)
            release-scope (scope-map "p-release" release-reason)
            refund-scope (scope-map "p-refund" refund-reason)
            wrong-amount-scope (assoc release-scope :amount 99)
            wrong-account-scope (assoc release-scope :held/account :escrow-settlement)]
        (testing "release permit transplanted to a refund scope -> reject"
          (assert-rejected :held-custody/force-authorised-refund refund-reason
                           release-permit
                           (override-context refund-scope [release-permit])))
        (testing "refund permit transplanted to a release scope -> reject"
          (assert-rejected :held-custody/force-authorised-release release-reason
                           refund-permit
                           (override-context release-scope [refund-permit])))
        (testing ":in permit used for egress (:out release scope) -> reject"
          (assert-rejected :held-custody/force-authorised-release release-reason
                           in-permit
                           (override-context release-scope [in-permit])))
        (testing "wrong amount -> reject"
          (assert-rejected :held-custody/force-authorised-release release-reason
                           release-permit
                           (override-context wrong-amount-scope [release-permit])))
        (testing "wrong account -> reject"
          (assert-rejected :held-custody/force-authorised-release release-reason
                           release-permit
                           (override-context wrong-account-scope [release-permit])))
        (testing "consumed permit -> reject"
          (let [consumed (assoc release-permit :consumed? true)]
            (assert-rejected :held-custody/force-authorised-release release-reason
                             consumed
                             (override-context release-scope [consumed]))))
        (testing "expired/stale permit -> reject"
          (let [stale (assoc release-permit :expires-at 100)]
            (assert-rejected :held-custody/force-authorised-release release-reason
                             stale
                             (override-context release-scope [stale] {:now-ts 500}))))
        (testing "absent extension -> reject"
          (assert-rejected :held-custody/force-authorised-release release-reason
                           release-permit
                           (assoc (override-context release-scope [release-permit])
                                  :extension-resolution nil)))
        (testing "ambiguous (two usable) permits -> reject"
          (assert-rejected :held-custody/force-authorised-release release-reason
                           release-permit
                           (override-context release-scope
                                             [release-permit
                                              (assoc release-permit
                                                     :authorization/id "p-release-2")])))))))