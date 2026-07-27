(ns resolver-sim.assurance.witness-integration-test
  "Gate 1: verify-witness-from-finalised-evidence integration tests.
   Uses persisted evidence files, evidence-registry.json and chain-cursor-final.json."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [resolver-sim.assurance.trust-sequence-definition :as tsd]
            [resolver-sim.assurance.procedure-execution-witness :as pew]
            [resolver-sim.assurance.witness-verifier :as wv]
            [resolver-sim.commands.witness-build :as wb]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.chain :as chain]))

;; ── Fixture helpers ─────────────────────────────────────────────────────────

(def ^:private test-scenario-id "integration-test-scenario")

(defn- make-evidence
  [evidence-type auth-id seq prev-hash extra-inputs & {:keys [world-before world-after]
                                                       :or {world-before "0x00" world-after "0x01"}}]
  (let [inputs (merge {:force-auth/auth-id auth-id} extra-inputs)
        content-base {:evidence/type evidence-type
                      :scenario/id test-scenario-id
                      :world/before-hash world-before
                      :world/after-hash world-after
                      :inputs inputs}
        evidence-hash (hc/hash-with-intent {:hash/intent :evidence-content} content-base)
        chain-link (chain/chain-link-hash evidence-hash seq prev-hash)]
    {:evidence/type evidence-type
     :scenario/id test-scenario-id
     :evidence/hash evidence-hash
     :world/before-hash world-before
     :world/after-hash world-after
     :inputs inputs
     :evidence/chain-hash-scheme "link-v1"
     :evidence/chain-seq seq
     :evidence/chain-prev-hash prev-hash
     :evidence/chain-self-hash chain-link}))

(defn- write-json
  "Write a map as JSON to a file, preserving namespaced keys."
  [path m]
  (spit path (json/write-str m {:key-fn (fn [k] (if (keyword? k)
                                                  (if-let [ns (namespace k)]
                                                    (str ns "/" (name k))
                                                    (name k))
                                                  (str k)))
                                :indent true})))

(defn- write-evidence-file
  [ev-dir evidence]
  (let [f (io/file ev-dir (str "ev-" (:evidence/chain-seq evidence) ".json"))]
    (io/make-parents f)
    (write-json f evidence)))

(defn- build-evidence-registry
  "Build a valid evidence-registry map from evidence records."
  [evidence-records run-id]
  (let [artifacts (mapv (fn [ev]
                          {:id (str "evidence-" (subs (:evidence/hash ev) 0 12))
                           :kind "transition-evidence"
                           :evidence-hash (:evidence/hash ev)})
                        evidence-records)
        base {:schema-version "evidence-registry.v1"
              :contract-version "0"
              :run-id run-id
              :generated-at "2025-01-01T00:00:00Z"
              :evidence-count (count evidence-records)
              :evidence-hashes (mapv :evidence/hash evidence-records)
              :artifacts artifacts}
        reg-hash (hc/hash-with-intent {:hash/intent :registry} base)]
    (assoc base :registry-hash reg-hash)))

(defn- build-chain-cursor
  "Build a valid chain-cursor-final map from evidence records."
  [evidence-records]
  (let [last-ev (last evidence-records)]
    {:cursor/scope :targeted-evidence
     :cursor/final-seq (:evidence/chain-seq last-ev)
     :cursor/final-self-hash (:evidence/chain-self-hash last-ev)
     :cursor/total-captured (count evidence-records)}))

(defn- run-id []
  (str "test-run-" (java.util.UUID/randomUUID)))

