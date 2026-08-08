(ns resolver-sim.allocation.round-state
  "Consume the completed cancellation vocabulary for the allocation coprocessor.

   Maps a coprocessor allocation-round state token onto the canonical
   probabilistic-allocation lifecycle (probabilistic-allocation-window, a
   cancellation-window.v1 instantiation) and lifts a classification into a
   certificate-ready cancellation-window assertion.

   This namespace only *consumes* the finished vocabulary in
   resolver-sim.assurance.canonical-force-authorisation; it adds no new
   lifecycle semantics, no 2-of-3 authority, and no window mechanics. The
   random-request cutpoint therefore still lives only in
   probabilistic-allocation-window.

   The coprocessor round states accepted here are the canonical lifecycle
   spellings (kebab keywords and their string forms); unknown tokens fail
   closed to :unknown-target-state."
  (:require [resolver-sim.assurance.canonical-force-authorisation :as cfa]))

(def coprocessor-round-states
  "Canonical coprocessor round-state tokens the mapper recognises, in the order
   they occur in a round (pre-cutpoint to terminal)."
  [:allocation-committed :randomness-requested :randomness-fulfilled
   :result-proposed :result-accepted :claim-consumption-started])

(def ^:private lifecycle-target-by-token
  "Token spelling (keyword or string) -> canonical lifecycle target-state.
   Every value is a member of the probabilistic-allocation-window vocabulary."
  (into {cfa/cancellation-window-v1-schema :window/schema}
        (mapcat (fn [st] [[st st] [(name st) st]]))
        coprocessor-round-states))

(defn lifecycle-target-state
  "Map a coprocessor round-state token to its canonical lifecycle target-state
   keyword (a member of `probabilistic-allocation-window`). Fails closed: an
   unrecognised token maps to nil."
  [token]
  (get lifecycle-target-by-token token))

(defn classify-round-state
  "Classify a coprocessor round-state token against the probabilistic-allocation
   window. Returns the raw cancellation-window.v1 classification, or a failing
   :invalid classification when the token is missing, unrecognised, or
   malformed. Missing, unrecognised, and malformed tokens are distinguished so
   the public projection can report the precise fail-closed reason:
     absent/nil token        -> :missing-target-state
     unrecognised token      -> :unknown-target-state
     non-token value         -> :malformed-round-state

   Tokens are accepted in either canonical spelling (keyword or string); a
   recognised token classifies its window, an unrecognised token is
   :unknown-target-state, and any non-(keyword-or-string) value is
   :malformed-round-state."
  [token]
  (let [target (lifecycle-target-state token)]
    (cond
      (nil? token)
      {:window/schema cfa/cancellation-window-v1-schema
       :window/state :invalid
       :window/possible? false
       :window/blocking-reasons [:missing-target-state]}

      (some? target)
      (cfa/classify-lifecycle-window cfa/probabilistic-allocation-window target)

      (or (keyword? token) (string? token))
      {:window/schema cfa/cancellation-window-v1-schema
       :window/state :invalid
       :window/possible? false
       :window/blocking-reasons [:unknown-target-state]}

      :else
      {:window/schema cfa/cancellation-window-v1-schema
       :window/state :invalid
       :window/possible? false
       :window/blocking-reasons [:malformed-round-state]})))

(defn classify-round-cancellation
  "Reconcile cancelling a coprocessor round at `token` against the canonical
   three-member profile and the probabilistic-allocation window. Consumes
   `classify-cancellation`; supply the decision profile in opts as usual
   (:member-count :threshold :profile-id :named-policy?)."
  [opts token]
  (cfa/classify-cancellation
   (merge opts {:window cfa/probabilistic-allocation-window})
   (lifecycle-target-state token)))

(defn cancellation-assertion
  "Produce the certificate-ready cancellation-window assertion for a
   coprocessor round at `token`. The classification is recomputed from the
   observed round-state input token, so the assertion may claim
   `:assurance :independent-replay` (contract 8): deterministic given the same
   input.

   Consumes `cancellation-window-assertion`'s derived-state path: the domain
   projection here is `lifecycle-target-state`, the lifecycle profile is
   the canonical probabilistic-allocation window, and `decision-opts` carries
   the canonical decision profile. Pass opts through for the decision profile
   (:profile-id :member-count :threshold :named-policy?)."
  [opts token]
  (cfa/cancellation-window-assertion
   {:target-evidence {:round/state token}
    :lifecycle-profile cfa/probabilistic-allocation-window
    :domain-projection (fn [evidence] (lifecycle-target-state (:round/state evidence)))
    :decision-opts opts}))

(def lifecycle-profile-id
  "Public lifecycle profile identifier for the probabilistic-allocation window."
  "prf.lifecycle-window/probabilistic-allocation")

(def lifecycle-profile-version
  "Public lifecycle profile version for the probabilistic-allocation window."
  1)

(defn round-lifecycle
  "Project the stable public `round-lifecycle` for a coprocessor round-state
   token. Always present in the kernel public values; every field is committed
   into CERTIFICATE_ASSERTIONS_V2.

   The classification is derived from the observed round-state input token.
   Both the observed state and the derived classification are committed into
   CERTIFICATE_ASSERTIONS_V2, so an independent verifier can re-run the kernel
   and compare. The `:assurance` is `:independent-replay`: deterministic given
   the same input token.
   The lifecycle assertion status is :passing when the window classifies :open
   or :closed (the window was respected) and :failing when it is :invalid.

   `_opts` is reserved for future decision-profile evaluation. Present for
   interface consistency with `cancellation-assertion` (which passes its
   decision opts through to `classify-cancellation`); currently unused by
   `round-lifecycle`."

  [_opts token]
  (let [target (lifecycle-target-state token)
        classification (classify-round-state token)
        window-state (:window/state classification)
        reasons (:window/blocking-reasons classification)]
    {:round-state (cond (string? token) token
                        (keyword? token) (name token)
                        :else nil)
     :derived-state (some-> target name)
     :lifecycle-profile-id lifecycle-profile-id
     :lifecycle-profile-version lifecycle-profile-version
     :cancellation-window-schema cfa/cancellation-window-v1-schema
     :cancellation-window (name window-state)
     :cancellation-possible (= :open window-state)
     :cancellation-blocking-reasons (mapv name reasons)
     :lifecycle-assertion-status (if (= :invalid window-state) "failing" "passing")
     :evidence-status "evidence/derived-state"
     :assurance "independent-replay"}))