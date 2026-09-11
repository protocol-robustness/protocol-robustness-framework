(ns resolver-sim.scenario.theory-eval
  "Named profiles and option resolution for theory evaluation fallbacks.")

(def theory-eval-profiles
  {;; Authoring / WIP — may emit :status :not-falsified with :diagnostics {:grounded? false}
   :optimistic
   {:missing-metric-policy     :all-missing-only
    :empty-logical-policy      :inconclusive
    :validator-error-policy    :inconclusive
    :unsupported-concept-policy :inconclusive}

   :regression
   {:missing-metric-policy     :any-missing-inconclusive
    :empty-logical-policy      :inconclusive
    :validator-error-policy    :inconclusive
    :unsupported-concept-policy :inconclusive}

   ;; :any-missing-fail → :status :inconclusive (:reason :strict-missing-metrics), not :falsified
   :strict
   {:missing-metric-policy     :any-missing-fail
    :empty-logical-policy      :fail
    :validator-error-policy    :throw
    :unsupported-concept-policy :fail}

   :public-evidence
   {:missing-metric-policy     :any-missing-fail
    :empty-logical-policy      :fail
    :validator-error-policy    :fail
    :unsupported-concept-policy :fail}})

(def default-theory-eval-profile :regression)

(def ^:private profile-aliases
  {:exploratory :optimistic
   :authoring   :optimistic})

(defn resolve-theory-eval-opts
  "Merge profile defaults with explicit opts. Profile from :theory-eval-profile
   or :theory-eval-mode (alias). Legacy :exploratory / :authoring → :optimistic."
  [opts]
  (let [raw-kw     (or (:theory-eval-profile opts)
                       (:theory-eval-mode opts)
                       default-theory-eval-profile)
        profile-kw (get profile-aliases raw-kw raw-kw)
        profile    (get theory-eval-profiles profile-kw
                        (get theory-eval-profiles default-theory-eval-profile))]
    (merge profile opts {:theory-eval-profile profile-kw})))
