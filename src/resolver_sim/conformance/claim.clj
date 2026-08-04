(ns resolver-sim.conformance.claim
  "Claim and verdict taxonomy for cross-implementation conformance.

   A machine result must always carry an explicit claim class.  Wording is
   DERIVED from the class; it is never implied by a command name or exit code.

   Claim classes:
     :attested              — the supplied artifacts exactly match a previously
                              attested subject and replay successfully.
     :reproduced            — the subject was independently regenerated from
                              bound implementation identities and produced the
                              expected result.
     :candidate-compatible  — a candidate implementation was evaluated for
                              compatibility; no prior attestation is inherited.
     :accepted-divergence   — a documented, profile-approved divergence from
                              the baseline (deliberate correction or upgrade).
     :not-evaluated         — evaluation did not complete or the claim was
                              denied (fail-closed default).

   Evaluation modes and their permitted claim classes:
     :attested   -> #{:attested}
     :reproduce  -> #{:reproduced}
     :candidate  -> #{:candidate-compatible :accepted-divergence :not-evaluated}
     :compare    -> #{:candidate-compatible :accepted-divergence :not-evaluated}

   A symmetric comparison of two unprivileged implementations (neither
   attested nor canonical) uses :compare; :candidate must not be overloaded
   for that purpose.")

(def claim-classes
  "All machine claim classes."
  #{:attested :reproduced :candidate-compatible :accepted-divergence :not-evaluated})

(def evaluation-modes
  "All evaluation modes."
  #{:attested :reproduce :candidate :compare})

(def mode-permitted-claims
  "Evaluation mode -> permitted claim classes.  This is the authoritative
   mapping, mirrored in etc/conformance/claims.edn for non-Clojure tooling."
  {:attested  #{:attested}
   :reproduce #{:reproduced}
   :candidate #{:candidate-compatible :accepted-divergence :not-evaluated}
   :compare   #{:candidate-compatible :accepted-divergence :not-evaluated}})

(defn permitted-claims-for-mode
  "Permitted claim classes for an evaluation mode (or nil for an unknown mode)."
  [mode]
  (get mode-permitted-claims mode))

(defn known-mode?
  [mode]
  (contains? evaluation-modes mode))

(defn claim-label
  "Human label DERIVED from a machine claim class.  Never the reverse."
  [class]
  (case class
    :attested              "attested"
    :reproduced            "reproduced"
    :candidate-compatible  "candidate-compatible"
    :accepted-divergence   "accepted-divergence"
    :not-evaluated         "not-evaluated"
    (str class)))

(defn valid-claim-class?
  [class]
  (contains? claim-classes class))

(defn claim-result
  "Build a validated machine claim result.

   (claim-result mode class)
   (claim-result mode class status)
   (claim-result mode class status opts)

   `status` is :pass | :fail | :partial.  `opts` may carry evidence bindings
   such as :subject/root, :profile/root, :implementation-set/root, and
   :divergence {...}.  Throws when the class is not permitted for the mode, so
   a caller can never accidentally emit a stronger claim than the mode allows."
  ([mode class] (claim-result mode class :pass {}))
  ([mode class status] (claim-result mode class status {}))
  ([mode class status opts]
   (let [permitted (permitted-claims-for-mode mode)]
     (when-not permitted
       (throw (ex-info "unknown evaluation mode"
                       {:mode mode :known (vec (sort evaluation-modes))})))
     (when-not (contains? permitted class)
       (throw (ex-info "claim class not permitted for evaluation mode"
                       {:mode mode :class class
                        :permitted (vec (sort permitted))})))
     (when-not (contains? #{:pass :fail :partial} status)
       (throw (ex-info "invalid claim status"
                       {:status status :supported [:pass :fail :partial]})))
     (merge {:evaluation/mode mode
             :claim/class class
             :claim/status status}
            opts))))

(defn derive-claim
  "Derive the machine claim class from an evaluation mode and outcome, using
   only modes and boolean outcomes — callers never author the class by hand.

   (derive-claim mode ok? divergence?)"
  [mode ok? diverge?]
  (cond
    (not (known-mode? mode)) :not-evaluated
    (not ok?) :not-evaluated
    (= mode :attested) :attested
    (= mode :reproduce) :reproduced
    diverge? :accepted-divergence
    :else :candidate-compatible))

(defn claim-consistent?
  "True when the claim-result's class is permitted for its mode and its status
   is a valid status."
  [{:keys [evaluation/mode claim/class claim/status]}]
  (and (contains? (or (permitted-claims-for-mode mode) #{}) class)
       (contains? #{:pass :fail :partial} status)))

(defn claim-with-coverage
  "Coverage-bound claim emission: return `claim` only when the coverage receipt
   is complete for the subject set, else nil (no claim emit-able).  A claim can
   never arise from aggregate success while individual subject coverage is
   incomplete."
  [coverage claim]
  (when (:coverage/complete? coverage) claim))

(defn claim-with-evidence
  "Coverage- and reconciliation-bound claim emission.  Returns the claim-result
   with :reconciliation/root bound only when coverage is complete AND the
   planned-vs-observed reconciliation passed.  The claim therefore binds the
   reconciliation root — proof the declared plan was followed — not merely the
   plan fingerprint."
  [coverage reconciliation claim]
  (when (and (:coverage/complete? coverage)
             (= :pass (:reconciliation/status reconciliation)))
    (assoc claim :reconciliation/root (:reconciliation/root reconciliation))))
