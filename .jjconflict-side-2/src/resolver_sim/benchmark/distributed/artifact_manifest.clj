(ns resolver-sim.benchmark.distributed.artifact-manifest
  "Rooted successor for canonical benchmark artifact manifests."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "benchmark-artifact-manifest.v2")
(def ^:private domain-tag "PRF_BENCHMARK_ARTIFACT_MANIFEST_V2")

(defn manifest-body
  "Return the canonical V2 body committed by an artifact-manifest root."
  [manifest]
  (select-keys manifest [:artifact/manifest-version :artifacts]))

(defn manifest-root
  "Derive the aggregate identity of the existing ordered artifact manifest body."
  [manifest]
  (hash-ref/sha256-ref (hc/domain-hash domain-tag (manifest-body manifest))))

(defn rooted-manifest
  "Upgrade canonical V1 manifest contents into a derived-root V2 manifest."
  [manifest]
  (let [body {:artifact/manifest-version schema
              :artifacts (:artifacts manifest)}]
    (assoc body :artifact-manifest/root (manifest-root body))))

(defn verify-rooted-manifest
  "Return true only when a V2 manifest carries its derived aggregate root."
  [manifest]
  (and (= schema (:artifact/manifest-version manifest))
       (hash-ref/valid-sha256-ref? (:artifact-manifest/root manifest))
       (= (:artifact-manifest/root manifest) (manifest-root manifest))))
