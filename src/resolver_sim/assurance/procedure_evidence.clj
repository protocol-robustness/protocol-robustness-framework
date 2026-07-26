(ns resolver-sim.assurance.procedure-evidence
  "Generic evidence interpretation for procedure verification.
   
   A procedure evidence adapter maps evidence event types to
   correlation-path extraction rules and optionally maps definition
   step IDs to expected evidence event types.
   
   Adapters are injected into the witness verifier. No protocol-specific
   event names are hardcoded in this namespace.")

(def ^:const unknown-type :procedure-evidence/unknown-type)
(def ^:const no-correlation-id :procedure-evidence/no-correlation-id)

(defn correlation-id
  "Extract the correlation identity from an evidence record using
   the given adapter's :correlation-paths map.
   
   Adapter entry: {evidence-type-string [path-vector ...]}
   
   Returns the first non-nil path result, or ::unknown-type when
   the evidence type is not declared in the adapter, or
   ::no-correlation-id when all paths yield nil."
  [adapter evidence-record]
  (let [type-str (:evidence/type evidence-record)
        paths (get-in adapter [:correlation-paths type-str] ::not-found)]
    (if (= ::not-found paths)
      unknown-type
      (let [result (some #(get-in evidence-record %) paths)]
        (if (some? result) result no-correlation-id)))))

(defn correlation-id-all-same?
  "True when all evidence records carry the same non-nil correlation identity."
  [adapter evidence-records]
  (let [ids (keep #(let [id (correlation-id adapter %)]
                     (when (not= no-correlation-id id) id))
                  evidence-records)]
    (and (seq ids) (apply = ids))))

(defn expected-evidence-type
  "Return the expected evidence type string for a definition step ID,
   or nil when the adapter does not define a mapping."
  [adapter step-id]
  (get-in adapter [:step-evidence-types step-id]))

(defn valid-step-types
  "Return the set of valid step type keywords for a sequence definition,
   or nil when the adapter does not define constraints."
  [adapter]
  (:valid-step-types adapter))
