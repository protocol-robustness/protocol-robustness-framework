(ns resolver-sim.benchmark.distributed.chunk-result
  "Canonical detached result manifest for one fixed benchmark execution chunk.

   The manifest is built only after the worker has produced the chunk's staged
   execution artifacts. It binds those artifacts to the claimed fixed-chunk
   descriptor without publishing them into the canonical benchmark package."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "benchmark-detached-chunk-result.v1")
(def ^:private domain-tag "PRF_BENCHMARK_DETACHED_CHUNK_RESULT_V1")

(def ^:private fields
  #{:artifact/schema
    :chunk/id
    :run-plan/root
    :execution-plan/root
    :chunk/input-root
    :chunk/work-root
    :executable-distribution/root
    :chunk/execution-ids
    :chunk/staged-artifact-manifest-roots
    :chunk/result-root
    :sensitivity/root
    :detached-chunk-result/root})

(def ^:private root-fields
  #{:run-plan/root
    :execution-plan/root
    :chunk/input-root
    :chunk/work-root
    :executable-distribution/root
    :chunk/result-root
    :sensitivity/root
    :detached-chunk-result/root})

(defn- root? [value]
  (and (string? value) (hash-ref/valid-sha256-ref? value)))

(defn- nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn result-root
  "Derive the semantic result commitment from ordered execution/artifact rows."
  [{:keys [chunk-execution-ids chunk-staged-artifact-manifest-roots]
    :as manifest}]
  (let [execution-ids (or chunk-execution-ids (:chunk/execution-ids manifest))
        staged-roots (or chunk-staged-artifact-manifest-roots
                         (:chunk/staged-artifact-manifest-roots manifest))]
    (hash-ref/sha256-ref
     (hc/domain-hash "PRF_BENCHMARK_CHUNK_RESULT_V1"
                     {:chunk/id (:chunk/id manifest)
                      :run-plan/root (:run-plan/root manifest)
                      :execution-plan/root (:execution-plan/root manifest)
                      :chunk/input-root (:chunk/input-root manifest)
                      :chunk/work-root (:chunk/work-root manifest)
                      :executable-distribution/root (:executable-distribution/root manifest)
                      :chunk/execution-results
                      (mapv (fn [execution-id manifest-root]
                              {:execution/id execution-id
                               :staged-artifact-manifest/root manifest-root})
                            execution-ids staged-roots)}))))

(defn- root
  [manifest]
  (hash-ref/sha256-ref
   (hc/domain-hash domain-tag
                   (dissoc manifest :detached-chunk-result/root))))

(defn validate-manifest
  "Return structured validation for a closed detached chunk-result manifest."
  [manifest]
  (let [keys* (set (keys manifest))
        execution-ids (:chunk/execution-ids manifest)
        staged-roots (:chunk/staged-artifact-manifest-roots manifest)
        errors (cond-> []
                 (not (map? manifest))
                 (conj "manifest must be a map")

                 (and (map? manifest) (not= fields keys*))
                 (conj "manifest must have exactly the detached chunk-result fields")

                 (and (map? manifest) (not= schema (:artifact/schema manifest)))
                 (conj "manifest has an unsupported schema")

                 (and (map? manifest) (not (nonblank? (:chunk/id manifest))))
                 (conj "chunk/id must be a nonblank string")

                 (and (map? manifest)
                      (some #(not (root? (get manifest %))) root-fields))
                 (conj "all root fields must be canonical SHA-256 references")

                 (and (map? manifest)
                      (not (and (vector? execution-ids)
                                (seq execution-ids)
                                (every? nonblank? execution-ids)
                                (= (count execution-ids) (count (set execution-ids))))))
                 (conj "chunk/execution-ids must be a nonempty vector of unique nonblank strings")

                 (and (map? manifest)
                      (not (and (vector? staged-roots)
                                (= (count execution-ids) (count staged-roots))
                                (every? root? staged-roots))))
                 (conj "chunk/staged-artifact-manifest-roots must align with execution IDs and contain canonical SHA-256 references")

                 (and (map? manifest)
                      (root? (:chunk/result-root manifest))
                      (not= (:chunk/result-root manifest) (result-root manifest)))
                 (conj "chunk result root does not match canonical execution-result rows")

                 (and (map? manifest)
                      (root? (:detached-chunk-result/root manifest))
                      (not= (:detached-chunk-result/root manifest) (root manifest)))
                 (conj "detached chunk-result root does not match canonical content"))]
    {:valid? (empty? errors) :errors errors}))

(defn build-manifest
  "Build a closed, self-rooted detached chunk-result manifest.

   The caller supplies only the semantic fields. This function derives the
   schema and self-root; it never accepts a caller-provided manifest root."
  [manifest]
  (let [candidate (-> manifest
                      (dissoc :detached-chunk-result/root :chunk/result-root)
                      (assoc :artifact/schema schema))
        candidate (assoc candidate :chunk/result-root (result-root candidate))
        rooted (assoc candidate :detached-chunk-result/root (root candidate))
        result (validate-manifest rooted)]
    (when-not (:valid? result)
      (throw (ex-info "Detached chunk-result manifest is invalid"
                      {:reason :invalid-detached-chunk-result
                       :errors (:errors result)})))
    rooted))

(defn verify-manifest
  "Return whether a detached chunk-result manifest is structurally and
   cryptographically valid."
  [manifest]
  (:valid? (validate-manifest manifest)))
