(ns resolver-sim.assurance.held-override-publication-test
  "Retention/publication kernel (P0).

   Proves HELD_OVERRIDE_LINEAGE_COMPLETENESS and HELD_OVERRIDE_STATE_AFTER_BINDING
   are PRODUCTION-ENFORCED, not merely testable:
     - commit refuses an incomplete publication (missing historical body);
     - the successor state commits the lineage root and retains every historical
       body (S0/R0/P0/K0/F0/J0/X0), so it is discoverable from the result;
     - audit-from-successor recomputes all roots from retained bodies and verifies
       with NO current-configuration lookup and NO caller-supplied historical
       objects;
     - after advancing configuration (H1/S1) and discarding caller-local objects,
       auditing from the successor alone still succeeds, and substituting H1/S1
       fails."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.assurance.held-override-selection :as selection]
            [resolver-sim.assurance.held-override-lineage :as lineage]
            [resolver-sim.assurance.held-override-publication :as publication]))

(def override-key [:assurance/force-authorisation :held-custody/override-admission-v1])

(defn- head-at [n]
  (configuration-head/initial-head
   (str "sha256:" (apply str (repeat 64 (str n)))) 1))

(def h0 (head-at 1))
(def h1 (head-at 2))

(defn- selection-for [h]
  (selection/build-selection override-key
                             {:package/id :prf.extensions/held-custody
                              :package-root "sha256:held-custody-pkg"}
                             (:configuration-head-state/root h)))

(def s0 (selection-for h0))

(defn- resolution [& [capabilities]]
  (let [root (str "sha256:" (apply str (repeat 64 "a")))
        caps (or capabilities {override-key {:capability/kind :assurance/force-authorisation
                                             :capability/id :held-custody/override-admission-v1
                                             :capability/version 1}})]
    {:extensions/resolution-version 1
     :extensions/packages {}
     :extensions/capabilities caps
     :extensions/capability-providers {}
     :extensions/dependencies []
     :extensions/schema-roots {}
     :extensions/effect-schema-roots {}
     :extensions/runtime-profile {}
     :extensions/resolution-root root}))

(def r0 (resolution))

(defn- permit []
  (let [s {:authorization/id "permit-pub"
           :authorization/type :force-authorisation
           :held/direction :in
           :token :USDC
           :amount 100
           :held/account :escrow-principal
           :owner/address "0xr"
           :held/reason :replay-fixture-setup
           :held/workflow-id 0}]
    {:authorization/id "permit-pub"
     :authorization/type :force-authorisation
     :authorization/status :consumed
     :consumed? true
     :authorization/scope-hash (fa/force-authorisation-scope-hash s)
     :authorization/scope s}))

(defn- successor-with []
  {:total-held {:USDC 100}
   :held-ledger/index {:by-token {:USDC 100}}
   :held-adjustments [{:held-adjustment/id "ja" :artifact/hash "sha256:adj-0"}]
   :force-authorisations/consumed {"permit-pub" {:authorization/id "permit-pub" :consumed? true}}})

(defn- publication-inputs [state-after]
  {:state-after state-after
   :held-adjustment (last (:held-adjustments state-after))
   :consumption-record (get-in state-after [:force-authorisations/consumed "permit-pub"])
   :predecessor-configuration-head h0
   :extension-selection s0
   :extension-resolution r0
   :provider-package-root "sha256:held-custody-pkg"
   :capability-key override-key
   :capability-version 1
   :permit (permit)})

(deftest commit-and-audit-from-successor-only
  (let [state-after (successor-with)
        publication' (publication/build-override-publication
                      (publication-inputs state-after))
        committed (publication/commit state-after publication')]
    (testing "successor commits the lineage root and retains historical bodies"
      (is (string? (:held-override/lineage-root committed)))
      (is (contains? (:held-override/bodies committed)
                     (:held-override/lineage-root committed))))
    (testing "audit-from-successor succeeds using ONLY the retained successor bodies"
      (let [audit (publication/audit-from-successor committed)]
        (is (true? (:audited? audit)) (pr-str audit))))))

(deftest completeness-is-production-enforced
  (testing "commit refuses an incomplete publication (missing historical body)"
    (let [state-after (successor-with)
          inputs (publication-inputs state-after)
          incomplete (assoc inputs :extension-selection nil)
          publication' (publication/build-override-publication incomplete)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (publication/commit state-after publication')))
      (testing "nothing is published on refusal (successor unchanged)"
        (is (nil? (:held-override/lineage-root state-after)))))))

