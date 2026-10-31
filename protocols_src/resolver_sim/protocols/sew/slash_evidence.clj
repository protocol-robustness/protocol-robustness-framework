(ns resolver-sim.protocols.sew.slash-evidence
  "Optional evidence hooks for Sew slash execution.

   This namespace belongs to Sew core and defines the handoff contract only.
   Pro-rata evidence construction and artifact persistence live in the separately
   selected `sew.pro-rata.evidence` integration. When that integration is not
   loaded, slash execution fails before applying slash side effects.")

(defmulti build-prorata-slash-evidence
  "Build allocation evidence for a slash when the pro-rata evidence adapter is
   installed. Throws when the selected integration is absent."
  (fn [_request] ::pro-rata-slash))

(defmethod build-prorata-slash-evidence :default
  [_request]
  (throw (ex-info "Sew pro-rata slash-evidence adapter is not loaded"
                  {:component :prf/sew-pro-rata-slashing-v1
                   :operation :build-prorata-slash-evidence})))

(defmulti ensure-prorata-slash-evidence-loaded!
  "Fail before slash execution mutates state if the evidence adapter is absent."
  (fn [] ::pro-rata-slash))

(defmethod ensure-prorata-slash-evidence-loaded! :default
  []
  (throw (ex-info "Sew pro-rata slash-evidence adapter is not loaded"
                  {:component :prf/sew-pro-rata-slashing-v1
                   :operation :execute-slash})))

(defmulti write-allocation-result-artifact!
  "Persist the result artifact associated with pro-rata slash evidence.
   Throws when the evidence adapter is absent."
  (fn [_artifact] ::pro-rata-slash))

(defmethod write-allocation-result-artifact! :default
  [_artifact]
  (throw (ex-info "Sew pro-rata slash-evidence adapter is not loaded"
                  {:component :prf/sew-pro-rata-slashing-v1
                   :operation :write-allocation-result-artifact!})))
