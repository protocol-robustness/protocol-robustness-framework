(ns resolver-sim.benchmark.outcome-manifest-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.outcome-manifest :as om]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.benchmark.research-theorem-outcome :as rto]
            [resolver-sim.benchmark.research-conclusion :as rc]))

(defn- h
  "Produce a sha256: hash from a known hex pattern (characters 0-9, a-f only)."
  [pattern]
  (assert (re-matches #"[0-9a-f]+" pattern) (str "not hex: " pattern))
  (str "sha256:" (apply str (take 64 (cycle pattern)))))

(def base-manifest
  {:benchmark/content-root (h "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")
   :benchmark/model-root (h "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
   :benchmark/evaluation-policy-root (h "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
   :execution/model-instance-root (h "1111111111111111111111111111111111111111111111111111111111111111")
   :execution/plan-root (h "2222222222222222222222222222222222222222222222222222222222222222")
   :execution/status :completed
   :results/operational {:conservation :pass}})

(deftest build-manifest-minimal
  (let [manifest (om/build-manifest base-manifest)]
    (is (om/manifest-valid? manifest))
    (is (some? (:benchmark-outcome/hash manifest)))
    (is (some? (:benchmark/model-root manifest)))))

(deftest exact-replication-scope
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/realised-parameter-set-root (h "f")
                  :execution/generated-case-set-root (h "c")))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/realised-parameter-set-root (h "f")
                  :execution/generated-case-set-root (h "c")))]
    (is (om/exact-replication-scope? a b))))

(deftest incomplete-manifests-are-never-exact-replication
  (is (not (om/exact-replication-scope? {} {})))
  (is (not (om/exact-replication-scope?
           base-manifest
           (dissoc base-manifest :execution/plan-root)))))

(deftest not-exact-replication-different-domains
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d1")
                  :execution/generated-case-set-root (h "c")))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d2")
                  :execution/generated-case-set-root (h "c")))]
    (is (not (om/exact-replication-scope? a b)))))

(defn- command-for
  [includes]
  (command/build-command
   {:command/id :test/benchmark-evaluation
    :command/type :benchmark-evaluation
    :command/argv ["prf" "run-benchmark"]
    :command/include includes}))

(defn- command-bound-manifest
  [research-command & {:keys [incentive-root operational-root semantic-commitments]
                       :or {operational-root (h "0ead")}}]
  (om/build-manifest
   (cond-> (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/realised-parameter-set-root (h "f")
                  :execution/generated-case-set-root (h "c")
                  :execution/command-root (:command/hash research-command)
                  :outcomes/operational-root operational-root)
     incentive-root (assoc :outcomes/incentive-root incentive-root)
     semantic-commitments (assoc :evidence/semantic-commitments semantic-commitments))))

(deftest command-output-completeness-is-conditional-on-includes
  (let [operational-command (command-for [])
        incentive-command (command-for [:incentive])
        operational-manifest (command-bound-manifest operational-command)
        incomplete-incentive-manifest (command-bound-manifest incentive-command)
        complete-incentive-manifest (command-bound-manifest incentive-command
                                                           :incentive-root (h "1c"))]
    (is (:complete? (om/outcome-complete-for-command?
                     operational-command operational-manifest)))
    (is (not (:complete? (om/outcome-complete-for-command?
                          incentive-command incomplete-incentive-manifest))))
    (is (:complete? (om/outcome-complete-for-command?
                     incentive-command complete-incentive-manifest)))))

(deftest claim-scope-and-identical-outcome-are-distinct
  (let [command-a (command-for [:incentive])
        command-b (command-for [:incentive])
        command-without-incentive (command-for [])
        a (command-bound-manifest command-a
                                  :incentive-root (h "1c01")
                                  :semantic-commitments {:semantic/scope (h "aa")})
        b (command-bound-manifest command-b
                                  :incentive-root (h "1c02")
                                  :semantic-commitments {:semantic/scope (h "aa")})
        different-request (command-bound-manifest command-without-incentive
                                                  :semantic-commitments {:semantic/scope (h "aa")})]
    (is (om/exact-claim-scope? a command-a b command-b))
    (is (not (om/identical-outcome? a command-a b command-b)))
    (is (not (om/exact-claim-scope? a command-a
                                    different-request command-without-incentive)))))

(deftest sampling-comparison-scope
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/generated-case-set-root (h "c1")))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/generated-case-set-root (h "c2")))]
    (is (om/sampling-comparison-scope? a b))))

