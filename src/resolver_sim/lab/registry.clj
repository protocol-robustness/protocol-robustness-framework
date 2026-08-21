(ns resolver-sim.lab.registry
  "The Assurance Lab experiment registry.

   Every experiment a browser visitor can run is explicitly registered here.
   A registry entry declares:

     - the experiment identity and version;
     - a stable slug used in the public API (\"<slug>.v<N>\");
     - visitor-facing copy (title, question, description, mechanism);
     - typed parameter definitions used for BOTH validation and UI rendering;
     - a `:runner` key that the execution layer resolves through a fixed
       allowlist (see resolver-sim.lab.runner). No arbitrary namespace,
       symbol, or shell command can be reached from a visitor request.

   The registry is data; the runner dispatch is code. Keeping them separate
   means a new experiment is added by registering it here AND wiring its
   runner function explicitly — never by naming a symbol from user input."
  (:require [clojure.string :as str]
            [resolver-sim.lab.json :as lab-json]))

(def ^:private supported-param-types
  #{:integer :enum :boolean})

(def ^:const experiment-version 1)

(defn- validate-param-spec!
  [experiment-id param]
  (let [id (:parameter/id param)
        type (:type param)]
    (when (nil? id)
      (throw (ex-info (str "Missing :parameter/id in experiment " experiment-id)
                      {:experiment/id experiment-id :parameter param})))
    (when-not (contains? supported-param-types type)
      (throw (ex-info (str "Unsupported parameter type " type " for " id)
                      {:experiment/id experiment-id :parameter param})))
    (when (and (= type :integer)
               (not (and (integer? (:min param)) (integer? (:max param)))))
      (throw (ex-info (str "Integer parameter " id " requires integer :min/:max")
                      {:experiment/id experiment-id :parameter param})))
    (when (and (= type :enum) (empty? (:options param)))
      (throw (ex-info (str "Enum parameter " id " requires :options")
                      {:experiment/id experiment-id :parameter param})))))

(defn- params
  [& specs]
  (vec specs))

(declare experiments)

(defn validate-registry!
  "Fail fast on registry errors: duplicate ids, duplicate parameter ids,
   malformed parameter specs. Accepts an optional collection (defaults to the
   live registry) so tests can validate synthetic registries."
  ([] (validate-registry! experiments))
  ([experiments']
   (let [ids (mapv :experiment/id experiments')
         dups (->> ids frequencies (filter (fn [[_ n]] (> n 1))) (mapv first))]
     (when (seq dups)
       (throw (ex-info "Duplicate experiment ids in lab registry"
                       {:duplicate-ids dups})))
     (doseq [{:keys [experiment/id parameters]} experiments']
       (let [param-ids (mapv :parameter/id parameters)
             pdups (->> param-ids frequencies (filter (fn [[_ n]] (> n 1))) (mapv first))]
         (when (seq pdups)
           (throw (ex-info (str "Duplicate parameter ids in experiment " id)
                           {:experiment/id id :duplicate-parameters pdups})))
         (doseq [p parameters]
           (validate-param-spec! id p))))
     true)))

(def experiments
  "Registered, browser-runnable experiments."
  [{:experiment/id :withdrawal/constrained-liquidity
     :experiment/version experiment-version
    :experiment/slug "withdrawal-constrained-liquidity"
    :experiment/title "Withdrawal under constrained liquidity"
    :experiment/question "What happens when withdrawal demand exceeds available liquidity?"
    :experiment/description
    "Three holders ask to withdraw from a shared pool that cannot cover every request. The lab runs the actual PRF allocation semantics: first-come-first-served sequential fill or canonical pro-rata allocation, then reports who was served, deferred, or left short, with the mechanism's own evidence commitments."
    :experiment/mechanism "Sew yield shared-pool semantics: FCFS batch withdrawal (liquid-lending withdraw-many ordering) vs canonical pro-rata allocation (pro-rata-allocation.v1)."
    :experiment/protocol "sew/yield + pro-rata"
    :experiment/comparison true
    :runner :withdrawal/constrained-liquidity
    :parameters
    (params {:parameter/id :available-liquidity :type :integer
             :min 0 :max 10000000 :default 1000 :label "Available liquidity"}
            {:parameter/id :alice-requested :type :integer
             :min 0 :max 10000000 :default 500 :label "Alice requested"}
            {:parameter/id :bob-requested :type :integer
             :min 0 :max 10000000 :default 500 :label "Bob requested"}
            {:parameter/id :carol-requested :type :integer
             :min 0 :max 10000000 :default 400 :label "Carol requested"}
            {:parameter/id :mechanism :type :enum
             :options ["fcfs" "pro-rata"] :default "pro-rata"
             :label "Allocation mechanism"}
            {:parameter/id :rounding-policy :type :enum
             :options ["largest-remainder" "floor"] :default "largest-remainder"
             :label "Rounding policy"})}

   {:experiment/id :insolvency/impairment
     :experiment/version experiment-version
    :experiment/slug "insolvency-after-loss"
    :experiment/title "Insolvency after loss"
    :experiment/question "Is the vault still solvent after a realized loss, and what does PRF's assessment vocabulary say?"
    :experiment/description
    "A custody ledger holds assets against a live obligation. The lab constructs a minimal world and runs the Sew solvency classifier over the canonical economic-liability-set, reporting the assessment vocabulary (:solvent, :impaired, :insolvent, :unassessable, :assessment-invalid), the accounting versus economic-solvency dimensions, the committed liability-set root, and the state commitment."
    :experiment/mechanism "Sew financial solvency classifier over economic-liability-set.v1 (classify-solvency)."
    :experiment/protocol "sew/financial"
    :experiment/comparison false
    :runner :insolvency/impairment
    :parameters
    (params {:parameter/id :custody :type :integer
             :min 0 :max 10000000 :label "Assets on ledger (USDC)"}
            {:parameter/id :recognized-loss :type :integer
             :min 0 :max 10000000 :label "Recognized loss / haircut (USDC)"}
            {:parameter/id :observed-balances :type :integer :optional true
             :min 0 :max 10000000 :label "Observed custody balance (USDC)"}
            {:parameter/id :require-external-coverage :type :boolean
             :default false :label "Require external coverage evidence"})}

   {:experiment/id :pro-rata/allocation
     :experiment/version experiment-version
    :experiment/slug "pro-rata-allocation"
    :experiment/title "Pro-rata fractional allocation"
    :experiment/question "How is a constrained quantity divided among claims — and how do rounding, caps, and remainders change the distribution?"
    :experiment/description
    "Run the canonical deterministic pro-rata allocation mechanism over three claims with integer rounding, optional caps, and remainder distribution. The result includes the full allocation witness: rounds, quotas, fractional remainders, residual, and the mechanism's committed allocation hash."
    :experiment/mechanism "pro-rata-allocation.v1 deterministic integer engine (resolver-sim.pro-rata.allocation/allocate)."
    :experiment/protocol "prf/pro-rata"
    :experiment/comparison true
    :runner :pro-rata/allocation
    :parameters
    (params {:parameter/id :available :type :integer
             :min 0 :max 10000000 :default 1000 :label "Available capacity"}
            {:parameter/id :alice-requested :type :integer
             :min 0 :max 10000000 :default 500 :label "Alice claim"}
            {:parameter/id :bob-requested :type :integer
             :min 0 :max 10000000 :default 300 :label "Bob claim"}
            {:parameter/id :carol-requested :type :integer
             :min 0 :max 10000000 :default 200 :label "Carol claim"}
            {:parameter/id :cap-alice :type :integer :optional true
             :min 0 :max 10000000 :label "Alice cap (empty = no cap)"}
            {:parameter/id :rounding-policy :type :enum
             :options ["largest-remainder" "floor"] :default "largest-remainder"
             :label "Rounding policy"}
            {:parameter/id :redistribution-policy :type :enum
             :options ["unallocated" "redistribute-cap-excess"] :default "unallocated"
             :label "Cap redistribution"})}])

(defn find-experiment
  "Look up an experiment by id keyword, slug string, or slug string with a
   version suffix (\"<slug>.v<N>\"). Returns nil when unknown."
  [reference]
  (cond
    (keyword? reference)
    (first (filter #(= reference (:experiment/id %)) experiments))

    (string? reference)
    (let [[slug _version] (str/split reference #"\.v" 2)]
      (or (first (filter #(= slug (:experiment/slug %)) experiments))
          (first (filter #(= (keyword reference) (:experiment/id %)) experiments))))))

(defn resolve-reference
  "Resolve a request's experiment string into
   {:experiment <entry> :version int :error str?}."
  [reference]
  (if-not (and (string? reference) (seq reference))
    {:error "experiment must be a non-empty string"}
    (let [[slug version-str] (str/split reference #"\.v" 2)
          experiment (find-experiment slug)
          requested-version (when version-str (parse-long version-str))]
      (cond
        (nil? experiment)
        {:error (str "unknown experiment: " reference)}

        (and version-str (nil? requested-version))
        {:error (str "invalid experiment version suffix: " reference)}

        (and version-str (not= requested-version (:experiment/version experiment)))
        {:error (str "unsupported experiment version: " reference
                     " (supported: v" (:experiment/version experiment) ")")}

        :else
        {:experiment experiment
         :version (:experiment/version experiment)}))))

(defn parameter-specs
  [experiment]
  (get experiment :parameters []))

(defn find-parameter
  [experiment parameter-id]
  (first (filter #(= parameter-id (:parameter/id %)) (:parameters experiment))))

(defn experiment->public
  "Public, visitor-facing view of an experiment (no internal runner keys)."
  [experiment]
  {:experiment/id (lab-json/keyword->string (:experiment/id experiment))
   :experiment/version (:experiment/version experiment)
   :experiment/slug (:experiment/slug experiment)
   :experiment/ref (str (:experiment/slug experiment) ".v"
                        (:experiment/version experiment))
   :experiment/title (:experiment/title experiment)
   :experiment/question (:experiment/question experiment)
   :experiment/description (:experiment/description experiment)
   :experiment/mechanism (:experiment/mechanism experiment)
   :experiment/protocol (:experiment/protocol experiment)
   :experiment/comparison (boolean (:experiment/comparison experiment))
   :parameters (mapv (fn [p]
                       {:parameter/id (lab-json/keyword->string (:parameter/id p))
                        :parameter/type (lab-json/keyword->string (:type p))
                        :parameter/min (:min p)
                        :parameter/max (:max p)
                        :parameter/default (:default p)
                        :parameter/options (mapv str (:options p))
                        :parameter/optional (boolean (:optional p))
                        :parameter/label (:label p)})
                     (:parameters experiment))})
