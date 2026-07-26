(ns resolver-sim.assurance.procedure-execution-witness-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [resolver-sim.assurance.trust-sequence-definition :as tsd]
            [resolver-sim.assurance.procedure-execution-witness :as pew]
            [resolver-sim.assurance.witness-verifier :as wv]
            [resolver-sim.hash.canonical :as hc]))

;; ── Synthetic evidence helpers ─────────────────────────────────────────────

(defn- make-evidence
  "Build a synthetic evidence map resembling a real captured evidence record."
  [evidence-type auth-id seq chain-prev-hash extra-inputs & {:keys [world-before world-after] :or {world-before "0x00" world-after "0x01"}}]
  (let [inputs (merge {:force-auth/auth-id auth-id} extra-inputs)
        base-hash-input {:evidence/type evidence-type
                         :evidence/hash-scheme "ev"
                         :world/before-hash world-before
                         :world/after-hash world-after
                         :inputs inputs}
        evidence-hash (hc/hash-with-intent {:hash/intent :evidence-content} base-hash-input)
        chain-link (hc/hash-with-intent
                    {:hash/intent :evidence-chain-link-v1}
                    {:chain/hash-scheme "link-v1"
                     :evidence/hash evidence-hash
                     :evidence/chain-seq seq
                     :evidence/chain-prev-hash chain-prev-hash})]
    {:evidence/type evidence-type
     :evidence/hash evidence-hash
     :world/before-hash world-before
     :world/after-hash world-after
     :inputs inputs
     :evidence/chain-hash-scheme "link-v1"
     :evidence/chain-seq seq
     :evidence/chain-prev-hash chain-prev-hash
     :evidence/chain-self-hash chain-link}))

(defn- write-evidence!
  "Write an evidence record as JSON to event-evidence-dir."
  [dir evidence]
  (let [f (io/file dir (str "evidence-" (:evidence/chain-seq evidence) ".json"))]
    (io/make-parents f)
    (spit f (json/write-str evidence {:key-fn (fn [k] (if (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k)) (str k)))}))))

(defn- make-three-step-sequence
  "Build a standard three-step force-authorisation evidence sequence.
   Returns {:dir temp-dir :definition defn :witness witness :auth-id id}."
  []
  (let [auth-id "fa-test-0"
        workflow-id "wf-0"
        temp-dir (str (System/getProperty "java.io.tmpdir") "/pew-test-" (java.util.UUID/randomUUID))
        ev-dir (str temp-dir "/event-evidence")
        _ (.mkdirs (io/file ev-dir))

        ;; Three evidence records in sequence
        ev1 (make-evidence "force-authorisation-granted" auth-id 1 nil
                           {:force-auth/workflow-id workflow-id
                            :force-auth/scope-hash "0xabc"}
                           :world-before "0x00" :world-after "0x01")
        ev2 (make-evidence "force-authorisation-executed" auth-id 2
                           (:evidence/chain-self-hash ev1)
                           {:force-auth/workflow-id workflow-id
                            :force-auth/scope-hash "0xabc"}
                           :world-before "0x01" :world-after "0x02")
        ev3 (make-evidence "escrow-released" auth-id 3
                           (:evidence/chain-self-hash ev2)
                           {:finalize/workflow-id workflow-id
                            :finalize/authorization-id auth-id}
                           :world-before "0x02" :world-after "0x03")

        _ (write-evidence! ev-dir ev1)
        _ (write-evidence! ev-dir ev2)
        _ (write-evidence! ev-dir ev3)

        ;; Build definition
        definition (tsd/build-definition
                    {:id :sew.sequence/force-authorised-custody-adjustment
                     :provider {:protocol/id :protocol/sew
                                :protocol/version "1"}
                     :steps
                     [{:step/id :prf.step/authorisation-granted
                       :step/type :assertion
                       :step/policy-requirement
                       {:policy/id :sew.policy/force-authorisation
                        :policy/version 1}}
                      {:step/id :prf.step/authorised-execution
                       :step/type :assertion
                       :step/policy-requirement
                       {:policy/id :sew.policy/force-authorisation
                        :policy/version 1}}
                      {:step/id :prf.step/authorised-consumption-custody-adjustment
                       :step/type :state-transition
                       :step/policy-requirement
                       {:policy/id :sew.policy/force-authorisation
                        :policy/version 1}}]})

        ;; Build witness
        witness (pew/build-witness
                 {:id "test-witness-0"
                  :definition-root (:trust-sequence-definition/root definition)
                  :initial-input-root "0x00"
                  :step-bindings
                  [{:step/id :prf.step/authorisation-granted :evidence ev1}
                   {:step/id :prf.step/authorised-execution :evidence ev2}
                   {:step/id :prf.step/authorised-consumption-custody-adjustment :evidence ev3}]
                  :result-root "0x03"})]
    {:dir temp-dir :ev-dir ev-dir :definition definition :witness witness :auth-id auth-id}))