(deftest sampling-comparison-rejects-same-cases
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/generated-case-set-root (h "c")))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/generated-case-set-root (h "c")))]
    (is (not (om/sampling-comparison-scope? a b)))))

(deftest related-model-scope
  (let [a (om/build-manifest (assoc base-manifest :benchmark/model-root (h "a")))
        b (om/build-manifest (assoc base-manifest :benchmark/model-root (h "a")
                                    :execution/parameter-domain-root (h "dead")))]
    (is (om/related-model-scope? a b))))

(deftest not-related-model-scope
  (let [a (om/build-manifest (assoc base-manifest :benchmark/model-root (h "a0")))
        b (om/build-manifest (assoc base-manifest :benchmark/model-root (h "b0")))]
    (is (not (om/related-model-scope? a b)))))

(deftest compatible-outcomes-exact
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/realised-parameter-set-root (h "f")
                  :execution/generated-case-set-root (h "c")))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/realised-parameter-set-root (h "f")
                  :execution/generated-case-set-root (h "c")))]
    (is (= :exact-replication (om/classify-outcome-compatibility a b)))))

(deftest compatible-outcomes-sampling
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/generated-case-set-root (h "c1")))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/generated-case-set-root (h "c2")))]
    (is (= :independent-sampling (om/classify-outcome-compatibility a b)))))

(deftest compatible-outcomes-model-corroboration
  (let [a (om/build-manifest
           (assoc base-manifest
                  :benchmark/model-root (h "a")
                  :execution/parameter-domain-root (h "d1")
                  :execution/realised-parameter-set-root (h "f1")
                  :execution/generated-case-set-root (h "c1")))
        b (om/build-manifest
           (assoc base-manifest
                  :benchmark/model-root (h "a")
                  :execution/parameter-domain-root (h "d2")
                  :execution/realised-parameter-set-root (h "f2")
                  :execution/generated-case-set-root (h "c2")))]
    (is (= :model-corroboration (om/classify-outcome-compatibility a b)))))

(deftest compatible-outcomes-incompatible
  (let [a (om/build-manifest
           (assoc base-manifest
                  :benchmark/content-root (h "ca")
                  :benchmark/model-root (h "a0")))
        b (om/build-manifest
           (assoc base-manifest
                  :benchmark/content-root (h "cb")
                  :benchmark/model-root (h "b0")))]
    (is (= :incompatible-scope (om/classify-outcome-compatibility a b)))))

(deftest compatibility-symmetric
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/generated-case-set-root (h "c1")))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/generated-case-set-root (h "c2")))]
    (is (= (om/classify-outcome-compatibility a b) (om/classify-outcome-compatibility b a)))))

(deftest compatibility-symmetric-exact
  (let [a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/realised-parameter-set-root (h "f")
                  :execution/generated-case-set-root (h "c")))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/realised-parameter-set-root (h "f")
                  :execution/generated-case-set-root (h "c")))]
    (is (= (om/classify-outcome-compatibility a b) (om/classify-outcome-compatibility b a)))))

;; ── Hierarchical outcome manifest ─────────────────────────────────────────

(def ^:const sample-theorem-params
  {:theorem/id :theorem/quota-bounded
   :theorem/type :boundedness
   :theorem/statement
   {:if {:claim :partial-fill-calculated}
    :then {:claim :quota-bounded}}
   :theorem/scope {:model/root (h "a")}
   :theorem/conclusion {:status :established :claim-id :claim/quota-bounded}})

(def ^:const sample-conclusion-params
  {:conclusion/id :conclusion/partial-fill-correctness
   :conclusion/premise {:x "Allocations bounded by quota, state written back."}
   :conclusion/result {:y "Partial-fill preserves authoritative state."}})

