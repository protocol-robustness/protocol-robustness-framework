(ns scripts.deployment.registry-equivalence
  "Shared PRF/Solidity verifier-registry.v1 fixtures. Run -main to regenerate."
  (:require [clojure.data.json :as json]
            [resolver-sim.genesis :as genesis]))

(def fixture-path "etc/conformance/fixtures/verifier-registry-equivalence.v1.json")

(defn- word [n] (format "0x%064x" n))

(defn- entry [n active]
  {:verifier/id (word n)
   :verifier/adapter (format "0x%040x" (+ 160 n))
   :verifier/program-vkey (word 2)
   :verifier/program-elf-sha256 (str "sha256:" (subs (word 3) 2))
   :verifier/program-id "source-bound-entitlement-sp1-program.v1"
   :verifier/statement-schema-version-hash (word 4)
   :verifier/public-values-schema-hash (word 5)
   :verifier/active active})

(defn fixture-cases []
  (let [a (entry 1 true) b (entry 7 true)]
    {"empty" [] "single" [a] "multi" [a b]
     "reordered" [b a] "inactive" [a (assoc b :verifier/active false)]}))

(defn fixture-document []
  {"schema" "verifier-registry-equivalence.v1"
   "cases"
   (into (sorted-map)
         (for [[label entries] (fixture-cases)]
           [label {"root" (str "0x" (subs (genesis/canonical-verifier-registry-root entries) 7))
                   "ids" (mapv :verifier/id entries)
                   "adapters" (mapv :verifier/adapter entries)
                   "vkeys" (mapv :verifier/program-vkey entries)
                   "elfHashes" (mapv #(str "0x" (subs (:verifier/program-elf-sha256 %) 7)) entries)
                   "programIds" (mapv :verifier/program-id entries)
                   "schemaHashes" (mapv :verifier/statement-schema-version-hash entries)
                   "publicValuesSchemas" (mapv :verifier/public-values-schema-hash entries)
                   "actives" (mapv :verifier/active entries)}]))})

(defn derive-json-document [request]
  (when-not (= "prf-verifier-registry-derivation-request.v1" (:schema request))
    (throw (ex-info "unsupported verifier-registry derivation request schema" {:schema (:schema request)})))
  (let [entries (mapv (fn [entry]
                        {:verifier/id (:id entry)
                         :verifier/adapter (:adapter entry)
                         :verifier/program-vkey (:programVKey entry)
                         :verifier/program-elf-sha256 (:programElfSha256 entry)
                         :verifier/program-id (:programId entry)
                         :verifier/statement-schema-version-hash (:statementSchemaVersionHash entry)
                         :verifier/public-values-schema-hash (:publicValuesSchema entry)
                         :verifier/active (:active entry)})
                      (:entries request))
        root (genesis/canonical-verifier-registry-root entries)]
    (when (empty? entries)
      (throw (ex-info "successor registry derivation requires at least one verifier" {})))
    {"schema" "prf-verifier-registry-derivation-result.v1"
     "root" (str "0x" (subs root 7))
     "entries" (:entries request)}))

(defn -main [& args]
  (if (= "derive-json" (first args))
    (let [file (second args)]
      (when-not (and file (= 2 (count args)))
        (throw (ex-info "usage: registry-equivalence derive-json <request.json>" {})))
      (println (json/write-str (derive-json-document (json/read-str (slurp file) :key-fn keyword)) :escape-slash false)))
    (spit fixture-path (str (json/write-str (fixture-document) :escape-slash false) "\n"))))
