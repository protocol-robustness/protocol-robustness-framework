(ns resolver-sim.registry-equivalence-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [scripts.deployment.registry-equivalence :as fixtures]))

(deftest pinned-cross-language-fixtures
  (let [pinned (json/read-str (slurp fixtures/fixture-path))
        derived (fixtures/fixture-document)]
    (is (= pinned derived) "All fixture fields and roots must match PRF derivation")
    (is (= (get-in pinned ["cases" "multi" "root"])
           (get-in pinned ["cases" "reordered" "root"])))
    (is (not= (get-in pinned ["cases" "multi" "root"])
              (get-in pinned ["cases" "inactive" "root"])))))

(deftest deployment-json-derivation-matches-the-pinned-single-entry-vector
  (let [entry (first (get (fixtures/fixture-cases) "single"))
        request {"schema" "prf-verifier-registry-derivation-request.v1"
                 "entries" [{"id" (:verifier/id entry)
                             "adapter" (:verifier/adapter entry)
                             "programVKey" (:verifier/program-vkey entry)
                             "programElfSha256" (str "0x" (subs (:verifier/program-elf-sha256 entry) 7))
                             "programId" (:verifier/program-id entry)
                             "statementSchemaVersionHash" (:verifier/statement-schema-version-hash entry)
                             "publicValuesSchema" (:verifier/public-values-schema-hash entry)
                             "active" (:verifier/active entry)}]}
        result (fixtures/derive-json-document (json/read-str (json/write-str request) :key-fn keyword))]
    (is (= "prf-verifier-registry-derivation-result.v1" (get result "schema")))
    (is (= (get-in (fixtures/fixture-document) ["cases" "single" "root"])
           (get result "root")))))