(deftest build-manifest-with-hierarchical-outcomes
  (let [t1 (rto/build-theorem-outcome sample-theorem-params)
        t2 (rto/build-theorem-outcome
            (assoc sample-theorem-params
                   :theorem/id :theorem/current-amount-continuity
                   :theorem/type :state-transition))
        c1 (rc/build-conclusion sample-conclusion-params)
        manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/command-root (h "c0d")
                         :outcomes/operational-root (h "0ead")
                         :outcomes/incentive-root (h "1c")
                         :outcomes/incentive-compatibility-root (h "1c")
                         :outcomes/theorems
                         [{:theorem/id :theorem/quota-bounded
                           :theorem/hash (:theorem/hash t1)
                           :status :established}
                          {:theorem/id :theorem/current-amount-continuity
                           :theorem/hash (:theorem/hash t2)
                           :status :established}]
                         :outcomes/conclusions
                         [{:conclusion/id :conclusion/partial-fill-correctness
                           :conclusion/hash (:conclusion/hash c1)}]))]
    (is (om/manifest-valid? manifest))
    (is (some? (:benchmark-outcome/hash manifest)))
    (is (= (h "c0d") (:execution/command-root manifest)))
    (is (contains? manifest :outcomes/operational-root))
    (is (contains? manifest :outcomes/incentive-root))
    (is (contains? manifest :outcomes/incentive-compatibility-root))
    (is (= 2 (count (:outcomes/theorems manifest))))
    (is (= 1 (count (:outcomes/conclusions manifest))))
    (is (contains? manifest :outcome-hashes))
    (is (some? (get-in manifest [:outcome-hashes :theorem-root])))
    (is (some? (get-in manifest [:outcome-hashes :conclusion-root])))))

(deftest build-manifest-without-hierarchical-is-backward-compatible
  (let [manifest (om/build-manifest base-manifest)]
    (is (om/manifest-valid? manifest))
    (is (not (contains? manifest :outcomes/operational-root)))
    (is (not (contains? manifest :outcome-hashes)))
    (is (not (contains? manifest :outcomes/theorems)))))

(deftest hierarchical-manifest-compares-with-exact-replication
  (let [t1 (rto/build-theorem-outcome sample-theorem-params)
        a (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/realised-parameter-set-root (h "f")
                  :execution/generated-case-set-root (h "c")
                  :outcomes/theorems
                  [{:theorem/id :theorem/quota-bounded
                    :theorem/hash (:theorem/hash t1)
                    :status :established}]))
        b (om/build-manifest
           (assoc base-manifest
                  :execution/parameter-domain-root (h "d")
                  :execution/sampling-policy-root (h "c0")
                  :execution/realised-parameter-set-root (h "f")
                  :execution/generated-case-set-root (h "c")
                  :outcomes/theorems
                  [{:theorem/id :theorem/quota-bounded
                    :theorem/hash (:theorem/hash t1)
                    :status :established}]))]
    (is (om/exact-replication-scope? a b))
    (is (om/compatible-outcomes? a b))))

;; ── Hash projection: root changes alter the singular outcome hash ────────

(deftest command-root-change-alters-outcome-hash
  (let [base (assoc base-manifest
                    :execution/command-root (h "aaaa")
                    :outcomes/operational-root (h "0ead")
                    :outcomes/incentive-root (h "1c")
                    :outcomes/incentive-compatibility-root (h "1c"))
        a (om/build-manifest base)
        b (om/build-manifest (assoc base :execution/command-root (h "bbbb")))]
    (is (not= (:benchmark-outcome/hash a) (:benchmark-outcome/hash b)))))

(deftest incentive-compatibility-root-change-alters-outcome-hash
  (let [base (assoc base-manifest
                    :execution/command-root (h "c0d")
                    :outcomes/operational-root (h "0ead")
                    :outcomes/incentive-root (h "1c")
                    :outcomes/incentive-compatibility-root (h "1c1"))
        a (om/build-manifest base)
        b (om/build-manifest (assoc base :outcomes/incentive-compatibility-root (h "1c2")))]
    (is (not= (:benchmark-outcome/hash a) (:benchmark-outcome/hash b)))))

(deftest operational-root-change-alters-outcome-hash
  (let [base (assoc base-manifest
                    :execution/command-root (h "c0d")
                    :outcomes/operational-root (h "f001")
                    :outcomes/incentive-root (h "1c")
                    :outcomes/incentive-compatibility-root (h "1c"))
        a (om/build-manifest base)
        b (om/build-manifest (assoc base :outcomes/operational-root (h "f002")))]
    (is (not= (:benchmark-outcome/hash a) (:benchmark-outcome/hash b)))))

(deftest incentive-root-change-alters-outcome-hash
  (let [base (assoc base-manifest
                    :execution/command-root (h "c0d")
                    :outcomes/operational-root (h "0ead")
                    :outcomes/incentive-root (h "1c01")
                    :outcomes/incentive-compatibility-root (h "1c"))
        a (om/build-manifest base)
        b (om/build-manifest (assoc base :outcomes/incentive-root (h "1c02")))]
    (is (not= (:benchmark-outcome/hash a) (:benchmark-outcome/hash b)))))