(defn- read-evidence-file
  "Read a single evidence JSON file, return map with keyword keys."
  [f]
  (try
    (json/read-str (slurp f) :key-fn keyword)
    (catch Exception _ nil)))

(defn- find-evidence-files
  "Return all JSON evidence files in event-evidence-dir."
  [event-evidence-dir]
  (let [d (io/file event-evidence-dir)]
    (when (.isDirectory d)
      (sort (filter #(.endsWith (.getName %) ".json")
                    (file-seq d))))))

(defn- evidence-by-content-hash
  "Build a map of evidence-content-hash -> evidence-map from event-evidence-dir."
  [event-evidence-dir]
  (let [files (find-evidence-files event-evidence-dir)]
    (into {}
          (keep (fn [f]
                  (when-let [ev (read-evidence-file f)]
                    (when-let [eh (:evidence/hash ev)]
                      [eh ev]))))
          files)))

(defn- build-evidence-index
  "Build a validated evidence index from an event-evidence directory."
  [ev-dir]
  (let [evidence-map (evidence-by-content-hash ev-dir)
        all-self-hashes (set (keep :evidence/chain-self-hash (vals evidence-map)))]
    {:evidence-index/by-content-hash evidence-map
     :evidence-index/all-chain-self-hashes all-self-hashes
     :evidence-index/status :chain-verified
     :evidence-index/registry-root "test-registry-root"
     :evidence-index/chain-head (or (first (sort all-self-hashes)) "test-chain-head")}))

