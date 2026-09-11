(ns resolver-sim.pro-rata.evm
  "Composable commitment model for a proved pro-rata state transition.

   This namespace does not verify SP1 proof bytes. It defines the exact
   statement which a prover must recompute and expose as an EVM bytes32:
   allocation fact + derived state transition + proof-native provenance +
   configured admission identity."
  (:require [resolver-sim.hash.canonical :as hc]))

(def allocation-schema "pro-rata-allocation-application.v1")
(def transition-schema "pro-rata-allocation-state-transition.v1")
(def provenance-schema "pro-rata-proof-provenance.v1")
(def statement-schema "pro-rata-evm-v1")

(defn- root? [value]
  (and (string? value) (re-matches #"(?:sha256:)?[0-9a-f]{64}" value)))

(defn- required-roots! [object fields]
  (when-let [missing (seq (remove #(root? (get object %)) fields))]
    (throw (ex-info "invalid or missing pro-rata commitment root"
                    {:missing (vec missing) :object object}))))

(defn application-root [application]
  (hc/domain-hash :pro-rata-allocation-application
                  (select-keys application [:schema-version :state-before/root
                                            :allocation/root :application-policy/root
                                            :applications :state-after/root])))

(defn build-application
  "Commit the exact affected-set and its canonical row-level effects. The
   protocol application kernel must derive (not accept) `:state-after/root`.
   `:applications` is deliberately machine-readable evidence; the complete
   post-state remains committed independently by `:state-after/root`."
  [{:keys [state-before-root allocation-root application-policy-root
           state-after-root applications] :as application}]
  (when-not (vector? applications)
    (throw (ex-info "pro-rata application rows must be a vector" {:value applications})))
  (let [base {:schema-version allocation-schema
              :state-before/root state-before-root
              :allocation/root allocation-root
              :application-policy/root application-policy-root
              :applications applications
              :state-after/root state-after-root}]
    (required-roots! base [:state-before/root :allocation/root
                           :application-policy/root :state-after/root])
    (assoc base :application/root (application-root base))))

(defn application-valid? [application]
  (try
    (and (= allocation-schema (:schema-version application))
         (do (required-roots! application [:state-before/root :allocation/root
                                           :application-policy/root :state-after/root])
             true)
         (vector? (:applications application))
         (= (:application/root application) (application-root application)))
    (catch Exception _ false)))

(defn transition-root [transition]
  (hc/domain-hash :pro-rata-allocation-state-transition
                  (select-keys transition [:schema-version :state-before/root
                                           :allocation/root :application-policy/root
                                           :application/root :state-after/root])))

(defn build-transition
  "Create the state-transition fact. It binds all semantic inputs and the
   application explanation. Callers must first derive the complete post-state
   through their canonical protocol kernel and pass its recomputed root."
  [{:keys [state-before-root allocation-root application-policy-root
           application-root state-after-root] :as transition}]
  (let [base {:schema-version transition-schema
              :state-before/root state-before-root
              :allocation/root allocation-root
              :application-policy/root application-policy-root
              :application/root application-root
              :state-after/root state-after-root}]
    (required-roots! base [:state-before/root :allocation/root
                           :application-policy/root :application/root
                           :state-after/root])
    (assoc base :transition/root (transition-root base))))

(defn transition-valid? [transition application]
  (and (application-valid? application)
       (= (:state-before/root transition) (:state-before/root application))
       (= (:allocation/root transition) (:allocation/root application))
       (= (:application-policy/root transition) (:application-policy/root application))
       (= (:application/root transition) (:application/root application))
       (= (:state-after/root transition) (:state-after/root application))
       (= (:transition/root transition) (transition-root transition))))

(defn provenance-root [provenance]
  (hc/domain-hash :pro-rata-proof-provenance
                  (select-keys provenance [:schema-version :configuration/root
                                           :allocation-input/root :allocation/root
                                           :state-before/root :application/root
                                           :state-after/root :program-identity/root
                                           :statement-schema/root :asserted-provenance
                                           :attested-provenance])))

(defn build-provenance
  "Commit only proof-native or configuration-authenticated provenance. External
   historical assertions remain explicit data, not facts established by this
   root or by an SP1 proof."
  [{:keys [configuration-root allocation-input-root allocation-root
           state-before-root application-root state-after-root
           program-identity-root statement-schema-root
           asserted-provenance attested-provenance] :as provenance}]
  (let [base {:schema-version provenance-schema
              :configuration/root configuration-root
              :allocation-input/root allocation-input-root
              :allocation/root allocation-root
              :state-before/root state-before-root
              :application/root application-root
              :state-after/root state-after-root
              :program-identity/root program-identity-root
              :statement-schema/root statement-schema-root
              :asserted-provenance asserted-provenance
              :attested-provenance attested-provenance}]
    (required-roots! base [:configuration/root :allocation-input/root
                           :allocation/root :state-before/root
                           :application/root :state-after/root
                           :program-identity/root :statement-schema/root])
    (assoc base :provenance/root (provenance-root base))))

(defn provenance-valid? [provenance]
  (try
    (and (= provenance-schema (:schema-version provenance))
         (do (required-roots! provenance [:configuration/root :allocation-input/root
                                          :allocation/root :state-before/root
                                          :application/root :state-after/root
                                          :program-identity/root :statement-schema/root])
             true)
         (= (:provenance/root provenance) (provenance-root provenance)))
    (catch Exception _ false)))

(defn statement-root [statement]
  (hc/domain-hash :pro-rata-evm-v1
                  (select-keys statement [:schema-version :allocation/root
                                          :transition/root :provenance/root
                                          :configuration/root])))

(defn build-statement
  "Compose the three semantic facts under a configured admission identity.
   The resulting SHA-256 reference is the only value that belongs in the
   `:evm-bytes32-v1` public projection."
  [{:keys [allocation-root transition-root provenance-root configuration-root] :as statement}]
  (let [base {:schema-version statement-schema
              :allocation/root allocation-root
              :transition/root transition-root
              :provenance/root provenance-root
              :configuration/root configuration-root}]
    (required-roots! base [:allocation/root :transition/root
                           :provenance/root :configuration/root])
    (assoc base :pro-rata-evm-v1/root (statement-root base))))

(defn statement-valid? [statement transition provenance]
  (and (transition-valid? transition (:application transition))
       (provenance-valid? provenance)
       (= (:allocation/root statement) (:allocation/root transition))
       (= (:allocation/root statement) (:allocation/root provenance))
       (= (:transition/root statement) (:transition/root transition))
       (= (:provenance/root statement) (:provenance/root provenance))
       (= (:configuration/root statement) (:configuration/root provenance))
       (= (:pro-rata-evm-v1/root statement) (statement-root statement))))