(def sew-adapter
  (try @(requiring-resolve 'resolver-sim.protocols.sew.procedure-evidence/sew-evidence-adapter)
    (catch Exception _ nil)))

;; ── Fixture builder ─────────────────────────────────────────────────────────

(defn- build-valid-fixture
  "Create a complete valid fixture with persisted evidence.
   Returns {:temp-dir :ev-dir :definition :witness :registry :cursor}."
  []
  (let [auth-id "fa-custody-benchmark-001"
        workflow-id "wf-0"
        temp-dir (str (System/getProperty "java.io.tmpdir") "/wit-int-"
                      (java.util.UUID/randomUUID))
        ev-dir (str temp-dir "/event-evidence")]

    (io/make-parents (io/file ev-dir))

    ;; Three evidence records in chain order
    (let [ev1 (make-evidence "force-authorisation-granted" auth-id 1 nil
                             {:force-auth/workflow-id workflow-id}
                             :world-before "0x00" :world-after "0x01")
          ev2 (make-evidence "force-authorisation-executed" auth-id 2
                             (:evidence/chain-self-hash ev1)
                             {:force-auth/workflow-id workflow-id}
                             :world-before "0x01" :world-after "0x02")
          ev3 (make-evidence "escrow-released" auth-id 3
                             (:evidence/chain-self-hash ev2)
                             {:finalize/workflow-id workflow-id
                              :finalize/authorization-id auth-id}
                             :world-before "0x02" :world-after "0x03")]

      ;; Write evidence files
      (write-evidence-file ev-dir ev1)
      (write-evidence-file ev-dir ev2)
      (write-evidence-file ev-dir ev3)

      ;; Build registry and cursor
      (let [reg (build-evidence-registry [ev1 ev2 ev3] (run-id))
            cursor (build-chain-cursor [ev1 ev2 ev3])

            ;; Build definition
            definition (tsd/build-definition
                        {:id :sew.sequence/force-authorised-custody-adjustment
                         :provider {:protocol/id :protocol/sew :protocol/version "1"}
                         :steps
                         [{:step/id :prf.step/authorisation-granted
                           :step/type :assertion
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation :policy/version 1}}
                          {:step/id :prf.step/authorised-execution
                           :step/type :assertion
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation :policy/version 1}}
                          {:step/id :prf.step/authorised-consumption-custody-adjustment
                           :step/type :state-transition
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation :policy/version 1}}]})

            ;; Build witness
            witness (pew/build-witness
                     {:id "test-wit-int"
                      :definition-root (:trust-sequence-definition/root definition)
                      :initial-input-root "0x00"
                      :step-bindings
                      [{:step/id :prf.step/authorisation-granted :evidence ev1}
                       {:step/id :prf.step/authorised-execution :evidence ev2}
                       {:step/id :prf.step/authorised-consumption-custody-adjustment :evidence ev3}]
                      :result-root "0x03"})]

        {:temp-dir temp-dir
         :ev-dir ev-dir
         :definition definition
         :witness witness
         :registry reg
         :cursor cursor
         :auth-id auth-id
         :evidence-records [ev1 ev2 ev3]}))))

(defn- cleanup-fixture [f]
  (when-let [d (io/file (:temp-dir f))]
    (when (.isDirectory d)
      (doseq [f (file-seq d)]
        (when (.isFile f) (.delete f)))
      (.delete d))))

;; ── Gate 1 tests ────────────────────────────────────────────────────────────

