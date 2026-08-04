(ns resolver-sim.conformance.corpus-test
  "G9a: implementation-neutral conformance corpus.  Every committed corpus case
   must produce the expected machine classification, and bundle cases must be
   classified identically by the Clojure verifier and the Python verifier."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [resolver-sim.conformance.bundle :as bundle]
            [resolver-sim.conformance.identity :as identity]
            [resolver-sim.trace.conformance.validators :as tv]))

(def corpus-root "etc/conformance/corpus")

(defn- read-case [path] (json/read-str (slurp (str corpus-root "/" path)) :key-fn keyword))

(defn- norm-status [s]
  (condp = s "pass" "pass" "reject" "reject" "rejected" "reject" "unsupported-version" "reject"))

(defn- python-bundle [path]
  (let [{:keys [exit out]} (shell/sh "python3" "scripts/bundle_verify.py" (str corpus-root "/" path))]
    (when-not (zero? exit) (throw (ex-info "python failed" {:out out})))
    (let [d (json/read-str out :key-fn keyword)]
      {:status (norm-status (:verification/status d))
       :claimable? (:claimable? d)
       :codes (:issue-codes d)})))

(defn- clojure-bundle [path]
  (let [b (read-case path)
        v (bundle/verify-bundle b)]
    {:status (norm-status (name (:status v)))
     :claimable? (:claimable? v)
     :codes (mapv #(if (keyword? %) (name %) (str %))
                  (map :issue/code (:issues v)))}))

(defn- clojure-identity [path]
  (let [c (read-case path)
        identities (mapv (fn [i] (identity/subject-identity
                                  {:subject/id (:subject/id i)
                                   :subject/kind (:subject/kind i)
                                   :subject/canonical-root (:subject/canonical-root i)}))
                         (:identities c))
        r (identity/validate-identities identities [])]
    {:status (if (:valid? r) "pass" "reject")
     :claimable? false
     :codes (mapv #(name (:violation/id %)) (:violations r))}))

(defn- clojure-schema [path]
  (let [c (read-case path)
        {:keys [valid? results]} (tv/validate-fixture (:fixture c))
        codes (mapcat (fn [r] (map :issue/code (:validation/issues r))) results)]
    {:status (if valid? "pass" "reject")
     :claimable? false
     :codes (mapv name codes)}))

(defn- evaluate [case]
  (condp = (:kind case)
    "bundle" (clojure-bundle (:path case))
    "identity" (clojure-identity (:path case))
    "trace-schema" (clojure-schema (:path case))))

(defn- python-evaluate [case]
  (condp = (:kind case)
    "bundle" (python-bundle (:path case))
    ;; non-bundle kinds have no independent Python verifier yet
    nil))

(deftest corpus-manifest-cases-classify-correctly
  (let [manifest (json/read-str (slurp (str corpus-root "/manifest.json")) :key-fn keyword)]
    (doseq [case manifest]
      (let [expected (:expected_status case)
            result (evaluate case)]
        (testing (str (:case_id case) " " (:kind case))
          (is (= expected (:status result)) (pr-str result))
          (is (= (:claimable case) (:claimable? result)))
          (doseq [code (:expected_issue_codes case)]
            (is (some #(= code %) (:codes result))
                (str "missing expected code " code " in " (:codes result)))))))))

(deftest corpus-bundle-cases-agree-across-implementations
  (let [manifest (json/read-str (slurp (str corpus-root "/manifest.json")) :key-fn keyword)]
    (doseq [case manifest
            :when (= "bundle" (:kind case))]
      (let [clj (evaluate case)
            py (python-evaluate case)]
        (testing (str (:case_id case) " Clojure == Python")
          (is (= (:status clj) (:status py)) (str clj " vs " py))
          (is (= (:claimable? clj) (:claimable? py))))))))
