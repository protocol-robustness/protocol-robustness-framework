(ns prf.extensions.held-custody.legacy-add-held-test
  "PERMANENT historical compatibility suite for the held-custody extension.

   After Phase 3B the historical read contract is extension-owned (see
   :extension/historical-read on the package manifest): the frozen
   force-auth-add-held v1/v2 and summary v1/v2 verifiers live in
   prf.extensions.held-custody.legacy-validate, and historical PRODUCTION is
   forbidden. This suite proves historical artifacts remain verifiable after
   the legacy core namespace was removed — it reads ONLY the pinned
   historical_artifacts.edn fixture and the extension validators, and never
   references the deleted legacy namespace or any legacy producer.

   The temporary old==new equivalence suite (legacy-validator-equivalence-test)
   is a Phase 3B migration proof and is deleted; this suite is permanent."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [prf.extensions.held-custody.mutation :as mut]
            [prf.extensions.held-custody.legacy-add-held :as legacy]
            [prf.extensions.held-custody.legacy-validate :as lv]))

(defn- load-fixture []
  (edn/read-string
   (slurp (io/resource "prf/extensions/held_custody/historical_artifacts.edn"))))

(def fixture
  (delay (load-fixture)))

(defn- scope [id dir amt]
  {:authorization/id id
   :authorization/type :force-authorisation
   :held/direction dir
   :token "USDC"
   :amount amt
   :held/account :escrow-principal
   :owner/address "0xrecipient"
   :held/reason :force-authorised-release
   :held/workflow-id 0})

(defn- auth [id dir amt]
  (let [s (scope id dir amt)]
    {:authorization/id id
     :authorization/status :active
     :authorization/type :force-authorisation
     :authorization/scope-hash (fa/force-authorisation-scope-hash
                                (fa/normalize-force-authorisation-scope s))
     :authorization/scope (fa/normalize-force-authorisation-scope s)
     :starts-at 0
     :expires-at 1000}))

(defn- new-mutation []
  (mut/build-force-auth-held-mutation
   (auth "fa-0" :out 100)
   {:mutation/id "adj-1"
    :held/action :finalize-released
    :held/direction :out
    :held/amount 100
    :held/token "USDC"
    :held/account :escrow-principal
    :owner/address "0xrecipient"
    :held/reason :force-authorised-release
    :held/workflow-id 0}
   {}))

;; ── pinned historical artifacts remain verifiable ─────────────────────────

(deftest pinned-historical-artifacts-verify
  (testing "pinned v1 member verifies under the extension historical validator"
    (let [v1 (:v1-member (:members @fixture))]
      (is (true? (lv/valid-force-auth-add-held? v1)))
      (is (true? (lv/exact-force-auth-add-held? v1)))))
  (testing "pinned v2 members verify (add and sub directions)"
    (let [v2 (:v2-member (:members @fixture))
          sub (:v2-member-sub (:members @fixture))]
      (is (true? (lv/valid-force-auth-add-held-v2? v2)))
      (is (true? (lv/force-auth-add-held-scope-verifies? v2)))
      (is (true? (lv/valid-force-auth-add-held-v2? sub)))
      (is (= :out (:held/direction sub)))))
  (testing "pinned v1/v2 summaries verify, and the v1 relabel verifies ONLY
            through the migration reader (never the exact v1 reader)"
    (let [s1 (:v1-summary (:summaries @fixture))
          s2 (:v2-summary (:summaries @fixture))
          relabel (:v1-relabel (:summaries @fixture))]
      (is (true? (lv/valid-force-auth-add-held-summary-v1? s1)))
      (is (true? (lv/valid-force-auth-add-held-summary? s2)))
      (is (true? (lv/valid-force-auth-add-held-summary-v1-migration? relabel)))
      (is (false? (lv/valid-force-auth-add-held-summary-v1? relabel))))))

(deftest pinned-summaries-reconcile-with-their-members
  (let [ms (:summary-members @fixture)
        s1 (:v1-summary (:summaries @fixture))
        s2 (:v2-summary (:summaries @fixture))]
    (testing "check-aggregate passes on the pinned member sets"
      (is (true? (:valid? (lv/check-aggregate s1 ms {}))))
      (is (true? (:valid? (lv/check-aggregate s2 ms {:summary-version :v2})))))
    (testing "deterministic recomputation is byte-stable"
      (is (= (:artifact/hash s1)
             (:artifact/hash (lv/recompute-force-auth-add-held-summary ms {}))))
      (is (= (:artifact/hash s2)
             (:artifact/hash (lv/recompute-force-auth-add-held-summary ms {:summary-version :v2})))))))

;; ── pinned rejection vectors fail closed ──────────────────────────────────

(deftest pinned-member-rejections-fail-closed
  (doseq [[label a] (:member-rejections @fixture)]
    (testing (str label " is rejected by the extension member validators")
      (is (false? (lv/valid-force-auth-add-held? a)))
      (is (false? (lv/valid-force-auth-add-held-v2? a)))
      (is (false? (lv/exact-force-auth-add-held? a))))))