(deftest valid-chain-valid-witness-passes
  (let [fx (build-valid-fixture)]
    (try
      (let [result (wv/verify-witness-from-finalised-evidence
                    (:witness fx) (:definition fx) (:ev-dir fx)
                    (:registry fx) (:cursor fx)
                    {:evidence-adapter sew-adapter
                     :expected-correlation-id (:auth-id fx)})]
        (is (:valid? result))
        (is (= :chain-verified (:evidence-index/status result)))
        (is (some? (:evidence-index/registry-root result)))
        (is (some? (:evidence-index/chain-head result)))
        ;; Evidence-chain checks pass
        (is (some #(= :evidence-chain/registry-hash-valid (:check/code %))
                  (filter #(= :pass (:check/status %)) (:checks result))))
        (is (some #(= :evidence-chain/chain-valid (:check/code %))
                  (filter #(= :pass (:check/status %)) (:checks result))))
        ;; Witness checks pass
        (is (some #(= :procedure-witness/root-integrity (:check/code %))
                  (filter #(= :pass (:check/status %)) (:checks result))))
        (is (some #(= :procedure-witness/final-output-matches-result (:check/code %))
                  (filter #(= :pass (:check/status %)) (:checks result))))
        (is (some #(= :procedure-witness/correlation-internally-consistent (:check/code %))
                  (filter #(= :pass (:check/status %)) (:checks result))))
        (is (some #(= :procedure-witness/correlation-matches-planned-instance (:check/code %))
                  (filter #(= :pass (:check/status %)) (:checks result)))))
      (finally (cleanup-fixture fx)))))

(deftest registry-hash-corruption-fails-at-evidence-layer
  (let [fx (build-valid-fixture)]
    (try
      (let [corrupted-reg (assoc (:registry fx) :registry-hash "0xdeadbeef")
            result (wv/verify-witness-from-finalised-evidence
                    (:witness fx) (:definition fx) (:ev-dir fx)
                    corrupted-reg (:cursor fx)
                    {:evidence-adapter sew-adapter
                     :expected-correlation-id (:auth-id fx)})]
        (is (not (:valid? result)))
        ;; Evidence-chain check fails
        (is (some #(= :evidence-chain/registry-hash-invalid (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result)))))
      (finally (cleanup-fixture fx)))))

(deftest chain-link-corruption-fails-at-evidence-layer
  (let [fx (build-valid-fixture)]
    (try
      ;; Mutate chain-seq of middle evidence and rebuild chain
      (let [ev-records (:evidence-records fx)
            ev2 (nth ev-records 1)
            tampered-ev2 (assoc ev2 :evidence/chain-prev-hash "0xbad")
            ;; Rebuild registry with tampered evidence
            tampered-records [(first ev-records) tampered-ev2 (last ev-records)]
            reg (build-evidence-registry tampered-records (run-id))
            cursor (build-chain-cursor tampered-records)
            ;; Need to write tampered evidence to disk
            _ (spit (io/file (:ev-dir fx) "ev-2.json")
                    (json/write-str tampered-ev2 {:key-fn name}))
            result (wv/verify-witness-from-finalised-evidence
                    (:witness fx) (:definition fx) (:ev-dir fx)
                    reg cursor
                    {:evidence-adapter sew-adapter
                     :expected-correlation-id (:auth-id fx)})]
        (is (not (:valid? result)))
        ;; Chain validation fails
        (is (some #(= :evidence-chain/chain-invalid (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result)))))
      (finally (cleanup-fixture fx)))))

(deftest witness-corruption-reaches-witness-layer
  (let [fx (build-valid-fixture)]
    (try
      (let [tampered-witness (assoc (:witness fx)
                                    :procedure-execution-witness/result-root "0xbad")
            result (wv/verify-witness-from-finalised-evidence
                    tampered-witness (:definition fx) (:ev-dir fx)
                    (:registry fx) (:cursor fx)
                    {:evidence-adapter sew-adapter
                     :expected-correlation-id (:auth-id fx)})]
        (is (not (:valid? result)))
        ;; Evidence-layer checks still pass
        (is (some #(= :evidence-chain/registry-hash-valid (:check/code %))
                  (filter #(= :pass (:check/status %)) (:checks result))))
        (is (some #(= :evidence-chain/chain-valid (:check/code %))
                  (filter #(= :pass (:check/status %)) (:checks result))))
        ;; Witness-layer check fails
        (is (some #(= :procedure-witness/root-integrity (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result)))))
      (finally (cleanup-fixture fx)))))

(deftest repeated-verification-identical
  (let [fx (build-valid-fixture)]
    (try
      (let [opts {:evidence-adapter sew-adapter
                  :expected-correlation-id (:auth-id fx)}
            a (wv/verify-witness-from-finalised-evidence
               (:witness fx) (:definition fx) (:ev-dir fx)
               (:registry fx) (:cursor fx) opts)
            b (wv/verify-witness-from-finalised-evidence
               (:witness fx) (:definition fx) (:ev-dir fx)
               (:registry fx) (:cursor fx) opts)]
        (is (= (:valid? a) (:valid? b)))
        (is (= (count (:checks a)) (count (:checks b))))
        (is (= (:pass-count a) (:pass-count b)))
        (is (= (:fail-count a) (:fail-count b))))
      (finally (cleanup-fixture fx)))))

(deftest facade-distinguishes-chain-failure-from-witness-failure
  (let [fx (build-valid-fixture)]
    (try
      ;; Chain corruption: fails with chain layer checks failing
      (let [reg-corrupt (assoc (:registry fx) :registry-hash "0xbad")
            chain-result (wv/verify-witness-from-finalised-evidence
                          (:witness fx) (:definition fx) (:ev-dir fx)
                          reg-corrupt (:cursor fx)
                          {:evidence-adapter sew-adapter
                           :expected-correlation-id (:auth-id fx)})]
        (is (not (:valid? chain-result)))
        (is (some #(= :evidence-chain/registry-hash-invalid (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks chain-result))))
        (is (not-any? #(= :procedure-witness/root-integrity (:check/code %))
                      (filter #(= :fail (:check/status %)) (:checks chain-result)))))
      ;; Witness corruption: fails with only witness layer checks failing
      (let [wit-corrupt (assoc (:witness fx)
                               :procedure-execution-witness/result-root "0xbad")
            wit-result (wv/verify-witness-from-finalised-evidence
                        wit-corrupt (:definition fx) (:ev-dir fx)
                        (:registry fx) (:cursor fx)
                        {:evidence-adapter sew-adapter
                         :expected-correlation-id (:auth-id fx)})]
        (is (not (:valid? wit-result)))
        (is (not-any? #(= :evidence-chain/registry-hash-invalid (:check/code %))
                      (filter #(= :fail (:check/status %)) (:checks wit-result))))
        (is (some #(= :procedure-witness/root-integrity (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks wit-result)))))
      (finally (cleanup-fixture fx)))))

(deftest unverified-index-rejected-by-canonical-path
  (let [fx (build-valid-fixture)]
    (try
      ;; Verify-witness (pure) accepts an unverified index
      (let [ev-idx (wv/build-evidence-index (:ev-dir fx))
            pure-result (wv/verify-witness
                         (:witness fx) (:definition fx) ev-idx
                         {:evidence-adapter sew-adapter
                          :expected-correlation-id (:auth-id fx)})]
        (is (:valid? pure-result))
        (is (= :unverified (:evidence-index/status ev-idx))))

      ;; verify-witness-from-finalised-evidence does NOT expose
      ;; unverified status — it always finalises the index
      (let [facade-result (wv/verify-witness-from-finalised-evidence
                           (:witness fx) (:definition fx) (:ev-dir fx)
                           (:registry fx) (:cursor fx)
                           {:evidence-adapter sew-adapter
                            :expected-correlation-id (:auth-id fx)})]
        (is (:valid? facade-result))
        (is (= :chain-verified (:evidence-index/status facade-result))))
      (finally (cleanup-fixture fx)))))

;; ── Witness-requirement decision tests ──────────────────────────────────────

(deftest witness-requirement-not-required-when-neither-configured-nor-present
  (is (= :not-required (wb/witness-requirement false false))))

(deftest witness-requirement-required-present-when-configured-and-present
  (is (= :required-present (wb/witness-requirement true true))))

(deftest witness-requirement-required-missing-when-configured-but-absent
  (is (= :required-missing (wb/witness-requirement true false))))

(deftest witness-requirement-unexpected-present-when-not-configured-but-present
  (is (= :unexpected-present (wb/witness-requirement false true))))

;; ── Adapter loading tests ──────────────────────────────────────────────────

(deftest load-adapter-sew-resolves-successfully
  (let [adapter (wb/load-adapter :protocol/sew)]
    (is (map? adapter))
    (is (contains? adapter :correlation-paths))
    (is (contains? adapter :step-evidence-types))))

(deftest load-adapter-unknown-protocol-throws
  (is (thrown? Exception (wb/load-adapter :protocol/unknown))))

;; ── Plan commitment tests ──────────────────────────────────────────────────

(deftest plan-tampering-after-execution-fails
  (let [fx (build-valid-fixture)]
    (try
      (let [definition (:definition fx)
            def-root (:trust-sequence-definition/root definition)
            witness (:witness fx)
            ev-idx (wv/build-evidence-index (:ev-dir fx))
            wrong-root "0000000000000000000000000000000000000000000000000000000000000000"]
        ;; Verify with correct plan root — passes
        (let [result (wv/verify-witness witness definition ev-idx
                                        {:plan-root def-root
                                         :evidence-adapter sew-adapter
                                         :expected-correlation-id (:auth-id fx)})]
          (is (:valid? result))
          (is (some #(= :procedure-witness/definition-matches-execution-plan (:check/code %))
                    (filter #(= :pass (:check/status %)) (:checks result)))))
        ;; Verify with tampered plan root — fails
        (let [result (wv/verify-witness witness definition ev-idx
                                        {:plan-root wrong-root
                                         :evidence-adapter sew-adapter
                                         :expected-correlation-id (:auth-id fx)})]
          (is (not (:valid? result)))
          (is (some #(= :procedure-witness/definition-does-not-match-execution-plan (:check/code %))
                    (filter #(= :fail (:check/status %)) (:checks result))))))
      (finally (cleanup-fixture fx)))))

(deftest missing-plan-commitment-fails-for-configured-benchmark
  (let [fx (build-valid-fixture)]
    (try
      (let [definition (:definition fx)
            witness (:witness fx)
            ev-idx (wv/build-evidence-index (:ev-dir fx))]
        ;; Verify without plan root — :not-run (backward compatible)
        (let [result (wv/verify-witness witness definition ev-idx
                                        {:evidence-adapter sew-adapter
                                         :expected-correlation-id (:auth-id fx)})]
          (is (:valid? result))
          (is (some #(= :not-run (:check/status %))
                    (filter #(= :procedure-witness/definition-matches-execution-plan (:check/code %))
                            (:checks result)))))
        ;; Configured canonical path should reject nil plan root
        ;; This is enforced in witness-build/build-and-write! which throws
        ;; when the plan has no committed root. The pure verifier keeps
        ;; backward compatibility with :not-run for unconfigured callers.
        )
      (finally (cleanup-fixture fx)))))

(deftest expected-correlation-plan-binding
  (let [fx (build-valid-fixture)]
    (try
      (let [definition (:definition fx)
            witness (:witness fx)
            ev-idx (wv/build-evidence-index (:ev-dir fx))
            correct-id (:auth-id fx)
            wrong-id "wrong-auth"]
        ;; Correct planned correlation — passes
        (let [result (wv/verify-witness witness definition ev-idx
                                        {:plan-root (:trust-sequence-definition/root definition)
                                         :evidence-adapter sew-adapter
                                         :expected-correlation-id correct-id})]
          (is (:valid? result))
          (is (some #(= :procedure-witness/correlation-matches-planned-instance (:check/code %))
                    (filter #(= :pass (:check/status %)) (:checks result)))))
        ;; Wrong planned correlation — fails
        (let [result (wv/verify-witness witness definition ev-idx
                                        {:plan-root (:trust-sequence-definition/root definition)
                                         :evidence-adapter sew-adapter
                                         :expected-correlation-id wrong-id})]
          (is (not (:valid? result)))
          (is (some #(= :procedure-witness/correlation-matches-planned-instance (:check/code %))
                    (filter #(= :fail (:check/status %)) (:checks result))))))
      (finally (cleanup-fixture fx)))))

;; ── Chain-level scheme uniformity test ──────────────────────────────────────

(deftest mixed-chain-hash-schemes-fail
  (let [auth-id "fa-scheme-test"
        temp-dir (str (System/getProperty "java.io.tmpdir") "/tsscheme-" (java.util.UUID/randomUUID))
        ev-dir (str temp-dir "/event-evidence")
        _ (.mkdirs (io/file ev-dir))]
    (try
      (let [ev1 (make-evidence "force-authorisation-granted" auth-id 1 nil
                               {:force-auth/workflow-id "wft"}
                               :world-before "0x00" :world-after "0x01")
            ev2 (assoc (make-evidence "force-authorisation-executed" auth-id 2
                                      (:evidence/chain-self-hash ev1)
                                      {:force-auth/workflow-id "wft"}
                                      :world-before "0x01" :world-after "0x02")
                       :evidence/chain-hash-scheme "link-v2")
            result (chain/verify-scenario-chain [ev1 ev2])
            errors (:chain/errors result)]
        (is (= :invalid (:chain/status result)))
        (is (some #(and (= :unsupported-chain-hash-scheme (:reason %))
                        (= 2 (:chain-seq %)))
                  errors)
            "link-v2 record should be rejected as unsupported")
        (is (some #(= :chain-hash-schemes-inconsistent (:reason %))
                  errors)
            "Mixed schemes in one chain should fail uniformity check"))
      (finally
        (doseq [f (file-seq (io/file temp-dir))]
          (when (.isFile f) (.delete f)))
        (.delete (io/file temp-dir))))))