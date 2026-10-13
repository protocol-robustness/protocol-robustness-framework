(ns resolver-sim.scenario.reputation-profiles
  "Named utility profiles for reputation-aware SPE evaluation.

   A profile is a map of utility-spec fields that can be merged into an
   :spe-config :utility-spec.  All profiles extend :resolver-reputation-v1.

   Profile taxonomy (layered):

   Layer 1 — Reputation strength (sensitivity analysis)
     :reputation/none          No reputation effect; tests mechanism without reputation assumptions.
     :reputation/conservative  Low but non-zero penalty; guards against overclaiming.
     :reputation/baseline      Default launch assumption for curated resolver context.

   Layer 2 — Actor type (adversarial / lifecycle realism)
     :actor/senior-resolver    High future value; strong status-loss deterrence.
     :actor/exiting-resolver   Final-period risk; low future earnings expected.

   Layer 3 — Identity continuity (sybil / re-entry resistance)
     :identity/cheap-reentry   Resolver can cheaply abandon identity; weak reputation binding.

   All profiles declare:
     :profile/id               canonical keyword identifier
     :profile/category         :sensitivity | :actor | :market | :adversarial | :governance | :identity
     :profile/evidence-basis   :assumption | :empirical | :stress-test | :placeholder

   Resolved profiles are merged into spe-config.utility-spec before evaluation.
   Fields not present in a profile inherit the caller's utility-spec defaults.")

;; ---------------------------------------------------------------------------
;; Built-in profile definitions
;; ---------------------------------------------------------------------------

(def ^:private built-in-profiles
  {:reputation/none
   {:profile/id             :reputation/none
    :profile/category       :adversarial
    :profile/evidence-basis :stress-test
    :profile/description    "Resolver expects no future flow or can cheaply re-enter under a new identity. Tests whether the mechanism works without any reputation assumption."
    :type                   :resolver-reputation-v1
    :reputation/model       :fixed-penalty
    :reputation-slash-penalty 0
    :reputation-discount-rate 0.0
    :reputation-event-penalties {:resolver-slashed 0 :verdict-reversed 0 :senior-status-lost 0}}

   :reputation/conservative
   {:profile/id             :reputation/conservative
    :profile/category       :sensitivity
    :profile/evidence-basis :assumption
    :profile/description    "Low but non-zero reputation loss. Guards against overclaiming; reflects early-stage market with limited future flow and weak public reputation effects."
    :type                   :resolver-reputation-v1
    :reputation/model       :fixed-penalty
    :reputation-slash-penalty 25
    :reputation-discount-rate 0.5
    :reputation-event-penalties {:resolver-slashed 25 :verdict-reversed 5 :senior-status-lost 50}}

   :reputation/baseline
   {:profile/id             :reputation/baseline
    :profile/category       :sensitivity
    :profile/evidence-basis :assumption
    :profile/description    "Default launch assumption for a governance-curated resolver context where slashing materially affects future eligibility and routing."
    :type                   :resolver-reputation-v1
    :reputation/model       :fixed-penalty
    :reputation-slash-penalty 100
    :reputation-discount-rate 0.8
    :reputation-event-penalties {:resolver-slashed 100 :verdict-reversed 25 :senior-status-lost 200}}

   :actor/senior-resolver
   {:profile/id             :actor/senior-resolver
    :profile/category       :actor
    :profile/evidence-basis :assumption
    :profile/description    "High future value resolver. Loss of senior status materially reduces expected future earnings, routing probability, and eligibility for high-value cases."
    :type                   :resolver-reputation-v1
    :reputation/model       :event-penalty
    :reputation-discount-rate 0.9
    :reputation-event-penalties {:resolver-slashed 250 :verdict-reversed 75
                                 :malicious-verdict-reversed 200 :senior-status-lost 500
                                 :resolver-suspended 250}}

   :actor/exiting-resolver
   {:profile/id             :actor/exiting-resolver
    :profile/category       :actor
    :profile/evidence-basis :stress-test
    :profile/description    "Resolver expects low future case volume (near exit). Tests final-period / reputation-collapse risk where future reputation value approaches zero."
    :type                   :resolver-reputation-v1
    :reputation/model       :expected-future-earnings
    :expected-future-cases  5
    :expected-fee-per-case  2.0
    :routing-probability-before 0.05
    :routing-probability-after  0.0
    :resolver-margin        1.0
    :reputation-discount-rate 0.4}

   :identity/cheap-reentry
   {:profile/id             :identity/cheap-reentry
    :profile/category       :identity
    :profile/evidence-basis :stress-test
    :profile/description    "Resolver can cheaply abandon identity and re-enter under a new address. Reputation deterrence is weakened because identity continuity has low value."
    :type                   :resolver-reputation-v1
    :reputation/model       :fixed-penalty
    :reputation-slash-penalty 5
    :reputation-discount-rate 0.2
    :reputation-event-penalties {:resolver-slashed 5 :verdict-reversed 0 :senior-status-lost 10}}})

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn resolve-utility-profile
  "Return a utility-spec map for the given profile.

   - Keyword argument: looks up in built-in-profiles; throws if unknown.
   - Map argument: returned as-is (inline profile passthrough).
   - nil: returns nil (caller should handle).

   The returned map can be merged into spe-config.utility-spec via:
     (merge base-utility-spec (resolve-utility-profile :reputation/baseline))"
  [profile]
  (cond
    (nil? profile) nil
    (keyword? profile)
    (or (get built-in-profiles profile)
        (throw (ex-info (str "Unknown utility profile: " profile
                             ". Known profiles: " (keys built-in-profiles))
                        {:profile profile :known (keys built-in-profiles)})))
    ;; JSON deserializes keywords as strings; coerce "ns/name" or "name" to keyword
    (string? profile) (resolve-utility-profile (keyword profile))
    (map? profile) profile
    :else
    (throw (ex-info (str "Invalid profile argument (expected keyword or map): " (pr-str profile))
                    {:profile profile}))))

(defn all-profile-ids
  "Return the sorted sequence of all built-in profile keywords."
  []
  (sort (keys built-in-profiles)))

(defn profile-category
  "Return the :profile/category for a named profile, or nil."
  [profile-id]
  (get-in built-in-profiles [profile-id :profile/category]))
