(ns resolver-sim.conformance.parity-test
  "G7: cross-implementation parity — the Python bundle_verify.py and the
   Clojure bundle verifier must agree on machine classifications for the
   committed fixtures.  Diagnostic wording may differ; machine classifications
   must not.

   The parity anchor is the DERIVED CLAIM ROOT (canonical-JSON sha256), which is
   independent of the ambient implementation-registry state.  The reconciliation
   root is registry-bound by design, so the reconciliation recompute is not
   compared across the fixture boundary here."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [resolver-sim.conformance.bundle :as bundle]
            ;; load the production adapters so the fixtures reflect the full
            ;; implementation surface
            [resolver-sim.trace.conformance.validators]
            [resolver-sim.benchmark.conformance.reproduction]
            [resolver-sim.evidence-package.conformance.admission]))

(def fixtures-dir "etc/conformance/fixtures")

(def fixtures
  [["valid-trace-bundle.json" "pass" true]
   ["tampered-claim-bundle.json" "rejected" false]
   ["tampered-reconciliation-bundle.json" "rejected" false]])

(defn- python-classification [path]
  (let [{:keys [exit out]} (shell/sh "python3" "scripts/bundle_verify.py" path)]
    (when-not (zero? exit)
      (throw (ex-info "python bundle_verify failed" {:out out})))
    (let [d (json/read-str out :key-fn keyword)]
      {:status (:verification/status d)
       :claimable? (:claimable? d)
       :derived-root (:derived-claim/root d)})))

(defn- clojure-derived-root [path]
  (let [b (json/read-str (slurp path) :key-fn keyword)
        ;; JSON round-trip turns :pass/:fail into strings; restore keywords so
        ;; the in-memory envelopes are compared on the same terms as Python.
        b (update-in b [:reconciliation :reconciliation/status] keyword)]
    (get-in (bundle/derive-claim-from-bundle b) [:claim/json-root])))

(deftest python-matches-fixture-intent
  (doseq [[name expected-status expected-claimable] fixtures]
    (let [py (python-classification (str fixtures-dir "/" name))]
      (testing (str name " python classification")
        (is (= expected-status (:status py)))
        (is (= expected-claimable (:claimable? py)))))))

(deftest clojure-and-python-agree-on-derived-claim-root
  (let [valid-py (python-classification (str fixtures-dir "/valid-trace-bundle.json"))
        valid-clj-root (clojure-derived-root (str fixtures-dir "/valid-trace-bundle.json"))]
    (testing "valid bundle: both implementations derive the IDENTICAL claim root"
      (is (string? valid-clj-root))
      (is (= (:derived-root valid-py) valid-clj-root)
          (str "python " (:derived-root valid-py) " vs clojure " valid-clj-root))))
  (doseq [[name _ _] (rest fixtures)]
    (let [py (python-classification (str fixtures-dir "/" name))]
      (testing (str name " tampered evidence cannot produce a claim")
        (is (= false (:claimable? py)))))))