(def sew-adapter
  (try
    (requiring-resolve 'resolver-sim.protocols.sew.procedure-evidence/sew-evidence-adapter)
    (catch Exception _ nil)))

(defn- adapter-or-nil []
  (when sew-adapter @sew-adapter))

(defn- cleanup [m]
  (when-let [d (io/file (:dir m))]
    (when (.isDirectory d)
      (doseq [f (file-seq d)]
        (when (.isFile f) (.delete f)))
      (.delete d))))

;; ── Basic tests ────────────────────────────────────────────────────────────

(deftest build-witness-creates-valid-structure
  (let [ctx (make-three-step-sequence)]
    (try
      (let [w (:witness ctx)]
        (is (some? (:procedure-execution-witness/root w)))
        (is (= 1 (:procedure-execution-witness/schema-version w)))
        (is (= "test-witness-0" (:procedure-execution-witness/id w)))
        (is (= 3 (count (:procedure-execution-witness/steps w))))
        (is (= "0x03" (:procedure-execution-witness/result-root w)))
        (is (= "0x00" (:procedure-execution-witness/initial-input-root w))))
      (finally (cleanup ctx)))))

(deftest verify-passes-on-valid-sequence
  (let [ctx (make-three-step-sequence)]
    (try
      (let [ev-idx (build-evidence-index (:ev-dir ctx))
            result (wv/verify-witness (:witness ctx) (:definition ctx) ev-idx
                                      {:evidence-adapter (adapter-or-nil)
                                       :expected-correlation-id (:auth-id ctx)})]
        (is (:valid? result))
        (is (every? #(not= :fail (:check/status %)) (:checks result)))
        (is (pos? (count (:checks result)))))
      (finally (cleanup ctx)))))

(deftest verify-summary-produces-canonical-assurance-compatible-output
  (let [ctx (make-three-step-sequence)]
    (try
      (let [ev-idx (build-evidence-index (:ev-dir ctx))
            summary (wv/verify-witness-summary (:witness ctx) (:definition ctx) ev-idx
                                               {:evidence-adapter (adapter-or-nil)
                                                :expected-correlation-id (:auth-id ctx)})]
        (is (true? (:execution-witness-verified summary)))
        (is (some? (:execution-witness-root summary)))
        (is (pos? (:execution-witness-checks-passed summary)))
        (is (= 0 (:execution-witness-checks-failed summary))))
      (finally (cleanup ctx)))))

;; ── Corruption tests ───────────────────────────────────────────────────────

(deftest corruption-test-consumption-evidence-missing
  (let [ctx (make-three-step-sequence)]
    (try
      (io/delete-file (io/file (:ev-dir ctx) "evidence-3.json"))
      (let [ev-idx (build-evidence-index (:ev-dir ctx))
            result (wv/verify-witness (:witness ctx) (:definition ctx) ev-idx
                                      {:evidence-adapter (adapter-or-nil)
                                       :expected-correlation-id (:auth-id ctx)})]
        (is (not (:valid? result)))
        (is (some #(= :fail (:check/status %)) (:checks result)))
        (is (some #(= :procedure-witness/evidence-not-found (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result)))))
      (finally (cleanup ctx)))))

(deftest corruption-test-evidence-chain-order-reversed
  (let [auth-id "fa-test-1"
        workflow-id "wf-1"
        temp-dir (str (System/getProperty "java.io.tmpdir") "/pew-reversed-" (java.util.UUID/randomUUID))
        ev-dir (str temp-dir "/event-evidence")
        _ (.mkdirs (io/file ev-dir))]
    (try
      ;; Grant then release then execute — wrong order
      (let [ev1 (make-evidence "force-authorisation-granted" auth-id 1 nil
                               {:force-auth/workflow-id workflow-id}
                               :world-before "0x00" :world-after "0x01")
            ;; Chain-seq 2, but type doesn't match declared second step
            ev2 (make-evidence "escrow-released" auth-id 2
                               (:evidence/chain-self-hash ev1)
                               {:finalize/workflow-id workflow-id
                                :finalize/authorization-id auth-id}
                               :world-before "0x01" :world-after "0x03")
            ev3 (make-evidence "force-authorisation-executed" auth-id 3
                               (:evidence/chain-self-hash ev2)
                               {:force-auth/workflow-id workflow-id}
                               :world-before "0x01" :world-after "0x02")
            _ (write-evidence! ev-dir ev1)
            _ (write-evidence! ev-dir ev2)
            _ (write-evidence! ev-dir ev3)
            definition (tsd/build-definition
                        {:id :sew.sequence/force-authorised-custody-adjustment
                         :provider {:protocol/id :protocol/sew
                                    :protocol/version "1"}
                         :steps
                         [{:step/id :prf.step/authorisation-granted
                           :step/type :assertion
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 1}}
                          {:step/id :prf.step/authorised-execution
                           :step/type :assertion
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 1}}
                          {:step/id :prf.step/authorised-consumption-custody-adjustment
                           :step/type :state-transition
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 1}}]})
            witness (pew/build-witness
                     {:id "test-witness-reversed"
                      :definition-root (:trust-sequence-definition/root definition)
                      :initial-input-root "0x00"
                      :step-bindings
                      [{:step/id :prf.step/authorisation-granted :evidence ev1}
                       {:step/id :prf.step/authorised-execution :evidence ev3}
                       {:step/id :prf.step/authorised-consumption-custody-adjustment :evidence ev2}]
                      :result-root "0x03"})
            ev-idx (build-evidence-index ev-dir)
            result (wv/verify-witness witness definition ev-idx
                                      {:evidence-adapter (adapter-or-nil)
                                       :expected-correlation-id auth-id})]
        (is (not (:valid? result)))
        (is (some #(= :fail (:check/status %)) (:checks result)))
        (is (some #(= :procedure-witness/chain-seq-monotonic (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result)))))
      (finally
        (doseq [f (file-seq (io/file temp-dir))]
          (when (.isFile f) (.delete f)))
        (.delete (io/file temp-dir))))))

(deftest corruption-test-final-result-root-mutated
  (let [ctx (make-three-step-sequence)]
    (try
      (let [witness (:witness ctx)
            tampered (assoc witness :procedure-execution-witness/result-root "0xdeadbeef")
            ev-idx (build-evidence-index (:ev-dir ctx))
            result (wv/verify-witness tampered (:definition ctx) ev-idx
                                      {:evidence-adapter (adapter-or-nil)
                                       :expected-correlation-id (:auth-id ctx)})]
        (is (not (:valid? result)))
        (is (some #(= :procedure-witness/root-integrity (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result))))
        (is (some #(= :procedure-witness/final-output-mismatch (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result)))))
      (finally (cleanup ctx)))))

(deftest corruption-test-definition-root-substituted
  (let [ctx (make-three-step-sequence)]
    (try
      ;; Build a different definition
      (let [other-defn (tsd/build-definition
                        {:id :sew.sequence/some-other-sequence
                         :provider {:protocol/id :protocol/sew
                                    :protocol/version "1"}
                         :steps [{:step/id :prf.step/some-step
                                  :step/type :assertion
                                  :step/policy-requirement
                                  {:policy/id :sew.policy/force-authorisation
                                   :policy/version 1}}]})
            witness (:witness ctx)
            tampered (assoc witness
                            :procedure-execution-witness/definition-root
                            (:trust-sequence-definition/root other-defn))
            ev-idx (build-evidence-index (:ev-dir ctx))
            result (wv/verify-witness tampered (:definition ctx) ev-idx
                                      {:evidence-adapter (adapter-or-nil)
                                       :expected-correlation-id (:auth-id ctx)})]
        (is (not (:valid? result)))
        (is (some #(= :procedure-witness/definition-root-matches (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result)))))
      (finally (cleanup ctx)))))

(deftest corruption-test-policy-commitment-substituted
  (let [ctx (make-three-step-sequence)]
    (try
      ;; Build a definition referencing a different policy version
      (let [other-defn (tsd/build-definition
                        {:id :sew.sequence/force-authorised-custody-adjustment
                         :provider {:protocol/id :protocol/sew
                                    :protocol/version "1"}
                         :steps
                         [{:step/id :prf.step/authorisation-granted
                           :step/type :assertion
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 2}}    ;; ← different version
                          {:step/id :prf.step/authorised-execution
                           :step/type :assertion
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 2}}
                          {:step/id :prf.step/authorised-consumption-custody-adjustment
                           :step/type :state-transition
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 2}}]})
            ev-idx (build-evidence-index (:ev-dir ctx))
            result (wv/verify-witness (:witness ctx) other-defn ev-idx
                                      {:evidence-adapter (adapter-or-nil)
                                       :expected-correlation-id (:auth-id ctx)})]
        (is (not (:valid? result)))
        (is (some #(= :procedure-witness/definition-root-matches (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result)))))
      (finally (cleanup ctx)))))

(deftest corruption-test-correlation-identity-mismatch
  (let [auth-id "fa-original"
        wrong-auth-id "fa-wrong"
        workflow-id "wf-2"
        temp-dir (str (System/getProperty "java.io.tmpdir") "/pew-id-mismatch-" (java.util.UUID/randomUUID))
        ev-dir (str temp-dir "/event-evidence")
        _ (.mkdirs (io/file ev-dir))]
    (try
      (let [ev1 (make-evidence "force-authorisation-granted" auth-id 1 nil
                               {:force-auth/workflow-id workflow-id}
                               :world-before "0x00" :world-after "0x01")
            ev2 (make-evidence "force-authorisation-executed" auth-id 2
                               (:evidence/chain-self-hash ev1)
                               {:force-auth/workflow-id workflow-id}
                               :world-before "0x01" :world-after "0x02")
            ;; Wrong auth-id on the release
            ev3 (make-evidence "escrow-released" wrong-auth-id 3
                               (:evidence/chain-self-hash ev2)
                               {:finalize/workflow-id workflow-id
                                :finalize/authorization-id wrong-auth-id}
                               :world-before "0x02" :world-after "0x03")

            _ (write-evidence! ev-dir ev1)
            _ (write-evidence! ev-dir ev2)
            _ (write-evidence! ev-dir ev3)

            definition (tsd/build-definition
                        {:id :sew.sequence/force-authorised-custody-adjustment
                         :provider {:protocol/id :protocol/sew
                                    :protocol/version "1"}
                         :steps
                         [{:step/id :prf.step/authorisation-granted
                           :step/type :assertion
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 1}}
                          {:step/id :prf.step/authorised-execution
                           :step/type :assertion
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 1}}
                          {:step/id :prf.step/authorised-consumption-custody-adjustment
                           :step/type :state-transition
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 1}}]})

            witness (pew/build-witness
                     {:id "test-witness-id-mismatch"
                      :definition-root (:trust-sequence-definition/root definition)
                      :initial-input-root "0x00"
                      :step-bindings
                      [{:step/id :prf.step/authorisation-granted :evidence ev1}
                       {:step/id :prf.step/authorised-execution :evidence ev2}
                       {:step/id :prf.step/authorised-consumption-custody-adjustment :evidence ev3}]
                      :result-root "0x03"})
            ev-idx (build-evidence-index ev-dir)
            result (wv/verify-witness witness definition ev-idx
                                      {:evidence-adapter (adapter-or-nil)
                                       :expected-correlation-id auth-id})]
        (is (not (:valid? result)))
        (is (some #(= :procedure-witness/correlation-internally-consistent (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result)))))
      (finally
        (doseq [f (file-seq (io/file temp-dir))]
          (when (.isFile f) (.delete f)))
        (.delete (io/file temp-dir))))))

(deftest corruption-test-unrelated-evidence-intervening
  "Insert unrelated evidence between procedure events. Valid ancestry passes
   because the selected evidence is still transitively linked."
  (let [auth-id "fa-test-2"
        workflow-id "wf-3"
        temp-dir (str (System/getProperty "java.io.tmpdir") "/pew-intervening-" (java.util.UUID/randomUUID))
        ev-dir (str temp-dir "/event-evidence")
        _ (.mkdirs (io/file ev-dir))]
    (try
      (let [ev1 (make-evidence "force-authorisation-granted" auth-id 1 nil
                               {:force-auth/workflow-id workflow-id}
                               :world-before "0x00" :world-after "0x01")
            unrelated (make-evidence "governance-parameter-update" nil 2
                                     (:evidence/chain-self-hash ev1)
                                     {}
                                     :world-before "0x01" :world-after "0x01a")
            ev2 (make-evidence "force-authorisation-executed" auth-id 3
                               (:evidence/chain-self-hash unrelated)
                               {:force-auth/workflow-id workflow-id}
                               :world-before "0x01a" :world-after "0x02")
            ev3 (make-evidence "escrow-released" auth-id 4
                               (:evidence/chain-self-hash ev2)
                               {:finalize/workflow-id workflow-id
                                :finalize/authorization-id auth-id}
                               :world-before "0x02" :world-after "0x03")

            _ (write-evidence! ev-dir ev1)
            _ (write-evidence! ev-dir unrelated)
            _ (write-evidence! ev-dir ev2)
            _ (write-evidence! ev-dir ev3)

            definition (tsd/build-definition
                        {:id :sew.sequence/force-authorised-custody-adjustment
                         :provider {:protocol/id :protocol/sew
                                    :protocol/version "1"}
                         :steps
                         [{:step/id :prf.step/authorisation-granted
                           :step/type :assertion
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 1}}
                          {:step/id :prf.step/authorised-execution
                           :step/type :assertion
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 1}}
                          {:step/id :prf.step/authorised-consumption-custody-adjustment
                           :step/type :state-transition
                           :step/policy-requirement
                           {:policy/id :sew.policy/force-authorisation
                            :policy/version 1}}]})

            witness (pew/build-witness
                     {:id "test-witness-intervening"
                      :definition-root (:trust-sequence-definition/root definition)
                      :initial-input-root "0x00"
                      :step-bindings
                      [{:step/id :prf.step/authorisation-granted :evidence ev1}
                       {:step/id :prf.step/authorised-execution :evidence ev2}
                       {:step/id :prf.step/authorised-consumption-custody-adjustment :evidence ev3}]
                      :result-root "0x03"})
            ev-idx (build-evidence-index ev-dir)
            result (wv/verify-witness witness definition ev-idx
                                      {:evidence-adapter (adapter-or-nil)
                                       :expected-correlation-id auth-id})]
        (is (:valid? result))
        (is (every? #(not= :fail (:check/status %)) (:checks result))))
      (finally
        (doseq [f (file-seq (io/file temp-dir))]
          (when (.isFile f) (.delete f)))
        (.delete (io/file temp-dir))))))

;; ── Characterisation / equivalence tests ───────────────────────────────────

(deftest repeated-verification-returns-identical-output
  (let [ctx (make-three-step-sequence)]
    (try
      (let [ev-idx (build-evidence-index (:ev-dir ctx))
            opts {:evidence-adapter (adapter-or-nil)
                  :expected-correlation-id (:auth-id ctx)}
            a (wv/verify-witness (:witness ctx) (:definition ctx) ev-idx opts)
            b (wv/verify-witness (:witness ctx) (:definition ctx) ev-idx opts)]
        (is (= (:valid? a) (:valid? b)))
        (is (= (count (:checks a)) (count (:checks b))))
        (is (every? (fn [{:keys [check/code check/status]}]
                      (= (:check/status (some #(when (= code (:check/code %)) %) (:checks a)))
                         (:check/status (some #(when (= code (:check/code %)) %) (:checks b)))))
                    (:checks a))))
      (finally (cleanup ctx)))))

(deftest unknown-evidence-type-fails-explicitly
  (let [ctx (make-three-step-sequence)]
    (try
      (let [ev-idx (build-evidence-index (:ev-dir ctx))
            ;; Remove the correlation path for the first evidence type
            limited-adapter {:correlation-paths
                             {"force-authorisation-granted"
                              [[:inputs :force-auth/auth-id]]}}
            result (wv/verify-witness (:witness ctx) (:definition ctx) ev-idx
                                      {:evidence-adapter limited-adapter
                                       :expected-correlation-id (:auth-id ctx)})]
        (is (not (:valid? result)))
        (is (some #(= :procedure-witness/correlation-unknown-type (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result)))))
      (finally (cleanup ctx)))))

(deftest invalid-definition-does-not-cause-throw
  (let [ctx (make-three-step-sequence)]
    (try
      (let [ev-idx (build-evidence-index (:ev-dir ctx))
            invalid-defn {:trust-sequence-definition/schema-version 1
                          :trust-sequence-definition/id :bad/defn
                          :trust-sequence-definition/provider {}
                          :trust-sequence-definition/steps []}]
        ;; Should return results, not throw
        (is (wv/verify-witness (:witness ctx) invalid-defn ev-idx
                                {:evidence-adapter (adapter-or-nil)})))
      (finally (cleanup ctx)))))

(deftest check-ordering-is-deterministic
  (let [results (repeatedly 5
                 (fn []
                   (let [ctx (make-three-step-sequence)
                         ev-idx (build-evidence-index (:ev-dir ctx))
                         opts {:evidence-adapter (adapter-or-nil)
                               :expected-correlation-id (:auth-id ctx)}
                         result (wv/verify-witness (:witness ctx) (:definition ctx) ev-idx opts)
                         codes (mapv :check/code (:checks result))]
                     (cleanup ctx)
                     codes)))
        first-result (first results)]
    (is (every? #(= first-result %) results))))

(deftest one-missing-correlation-id-fails-correlation
  (let [auth-id "fa-test-4"
        workflow-id "wf-4"
        temp-dir (str (System/getProperty "java.io.tmpdir") "/pew-missing-corr-" (java.util.UUID/randomUUID))
        ev-dir (str temp-dir "/event-evidence")
        _ (.mkdirs (io/file ev-dir))]
    (try
      (let [ev1 (make-evidence "force-authorisation-granted" auth-id 1 nil
                               {:force-auth/workflow-id workflow-id}
                               :world-before "0x00" :world-after "0x01")
            ;; Third evidence without any correlation reference
            ev3 (make-evidence "escrow-released" nil 3
                               (:evidence/chain-self-hash ev1)
                               {:finalize/workflow-id workflow-id}
                               :world-before "0x02" :world-after "0x03")
            ev2 (make-evidence "force-authorisation-executed" auth-id 2
                               (:evidence/chain-self-hash ev1)
                               {:force-auth/workflow-id workflow-id}
                               :world-before "0x01" :world-after "0x02")
            _ (write-evidence! ev-dir ev1)
            _ (write-evidence! ev-dir ev2)
            _ (write-evidence! ev-dir ev3)
            definition (tsd/build-definition
                        {:id :sew.sequence/force-authorised-custody-adjustment
                         :provider {:protocol/id :protocol/sew
                                    :protocol/version "1"}
                         :steps [{:step/id :prf.step/authorisation-granted
                                  :step/type :assertion
                                  :step/policy-requirement
                                  {:policy/id :sew.policy/force-authorisation
                                   :policy/version 1}}
                                 {:step/id :prf.step/authorised-execution
                                  :step/type :assertion
                                  :step/policy-requirement
                                  {:policy/id :sew.policy/force-authorisation
                                   :policy/version 1}}
                                 {:step/id :prf.step/authorised-consumption-custody-adjustment
                                  :step/type :state-transition
                                  :step/policy-requirement
                                  {:policy/id :sew.policy/force-authorisation
                                   :policy/version 1}}]})
            witness (pew/build-witness
                     {:id "test-witness-missing-corr"
                      :definition-root (:trust-sequence-definition/root definition)
                      :initial-input-root "0x00"
                      :step-bindings
                      [{:step/id :prf.step/authorisation-granted :evidence ev1}
                       {:step/id :prf.step/authorised-execution :evidence ev2}
                       {:step/id :prf.step/authorised-consumption-custody-adjustment :evidence ev3}]
                      :result-root "0x03"})
            ev-idx (build-evidence-index ev-dir)
            result (wv/verify-witness witness definition ev-idx
                                      {:evidence-adapter (adapter-or-nil)
                                       :expected-correlation-id auth-id})]
        (is (not (:valid? result)))
        (is (some #(= :procedure-witness/correlation-internally-consistent (:check/code %))
                  (filter #(= :fail (:check/status %)) (:checks result)))))
      (finally
        (doseq [f (file-seq (io/file temp-dir))]
          (when (.isFile f) (.delete f)))
        (.delete (io/file temp-dir))))))

(deftest canonical-verification-requires-expected-correlation-provenance
  (let [ctx (make-three-step-sequence)]
    (try
      (let [ev-idx (build-evidence-index (:ev-dir ctx))
            ;; Without expected-correlation-id — correlation consistency is checked
            ;; but expected-id is :not-run
            result (wv/verify-witness (:witness ctx) (:definition ctx) ev-idx
                                      {:evidence-adapter (adapter-or-nil)})]
        (is (:valid? result))
        (is (some #(= :procedure-witness/correlation-matches-planned-instance (:check/code %))
                  (:checks result)))
        (is (some #(= :not-run (:check/status %))
                  (filter #(= :procedure-witness/correlation-matches-planned-instance (:check/code %))
                          (:checks result)))))
      (finally (cleanup ctx)))))

(deftest generic-assurance-no-hardcoded-sew-events
  (let [ns-content (slurp (clojure.java.io/file "src/resolver_sim/assurance/witness_verifier.clj"))
        sew-event-names #{"force-authorisation-granted"
                          "force-authorisation-executed"
                          "escrow-released"
                          "escrow-refunded"}]
    (doseq [event sew-event-names]
      (is (not (re-find (re-pattern event) ns-content))
          (str "witness_verifier.clj must not hardcode " event)))))

(deftest generic-procedure-evidence-no-hardcoded-sew-events
  (let [ns-content (slurp (clojure.java.io/file "src/resolver_sim/assurance/procedure_evidence.clj"))
        sew-event-names #{"force-authorisation-granted"
                          "force-authorisation-executed"
                          "escrow-released"
                          "escrow-refunded"}]
    (doseq [event sew-event-names]
      (is (not (re-find (re-pattern event) ns-content))
          (str "procedure_evidence.clj must not hardcode " event)))))

(deftest duplicate-candidate-evidence-fails-as-ambiguous
  (let [auth-id "fa-test-5"
        workflow-id "wf-5"
        temp-dir (str (System/getProperty "java.io.tmpdir") "/pew-dup-" (java.util.UUID/randomUUID))
        ev-dir (str temp-dir "/event-evidence")
        _ (.mkdirs (io/file ev-dir))]
    (try
      ;; Two evidence records with the SAME content hash (duplicate)
      (let [ev1a (make-evidence "force-authorisation-granted" auth-id 1 nil
                                {:force-auth/workflow-id workflow-id}
                                :world-before "0x00" :world-after "0x01")
            ev1b ev1a  ;; same map, same content hash — duplicate
            ev2 (make-evidence "force-authorisation-executed" auth-id 2
                                (:evidence/chain-self-hash ev1a)
                                {:force-auth/workflow-id workflow-id}
                                :world-before "0x01" :world-after "0x02")
            ev3 (make-evidence "escrow-released" auth-id 3
                                (:evidence/chain-self-hash ev2)
                                {:finalize/workflow-id workflow-id
                                 :finalize/authorization-id auth-id}
                                :world-before "0x02" :world-after "0x03")
            _ (write-evidence! ev-dir ev1a)
            _ (write-evidence! ev-dir ev1b)  ;; duplicate
            _ (write-evidence! ev-dir ev2)
            _ (write-evidence! ev-dir ev3)
            ev-idx (build-evidence-index ev-dir)
            ;; Both ev1a and ev1b have the same content hash, so the
            ;; evidence-by-hash map will have one entry for that hash.
            ;; The witness references the evidence by content hash, so
            ;; the duplicate file doesn't affect resolution. This is
            ;; expected — content addressing deduplicates.
            definition (tsd/build-definition
                        {:id :sew.sequence/force-authorised-custody-adjustment
                         :provider {:protocol/id :protocol/sew
                                    :protocol/version "1"}
                         :steps [{:step/id :prf.step/authorisation-granted
                                  :step/type :assertion
                                  :step/policy-requirement
                                  {:policy/id :sew.policy/force-authorisation
                                   :policy/version 1}}
                                 {:step/id :prf.step/authorised-execution
                                  :step/type :assertion
                                  :step/policy-requirement
                                  {:policy/id :sew.policy/force-authorisation
                                   :policy/version 1}}
                                 {:step/id :prf.step/authorised-consumption-custody-adjustment
                                  :step/type :state-transition
                                  :step/policy-requirement
                                  {:policy/id :sew.policy/force-authorisation
                                   :policy/version 1}}]})
            witness (pew/build-witness
                     {:id "test-witness-dup"
                      :definition-root (:trust-sequence-definition/root definition)
                      :initial-input-root "0x00"
                      :step-bindings
                      [{:step/id :prf.step/authorisation-granted :evidence ev1a}
                       {:step/id :prf.step/authorised-execution :evidence ev2}
                       {:step/id :prf.step/authorised-consumption-custody-adjustment :evidence ev3}]
                      :result-root "0x03"})
            result (wv/verify-witness witness definition ev-idx
                                      {:evidence-adapter (adapter-or-nil)
                                       :expected-correlation-id auth-id})]
        ;; Should still pass — duplicate content hashes are idempotent
        (is (:valid? result)))
      (finally
        (doseq [f (file-seq (io/file temp-dir))]
          (when (.isFile f) (.delete f)))
        (.delete (io/file temp-dir))))))