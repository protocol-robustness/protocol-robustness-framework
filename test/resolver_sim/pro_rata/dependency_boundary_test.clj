(ns resolver-sim.pro-rata.dependency-boundary-test
  "Architectural regression coverage for the two production pro-rata adapters.

   This intentionally scopes its source inspection to the adapter functions,
   rather than banning `payoffs` from their namespaces: principal-first,
   waterfall, fees, and the retained historical diagnostic implementation have
   legitimate independent uses."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- function-source
  [path start-marker end-marker]
  (let [source (slurp path)
        start (.indexOf source start-marker)
        end (.indexOf source end-marker start)]
    (when (or (neg? start) (neg? end))
      (throw (ex-info "Could not isolate pro-rata production adapter"
                      {:path path :start start-marker :end end-marker})))
    (subs source start end)))

(deftest production-pro-rata-adapters-use-public-mechanism-api
  (let [shared-withdrawal
        (function-source "src/resolver_sim/yield/partial_fill.clj"
                         "(defn- allocate-shared-withdrawal-rows"
                         "(defn- make-evidence")
        sew-slash
        (function-source "protocols_src/resolver_sim/protocols/sew/economics.clj"
                         "(defn calculate-sew-slash-allocation"
                         "(defn build-sew-slash-projection-artifact")]
    (is (str/includes? shared-withdrawal "pro-rata/allocate"))
    (is (not (str/includes? shared-withdrawal "payoffs/allocate-pro-rata")))
    (is (str/includes? sew-slash "pro-rata/allocate"))
    (is (not (str/includes? sew-slash "payoffs/allocate-pro-rata")))))