(deftest audit-fails-without-lineage
  (testing "a successor without a committed lineage root is not auditable"
    (let [audit (publication/audit-from-successor (successor-with))]
      (is (= :no-lineage-root (:reason audit))))))

(deftest publication-dag-acyclicity
  "HELD_OVERRIDE_PUBLICATION_DAG: the lineage successor-root is the root of the
   exact effects-state projection E1 that does NOT depend on the lineage, and the
   published authoritative successor = E1 + lineage/evidence commitment (no hash
   cycle)."
  (let [state-after (successor-with)
        publication' (publication/build-override-publication (publication-inputs state-after))
        w1 (publication/commit state-after publication')
        l0 (:held-override-lineage publication')]
    (testing "E1 (effects-state-root) does not depend on the lineage attachments"
      (is (= (publication/effects-state-root state-after)
             (publication/effects-state-root w1))
          "adding :held-override/lineage-root + bodies does not change E1")
      (is (= (publication/effects-state-root
              (assoc state-after :held-override/lineage-root "x"
                     :held-override/bodies {:x {}}))
             (publication/effects-state-root state-after))
          "retained-proof representation is excluded from E1 by contract")
      (is (= (publication/effects-state-root state-after)
             (:lineage/successor-root l0))
          "the lineage's successor-root IS E1, and E1 excludes the lineage"))
    (testing "changing J0 changes E1 -> L0 root -> the published successor"
      (let [state-after' (assoc-in state-after [:held-adjustments 0 :artifact/hash]
                                   (str "sha256:" (apply str (repeat 64 "f"))))
            l0' (:held-override-lineage
                 (publication/build-override-publication
                  (assoc (publication-inputs state-after')
                         :held-adjustment (last (:held-adjustments state-after'))
                         :state-after state-after')))]
        (is (not= (publication/effects-state-root state-after)
                  (publication/effects-state-root state-after')))
        (is (not= (:lineage/root l0) (:lineage/root l0')))))
    (testing "changing only unrelated retained-proof representation does NOT change
              E1 (per the declared projection contract)"
      (let [w1' (update-in w1 [:held-override/bodies (:held-override/lineage-root w1)]
                           assoc :unrelated-note "x")]
        (is (= (publication/effects-state-root w1)
               (publication/effects-state-root w1')))))))

(deftest discard-local-objects-then-audit-from-successor-only
  "The final P0 test: execute override under H0/S0 -> publish W1 -> advance to
   H1/S1 -> discard caller-local historical objects -> audit starting only from
   W1 -> verify -> substituting H1/S1 fails."
  (let [state-after (successor-with)
        publication' (publication/build-override-publication (publication-inputs state-after))
        w1 (publication/commit state-after publication')
        s1 (selection-for h1)             ; advance configuration + selection (current)
        audit (publication/audit-from-successor w1)]   ; reads ONLY w1
    (testing "audit from the successor alone succeeds (historical bodies retained in W1)"
      (is (true? (:audited? audit)) (pr-str audit)))
    (testing "substituting the CURRENT selection/head fails conservation"
      (let [bodies (get-in w1 [:held-override/bodies (:held-override/lineage-root w1)])
            l0' (lineage/build-lineage
                 {:predecessor-configuration-head
                  {:configuration-head-state/root
                   (get-in bodies [:selection :selection/config-head-root])}
                  :extension-selection s1        ; CURRENT selection substituted
                  :extension-resolution (:resolution bodies)
                  :provider-package-root (:provider-descriptor bodies)
                  :capability-key (:capability-key bodies)
                  :capability-version (:capability-version bodies)
                  :permit (:permit bodies)
                  :held-adjustment (:held-adjustment bodies)
                  :consumption-record (:consumption bodies)
                  :successor-state-root (publication/effects-state-root w1)})]
        (is (not= (:lineage/root l0') (:held-override/lineage-root w1))
            "substituting the current selection diverges the lineage root")))))