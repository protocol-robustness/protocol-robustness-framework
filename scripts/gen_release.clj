(ns scripts.gen-release
  "G9b: reproducible conformance-core release artifact.

   Binds the committed roots (profiles, registry, schemas, issues, corpus,
   vectors, verifier artifacts, source revision) into a single release
   envelope whose own root is the one stable subject external reviewers verify.

   Deterministic: the same committed files produce the same release root.
   Run: clojure -M:test:with-sew -i scripts/gen_release.clj"
  (:require [resolver-sim.conformance.canonical :as canonical]
            [resolver-sim.conformance.registry :as registry]
            [resolver-sim.conformance.issue]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn- sha256-file [path]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes (slurp path) "UTF-8"))
    (apply str (map #(format "%02x" (bit-and (int %) 0xFF)) (.digest md)))))

(defn- file-root [path] (str "sha256:" (sha256-file path)))

(defn- tree-root [root-dir]
  (let [files (->> (file-seq (io/file root-dir))
                   (filter #(.isFile %))
                   (map #(str/replace (.getPath %) (str root-dir "/") ""))
                   sort)]
    (canonical/root
     (into (sorted-map)
           (map (fn [rel] [(keyword rel) (sha256-file (str root-dir "/" rel))]))
           files))))

(def profiles-root
  (canonical/root
   (into (sorted-map)
         (map (fn [p] [(keyword p) (file-root (str "etc/conformance/profiles/" p))]))
         ["sew-trace-equivalence.v1.edn"
          "research-benchmark-reproduction.v1.edn"
          "evidence-package-admission.v1.edn"])))

(def schemas-root
  (canonical/root
   (sort ["conformance.bundle/v1"
          "conformance.reconciliation/v1"
          "conformance.subject-identity/v1"
          "conformance.profile/v1"
          "conformance.environment/v1"
          "conformance.plan/v1"
          "conformance.coverage/v1"
          "conformance.claim/v1"
          "conformance.implementation-registry/v1"])))

(def issues-root
  (canonical/root
   (sort (keys (ns-publics 'resolver-sim.conformance.issue)))))

(def corpus-root
  (let [manifest (sha256-file "etc/conformance/corpus/manifest.json")
        cases (->> (file-seq (io/file "etc/conformance/corpus"))
                   (filter #(.isFile %))
                   (filter #(str/ends-with? (.getName %) ".json"))
                   (filter #(not= "manifest.json" (.getName %)))
                   (map (fn [f] [(keyword (str/replace (.getPath f) "etc/conformance/corpus/" ""))
                                 (sha256-file (.getPath f))])))]
    (canonical/root (reduce conj (sorted-map :manifest manifest) cases))))

(def vectors-root
  (canonical/root
   (into (sorted-map)
         (map (fn [p] [(keyword p) (file-root (str "etc/conformance/vectors/" p))]))
         ["canonical-roots.json" "crypto.json"])))

(def verifiers-root
  (canonical/root
   (into (sorted-map)
         {:clojure (canonical/root
                    (into (sorted-map)
                          (map (fn [p] [(keyword p) (file-root p)]))
                          ["src/resolver_sim/conformance/canonical.clj"
                           "src/resolver_sim/conformance/bundle.clj"
                           "src/resolver_sim/conformance/claim.clj"
                           "src/resolver_sim/conformance/crypto.clj"
                           "src/resolver_sim/conformance/json.clj"
                           "src/resolver_sim/conformance/cli.clj"
                           "src/resolver_sim/conformance/issue.clj"]))
          :python (file-root "scripts/bundle_verify.py")})))

(def release
  {:release/id :conformance-core-1.0.0
   :conformance/core-version 1
   :source/revision (tree-root "src")
   :profiles/root profiles-root
   :registry/root (registry/registry-root)
   :schemas/root schemas-root
   :issues/root issues-root
   :corpus/root corpus-root
   :vectors/root vectors-root
   :verifiers verifiers-root
   :canonicalisation/version "canonical-json-sha256.v1"})

(defn- serialize [x]
  (clojure.data.json/write-str x {:indent true
                                  :key-fn (fn [k] (if (keyword? k)
                                                    (if-let [ns (namespace k)]
                                                      (str ns "/" (name k))
                                                      (name k))
                                                    k))}))

(def release-with-root (assoc release :release/root (canonical/root release)))

(spit "etc/conformance/release.v1.edn" (serialize release-with-root))
(println "wrote etc/conformance/release.v1.edn")
(println "release/root:" (:release/root release-with-root))
