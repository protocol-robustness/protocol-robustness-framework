(ns prf.extensions.repairability-report.report
  "Deterministic, read-only projection of repair evidence producer assertions.

   This code deliberately does not verify signatures or trust verifier output.
   It cannot promote a claim to verified or infer repair/publication authority.")

(def ^:private option-fields
  [:id :snapshot-root :source-revision :target-revision :proposed-operation
   :preconditions :recoverable-state :missing-evidence :history-impact
   :reversibility :limitations])

(def ^:private dimension-fields
  [:evidence-integrity :repair-feasibility :authorization :execution
   :independent-review :publication])

(defn- selected-fields [m fields]
  (into (sorted-map)
        (keep (fn [k]
                (when (contains? m k) [k (get m k)])))
        fields))

(defn- option-projection [option]
  (let [dimensions (selected-fields option dimension-fields)]
    (cond-> {:assertion/status :unverified
             :assertion/source :input-producer
             :details (selected-fields option option-fields)}
      (seq dimensions)
      (assoc :claimed-dimensions
             (into (sorted-map)
                   (map (fn [[k v]] [k {:claimed v :status :unverified}]))
                   dimensions)))))

(defn build-section
  "Project a v1 input map to a deterministic report section.

   Accepted input keys are `:snapshot-root`, `:options`, and optional
   `:verification`. Every supplied status is retained only as an unverified
   producer assertion. A verification object, regardless of its own status or
   signature-like fields, cannot change the report qualification."
  [input]
  (when-not (map? input)
    (throw (ex-info "repairability report input must be a map"
                    {:error :invalid-input})))
  (let [options (or (:options input) [])]
    (when-not (and (sequential? options) (every? map? options))
      (throw (ex-info "repairability report options must be a sequence of maps"
                      {:error :invalid-options})))
    {:schema :prf/repairability-report-section.v1
     :qualification :unverified-inputs
     :snapshot-root (:snapshot-root input)
     :options (mapv option-projection options)
     :verification-assertion (when (contains? input :verification)
                               {:schema (get-in input [:verification :schema])
                                :claimed-status (get-in input [:verification :status])
                                :status :unverified
                                :reason :no-independent-verifier-receipt})
     :limitations (->> [(when (nil? (:snapshot-root input)) :snapshot-root-absent)
                        (when (empty? options) :repair-options-absent)
                        (when (contains? input :verification)
                          :verification-output-not-independently-verified)
                        :repair-authorization-not-granted-by-report
                        :publication-authorization-not-granted-by-report]
                       (remove nil?)
                       (mapv name))}))
