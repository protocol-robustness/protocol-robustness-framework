(ns resolver-sim.pro-rata.dependency-boundary-test
  "Architectural regression coverage for the two production pro-rata adapters.

   This intentionally scopes its source inspection to the adapter functions,
   rather than banning `payoffs` from their namespaces: principal-first,
   waterfall, fees, and the retained historical diagnostic implementation have
   legitimate independent uses.

   It also freezes the pro-rata semantic closure: the pure allocator,
   redistribution, progress, quantity, target-map, exact verification, canonical
   result/root projection, and EVM projection must stay free of protocol,
   runner, research, application, and generic-economics-allocator dependencies,
   and `resolver-sim.economics.payoffs` must never depend on pro-rata."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private pro-rata-core-namespaces
  "The pro-rata semantic closure: namespaces that own canonical pro-rata
   allocation semantics. resolver-sim.pro-rata must be the sole producer of
   these behaviours; nothing here may reach a protocol, runner, research,
   application, or generic-economics allocator namespace."
  '[resolver-sim.pro-rata.allocation
    resolver-sim.pro-rata.redistribution
    resolver-sim.pro-rata.engine
    resolver-sim.pro-rata.progress
    resolver-sim.pro-rata.quantity
    resolver-sim.pro-rata.target-map
    resolver-sim.pro-rata.exact-verifier
    resolver-sim.pro-rata.evm
    resolver-sim.pro-rata.evaluation])

(def ^:private forbidden-prefixes
  "Namespace prefixes/names the pro-rata semantic closure must never require.
   economics.payoffs is forbidden here because it is a generic-economics
   consumer, not the owner of pro-rata allocation semantics."
  ["resolver-sim.protocols."
   "resolver-sim.research."
   "resolver-sim.economics.payoffs"
   "resolver-sim.server."
   "resolver-sim.sim."
   "resolver-sim.stochastic."
   "resolver-sim.scenario."
   "resolver-sim.yield."
   "resolver-sim.core.phases"
   "resolver-sim.io.scenario-runner"
   "resolver-sim.io.diff-runner"])

(defn- ns-source-path
  [ns-sym]
  (str "src/resolver_sim/"
       (-> (subs (str ns-sym) (count "resolver-sim."))
           (str/replace "-" "_")
           (str/replace "." "/"))
       ".clj"))

(defn- required-resolver-sim-namespaces
  [source]
  (->> (re-seq #"\[\s*(resolver-sim(?:\.[A-Za-z0-9_-]+)+)" source)
       (map second)
       (map symbol)
       set))

(deftest pro-rata-core-is-a-closed-semantic-closure
  (doseq [ns-sym pro-rata-core-namespaces]
    (let [path (ns-source-path ns-sym)
          source (slurp path)
          requires (required-resolver-sim-namespaces source)]
      (doseq [r requires]
        (let [rstr (str r)]
          (is (not (some #(str/starts-with? rstr %) forbidden-prefixes))
              (str ns-sym " must not require " r))
          (when (str/starts-with? rstr "resolver-sim.pro-rata.")
            (is (contains? (set pro-rata-core-namespaces) r)
                (str ns-sym " must not reach pro-rata application namespace " r))))))))

(deftest generic-economics-payoffs-does-not-depend-on-pro-rata
  (let [source (slurp "src/resolver_sim/economics/payoffs.clj")
        requires (required-resolver-sim-namespaces source)]
    (is (not (some #(str/starts-with? (str %) "resolver-sim.pro-rata.") requires))
        "resolver-sim.economics.payoffs must not require any resolver-sim.pro-rata.* namespace")))

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
    (is (or (str/includes? sew-slash "pro-rata/allocate")
            (str/includes? sew-slash "pro-rata.allocation/allocate")))
    (is (not (str/includes? sew-slash "payoffs/allocate-pro-rata")))))
