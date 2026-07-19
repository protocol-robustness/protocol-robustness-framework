#!/usr/bin/env clojure
(ns scripts.validate-claim-bindings
  "CI acceptance gate for public headline claim provenance."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.benchmark.claims :as benchmark-claims]
            [resolver-sim.benchmark.verify :as benchmark-verify])
  (:import [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def registry-path "benchmarks/public-headline-claims.edn")
(def default-report-path "target/claim-binding-report.json")
(def sha-pattern #"(?:sha256:)?[0-9a-f]{64}")

(defn- sha256 [value]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                            (.getBytes (str value) StandardCharsets/UTF_8)))))
(defn- sha-ref [file] (str "sha256:" (sha256 (slurp file))))
(defn- error [claim-id code detail] {:claim/id claim-id :code code :detail detail})

(defn- manifests []
  (into {}
        (keep (fn [file]
                (try
                  (let [manifest (edn/read-string (slurp file))]
                    (when-let [id (:benchmark/id manifest)] [id manifest]))
                  (catch Exception _ nil))))
        (filter #(.isFile ^java.io.File %) (file-seq (io/file "benchmarks/packs")))))

(defn- declared? [manifest claim-id]
  (some #(= claim-id (:claim/id %)) (:benchmark/claims manifest)))

(defn- package-errors [claim]
  (mapcat (fn [package]
            (let [root (:run/root package)
                  evidence-ref (:evidence/ref package)
                  evidence-file (when root (io/file root evidence-ref))
                  package-file (when-let [uri (:package/uri package)]
                                 (when (str/starts-with? uri "file:") (io/file (subs uri 5))))
                  verification (when (and root (.isDirectory (io/file root)))
                                 (benchmark-verify/verify! root))]
              (cond-> []
                (not (and (string? (:package/uri package)) (re-matches sha-pattern (:package/sha256 package))))
                (conj (error (:claim/id claim) :package-reference-invalid "package URI or SHA-256 is invalid"))
                (and package-file (not= (:package/sha256 package) (sha-ref package-file)))
                (conj (error (:claim/id claim) :package-hash-stale (:package/uri package)))
                (not (and evidence-file (.isFile evidence-file)))
                (conj (error (:claim/id claim) :evidence-reference-missing evidence-ref))
                (and evidence-file (.isFile evidence-file) (not= (:evidence/sha256 package) (sha-ref evidence-file)))
                (conj (error (:claim/id claim) :evidence-hash-stale evidence-ref))
                (not= "passed" (get verification "status"))
                (conj (error (:claim/id claim) :package-verification-failed (or (get verification "error") root))))))
          (:evidence/packages claim)))

(defn- claim-errors [manifests claim]
  (let [id (:claim/id claim)
        required [:claim/id :claim/version :claim/text :claim/scope :claim/assumptions
                  :benchmark/claims :evaluator/id :evaluator/version :evaluator/implementation
                  :policy/canonical-edn :policy/sha256 :evidence/packages]
        missing (filter #(not (contains? claim %)) required)
        implementation (:evaluator/implementation claim)
        source (io/file (:path implementation))]
    (vec (concat
          (map #(error id :binding-field-missing (name %)) missing)
          (when-not (and (string? id) (re-matches #"PHC-[0-9]{4}" id))
            [(error id :stable-claim-id-invalid (str id))])
          (when-not (and (string? (:claim/text claim)) (string? (:claim/scope claim)) (seq (:claim/assumptions claim)))
            [(error id :claim-text-or-scope-invalid "exact text, scope, and assumptions are required")])
          (when-not (benchmark-claims/evaluator-resolver (:evaluator/id claim))
            [(error id :evaluator-unresolved (str (:evaluator/id claim)))])
          (when-not (and (.isFile source) (= (:sha256 implementation) (sha256 (slurp source))))
            [(error id :evaluator-source-stale (:path implementation))])
          (when-not (= (:policy/sha256 claim) (sha256 (:policy/canonical-edn claim)))
            [(error id :policy-hash-stale "canonical policy text does not match hash")])
          (mapcat (fn [{:benchmark/keys [id] :keys [claims]}]
                    (let [manifest (get manifests id)]
                      (concat
                       (when-not manifest [(error (:claim/id claim) :benchmark-unresolved (str id))])
                       (mapcat (fn [claim-id]
                                 (concat
                                  (when-not (and manifest (declared? manifest claim-id))
                                    [(error (:claim/id claim) :benchmark-claim-unresolved (str id "/" claim-id))])
                                  (when-not (= claim-id (:evaluator/id claim))
                                    [(error (:claim/id claim) :evaluator-claim-mismatch (str claim-id))])))
                               claims))))
                  (:benchmark/claims claim))
          (if (= :published (:publication/status claim))
            (if (seq (:evidence/packages claim)) (package-errors claim)
                [(error id :published-claim-without-package "published claims require immutable package and evidence references")])
            [])))))

(defn- public-document-errors [documents ids]
  (mapcat (fn [path]
            (let [file (io/file path)]
              (cond
                (not (.isFile file)) [(error nil :public-document-missing path)]
                (not (str/includes? (slurp file) "public-claims"))
                [(error nil :public-document-unmarked path)]
                :else (for [claim-id (re-seq #"PHC-[0-9]{4}" (slurp file))
                            :when (not (contains? ids claim-id))]
                        (error claim-id :unregistered-public-headline path)))))
          documents))

(defn -main [& args]
  (let [report-path (or (first args) default-report-path)
        registry (edn/read-string (slurp registry-path))
        claims (:claims registry)
        ids (set (map :claim/id claims))
        errors (vec (concat (mapcat #(claim-errors (manifests) %) claims)
                            (public-document-errors (:public/documents registry) ids)))
        report {:schema_version "claim-binding-report.v1"
                :registry registry-path
                :claims_total (count claims)
                :published_claims (count (filter #(= :published (:publication/status %)) claims))
                :status (if (empty? errors) "passed" "failed")
                :errors errors}]
    (.mkdirs (.getParentFile (io/file report-path)))
    (spit report-path (json/write-str report {:key-fn name :indent true}))
    (println "[claim-binding]" (:status report) "report:" report-path)
    (when (seq errors) (System/exit 1))))
