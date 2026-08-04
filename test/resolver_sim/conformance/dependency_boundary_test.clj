(ns resolver-sim.conformance.dependency-boundary-test
  "G4-close: the generic conformance package must not depend on domain
   namespaces.  A :shared-unchanged implementation imported by both the trace
   and benchmark adapters may reference resolver-sim.conformance.* and the
   hash/canonical helpers, but never resolver-sim.trace.* or
   resolver-sim.benchmark.*."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]))

(def ^:private generic-conformance-namespaces
  ["resolver_sim/conformance/claim.clj"
   "resolver_sim/conformance/capability.clj"
   "resolver_sim/conformance/derivation.clj"
   "resolver_sim/conformance/profile.clj"
   "resolver_sim/conformance/validation.clj"
   "resolver_sim/conformance/plan.clj"
   "resolver_sim/conformance/coverage.clj"
   "resolver_sim/conformance/outcome.clj"
   "resolver_sim/conformance/registry.clj"
   "resolver_sim/conformance/reconciliation.clj"
   "resolver_sim/conformance/identity.clj"
   "resolver_sim/conformance/envelope.clj"
   "resolver_sim/conformance/issue.clj"
   "resolver_sim/conformance/environment.clj"
   "resolver_sim/conformance/bundle.clj"
   "resolver_sim/conformance/crypto.clj"
   "resolver_sim/conformance/cli.clj"])

(defn- ns-requires [file]
  (let [src (slurp (io/file "src" file))]
    (->> (re-seq #":require\s+(\[.*?\])" src)
         (mapcat (fn [[_ vec]] (re-seq #"resolver-sim\.[a-z0-9.-]+" vec)))
         vec)))

(deftest generic-conformance-imports-no-domain-namespace
  (doseq [f generic-conformance-namespaces]
    (let [imports (ns-requires f)
          domain (filter #(or (re-find #"^resolver-sim\.trace" %) 
                              (re-find #"^resolver-sim\.benchmark" %)
                              (re-find #"^resolver-sim\.evidence-package" %)) imports)]
      (testing (str f " must not import trace/benchmark namespaces")
        (is (empty? domain)
            (str "domain imports: " (vec domain)))))))

(deftest shared-abstractions-used-by-both-adapters
  ;; The adapters must actually require the generic namespaces (sanity that the
  ;; boundary test has teeth, not that adapters are absent).
  (let [trace (slurp (io/file "src" "resolver_sim/trace/conformance/validators.clj"))
        bench (slurp (io/file "src" "resolver_sim/benchmark/conformance/reproduction.clj"))]
    (is (re-find #"resolver-sim\.conformance\.validation" trace))
    (is (re-find #"resolver-sim\.conformance\.validation" bench))
    (is (re-find #"resolver-sim\.conformance\.profile" trace))
    (is (re-find #"resolver-sim\.conformance\.profile" bench))))
