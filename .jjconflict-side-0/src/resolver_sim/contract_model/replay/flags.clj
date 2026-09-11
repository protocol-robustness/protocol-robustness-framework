(ns resolver-sim.contract-model.replay.flags
  "Replay capability flags — opt-in orchestration for theory, temporal, and validation.

   Defaults preserve existing Sew invariant-suite behaviour. Use
   `simple-replay` for library-style scenarios; `replay-events` for full control.")

(def default-replay-flags
  "Full replay: invariants on, expectations on, strict validation, temporal from scenario.
  Default :metrics-profile is :sew-integrated; pass :metrics-profile for other protocols."
  {:check-invariants?        true
   :evaluate-expectations?    true
   :evaluate-theory?         nil
   :temporal-enabled?        nil
   :strict-validation?       true
   :metrics-profile          :sew-integrated
   :world-checkpoint-policy  :decision-nodes-only
   :projection-mode          :full
   :require-event-id?        false
   :include-telemetry-evidence? false
   :evidence-mode              :all
   :yield-dt-validation?       false
   :fail-on-short-circuits     #{}})

(def fast-regression-flags
  "Fast regression: theory deferred (or disabled)."
  (assoc default-replay-flags
         :evaluate-theory? false
         :projection-mode :finalize-only))

(def golden-full-flags
  "Golden full replay."
  default-replay-flags)

(def audit-flags
  "Audit replay: enable telemetry."
  (assoc default-replay-flags
         :include-telemetry-evidence? true))

(def simple-forced-flags
  "Flags the :simple profile enforces after scenario and caller flags merge.
   They preserve the profile's no-evidence/no-checkpoint contract; callers may
   still opt into safe evaluation flags such as :strict-validation?."
  {:evidence-mode :none
   :include-telemetry-evidence? false
   :world-checkpoint-policy :omit
   :projection-mode :finalize-only})

(def minimal-replay-flags
  "Library-style replay: no temporal enforcement, no theory DSL, relaxed validation, no evidence."
  (assoc default-replay-flags
         :evaluate-theory?         false
         :temporal-enabled?        false
         :strict-validation?       false
         :metrics-profile          :yield-provider
         :world-checkpoint-policy  :omit
         :require-event-id?        false
         :evidence-mode            :none))

(def profiles
  {:replay/fast-regression fast-regression-flags
   :replay/golden-full     golden-full-flags
   :replay/audit           audit-flags
   :replay/simple          minimal-replay-flags})

(def external-log-replay-flags
  "External log / chain-ingestion replay: require event-id on replay-sensitive actions."
  (assoc default-replay-flags
         :require-event-id? true
         :world-checkpoint-policy :retain-all))

(defn- flag-lookup
  "Look up a replay flag `k` from the most specific source.
   Uses `contains?` so that legitimately false values (e.g. :strict-validation? false)
   are not mistaken for missing entries."
  [scenario replay-opts k default]
  (let [from-replay-flags (some-> replay-opts :flags (find k) val)
        from-replay-opts  (some-> replay-opts (find k) val)
        from-scenario     (some-> scenario :options :flags (find k) val)
        from-minimal      (let [minimal? (or (get-in replay-opts [:minimal])
                                             (get-in scenario [:options :minimal]))]
                            (when minimal?
                              (get minimal-replay-flags k)))]
    (cond
      (contains? replay-opts k)          from-replay-opts
      (contains? (:flags replay-opts) k) from-replay-flags
      (contains? (get-in scenario [:options :flags]) k) from-scenario
      (some? from-minimal)               from-minimal
      :else                              default)))

(defn resolve-replay-flags
  "Merge explicit `replay-opts`, scenario `:options`, and defaults.

   `:evaluate-theory?` / `:temporal-enabled?` nil means derive from scenario
   (theory block present; temporal-evidence :enabled?)."
  ([scenario] (resolve-replay-flags scenario {}))
  ([scenario replay-opts]
   (let [profile    (or (:profile replay-opts)
                        (get-in scenario [:options :profile]))
         base       (get profiles profile (if (:minimal replay-opts) minimal-replay-flags default-replay-flags))
         theory-present? (boolean (:theory scenario))
         temporal-cfg    (:temporal-evidence scenario)
         temporal-default? (boolean (:enabled? temporal-cfg))]
     (let [resolved (merge base
                           (select-keys (or (get-in scenario [:options :flags]) {}) (keys base))
                           (select-keys (or (:flags replay-opts) {}) (keys base))
                           {:evaluate-theory?
                            (let [v (flag-lookup scenario replay-opts :evaluate-theory? nil)]
                              (if (nil? v)
                                (and (not (or (:minimal replay-opts) (= profile :minimal))) theory-present?)
                                (boolean v)))
                            :temporal-enabled?
                            (let [v (flag-lookup scenario replay-opts :temporal-enabled? nil)]
                              (if (nil? v) temporal-default? (boolean v)))
                            :check-invariants?
                            (boolean (flag-lookup scenario replay-opts :check-invariants? true))
                            :evaluate-expectations?
                            (boolean (flag-lookup scenario replay-opts :evaluate-expectations? true))
                            :strict-validation?
                            (boolean (flag-lookup scenario replay-opts :strict-validation?
                                                  (not (or (:minimal replay-opts) (= profile :minimal)))))
                            :metrics-profile
                            (keyword (name (or (flag-lookup scenario replay-opts :metrics-profile
                                                            (if (or (:minimal replay-opts) (= profile :minimal)) :yield-provider :sew-integrated))
                                               :sew-integrated)))
                            :world-checkpoint-policy
                            (keyword (name (or (flag-lookup scenario replay-opts :world-checkpoint-policy
                                                            (if (or (:minimal replay-opts) (= profile :minimal)) :omit :decision-nodes-only))
                                               :decision-nodes-only)))
                            :projection-mode
                            (keyword (name (or (flag-lookup scenario replay-opts :projection-mode
                                                            (if (or (:minimal replay-opts) (= profile :minimal)) :finalize-only :full))
                                               :full)))
                            :require-event-id?
                            (boolean (flag-lookup scenario replay-opts :require-event-id? false))
                            :evidence-mode
                            (keyword (name (or (flag-lookup scenario replay-opts :evidence-mode
                                                            (if (or (:minimal replay-opts) (= profile :minimal)) :none :all))
                                               :all)))})]
       (if (= profile :replay/simple)
         (merge resolved simple-forced-flags)
         resolved)))))

(defn runner-opts-from-flags
  "Map replay flags to `scenario.runner` theory opts."
  [flags]
  {:evaluate-theory? (:evaluate-theory? flags)
   :require-theory?   false
   :strict-theory?    false})
