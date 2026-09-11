(ns resolver-sim.scenario.equilibrium-result
  "Structured result vocabulary for mechanism/equilibrium validators.

  Status (:pass :fail :inconclusive :not-applicable) drives suite roll-up.
  :reason disambiguates why a non-pass status was assigned."
  (:require [resolver-sim.definitions.registry :as defs]))

(def domain-result-schema-version "1.0")

;; Reason codes (subset of status — explains non-pass outcomes)
(def reason-codes
  #{:untested-no-adversary
    :missing-deviation-bundles
    :missing-valid-time-provenance
    :missing-verified-attestation
    :unsupported-concept
    :multi-epoch-required
    :absent-projection-evidence
    :property-violated
    :validator-error})

(defn make-result
  "Build a validator result map with structured reason fields."
  [property status severity basis & {:keys [reason reason-detail required available observed expected offending validation-class]}]
  (cond-> {:property  property
           :status    status
           :severity  severity
           :basis     basis
           :reason    reason
           :observed  observed
           :expected  expected
           :offending (vec (or offending []))
           :required  (vec (or required []))
           :available (vec (or available []))}
    reason-detail (assoc :reason-detail reason-detail)
    (and (nil? reason) reason-detail) (assoc :reason :unspecified)
    reason-detail (assoc :requires [reason-detail])
    validation-class (assoc :validation-class validation-class)))

(defn pass-result
  [property basis observed expected & {:keys [validation-class]}]
  (make-result property :pass :hard basis
               :observed observed :expected expected
               :validation-class validation-class))

(defn fail-result
  [property basis observed expected offending & {:keys [validation-class]}]
  (make-result property :fail :hard basis
               :reason :property-violated
               :observed observed :expected expected :offending offending
               :validation-class validation-class))

(defn inconclusive-result
  [property basis reason & {:keys [detail required available validation-class]}]
  (make-result property :inconclusive :soft basis
               :reason reason
               :reason-detail detail
               :required required
               :available available
               :validation-class validation-class))

(defn not-applicable-result
  [property reason & {:keys [detail required available validation-class]}]
  (make-result property :not-applicable :soft :not-applicable
               :reason reason
               :reason-detail detail
               :required required
               :available available
               :validation-class validation-class))

(defn validator-error-result
  [property throwable]
  (make-result property :inconclusive :soft :absent-evidence
               :reason :validator-error
               :reason-detail (str (class throwable) ": " (.getMessage throwable))))

(defn proxy-status-display-label
  "Human label for mechanism/equilibrium validator status (not metric falsification)."
  [status]
  (or (:label (defs/proxy-status-def status))
      (:label (defs/proxy-status-def :inconclusive))
      "Proxy check inconclusive"))

(defn summarize-validator
  "Compact shape for golden snapshots and notebooks."
  [result]
  (-> (select-keys result [:status :reason :reason-detail :basis :severity
                           :required :available])
      (assoc :display-label (proxy-status-display-label (:status result)))))

(defn summarize-domain-results
  "Map of property/concept kw → summarized validator result."
  [results-map]
  (into {}
        (map (fn [[k v]] [k (summarize-validator v)])
             (or results-map {}))))

(defn domain-status-reasons
  "Roll-up of non-pass :reason codes per domain entry (for diagnostics)."
  [results-map]
  (into {}
        (keep (fn [[k {:keys [status reason]}]]
                (when (and reason (not= status :pass))
                  [k reason]))
              (or results-map {}))))