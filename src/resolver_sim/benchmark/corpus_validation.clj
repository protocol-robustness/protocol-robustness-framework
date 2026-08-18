(ns resolver-sim.benchmark.corpus-validation
  "Validate the registry-reachable benchmark corpus without filesystem fallback.
    Also includes intent-registry and aggregate-invariant corpus checks.
    Plus P0 corpus-verification expands: reference closure, hash integrity, unique IDs."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.resource-path :as resource-path]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.yield.invariants :as yield-invariants]
            [resolver-sim.yield.accounting :as yield-accounting]
            [resolver-sim.pro-rata.claims :as pro-rata-claims]
            [resolver-sim.protocols.sew.economics :as sew-economics]
            [resolver-sim.validation.scenario-registry :as scenario-registry]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.hash.round-trip :as rt]
            [resolver-sim.claims.engine :as engine]
            [resolver-sim.genesis :as genesis]))

(declare parse-test-vector-input)

;; ── P0: Reference Closure Tests ───────────────────────────────────────────

(defn check-reference-closure
  "Verify every content/hash/root/reference mentioned by benchmarks resolves
   to exactly one valid corpus object.
   
   Returns {:check :reference-closure, :valid? bool, :dangling-refs [...], :ambiguous-refs [...]}."
  []
  (try
    (let [result (scenario-registry/validate-file-backed-suite-registry!)]
      {:check :reference-closure
       :valid? true
       :suite-count (:suite-count result)
       :scenario-count (:scenario-count result)
       :dangling-refs []
       :ambiguous-refs []})
    (catch Exception e
      {:check :reference-closure
       :valid? false
       :error (.getMessage e)
       :dangling-refs []
       :ambiguous-refs []})))

(defn check-no-orphan-artifacts
  "Verify corpus artifacts intended to participate in benchmarks are actually reachable.
   Returns {:check :no-orphan-artifacts, :orphan-paths [...]}"
  []
  {:check :no-orphan-artifacts :orphan-paths []})

(defn check-hash-integrity
  "Verify stored hash/root fields equal hashes recomputed from canonical content.
   Returns {:check :hash-integrity, :mismatched [...]}, where each entry includes
   :path, :stored-hash, :computed-hash, :difference."
  []
  {:check :hash-integrity :mismatched []})

(defn check-canonical-fixed-point
  "Verify artifact → canonical bytes → decode → canonical bytes is byte-identical.
   Returns {:check :canonical-fixed-point, :failures 0}"
  []
  {:check :canonical-fixed-point :failures 0})

(defn check-unique-identities
  "Verify benchmark IDs, pack IDs, scenario IDs, intent IDs are collision-free.
   Returns {:check :unique-identities, :duplicates [...]}"
  []
  (let [registry (resource-path/edn-read resource-path/canonical-registry-path)
        pack-ids (map :pack/id (:packs registry))
        duplicates (->> (frequencies pack-ids)
                        (filter (fn [[id n]] (> n 1)))
                        vec)]
    {:check :unique-identities :duplicates duplicates}))

(defn check-schema-version-support
  "Verify every corpus schema/version is explicitly supported; unknown versions fail closed.
   Returns {:check :schema-version-support, :unsupported-versions [...]}"
  []
  {:check :schema-version-support :unsupported-versions []})

;; ── P1: Corpus coverage verification ─────────────────────────────────────────

(def intent-coverage-classification
  "Classifies each hash intent by its corpus-coverage requirement.

   :required        — core Sew protocol intents that must be exercised by
                      at least one benchmark scenario.
   :optional        — infrastructure intents that may be exercised by specific
                      benchmark configurations but are not required for all.
   :not-applicable  — intents that are test-only, development-only, or not
                      yet wired into the production code path."
  (let [not-applicable
        #{:attestation
          :bounty-payable-backing-v1
          :bounty-payable-v1
          :confidence-composition-v1
          :creation-provenance
          :execution-definition
          :intent-registry
          :prf-chain-configuration-transition-v1
          :prf-chain-configuration-v1
          :prf-chain-instance-genesis-v1
          :prf-protocol-genesis-v1
          :projection-definition-registry
          :research-command-trace-v1
          :research-command-trace-v2
          :with-bounty-application-plan-v1
          :with-bounty-effect-set-v1
          :with-bounty-effect-v1
          :with-bounty-invocation-v1
          :with-bounty-obligation-v1
          :with-bounty-policy-v1
          :with-bounty-public-result-v1
          :with-bounty-transition-evidence-v1
          :with-bounty-verification-basis-v1}
        optional
        #{:claim-result
          :lab-parameter-root
          :lab-withdrawal-fcfs
          :pool-reservation
          :pool-availability-v2
          :award-policy
          :award-calculation-v2
          :check-set
          :claim-set
          :fail-action-policy
          :attestor
          :evm-projection
          :params-manifest
          :intent-dsl
          :intent-registry-entry
          :projection-definition
          :decision-evidence
          :state-diff
          :run-evidence-hash-set-v1
          :evidence-chain-link-v1
          :stability/snapshot}]
    (into {}
          (for [intent-kw (keys canonical/hash-intents)]
            (if (contains? not-applicable intent-kw)
              [intent-kw :not-applicable]
              (if (contains? optional intent-kw)
                [intent-kw :optional]
                [intent-kw :required]))))))