(deftest pinned-summary-rejections-fail-closed
  (doseq [[label a] (:summary-rejections @fixture)]
    (testing (str label " is rejected by the extension summary validators")
      (is (false? (lv/valid-force-auth-add-held-summary? a)))
      (is (false? (lv/valid-force-auth-add-held-summary-v1? a)))
      (is (false? (lv/valid-force-auth-add-held-summary-v1-migration? a))))))

(deftest unknown-versions-and-non-artifacts-fail-closed
  (is (false? (lv/valid-force-auth-add-held? {:schema-version "force-auth-add-held.v9"})))
  (is (false? (lv/valid-force-auth-add-held-v2? {:schema-version "force-auth-add-held.v9"})))
  (is (false? (lv/valid-force-auth-add-held-summary? "nope")))
  (is (false? (lv/valid-force-auth-add-held-summary-v1-migration? nil)))
  (is (false? (lv/exact-force-auth-add-held? 42)))
  (is (false? (lv/valid-force-auth-add-held? nil))))

;; ── classification and projection (extension-owned reader) ────────────────

(deftest legacy-assurance-classifications
  (testing "pinned v1 is direction-unbound: direction/scope is not independently committed"
    (let [v1 (:v1-member (:members @fixture))]
      (is (= :legacy-direction-unbound (legacy/classify-legacy-add-held v1)))))
  (testing "pinned v2 is direction-bound: direction is committed via the body and
            the scope projection and the v2 validator cross-checks them; the action
            string is committed but NOT action↔direction bound"
    (let [v2 (:v2-member (:members @fixture))]
      (is (lv/valid-force-auth-add-held-v2? v2))
      (is (= :legacy-direction-bound (legacy/classify-legacy-add-held v2)))))
  (testing "the current mutation artifact is action-and-direction-bound"
    (is (= :action-and-direction-bound (legacy/classify-legacy-add-held (new-mutation)))))
  (testing "non-artifacts / invalid are not classified as legacy held-custody"
    (is (= :not-force-auth-add-held (legacy/classify-legacy-add-held nil)))
    (is (= :not-force-auth-add-held
           (legacy/classify-legacy-add-held {:schema-version "nonsense"})))))

(deftest v2-direction-binding-cannot-be-broken
  (testing "changing the direction breaks the artifact under its own contract"
    (let [v2 (:v2-member (:members @fixture))
          tampered (assoc v2 :held/direction :out)]
      (is (not (lv/valid-force-auth-add-held-v2? tampered)))
      (is (= :legacy-direction-unbound (legacy/classify-legacy-add-held tampered))))))

(deftest validates-under-original-contract
  (is (:valid? (legacy/validate-legacy-add-held (:v1-member (:members @fixture)))))
  (is (:valid? (legacy/validate-legacy-add-held (:v2-member (:members @fixture)))))
  (is (= :legacy-direction-unbound
         (:classification (legacy/validate-legacy-add-held (:v1-member (:members @fixture))))))
  (is (= :legacy-direction-bound
         (:classification (legacy/validate-legacy-add-held (:v2-member (:members @fixture))))))
  (is (not (:valid? (legacy/validate-legacy-add-held {:schema-version "nonsense"})))))

(deftest projects-to-in-memory-mutation-without-rewriting
  (let [v1 (:v1-member (:members @fixture))
        v2 (:v2-member (:members @fixture))
        p1 (legacy/project-legacy-add-held v1)
        p2 (legacy/project-legacy-add-held v2)]
    (testing "the original artifact hash is preserved"
      (is (= (:artifact/hash v1) (:artifact/hash (:v1-member (:members @fixture)))))
      (is (= (:artifact/hash v2) (:artifact/hash (:v2-member (:members @fixture))))))
    (testing "in-memory projection uses canonical mutation vocabulary"
      (is (= "adj-1" (:mutation/id p1)))
      (is (= "adj-2" (:mutation/id p2)))
      (is (= "0xrecipient" (:owner/address p1)))
      (is (= :in (:held/direction p2))))
    (testing "v1 does not claim direction/scope assurance it cannot provide"
      (is (false? (:legacy/scope-committed? p1)))
      (is (false? (:legacy/action-bound? p1))))
    (testing "v2 carries the committed projection and marks scope committed"
      (is (true? (:legacy/scope-committed? p2)))
      (is (true? (:legacy/action-bound? p2)))
      (is (map? (:authorization-scope/projection p2))))
    (testing "the projection never rewrites the original artifact hash"
      (is (= (:artifact/hash v2)
             (:artifact/hash (:v2-member (:members @fixture))))))))

(deftest legacy-total-is-gross-flow-warning
  (is (= {:reason :legacy-total-is-gross-flow
          :field :total-amount
          :gross-inflow 100
          :gross-outflow 40
          :gross-flow 140
          :net-change 60}
         (legacy/legacy-total-is-gross-flow-warning 100 40))))