(deftest incentive-roots-ungated-from-operational
  (let [without-oper (om/build-manifest
                      (assoc base-manifest
                             :outcomes/incentive-root (h "1c")
                             :outcomes/incentive-compatibility-root (h "1c")))
        with-oper (om/build-manifest
                   (assoc base-manifest
                          :outcomes/operational-root (h "0ead")
                          :outcomes/incentive-root (h "1c")
                          :outcomes/incentive-compatibility-root (h "1c")))]
    (is (contains? without-oper :outcomes/incentive-root)
        "incentive-root present without operational-root")
    (is (contains? without-oper :outcomes/incentive-compatibility-root)
        "incentive-compatibility-root present without operational-root")
    (is (contains? without-oper :outcome-hashes)
        "outcome-hashes present from incentive roots alone")
    (is (not= (:benchmark-outcome/hash without-oper)
              (:benchmark-outcome/hash with-oper))
        "adding operational-root changes the hash")))

;; ── Derived outcome-hashes integrity ─────────────────────────────────────

(deftest mismatched-outcome-hashes-rejected
  (let [manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/command-root (h "c0d")
                         :outcomes/operational-root (h "0ead")))
        tampered (assoc manifest :outcome-hashes
                        (assoc (:outcome-hashes manifest)
                               :command-root (h "badd")))]
    (is (not (:valid? (om/validate-manifest tampered))))
    (let [errors (:errors (om/validate-manifest tampered))]
      (is (some #(re-find #"outcome-hashes mismatch" %) errors)))))

(deftest omitted-outcome-hashes-entry-rejected
  (let [manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/command-root (h "c0d")
                         :outcomes/operational-root (h "0ead")))
        truncated (update manifest :outcome-hashes dissoc :command-root)]
    (is (not (:valid? (om/validate-manifest truncated))))
    (let [errors (:errors (om/validate-manifest truncated))]
      (is (some #(re-find #"outcome-hashes mismatch" %) errors)))))

;; ── Unknown field rejection ─────────────────────────────────────────────

(deftest unknown-manifest-field-rejected
  (let [manifest (om/build-manifest base-manifest)
        polluted (assoc manifest :outcomes/unknown-root (h "dead"))]
    (is (not (:valid? (om/validate-manifest polluted))))
    (let [errors (:errors (om/validate-manifest polluted))]
      (is (some #(re-find #"unknown manifest key" %) errors)))))

(deftest unknown-noncanonical-embedded-field-rejected
  (let [manifest (om/build-manifest base-manifest)
        polluted (assoc manifest :execution/extra-metadata {:foo "bar"})]
    (is (not (:valid? (om/validate-manifest polluted))))
    (let [errors (:errors (om/validate-manifest polluted))]
      (is (some #(re-find #"unknown manifest key" %) errors)))))

;; ── Pre-application checks ──────────────────────────────────────────────

(deftest pre-application-checks-accepts-complete-manifest
  (let [t1 (rto/build-theorem-outcome sample-theorem-params)
        c1 (rc/build-conclusion sample-conclusion-params)
        manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/parameter-domain-root (h "d")
                         :execution/sampling-policy-root (h "c0")
                         :execution/realised-parameter-set-root (h "f")
                         :execution/generated-case-set-root (h "c")
                         :execution/command-root (h "c0d")
                         :outcomes/operational-root (h "0ead")
                         :outcomes/incentive-root (h "1c")
                         :outcomes/incentive-compatibility-root (h "1c")
                         :outcomes/theorems
                         [{:theorem/id :theorem/quota-bounded
                           :theorem/hash (:theorem/hash t1)
                           :status :established}]
                         :outcomes/conclusions
                         [{:conclusion/id :conclusion/partial-fill-correctness
                           :conclusion/hash (:conclusion/hash c1)}]))
        result (om/pre-application-checks manifest)]
    (is (:pre-application-valid? result)
        (str "complete manifest should pass pre-application checks, errors: "
             (:errors result)))))

(deftest pre-application-checks-rejects-missing-content-root
  (let [manifest (om/build-manifest (dissoc base-manifest :benchmark/content-root))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"content-root" %) (:errors result)))))

(deftest pre-application-checks-rejects-missing-model-root
  (let [manifest (om/build-manifest (dissoc base-manifest :benchmark/model-root))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"model-root" %) (:errors result)))))

(deftest pre-application-checks-rejects-invalid-hash-root
  (let [manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/command-root "not-a-valid-hash"))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"not a valid sha256" %) (:errors result)))))

(deftest pre-application-checks-detects-outcome-hashes-mismatch
  (let [manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/command-root (h "c0d")))
        tampered (assoc manifest :outcome-hashes
                        {:command-root (h "badd")})
        result (om/pre-application-checks tampered)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"outcome-hashes mismatch" %) (:errors result)))))

(deftest pre-application-checks-rejects-invalid-model-root-hash
  (let [manifest (om/build-manifest
                  (assoc base-manifest :benchmark/model-root "not-a-valid-hash"))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"model-root is not a valid sha256" %) (:errors result)))))

