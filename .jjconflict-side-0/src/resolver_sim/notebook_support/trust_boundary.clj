(ns resolver-sim.notebook-support.trust-boundary
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [resolver-sim.assurance.trust-sequence-definition :as tsd]
            [resolver-sim.assurance.witness-verifier :as wv]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.run.package-index :as pkg]
            [resolver-sim.config.paths :as paths]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- sha256-hex [f]
  (let [d (java.security.MessageDigest/getInstance "SHA-256")]
    (.update d (java.nio.file.Files/readAllBytes (.toPath f)))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest d)))))

(defn- throw-missing [path msg]
  (throw (ex-info msg {:artifact/path (str path) :type :missing})))

(defn- throw-invalid [path msg data]
  (throw (ex-info msg (merge {:artifact/path (str path) :type :invalid} data))))

(defn load-trust-sequence-definition [path]
  (let [f (io/file path)]
    (when-not (.isFile f) (throw-missing path "Trust-sequence definition file not found"))
    (let [src (try (edn/read-string (slurp f)) (catch Exception _ nil))]
      (when (nil? src) (throw-invalid path "Unreadable trust-sequence definition" {}))
      (let [defn (tsd/build-definition
                  {:id (:trust-sequence-definition/id src)
                   :provider (:trust-sequence-definition/provider src)
                   :steps (:trust-sequence-definition/steps src)})
            validation (tsd/validate-definition defn)]
        (when-not (:valid? validation)
          (throw-invalid path "Invalid trust-sequence definition" {:errors (:errors validation)}))
        {:artifact/type :trust-sequence-definition
         :artifact/path (str path)
         :artifact/value defn
         :artifact/validation validation}))))

(defn load-execution-plan [path]
  (let [f (io/file path)]
    (when-not (.isFile f) (throw-missing path "Execution plan file not found"))
    (let [plan (try (edn/read-string (slurp f)) (catch Exception _ nil))]
      (when (nil? plan) (throw-invalid path "Unreadable execution plan" {}))
      {:artifact/type :execution-plan :artifact/path (str path) :artifact/value plan})))

(defn load-benchmark-definition [path]
  (let [f (io/file path)]
    (when-not (.isFile f) (throw-missing path "Benchmark definition file not found"))
    (let [defn (try (edn/read-string (slurp f)) (catch Exception _ nil))]
      (when (nil? defn) (throw-invalid path "Unreadable benchmark definition" {}))
      {:artifact/type :benchmark-definition :artifact/path (str path) :artifact/value defn})))

(defn load-execution-witness [path]
  (let [f (io/file path)]
    (when-not (.isFile f) (throw-missing path "Execution witness file not found"))
    (let [w (try (json/read-str (slurp f) :key-fn keyword) (catch Exception _ nil))]
      (when (nil? w) (throw-invalid path "Unreadable execution witness" {}))
      {:artifact/type :execution-witness :artifact/path (str path) :artifact/value w})))

(defn load-canonical-assurance [path]
  (let [f (io/file path)]
    (when-not (.isFile f) (throw-missing path "Canonical assurance file not found"))
    (let [a (try (json/read-str (slurp f) :key-fn keyword) (catch Exception _ nil))]
      (when (nil? a) (throw-invalid path "Unreadable canonical assurance" {}))
      {:artifact/type :canonical-assurance :artifact/path (str path) :artifact/value a})))

(defn load-package-index [path]
  (let [f (io/file path)]
    (when-not (.isFile f) (throw-missing path "Package index file not found"))
    (let [wire (try (json/read-str (slurp f) :key-fn keyword) (catch Exception _ nil))]
      (when (nil? wire) (throw-invalid path "Unreadable package index" {}))
      (try
        (let [idx (pkg/wire->package-index wire)]
          {:artifact/type :package-index :artifact/path (str path) :artifact/value idx})
        (catch Exception e
          (throw-invalid path "Package index decode failed" {:error (.getMessage e)}))))))

(defn load-completion [path]
  (let [f (io/file path)]
    (when-not (.isFile f) (throw-missing path "Completion file not found"))
    (let [c (try (json/read-str (slurp f) :key-fn keyword) (catch Exception _ nil))]
      (when (nil? c) (throw-invalid path "Unreadable completion" {}))
      {:artifact/type :completion :artifact/path (str path) :artifact/value c})))