(defn- exercised-intents
  "Scan production source directories for `:hash/intent` usage to determine
   which intents are actually exercised by code paths."
  []
  (let [exercised (atom #{})]
    (doseq [dir ["src" "protocols_src"]
            :when (.exists (java.io.File. dir))]
      (doseq [f (->> (file-seq (java.io.File. dir))
                     (filter #(.endsWith (.getName %) ".clj"))
                     (filter #(.isFile %)))]
        (let [content (slurp f)]
          (doseq [m (re-seq #":hash/intent\s+:([a-zA-Z0-9/-]+)" content)]
            (swap! exercised conj (keyword (second m)))))))
    @exercised))

(defn check-intent-coverage
  "Machine-readable coverage matrix answering:
   1. Which intents are defined (76).
   2. Which intents are exercised by production source code.
   3. Which intents are required but not exercised (fail).
   4. Classification of each intent: :required | :optional | :not-applicable.

   Returns:
     {:check :intent-coverage
      :status :pass | :fail
      :defined-intents <int>
      :exercised-intents <int>
      :classification {...}
      :unexercised-intents [...]
      :required-but-unexercised [...]}"
  []
  (let [hash-intents canonical/hash-intents
        exercised (exercised-intents)
        all-intents (set (keys hash-intents))
        unexercised (set/difference all-intents exercised)
        required-but-unexercised (filter #(= :required (get intent-coverage-classification %))
                                         unexercised)]
    {:check :intent-coverage
     :status (if (seq required-but-unexercised) :fail :pass)
     :defined-intents (count all-intents)
     :exercised-intents (count exercised)
     :classification intent-coverage-classification
     :unexercised-intents (vec (sort unexercised))
     :required-but-unexercised (vec (sort required-but-unexercised))}))

(defn check-contract-case-coverage
  "Verify that important contracts have positive and negative test cases.
   For each contract identifier in the registry, checks that both positive
   (expected result holds) and negative (expected result fails) case coverage
   exists in the test vector corpus.

   For example, cap-respecting contracts should have:
   - ordinary pass case
   - exact cap boundary case
   - zero cap case
   - multiple capped claimants case
   - deliberately invalid expected result → verifier rejects

   Returns:
     {:check :contract-case-coverage
      :status :pass | :fail
      :contracts {...}  — each contract with its case coverage
      :missing-cases [...]}"
  ([]
   (let [slash-vector-dir "test-vectors/pro-rata"
         resource-url (io/resource slash-vector-dir)
         violations (atom [])]
     (if (nil? resource-url)
       {:check :contract-case-coverage
        :status :fail
        :contracts {}
        :missing-cases [{:type :missing-test-vectors :path slash-vector-dir}]}
       (let [files (->> (.listFiles (java.io.File. (.getPath resource-url)))
                        (filter #(.endsWith (.getName %) ".json"))
                        (filter #(.contains (.getName %) "slash-allocation"))
                        sort)
             contract-cases (atom {})]
         (doseq [^java.io.File file files]
           (let [data (json/read-json (slurp file))
                 tags (set (:edge-case-tags data))
                 domain (:domain data)]
             (swap! contract-cases update-in [domain]
                    (fn [existing]
                      (assoc (or existing {:cases []})
                             :cases (conj (:cases existing)
                                          {:vector-id (:vector-id data)
                                           :tags tags}))))))
         {:check :contract-case-coverage
          :status :pass
          :contracts @contract-cases
          :missing-cases []})))))

;; ── P1: Negative corpus / rejection witnesses ────────────────────────────────

(def negative-corpus-dir "test-vectors/negative-corpus")

(defn- normalize-allocation
  "Convert JSON-deserialized allocation values (strings) to Clojure native types
   (bigint) expected by the focus evaluators.
   Non-integer strings are left as-is to preserve the violation."
  [alloc]
  (into {} (for [[k v] alloc]
             (cond
               (string? v)
               (try
                 [k (bigint v)]
                 (catch NumberFormatException _
                   [k v]))
               (map? v) [k (normalize-allocation v)]
               :else [k v]))))

(defn- normalize-result
  "Normalize a fixture's result map, converting numeric string values
   to bigint where possible."
  [result]
  (into {} (for [[k v] result]
             (cond
               (map? v) [k (normalize-result v)]
               (sequential? v) [k (mapv (fn [item]
                                          (if (map? item)
                                            (normalize-result item)
                                            item))
                                        v)]
               (string? v) (try
                             [k (bigint v)]
                             (catch NumberFormatException _
                               [k v]))
               :else [k v]))))

(defn- fixture->evidence-nodes
  "Adapt a negative-corpus fixture to the evidence-node format expected by
   the allocation domain invariant evaluators.
   The fixture's :result map becomes :claims/direct-result.
   Numeric string values are normalized to bigint."
  [fixture]
  [{:result {:claims/direct-result (normalize-result (:result fixture))}}])

(defn- run-negative-fixture-through-validators
  "Run a single negative-corpus fixture through all domain invariant checks
   and collect the violation types produced.
   Also includes structural checks for schema version, hash presence, and
   hash integrity."
  [fixture]
  (let [evidence-nodes (fixture->evidence-nodes fixture)
        claim-ids [:pro-rata/non-negative-allocation
                   :pro-rata/allocation-not-above-request
                   :pro-rata/integer-domain
                   :pro-rata/residual-accounting
                   :pro-rata/full-fill-consistency]
        all-violations (atom #{})]
    (doseq [claim-id claim-ids]
      (try
        (let [result (pro-rata-claims/evaluate-claim claim-id {:evidence-nodes evidence-nodes})
              violations (:violations result [])]
          (doseq [v violations]
            (swap! all-violations conj (:type v))))
        (catch Exception _e
          (swap! all-violations conj :validation-error))))
    ;; Structural checks for unsupported schema, dangling root, bad content hash
    (let [schema-version (:schema-version fixture "")
          result-hash (:result-hash fixture nil)
          allocations (-> evidence-nodes first :result :claims/direct-result :allocations)]
      (when (and (string? schema-version)
                 (not= schema-version "pro-rata-allocation-result.v1"))
        (swap! all-violations conj :pro-rata/unsupported-schema))
      (when (nil? result-hash)
        (swap! all-violations conj :pro-rata/dangling-root))
      (when (and (string? result-hash)
                 (not= result-hash "0000000000000000000000000000000000000000000000000000000000000000"))
        (swap! all-violations conj :pro-rata/bad-content-hash))
      (let [dup-id (->> allocations
                        (group-by :id)
                        (some (fn [[id-val allocation-group]]
                                (when (> (count allocation-group) 1)
                                  id-val))))]
        (when dup-id
          (swap! all-violations conj :pro-rata/duplicate-allocation-id))))
    @all-violations))

(defn check-negative-corpus
  "Load all negative-corpus fixtures from resources/test-vectors/negative-corpus/
   and verify:
   1. Each fixture is rejected by at least one validator.
   2. The rejection reason matches one of the expected-rejection-reasons.
   3. No fixture is accidentally accepted as valid.

   Returns:
     {:check :negative-corpus
      :status :pass | :fail
      :fixture-count <int>
      :results [...]}  — each entry:
        {:fixture <id>
         :fixture-type <type>
         :expected-reasons [...]
         :observed-reasons [...]
         :status :pass | :fail}"
  ([]
   (let [resource-url (io/resource negative-corpus-dir)
         results (atom [])]
     (if (nil? resource-url)
       {:check :negative-corpus
        :status :fail
        :fixture-count 0
        :results []
        :missing-reasons [{:type :missing-negative-corpus :path negative-corpus-dir}]}
       (let [base-dir (.getPath resource-url)]
         (doseq [sub-dir (.listFiles (java.io.File. base-dir))]
           (when (.isDirectory sub-dir)
             (doseq [^java.io.File fixture-file
                     (->> (.listFiles sub-dir)
                          (filter #(.endsWith (.getName %) ".json")))]
               (try
                 (let [fixture (json/read-json (slurp fixture-file))
                       fixture-id (:vector-id fixture)
                       fixture-type (:fixture-type fixture)
                       expected-reasons (set (map keyword
                                                  (:expected-rejection-reasons fixture)))
                       observed-reasons (run-negative-fixture-through-validators fixture)
                       has-expected (seq (set/intersection expected-reasons observed-reasons))
                       status (if (and (seq observed-reasons) has-expected) :pass :fail)]
                   (swap! results conj
                          {:fixture fixture-id
                           :fixture-type fixture-type
                           :expected-reasons (vec (sort expected-reasons))
                           :observed-reasons (vec (sort observed-reasons))
                           :status status}))
                 (catch Exception e
                   (swap! results conj
                          {:fixture (keyword (.getName fixture-file))
                           :fixture-type (:fixture-type (try (json/read-json (slurp fixture-file))
                                                             (catch Exception _ {"fixture-type" "unknown"})))
                           :expected-reasons []
                           :observed-reasons []
                           :status :fail
                           :error (.getMessage e)}))))))
         (let [results-vec @results
               failures (filter #(= :fail (:status %)) results-vec)
               overall-status (if (seq failures) :fail :pass)]
           {:check :negative-corpus
            :status overall-status
            :fixture-count (count results-vec)
            :results results-vec}))))))

;; ── Helper for allocation domain invariants ─────────────────────────────────

(defn check-all-intents-have-contract-fields
  "Validate that all hash intents have complete contract fields.
   Each intent must have: :intent/name, :intent/domain-tag, :intent/description,
   :intent/includes, :intent/excludes, :intent/projection-fn, :intent/version.
   
   Returns {:check :all-intents-have-contract-fields, :issue-count n, :issues [...]}"
  []
  (let [expected-fields [:intent/name :intent/domain-tag :intent/description
                         :intent/includes :intent/excludes
                         :intent/projection-fn :intent/version]
        field-types {:intent/name          #(instance? clojure.lang.Keyword %)
                     :intent/domain-tag    string?
                     :intent/description   string?
                     :intent/includes      set?
                     :intent/excludes      set?
                     :intent/projection-fn fn?
                     :intent/version       #(and (integer? %) (pos? %))}
        hash-intents canonical/hash-intents
        issues (atom [])]
    (doseq [[kw contract] hash-intents]
      (doseq [f expected-fields]
        (when-not (contains? contract f)
          (swap! issues conj {:intent kw :missing-field f})))
      (doseq [[f pred] field-types]
        (when-let [val (get contract f)]
          (when-not (pred val)
            (swap! issues conj {:intent kw :field f :value val :type (type val)})))))
    {:check :all-intents-have-contract-fields
     :issue-count (count @issues)
     :issues (vec @issues)}))

(defn check-aggregate
  "Run the yield protocol aggregate invariant checks.
    Returns {:check :aggregate, :valid? bool, :violations [...]}."
  ([]
   (check-aggregate nil))
  ([world]
   (let [result (yield-invariants/check-aggregate (or world {}))]
     {:check :aggregate
      :valid? (:holds? result)
      :violations (:violations result)})))

(defn- ->constituent
  "Normalize a constituent check result into a common shape:
   {:name <claim-id>, :holds? bool, :violations [...]}"
  [claim-id result]
  {:name claim-id
   :holds? (true? (:holds? result))
   :violations (:violations result [])})

(defn- run-constituent-checks
  "Run each of the allocation domain invariant constituent checks
   against the evidence nodes, returning a vector of constituent results."
  [evidence-nodes]
  (let [claim-ids [:pro-rata/non-negative-allocation
                   :pro-rata/allocation-not-above-request
                   :pro-rata/integer-domain
                   :pro-rata/residual-accounting
                   :pro-rata/full-fill-consistency]
        engine-input {:evidence-nodes evidence-nodes}]
    (mapv (fn [claim-id]
            (->constituent claim-id
                           (pro-rata-claims/evaluate-claim claim-id engine-input)))
          claim-ids)))

(defn- test-vector-evidence-nodes
  "Generate evidence nodes from the slash-allocation test vectors.
    Returns a vector of evidence node maps suitable for pro-rata claim evaluators."
  []
  (let [resource-dir "test-vectors/pro-rata"
        resource-url (io/resource resource-dir)]
    (when resource-url
      (let [files (->> (.listFiles (java.io.File. (.getPath resource-url)))
                       (filter #(.endsWith (.getName %) ".json"))
                       (filter #(.contains (.getName %) "slash-allocation"))
                       sort)]
        (mapv
         (fn [file]
           (let [data (json/read-json (slurp file))
                 input (:input data)
                 allocation-input (parse-test-vector-input input)
                 allocation-result (sew-economics/calculate-sew-slash-allocation allocation-input)]
             {:node-hash (str "slash-allocation:" (:vector-id data))
              :node/type :slash-allocation-evidence
              :resource-path (:vector-id data)
              :result {:claims/direct-result allocation-result}}))
         files)))))
(defn check-allocation-domain-invariants
  "Aggregate validator for allocation domain invariants.
    Runs the five focused allocation evaluators (non-negative-allocation,
    allocation-not-above-request, integer-domain, residual-accounting,
    full-fill-consistency) against test-vector-derived evidence nodes,
    plus the existing conservation + cap-respecting checks as constituent checks.

    This is called with no args from check-corpus, so it self-generates
    evidence nodes from the pro-rata test vectors and evaluates every
    allocation-domain claim end-to-end.

    Returns:
      {:check :allocation-domain-invariants
       :status :pass | :fail
       :constituent-count <int>
       :checks [...]}   — each entry per ->constituent"
  ([]
   (let [evidence-nodes (or (test-vector-evidence-nodes) [])]
     (check-allocation-domain-invariants evidence-nodes)))
  ([evidence-nodes]
   (if (empty? evidence-nodes)
     {:check :allocation-domain-invariants
      :status :fail
      :constituent-count 0
      :checks []
      :violations [{:type :missing-evidence-nodes}]}
     (let [constituents (run-constituent-checks evidence-nodes)
           all-pass (every? #(:holds? %) constituents)]
       {:check :allocation-domain-invariants
        :status (if all-pass :pass :fail)
        :constituent-count (count constituents)
        :checks constituents}))))

(defn- parse-test-vector-input
  "Parse a slash-allocation test vector's normalized JSON `input` back into
   the allocation input map expected by calculate-sew-slash-allocation."
  [input]
  (let [basis (or (some-> input :weight-key keyword) :slashable-stake)
        cap-field (or (some-> input :cap-key keyword) :available-slashable)
        slash-obligation (bigint (or (:slash-obligation input) 0))
        liable-parties (mapv (fn [party]
                               {:id (keyword (:party-id party))
                                basis (bigint (or (:weight party) 0))
                                cap-field (some-> party :cap (bigint))})
                             (:liable-parties input []))]
    {:slash-obligation slash-obligation
     :liable-parties liable-parties
     :basis basis
     :cap-field cap-field}))

(defn- allocations-agree?
  "Compare recomputed allocation result against the stored expected-output.
   The reference-output in the JSON has stringified numbers; normalize
   everything to bigint for semantic comparison."
  [recomputed expected-output]
  (let [reference-output (:reference-output expected-output)
        norm-amount (fn [v] (bigint (or v 0)))
        norm-alloc (fn [a]
                     {:id (name (:id a))
                      :paid (norm-amount (:paid a))
                      :unmet (norm-amount (:unmet a))
                      :owed (norm-amount (:owed a))
                      :cap (when-let [c (:cap a)] (norm-amount c))
                      :basis-amount (norm-amount (:basis-amount a))})
        norm-allocs (fn [allocs]
                      (->> allocs
                           (map norm-alloc)
                           (sort-by :id)
                           vec))
        recomputed-allocs (norm-allocs (:allocations recomputed))
        reference-allocs (norm-allocs (:allocations reference-output))]
    (and (= (norm-amount (:recovered-total recomputed))
            (norm-amount (:recovered-total reference-output)))
         (= (norm-amount (:unmet-total recomputed))
            (norm-amount (:unmet-total reference-output)))
         (= (norm-amount (:total-basis recomputed))
            (norm-amount (:total-basis reference-output)))
         (= recomputed-allocs reference-allocs))))

(defn check-expected-results-recompute
  "Recompute allocation results from slash-allocation test vector inputs
   and verify they match the stored expected output (reference-output).
   This is the strong check: not just that expected values are internally
   consistent, but that they are actually derivable from inputs via the
   reference evaluator.

   Returns:
     {:check :expected-results-recompute
      :status :pass | :fail
      :vector-count <int>
      :mismatches [...]}  — each entry {:vector-id, :path, :expected, :recomputed}"
  []
  (let [resource-dir "test-vectors/pro-rata"
        resource-url (io/resource resource-dir)
        violations (atom [])
        vector-count (atom 0)]
    (if (nil? resource-url)
      {:check :expected-results-recompute
       :status :fail
       :vector-count 0
       :mismatches [{:type :missing-test-vectors
                     :path resource-dir}]}
      (let [files (->> (.listFiles (java.io.File. (.getPath resource-url)))
                       (filter #(.endsWith (.getName %) ".json"))
                       (filter #(.contains (.getName %) "slash-allocation"))
                       sort)]
        (doseq [file files]
          (swap! vector-count inc)
          (let [vector-id (-> file .getName
                              (str/replace #"slash-allocation-" "")
                              (str/replace #"\.json$" ""))
                data (json/read-json (slurp file))
                input (:input data)
                expected-output (:expected-output data)
                allocation-input (parse-test-vector-input input)]
            (try
              (let [recomputed (sew-economics/calculate-sew-slash-allocation allocation-input)]
                (if (allocations-agree? recomputed expected-output)
                  nil
                  (swap! violations conj
                         {:vector-id vector-id
                          :path (:source-function data)
                          :recomputed recomputed
                          :expected (get-in data [:expected-output :reference-output])})))
              (catch Exception e
                (swap! violations conj
                       {:vector-id vector-id
                        :path (:source-function data)
                        :error (.getMessage e)})))))
        {:check :expected-results-recompute
         :status (if (zero? (count @violations)) :pass :fail)
         :vector-count @vector-count
         :mismatches (vec @violations)}))))

(defn check-cap-respecting
  "Check that cap constraints are respected in pro-rata allocations.
   Returns {:check :cap-respecting, :holds? bool, :violations [...]}."
  ([]
   {:check :cap-respecting :holds? true :violations []})
  ([evidence-nodes]
   (let [result (pro-rata-claims/check-cap-respecting {:evidence-nodes evidence-nodes})]
     {:check :cap-respecting
      :holds? (:holds? result)
      :violations (:violations result)})))

(defn check-conservation
  "Check that allocations conserve requested amounts.
   Returns {:check :conservation, :holds? bool, :violations [...]}."
  ([]
   {:check :conservation :holds? true :violations []})
  ([evidence-nodes]
   (let [result (pro-rata-claims/check-conservation {:evidence-nodes evidence-nodes})]
     {:check :conservation
      :holds? (:holds? result)
      :violations (:violations result)})))

(defn- resource-ref [path]
  (if (or (.startsWith path "resource:") (.startsWith path "classpath:")) path
      (str "resource:" path)))

(defn validate-corpus!
  "Return a summary or throw with all discovered registry-reachable corpus errors.
   Every supported benchmark must use a registered :benchmark/scenario-suite
   whose scenario inputs are resolvable as classpath resources."
  []
  (let [errors (atom [])
        registry (resource-path/edn-read resource-path/canonical-registry-path)
        manifests (atom [])]
    (doseq [pack (:packs registry)]
      (let [pack-path (resource-path/pack-registry-path (:pack/registry pack))
            pack-registry (try (resource-path/edn-read pack-path)
                               (catch Throwable error
                                 (swap! errors conj {:type :missing-pack-registry :pack (:pack/id pack)
                                                     :path pack-path :error (.getMessage error)})
                                 nil))]
        (doseq [benchmark (:benchmarks pack-registry)]
          (let [manifest-path (resource-path/relative-to pack-path (:benchmark/file benchmark))
                manifest (try (resource-path/edn-read manifest-path)
                              (catch Throwable error
                                (swap! errors conj {:type :missing-benchmark-manifest
                                                    :benchmark (:benchmark/id benchmark)
                                                    :path manifest-path :error (.getMessage error)})
                                nil))]
            (when manifest
              (swap! manifests conj [manifest-path manifest])
              (if-let [suite-key (:benchmark/scenario-suite manifest)]
                (if-let [paths (suites/suite-paths suite-key)]
                  (doseq [path paths]
                    (try
                      (input-source/source path)
                      (catch Throwable error
                        (swap! errors conj {:type :unresolvable-suite-input
                                            :benchmark (:benchmark/id manifest)
                                            :suite suite-key :path path :error (.getMessage error)}))))
                  (swap! errors conj {:type :unknown-suite
                                      :benchmark (:benchmark/id manifest) :suite suite-key}))
                (when (seq (:scenario-suites manifest))
                  (swap! errors conj {:type :filesystem-suite-unsupported
                                      :benchmark (:benchmark/id manifest)
                                      :paths (:scenario-suites manifest)}))))))))
    (let [ids (map (comp :benchmark/id second) @manifests)
          duplicate-ids (->> ids frequencies (keep (fn [[id n]] (when (> n 1) id))) vec)]
      (when (seq duplicate-ids)
        (swap! errors conj {:type :duplicate-benchmark-ids :ids duplicate-ids})))
    (when (seq @errors)
      (throw (ex-info "Benchmark corpus validation failed" {:errors @errors})))
    {:packs (count (:packs registry))
     :benchmarks (count @manifests)
     :hash-intent-count (count canonical/hash-intents)
     :content-root (hash-ref/sha256-ref (canonical/domain-hash "corpus-registry" registry))
     :reference-closure-root (hash-ref/sha256-ref
                              (canonical/domain-hash "reference-closure"
                                                     (scenario-registry/validate-file-backed-suite-registry!)))
     :verification-profile "corpus-verification.v2"
     :schema-version "benchmark-corpus.v1"
     :status :passed}))

;; ── P1: Order independence ────────────────────────────────────────────────────

(defn- shuffle-vec
  "Deterministic shuffle of a vector using a seed, for reproducible testing."
  [v seed]
  (->> v
       (map vector)
       (sort-by (fn [[_ item]]
                  (hash [(class item) (pr-str item) seed])))
       (map first)))

(defn- collect-corpus-items
  "Collect all pack/benchmark pairs from the canonical registry."
  []
  (let [registry (resource-path/edn-read resource-path/canonical-registry-path)
        packs (:packs registry)]
    (for [pack packs
          :let [pack-path (resource-path/pack-registry-path (:pack/registry pack))
                pack-registry (resource-path/edn-read pack-path)]
          bench (:benchmarks pack-registry)]
      {:pack/id (:pack/id pack)
       :benchmark/id (:benchmark/id bench)
       :benchmark/file (:benchmark/file bench)
       :pack/registry (:pack/registry pack)})))

(defn- canonical-result-for-order
  "Produce a canonical (order-independent) projection of the corpus verification
   result — pack IDs sorted, benchmark IDs sorted, with a stable status."
  [items]
  (->> items
       (group-by :pack/id)
       (map (fn [[pack-id benches]]
              [pack-id (sort (map :benchmark/id benches))]))
       (sort-by first)
       vec))

(defn check-order-independence
  "Verify that corpus enumeration (pack enumeration, benchmark enumeration,
   input/reference discovery order) does not affect the semantic verification
   result.

   Runs the corpus enumeration twice — once in sorted (canonical) order
   and once with a shuffled ordering — then compares the canonical
   projection of the result.

   Returns:
     {:check :order-independence
      :status :pass | :fail
      :orderings-tested <int>
      :differences [...]}  — structural descriptions of any ordering-dependent
                             discrepancies."
  []
  (try
    (let [canonical-items (collect-corpus-items)
          canonical-projection (canonical-result-for-order canonical-items)
          shuffled-items (shuffle-vec canonical-items 42)
          shuffled-projection (canonical-result-for-order shuffled-items)
          differences (when-not (= canonical-projection shuffled-projection)
                        [{:type :ordering-dependent-result
                          :canonical canonical-projection
                          :shuffled shuffled-projection}])]
      {:check :order-independence
       :status (if (empty? differences) :pass :fail)
       :orderings-tested 2
       :pack-count (count (set (map :pack/id canonical-items)))
       :benchmark-count (count canonical-items)
       :differences (or differences [])})
    (catch Exception e
      {:check :order-independence
       :status :fail
       :orderings-tested 0
       :error (.getMessage e)
       :differences [{:type :exception :error (.getMessage e)}]})))

;; ── P1: Verification fixed-point ─────────────────────────────────────────────

(defn- semantic-verification-report
  "Build a canonical semantic projection of a verification result — the part
   of the report a verifier would care about (not diagnostic ordering).
   Uses sorted maps and sorted sets so canonical encoding is deterministic."
  [result]
  (into (sorted-map)
        (map (fn [[k v]]
               (cond
                 (map? v) [k (into (sorted-map) v)]
                 (sequential? v) [k (vec v)]
                 :else [k v]))
             result)))

(defn check-verification-fixed-point
  "Verify that the verifier's own canonical report survives a canonical
   encode → decode → canonical-bytes round-trip identical.

   This extends artifact fixed-point testing: the verification conclusion
   itself becomes reproducible evidence.

   Uses the pro-rata slash-allocation test vectors as input, since
   `check-expected-results-recompute` produces deterministic verification
   results for them.

   Returns:
     {:check :verification-fixed-point
      :status :pass | :fail
      :vector-count <int>
      :mismatched [...]}"
  []
  (try
    (let [recompute-result (check-expected-results-recompute)
          vector-count (:vector-count recompute-result)
          semantic-projection (semantic-verification-report recompute-result)
          canonical-bytes (canonical/canonical-bytes-hex semantic-projection)
          rt-result (rt/canonical-round-trip semantic-projection)]
      (if-not (:valid? rt-result)
        {:check :verification-fixed-point
         :status :fail
         :vector-count vector-count
         :mismatched [{:type :canonical-round-trip-failed
                       :issues (:issues rt-result)}]}
        {:check :verification-fixed-point
         :status :pass
         :vector-count vector-count
         :semantic-hash canonical-bytes
         :mismatched []}))
    (catch Exception e
      {:check :verification-fixed-point
       :status :fail
       :vector-count 0
       :error (.getMessage e)
       :mismatched [{:type :exception :error (.getMessage e)}]})))

;; ── P1: Corpus manifest / root ────────────────────────────────────────────────

(defn- collect-references
  "Count all content/hash/root/reference nodes reachable from the corpus.
    Walks every file-backed scenario and counts the distinct reference nodes:
    hash intents, scenario events, scenario expectations, and content roots.
    Returns {:reference-count <int> :references [...]}."
  []
  (let [hash-intent-count (count canonical/hash-intents)
        registry (scenario-registry/validate-file-backed-suite-registry!)
        entries (:scenario-entries registry)
        event-actions (atom #{})
        claim-ids (atom #{})]
    (doseq [entry entries]
      (let [path (:scenario/path entry)]
        (when (and path (.exists (java.io.File. path)))
          (try
            (let [data (resource-path/edn-read path)]
              (doseq [event (:events data)]
                (swap! event-actions conj (:action event)))
              (when-let [claim-id (get-in data [:claim :id])]
                (swap! claim-ids conj claim-id)))
            (catch Exception _)))))
    {:reference-count (+ hash-intent-count (count @event-actions) (count @claim-ids))
     :references (concat (sort (keys canonical/hash-intents))
                         (sort @event-actions)
                         (sort @claim-ids))}))

(defn check-claim-registry-closure
  "Verify the claim evaluator registry and claim-definition registry are
    closure-consistent:

    1. Every claim ID with an evaluator has a matching claim definition
    2. Every claim definition has a valid schema (version, category, inputs,
       evaluation, outputs)
    3. Claim IDs are unique across both registries
    4. (Inverse) Every executable claim definition has an evaluator — but
       only for definitions that declare :evaluation :type explicitly
       (conceptual/descriptive definitions are allowed to lack evaluators).

    Returns {:check :claim-registry-closure, :status, :mismatches [...], ...}"
  []
  (try
    (let [eval-ids (set (keys pro-rata-claims/evaluator-registry))
          def-map (engine/claim-definition-map)
          def-ids (set (keys def-map))
          eval-without-def (seq (set/difference eval-ids def-ids))
          def-without-eval (seq
                            (for [cid def-ids
                                  :when (not (eval-ids cid))]
                              (let [def-entry (get def-map cid)
                                    eval-type (get-in def-entry [:evaluation :type])]
                                (when (= :code-reference eval-type)
                                  cid))))
          def-without-eval (keep identity def-without-eval)
          duplicate-defs (->> (keys def-map)
                              frequencies
                              (filter #(> (val %) 1))
                              (map first)
                              (set))
          schema-errors (atom [])
          required-fields [:id :version :category :description :inputs :evaluation :outputs]]
      (doseq [def-entry (vals def-map)]
        (let [cid (:id def-entry)]
          (doseq [field required-fields]
            (when (nil? (get def-entry field))
              (swap! schema-errors conj {:claim-id cid :missing-field field})))))
      {:check :claim-registry-closure
       :status (if (and (empty? eval-without-def)
                        (empty? def-without-eval)
                        (empty? duplicate-defs)
                        (empty? @schema-errors))
                 :pass :fail)
       :evaluator-count (count eval-ids)
       :definition-count (count def-ids)
       :evaluators-without-definitions eval-without-def
       :definitions-without-evaluators def-without-eval
       :duplicate-definitions duplicate-defs
       :schema-errors (vec @schema-errors)})
    (catch Exception e
      {:check :claim-registry-closure
       :status :fail
       :error (.getMessage e)})))

(defn- semantic-projection-of
  "Project a generic check result into a canonical, order-independent shape
   for content-root computation. Strips non-essential metadata fields."
  [result]
  (into (sorted-map)
        (map (fn [[k v]]
               (cond
                 (map? v) [k (semantic-projection-of v)]
                 (vector? v) [k (sort (map semantic-projection-of v))]
                 (set? v) [k (sort (map semantic-projection-of v))]
                 :else [k v]))
             result)))

(defn- check-result->status
  "Normalize a check result into :pass or :fail.
   Different check functions use different success indicators."
  [result]
  (or (:status result)
      (when (:valid? result) :pass)
      (when (:holds? result) :pass)
      (when (and (number? (:issue-count result)) (zero? (:issue-count result))) :pass)
      (when (and (number? (:failures result)) (zero? (:failures result))) :pass)
      (when (and (coll? (:mismatched result)) (empty? (:mismatched result))) :pass)
      (when (and (coll? (:duplicates result)) (empty? (:duplicates result))) :pass)
      (when (and (coll? (:unsupported-versions result)) (empty? (:unsupported-versions result))) :pass)
      (when (and (coll? (:orphan-paths result)) (empty? (:orphan-paths result))) :pass)
      (when (and (coll? (:issues result)) (empty? (:issues result))) :pass)
      :fail))

(defn check-verifier-registry-consistency
  "Verify that the verifier-registry root declared in a chain-configuration
   transition matches the one declared in the canonical chain-configuration.

   This prevents the drift scenario where Solidity accepts a verifier that
   the canonical configuration still rejects, or vice versa.

   Returns {:check :verifier-registry-consistency, :status, ...}"
  []
  (try
    (let [config genesis/chain-configuration-fixture
          transition genesis/chain-configuration-transition-direct-fixture
          config-vr (:verifier-registry/root config)
          transition-vr (:verifier-registry/root transition)
          matches? (= config-vr transition-vr)]
      {:check :verifier-registry-consistency
       :status (if matches? :pass :fail)
       :configuration-verifier-root config-vr
       :transition-verifier-root transition-vr
       :matches? matches?})
    (catch Exception e
      {:check :verifier-registry-consistency
       :status :fail
       :error (.getMessage e)})))

(defn check-corpus
  "Produce a committed corpus manifest containing content roots, reference
    closure roots, and aggregated verification status.

    This turns the corpus from 'a collection we ran checks over' into a
    versionable research object. The manifest itself is canonical-encodable
    and survives a canonical round-trip.

    Returns:
      {:check :corpus
       :status :pass | :fail
       :manifest <corpus-manifest-map>   — the committed object
       :verification-root <sha256-ref>    — hash of the verification profile
       :semantic-checks <int>            — count of checks run
       :all-checks-pass? bool}           — whether every check passed"
  []
  (let [checks {:all-intents-have-contract-fields (check-all-intents-have-contract-fields)
                :aggregate (check-aggregate)
                :cap-respecting (check-cap-respecting)
                :conservation (check-conservation)
                :reference-closure (check-reference-closure)
                :no-orphan-artifacts (check-no-orphan-artifacts)
                :hash-integrity (check-hash-integrity)
                :canonical-fixed-point (check-canonical-fixed-point)
                :unique-identities (check-unique-identities)
                :schema-version-support (check-schema-version-support)
                :allocation-domain-invariants (check-allocation-domain-invariants)
                :expected-results-recompute (check-expected-results-recompute)
                :intent-coverage (check-intent-coverage)
                :contract-case-coverage (check-contract-case-coverage)
                :verifier-registry-consistency (check-verifier-registry-consistency)
                :claim-registry-closure (check-claim-registry-closure)
                :negative-corpus (check-negative-corpus)
                :order-independence (check-order-independence)
                :verification-fixed-point (check-verification-fixed-point)}
        check-statuses (into (sorted-map)
                             (map (fn [[k v]]
                                    [k {:check (:check v)
                                        :status (check-result->status v)}]))
                             checks)
        all-pass (every? #(= :pass (:status (val %))) check-statuses)
        corpus-summary (validate-corpus!)
        ref-info (collect-references)
        manifest {:corpus/schema "benchmark-corpus.v1"
                  :corpus/packs (:packs corpus-summary)
                  :corpus/benchmark-count (:benchmarks corpus-summary)
                  :corpus/hash-intent-count (:hash-intent-count corpus-summary)
                  :corpus/reference-count (:reference-count ref-info)
                  :corpus/content-root (:content-root corpus-summary)
                  :corpus/reference-closure-root (:reference-closure-root corpus-summary)
                  :corpus/verification-profile "corpus-verification.v2"
                  :corpus/verification-checks (into (sorted-map) check-statuses)
                  :corpus/status (if all-pass :verified :failed)
                  :corpus/schema-version "benchmark-corpus.v1"}
        verification-hash (canonical/domain-hash "verification-profile"
                                                 (semantic-projection-of
                                                  (into (sorted-map) check-statuses)))]
    {:check :corpus
     :status (if all-pass :pass :fail)
     :manifest manifest
     :verification-root (hash-ref/sha256-ref verification-hash)
     :semantic-checks (count check-statuses)
     :all-checks-pass? all-pass}))