(deftest pre-application-checks-rejects-invalid-execution-root-hash
  (let [manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/parameter-domain-root "not-a-valid-hash"))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"parameter-domain-root is not a valid sha256" %)
              (:errors result)))))

(deftest gates-agree-on-valid-manifest
  (let [manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/parameter-domain-root (h "d")
                         :execution/sampling-policy-root (h "c0")
                         :execution/generated-case-set-root (h "c")))]
    (is (:pre-application-valid? (om/pre-application-checks manifest)))
    (is (:valid? (om/validate-manifest manifest))
        "pre-application-checks and validate-manifest must agree on a valid manifest")))

(deftest gates-agree-on-invalid-model-root-hash
  (let [manifest (om/build-manifest
                  (assoc base-manifest :benchmark/model-root "sha256:short"))]
    (is (not (:pre-application-valid? (om/pre-application-checks manifest))))
    (is (not (:valid? (om/validate-manifest manifest)))
        "pre-application-checks and validate-manifest must agree on an invalid model root")))

(deftest pre-application-checks-accepts-valid-force-authorisation
  (let [manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/parameter-domain-root (h "d")
                         :execution/sampling-policy-root (h "c0")
                         :execution/generated-case-set-root (h "c")
                         :execution/force-authorisation
                         {:authorisation-hash (h "aa")
                          :reservation-hash (h "bb")
                          :consumption-key (h "cc")
                          :execution-attempt-id :attempt-1
                          :branch-descriptor-hash (h "dd")
                          :baseline-content-root (h "ee")
                          :executed-content-root (h "ff")
                          :status :consumed}))
        result (om/pre-application-checks manifest)]
    (is (:pre-application-valid? result)
        (str "valid FA section should pass, errors: " (:errors result)))))

(deftest pre-application-checks-rejects-invalid-force-authorisation-hash
  (let [manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/parameter-domain-root (h "d")
                         :execution/sampling-policy-root (h "c0")
                         :execution/generated-case-set-root (h "c")
                         :execution/force-authorisation
                         {:authorisation-hash "not-a-valid-hash"
                          :reservation-hash (h "bb")
                          :consumption-key (h "cc")
                          :execution-attempt-id :attempt-1
                          :status :consumed}))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"force-authorisation\.authorisation-hash" %) (:errors result)))))

(deftest pre-application-checks-rejects-incomplete-force-authorisation
  (let [manifest (om/build-manifest
                  (assoc base-manifest
                         :execution/parameter-domain-root (h "d")
                         :execution/sampling-policy-root (h "c0")
                         :execution/generated-case-set-root (h "c")
                         :execution/force-authorisation
                         {:authorisation-hash (h "aa")}))
        result (om/pre-application-checks manifest)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"force-authorisation missing" %) (:errors result)))))

(deftest pre-application-checks-rejects-invalid-theorem-hash
  (let [t1 (rto/build-theorem-outcome sample-theorem-params)
        manifest (om/build-manifest
                  (assoc base-manifest
                         :outcomes/theorems
                         [{:theorem/id :theorem/quota-bounded
                           :theorem/hash (:theorem/hash t1)
                           :status :established}]))
        tampered (assoc manifest :outcomes/theorems
                        [{:theorem/id :theorem/quota-bounded
                          :theorem/hash "not-a-valid-hash"
                          :status :established}])
        result (om/pre-application-checks tampered)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"invalid :theorem/hash" %) (:errors result)))))

(deftest pre-application-checks-rejects-invalid-conclusion-hash
  (let [c1 (rc/build-conclusion sample-conclusion-params)
        manifest (om/build-manifest
                  (assoc base-manifest
                         :outcomes/conclusions
                         [{:conclusion/id :conclusion/partial-fill-correctness
                           :conclusion/hash (:conclusion/hash c1)}]))
        tampered (assoc manifest :outcomes/conclusions
                        [{:conclusion/id :conclusion/partial-fill-correctness
                          :conclusion/hash "not-a-valid-hash"}])
        result (om/pre-application-checks tampered)]
    (is (not (:pre-application-valid? result)))
    (is (some #(re-find #"invalid :conclusion/hash" %) (:errors result)))))