(defn build-evidence-index [ev-dir]
  (let [d (io/file ev-dir)]
    (when-not (.isDirectory d) (throw-missing ev-dir "Event-evidence directory not found"))
    (wv/build-evidence-index ev-dir)))

(defn check-pre-execution-commitment [benchmark-def plan]
  (let [src-root (:benchmark/trust-sequence-definition-root benchmark-def)
        plan-root (:trust-sequence-definition-root plan)]
    {:source-definition-root src-root
     :execution-plan-root plan-root
     :match? (= src-root plan-root)
     :expected-correlation-id (or (:expected-correlation-id plan)
                                  (:benchmark/expected-correlation-id benchmark-def))}))

(defn collect-artifact-provenance [run-root]
  (let [r (io/file run-root)
        refs {:benchmark-definition "benchmark/definition.edn"
              :execution-plan "benchmark/execution-plan.edn"
              :execution-witness "manifest/execution-witness.json"
              :package-index "manifest/run-package-index.json"
              :completion "completion.json"
              :canonical-assurance "benchmark/assertions/canonical-integrity.json"}
        entries (reduce-kv (fn [acc k path]
                             (let [f (io/file r path)]
                               (assoc acc k (if (.isFile f) (hash-ref/sha256-ref (sha256-hex f)) nil))))
                           {} refs)]
    (assoc entries :run-root (str run-root))))

(defn resolve-step-evidence [witness evidence-index]
  (let [steps (:procedure-execution-witness/steps witness [])]
    (mapv (fn [step]
            (let [eh (:step/evidence-content-hash step)
                  ev (get (:evidence-index/by-content-hash evidence-index) eh)]
              {:step/id (:step/id step) :evidence ev :resolved? (some? ev)}))
          steps)))

(defn project-boundary-cards [definition witness verification-result evidence-index display-config]
  (let [steps (:trust-sequence-definition/steps definition [])
        resolved (resolve-step-evidence witness evidence-index)
        checks (:checks verification-result [])
        descriptions (get display-config :step-descriptions {})
        type-labels (get display-config :evidence-type-labels {})
        cards (mapv (fn [step r]
                      (let [sid (:step/id step)
                            ev (:evidence r)
                            eh (:step/evidence-content-hash
                                (first (filter #(= sid (:step/id %))
                                               (:procedure-execution-witness/steps witness []))))]
                        {:step/id sid
                         :boundary-description (get descriptions sid (name sid))
                         :step/type (:step/type step)
                         :policy-requirement (:step/policy-requirement step)
                         :expected-evidence-type (get-in ev [:evidence/type] :no-evidence)
                         :evidence-type-label (get type-labels (get-in ev [:evidence/type]) "")
                         :evidence-content-hash eh
                         :evidence-present? (:resolved? r)
                         :chain-seq (when (:resolved? r) (:evidence/chain-seq ev))
                         :correlation-id (when (:resolved? r)
                                           (or (get-in ev [:inputs :force-auth/auth-id])
                                               (get-in ev [:inputs :finalize/authorization-id])))
                         :state-before-root (when (:resolved? r) (:world/before-hash ev))
                         :state-after-root (when (:resolved? r) (:world/after-hash ev))
                         :verification-status
                         (or (some #(when (= (:check/code %) :procedure-witness/evidence-resolved)
                                      (:check/status %)) checks)
                             (if (:resolved? r) :pass :not-run))}))
                    steps resolved)]
    {:cards cards :step-count (count steps) :resolved-count (count (filter :resolved? resolved))}))

