(ns resolver-sim.benchmark.distributed.executable-distribution
  "Canonical executable-distribution commitment and worker-side preflight verification."
  (:require [resolver-sim.execution.context :as execution]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "benchmark-executable-distribution.v2")
(def ^:private domain-tag "PRF_BENCHMARK_EXECUTABLE_DISTRIBUTION_V2")
(def ^:private semantic-options-domain-tag
  "PRF_BENCHMARK_SEMANTIC_CLAIMANT_OPTIONS_V1")
;; Execution capacity, scheduling, and timeout controls affect only how work is
;; performed. They are accepted as runtime profile inputs but never committed
;; into executable-distribution identity.
(def ^:private runtime-claimant-option-fields
  #{:execution/claimant-parallelism
    :execution/claimant-parallel-threshold
    :execution/quiescence-timeout-seconds})

;; Reserved for claimant/economic options whose contract changes benchmark
;; semantics. It is intentionally empty until such a closed semantic contract
;; exists.
(def semantic-claimant-option-fields #{})

(defn semantic-claimant-options-root [options]
  (hash-ref/sha256-ref
   (hc/domain-hash semantic-options-domain-tag options)))

(defn normalized-semantic-claimant-options
  "Return the closed, default-normalized semantic claimant-options projection.
   Unknown keys are rejected rather than silently omitted from the commitment."
  [options]
  (let [options (or options {})]
    (when-not (every? #(or (contains? semantic-claimant-option-fields %)
                           (contains? runtime-claimant-option-fields %))
                      (keys options))
      (throw (ex-info "Unknown claimant option"
                      {:reason :invalid-claimant-option
                       :options options
                       :allowed (into semantic-claimant-option-fields
                                      runtime-claimant-option-fields)})))
    (select-keys (execution/validate-context options)
                 semantic-claimant-option-fields)))

(defn- semantic-options-projection [options]
  (let [options (or (:semantic-claimant-options/options options) options)
        normalized (normalized-semantic-claimant-options options)]
    {:semantic-claimant-options/schema "semantic-claimant-options.v1"
     :semantic-claimant-options/version 1
     :semantic-claimant-options/options normalized
     :semantic-claimant-options/root (semantic-claimant-options-root normalized)}))

(defn distribution-body
  "The V2 semantic executable material committed by the distribution root.
   Plans and local placement controls are deliberately excluded."
  [distribution]
  (let [artifact-root (:executable-artifact/root distribution)]
    (when-not (hash-ref/valid-sha256-ref? artifact-root)
      (throw (ex-info "Executable distribution requires an executable artifact root"
                      {:reason :invalid-executable-distribution-artifact-root
                       :executable-artifact/root artifact-root})))
    {:executable-distribution/schema schema
     :executable-artifact/root artifact-root
     :semantic-claimant-options (semantic-options-projection
                                 (:semantic-claimant-options distribution))}))

(defn distribution-root [distribution]
  (hash-ref/sha256-ref (hc/domain-hash domain-tag (distribution-body distribution))))

(defn build-distribution [distribution]
  (assoc (distribution-body distribution)
         :executable-distribution/root (distribution-root distribution)))

(defn verify-distribution [distribution]
  (and (= schema (:executable-distribution/schema distribution))
       (hash-ref/valid-sha256-ref? (:executable-distribution/root distribution))
       (= (:executable-distribution/root distribution) (distribution-root distribution))))

(defn expected-shape
  "Project the expected executable-distribution binding carried to a worker."
  [distribution]
  (select-keys distribution [:executable-artifact/root
                             :semantic-claimant-options
                             :executable-distribution/root]))

(defn observed-shape
  "Project worker-observed distribution material into its preflight shape."
  [distribution]
  (select-keys distribution [:executable-artifact/root
                             :semantic-claimant-options
                             :executable-distribution/root]))

(defn distribution-preflight
  "Return a typed pre-execution distribution-preflight result; this never allocates a lease."
  [{:keys [expected observed]}]
  (cond
    (nil? expected) {:distribution-preflight/status :unresolved
                     :reason :execution/distribution-preflight-unresolved}
    (nil? observed) {:distribution-preflight/status :unresolved
                     :reason :execution/distribution-preflight-unresolved}
    (not (verify-distribution observed))
    {:distribution-preflight/status :mismatched
     :reason :execution/distribution-preflight-invalid}
    (not= expected (observed-shape observed))
    {:distribution-preflight/status :mismatched
     :reason :execution/distribution-preflight-mismatch
     :expected expected
     :observed (observed-shape observed)}
    :else {:distribution-preflight/status :verified
           :expected expected
           :observed (observed-shape observed)}))

(def verify-expected-observed distribution-preflight)
