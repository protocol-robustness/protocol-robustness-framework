(ns resolver-sim.use-cases.registry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.use-cases.registry :as registry]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "prf-use-cases-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write! [dir path content]
  (let [file (java.io.File. dir path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    (.getPath file)))

(defn- definition [id]
  (pr-str {:concept/id id :concept/type :use-case :concept/name "Example"
           :concept/summary "Summary" :concept/stakeholder-question "Question"
           :concept/protocols #{} :concept/roles {} :concept/entities {}
           :concept/actions {} :concept/outcomes {} :concept/failure-modes []
           :concept/metrics {} :concept/assumptions [] :concept/out-of-scope []
           :concept/maturity :illustrative :concept/support-status :not-asserted
           :concept/known-gaps [] :concept/evidence {}}))

(deftest explicit-registry-loads-with-provenance-and-root
  (let [dir (temp-dir)
        _ (write! dir "definitions/a.edn" (definition :acme/a))
        path (write! dir "registry.edn" (pr-str {:schema/id :prf/use-case-registry.v1 :registry/id "acme" :registry/version "1" :use-cases [{:use-case/id :acme/a :definition/ref "definitions/a.edn"}]}))
        loaded (registry/load-use-case-registry path)]
    (is (= :external (:use-case-registry/source loaded)))
    (is (= "acme" (:use-case-registry/id loaded)))
    (is (= 1 (:use-case-registry/count loaded)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:use-case-registry/root loaded)))
    (is (= :acme/a (get-in loaded [:use-cases 0 :concept/id])))))

(deftest loading-is-explicit-and-fails-closed
  (testing "no path, resource paths, duplicate IDs, and escaping references are rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit use-case registry" (registry/load-use-case-registry nil)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"registry file not found" (registry/load-use-case-registry "resource:examples/use-cases/ecommerce/registry.edn")))
    (let [dir (temp-dir)
          _ (write! dir "definitions/a.edn" (definition :acme/a))
          duplicate (write! dir "duplicate.edn" (pr-str {:schema/id :prf/use-case-registry.v1 :registry/id "a" :registry/version "1" :use-cases [{:use-case/id :acme/a :definition/ref "definitions/a.edn"} {:use-case/id :acme/a :definition/ref "definitions/a.edn"}]}))
          escape (write! dir "escape.edn" (pr-str {:schema/id :prf/use-case-registry.v1 :registry/id "a" :registry/version "1" :use-cases [{:use-case/id :acme/a :definition/ref "../definitions/a.edn"}]}))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate" (registry/load-use-case-registry duplicate)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"escapes" (registry/load-use-case-registry escape))))))

(deftest incomplete-definitions-fail-schema-validation
  (let [dir (temp-dir)
        _ (write! dir "definitions/a.edn" (pr-str {:concept/id :acme/a :concept/type :use-case}))
        path (write! dir "registry.edn" (pr-str {:schema/id :prf/use-case-registry.v1 :registry/id "acme" :registry/version "1" :use-cases [{:use-case/id :acme/a :definition/ref "definitions/a.edn"}]}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"schema validation"
                          (registry/load-use-case-registry path)))))

(deftest definition-changes-change-the-committed-root
  (let [dir (temp-dir)
        definition-path (write! dir "definitions/a.edn" (definition :acme/a))
        path (write! dir "registry.edn" (pr-str {:schema/id :prf/use-case-registry.v1 :registry/id "acme" :registry/version "1" :use-cases [{:use-case/id :acme/a :definition/ref "definitions/a.edn"}]}))
        first-root (:use-case-registry/root (registry/load-use-case-registry path))]
    (spit definition-path (str/replace (definition :acme/a) "Example" "Changed"))
    (is (not= first-root (:use-case-registry/root (registry/load-use-case-registry path))))))