(def verification-groups
  [{:group/id :definition-binding :group/label "Definition binding"
    :codes #{:procedure-witness/root-integrity :procedure-witness/step-ids-unique
             :procedure-witness/definition-valid :procedure-witness/definition-root-matches
             :procedure-witness/step-ids-match-definition
             :procedure-witness/step-order-matches-definition
             :procedure-witness/definition-matches-execution-plan
             :procedure-witness/definition-does-not-match-execution-plan}}
   {:group/id :evidence-chain :group/label "Evidence registry and chain"
    :codes #{:evidence-chain/registry-hash-valid :evidence-chain/registry-hash-invalid
             :evidence-chain/chain-valid :evidence-chain/chain-invalid
             :procedure-witness/chain-seq-monotonic :procedure-witness/chain-seq-unique
             :procedure-witness/chain-ancestry}}
   {:group/id :step-evidence :group/label "Step evidence resolution"
    :codes #{:procedure-witness/evidence-resolved :procedure-witness/evidence-not-found
             :procedure-witness/evidence-type-matches :procedure-witness/evidence-type-mismatch
             :procedure-witness/input-root-matches :procedure-witness/input-root-mismatch
             :procedure-witness/output-root-matches :procedure-witness/output-root-mismatch}}
   {:group/id :correlation :group/label "Correlation identity"
    :codes #{:procedure-witness/correlation-internally-consistent
             :procedure-witness/correlation-matches-planned-instance
             :procedure-witness/correlation-unknown-type}}
   {:group/id :result-binding :group/label "Input/result binding"
    :codes #{:procedure-witness/initial-input-matches :procedure-witness/initial-input-mismatch
             :procedure-witness/final-output-matches-result :procedure-witness/final-output-mismatch}}
   {:group/id :runtime-policy :group/label "Runtime policy binding" :codes #{}}])

(defn project-verification-groups [checks]
  (mapv (fn [group]
          (let [group-codes (:codes group)
                matched (filter #(contains? group-codes (:check/code %)) checks)
                prefix-matched (when (empty? group-codes)
                                 (filter #(.startsWith (name (:check/code %)) (name (:group/id group)))
                                         checks))
                all-matched (vec (concat matched prefix-matched))
                statuses (set (map :check/status all-matched))]
            (assoc group :checks all-matched
                   :status (cond (contains? statuses :fail) :fail
                                 (contains? statuses :not-run) :not-run
                                 (contains? statuses :not-verified) :not-verified
                                 (empty? all-matched) :not-run
                                 (every? #(= :pass %) statuses) :pass
                                 :else :not-run))))
        verification-groups))

(defn project-chain-provenance [evidence-index]
  (let [evidence-map (:evidence-index/by-content-hash evidence-index {})
        records (sort-by :evidence/chain-seq (vals evidence-map))
        schemes (set (keep :evidence/chain-hash-scheme records))]
    {:evidence-records records
     :evidence-count (count records)
     :hash-scheme (first schemes)
     :scheme-uniform? (<= (count schemes) 1)
     :all-chain-self-hashes (:evidence-index/all-chain-self-hashes evidence-index #{})
     :evidence-index/status (:evidence-index/status evidence-index :unverified)}))

(defn load-workbench-case [run-root]
  (let [r (str run-root)]
    {:run-root r
     :benchmark-definition (load-benchmark-definition (str r "/benchmark/definition.edn"))
     :execution-plan (load-execution-plan (str r "/benchmark/execution-plan.edn"))
     :trust-sequence-definition (load-trust-sequence-definition
                                 (paths/force-authorised-sequence))
     :execution-witness (load-execution-witness (str r "/manifest/execution-witness.json"))
     :canonical-assurance (load-canonical-assurance
                           (str r "/benchmark/assertions/canonical-integrity.json"))
     :package-index (load-package-index (str r "/manifest/run-package-index.json"))
     :completion (load-completion (str r "/completion.json"))}))

(defn validate-workbench-run-layout [run-root]
  (let [r (io/file run-root)
        required ["benchmark/definition.edn" "benchmark/execution-plan.edn"
                  "manifest/execution-witness.json" "event-evidence"
                  "benchmark/assertions/canonical-integrity.json"
                  "manifest/run-package-index.json" "completion.json"]
        results (mapv (fn [p] {:path p :present? (.exists (io/file r p))}) required)
        missing (vec (sort (keep #(when-not (:present? %) (:path %)) results)))]
    {:valid? (empty? missing) :check-count (count required)
     :existing (count (filter :present? results)) :missing missing :results results}))

(defn resolve-workbench-run [case-id]
  (case case-id
    :valid "test/fixtures/trust-boundary/valid"
    :unassured "test/fixtures/trust-boundary/complete-but-unassured"
    (str case-id)))
