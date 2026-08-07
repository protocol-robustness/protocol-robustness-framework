(ns resolver-sim.lab.validation
  "Strict, allowlisted validation of lab run requests.

   A visitor request is only two things: an experiment reference (resolved
   through the registry) and a map of typed parameters. There is no path to
   shell commands, arbitrary Clojure evaluation, arbitrary symbols, or
   filesystem paths — the validator rejects unknown parameters, wrong types,
   and out-of-bounds values before execution can begin."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.lab.registry :as registry]))

(def ^:private max-parameter-count 64)

(defn- parse-long-strict
  [s]
  (try
    (if (and (string? s) (re-matches #"-?\d+" (str/trim s)))
      (parse-long (str/trim s))
      s)
    (catch Exception _ s)))

(defn- coerce-integer
  "Accept an integer number or a decimal string (JSON parsers vary). Returns
   {:ok? true :value n} or {:ok? false :value raw}."
  [v]
  (let [value (if (string? v) (parse-long-strict v) v)]
    (if (integer? value)
      {:ok? true :value (long value)}
      {:ok? false :value v})))

(defn- coerce-boolean
  [v]
  (cond
    (boolean? v) {:ok? true :value v}
    (true? v) {:ok? true :value true}
    (false? v) {:ok? true :value false}
    (and (string? v) (= "true" (str/lower-case v))) {:ok? true :value true}
    (and (string? v) (= "false" (str/lower-case v))) {:ok? true :value false}
    :else {:ok? false :value v}))

(defn- validate-parameter
  [param value]
  (let [type (:type param)
        id (:parameter/id param)]
    (case type
      :integer
      (let [{:keys [ok? value] :as coerced} (coerce-integer value)]
        (cond
          (not ok?) (list (str (name id) ": expected an integer, got "
                               (pr-str (:value coerced))))
          (< value (:min param)) (list (str (name id) ": below minimum " (:min param)))
          (> value (:max param)) (list (str (name id) ": above maximum " (:max param)))
          :else nil))

      :enum
      (if (contains? (set (map str (:options param))) (str value))
        nil
        (list (str (name id) ": expected one of "
                   (str/join ", " (map str (:options param)))
                   ", got " (pr-str value))))

      :boolean
      (let [{:keys [ok?]} (coerce-boolean value)]
        (if ok? nil (list (str (name id) ": expected a boolean"))))

      (list (str (name id) ": unsupported parameter type " type)))))

(defn- normalize-parameters
  "Validate and coerce every supplied parameter, applying defaults for
   omitted optional parameters. Returns {:ok? bool :parameters map :errors [str]}."
  [experiment parameters]
  (let [specs (registry/parameter-specs experiment)
        by-id (into {} (map (juxt :parameter/id identity)) specs)
        supplied-keys (set (keys parameters))]
    (if (> (count parameters) max-parameter-count)
      {:ok? false :errors [(str "request too large: at most " max-parameter-count
                                " parameters allowed")]}
      (let [unknown (set/difference supplied-keys (set (keys by-id)))
            errors (atom (mapv (fn [k] (str "unknown parameter: " (name k))) unknown))
            coerced (atom {})]
        (doseq [[k v] parameters]
          (when-let [spec (get by-id k)]
            (when-let [errs (validate-parameter spec v)]
              (swap! errors into errs))
            (let [{:keys [ok? value]}
                  (case (:type spec)
                    :integer (coerce-integer v)
                    :boolean (coerce-boolean v)
                    :enum {:ok? true :value (str v)}
                    {:ok? false :value v})]
              (when ok?
                (swap! coerced assoc k value)))))
        (doseq [spec specs
                :when (and (not (contains? supplied-keys (:parameter/id spec)))
                           (contains? spec :default))]
          (swap! coerced assoc (:parameter/id spec) (:default spec)))
        (doseq [spec specs
                :when (and (not (:optional spec))
                           (not (contains? spec :default))
                           (not (contains? @coerced (:parameter/id spec))))]
          (swap! errors conj (str (name (:parameter/id spec))
                                  ": required parameter missing")))
        {:ok? (empty? @errors)
         :parameters @coerced
         :errors @errors}))))

(defn- key->name
  [k]
  (if (keyword? k) (name k) (str k)))

(defn validate-request
  "Validate a full lab run request.

   request := {:experiment <string> :parameters {keyword-or-string value}}

   Rejects unknown top-level keys (e.g. any :command/:script/:shell field) so
   a visitor request carries only the two allowlisted fields.

   Returns {:ok? true :experiment <entry> :parameters {coerced}}
           {:ok? false :errors [str ...]}"
  [request]
  (let [allowed-keys #{:experiment :parameters}
        unexpected (remove allowed-keys (keys request))]
    (cond
      (not (map? request)) {:ok? false :errors ["request must be a JSON object"]}
      (seq unexpected) {:ok? false :errors [(str "unexpected field(s): "
                                                 (str/join ", " (map key->name unexpected)))]}
      (nil? (:experiment request)) {:ok? false :errors ["missing field: experiment"]}
      (nil? (:parameters request)) {:ok? false :errors ["missing field: parameters"]}
      (not (map? (:parameters request))) {:ok? false :errors ["parameters must be an object"]}
      :else
      (let [{:keys [experiment error]} (registry/resolve-reference (:experiment request))]
        (cond
          error {:ok? false :errors [error]}
          (not (map? experiment)) {:ok? false :errors ["unknown experiment"]}
          :else
          (let [params (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword (str k))) v]))
                             (:parameters request))
                {:keys [ok? parameters errors]}
                (normalize-parameters experiment params)]
            (if ok?
              {:ok? true :experiment experiment :parameters parameters}
              {:ok? false :errors errors})))))))
