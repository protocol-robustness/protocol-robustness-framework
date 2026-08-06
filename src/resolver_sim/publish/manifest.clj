(ns resolver-sim.publish.manifest
  "All-or-nothing artifact-set verification for out-of-process publishing.

   An artifact set is a directory of stage files plus a manifest. The manifest
   declares the full set (path → sha256). Verification is all-or-nothing:

     - every required path must be declared in the manifest;
     - every declared path must exist on disk and hash to the declared sha256.

   Any missing or modified artifact fails the whole set (fail-closed). This is
   the file-integrity half of the publisher; the signed decision envelope is
   handled by `resolver-sim.publish.contract` and the authority in
   `resolver-sim.commands.publish`."
  (:require [buddy.core.codecs :as codecs]
            [resolver-sim.hash.canonical :as hc]
            [clojure.java.io :as io])
  (:import [java.security MessageDigest]))

(def manifest-domain "PRF_ARTIFACT_PUBLISH_MANIFEST_V1")

(defn file-sha256
  "Compute the lowercase sha256 hex digest of a file's bytes."
  ^String [^java.io.File f]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream f)]
      (let [buf (byte-array 65536)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n)
              (.update digest buf 0 n)
              (recur))))))
    (codecs/bytes->hex (.digest digest))))

(defn normalized-entries
  "Sort manifest entries by their relative path for deterministic commitments."
  [entries]
  (vec (sort-by :path entries)))

(defn manifest-commit
  "Canonical, domain-separated commitment to a manifest (path → sha256) plus
   its run identity. Excludes nothing: the whole declared set is bound."
  [run-id entries]
  (str "sha256:"
       (hc/domain-hash manifest-domain
                       {:run-id run-id
                        :entries (vec (for [{:keys [path sha256]} (normalized-entries entries)]
                                        {:path path :sha256 sha256}))})))


(defn verify-set
  "All-or-nothing verification of a staged artifact set.

   opts: {:root <dir> :entries <[{:path str :sha256 hex}]> :required <[str]>}

   The `:root` is the stage directory; `:path` values are relative to it.
   Returns {:ok? true :entries <sorted>} or
   {:ok? false :reason <kw> :detail <map of per-artifact findings>}.

   Fail-closed conditions (any triggers rejection):
     - :required path not declared in the manifest   → :undeclared-required
     - declared path does not exist on disk          → :missing-file
     - declared sha256 differs from the file bytes   → :hash-mismatch"
  [{:keys [root entries required]}]
  (let [root-file (io/file root)
        by-path (into {} (map (juxt :path identity)) entries)
        required-set (set (map str required))
        declared-set (set (keys by-path))
        undeclared-required (vec (sort (remove declared-set required-set)))
        problems (keep (fn [{:keys [path sha256]}]
                         (let [f (io/file root-file path)]
                           (cond
                             (not (and (.exists f) (.isFile f)))
                             {:path path :issue :missing-file :expected sha256}

                             (let [actual (file-sha256 f)]
                               (not= sha256 actual))
                             {:path path :issue :hash-mismatch :expected sha256 :actual (file-sha256 f)})))
                       entries)]
    (cond
      (seq undeclared-required)
      {:ok false :reason :undeclared-required :detail {:paths undeclared-required}}

      (seq problems)
      {:ok false :reason :incomplete-or-modified :detail {:problems (vec problems)}}

      :else
      {:ok true :checks {:paths (vec required-set)
                         :entry-count (count entries)}})))