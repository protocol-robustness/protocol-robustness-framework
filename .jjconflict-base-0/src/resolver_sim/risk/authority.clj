(ns resolver-sim.risk.authority
  "Authoritative consumption of risk-limit-policy.v1.

   An authoritative protocol transition may be admitted only when its exact
   risk projection satisfies the exact risk-limit-policy.v1 root committed by
   the applicable authoritative chain configuration (chain-configuration.v4).

   Flow:

     authoritative configuration
       -> committed :risk-limit-policy/root
       -> verified policy body (content-addressed store)

     exact candidate transition/state
       -> risk projection (exact-state-bound)
       -> evaluate-bound (exact source root)

   then require:
     - evaluation policy-root == authoritative policy root;
     - projection is exactly bound to the candidate being admitted;
     - evaluation is canonically valid/recomputable;
     - result == :pass.

   The evaluation does NOT grant authority. It is a required condition consumed
   by the existing authority mechanism. The policy used for admission is
   resolved ONLY from the :risk-limit-policy/root of the authoritative
   configuration body; a caller cannot nominate or substitute another policy —
   a substituted body is rejected because its recomputed root cannot equal the
   configured root.

   All integrity failures are rejections (:admitted? false); the existing
   commit/admission path may only continue on :admitted? true."
  (:require [resolver-sim.genesis :as genesis]
            [resolver-sim.risk.limit-evaluation :as le]
            [resolver-sim.risk.limit-policy :as lp]
            [resolver-sim.risk.pro-rata-producer :as producer]
            [resolver-sim.risk.projection :as rp]))

(defn authoritative-policy-root
  "The authoritative risk-limit-policy root committed by a valid
   chain-configuration.v4 body. Fails closed (throws) on a non-V4 or invalid
   configuration. A caller cannot nominate a policy root; it is read only from
   the authoritative configuration lineage."
  [configuration]
  (when-not (genesis/chain-configuration-v4? configuration)
    (throw (ex-info "authoritative risk admission requires a valid chain-configuration.v4"
                    {:type :configuration/invalid
                     :schema (:configuration/schema configuration)
                     :errors (:errors (genesis/validate-chain-configuration-v4 configuration))})))
  (:risk-limit-policy/root configuration))

(defn- configuration-root-check
  [configuration expected-configuration-root]
  (or (nil? expected-configuration-root)
      (= (genesis/chain-configuration-root configuration) expected-configuration-root)))

(defn resolve-policy
  "Resolve the authoritative risk-limit-policy body from a content-addressed
   store, given a validated chain-configuration.v4 body.

   `policy-store` is a function root -> policy body (or nil when absent); it is
   keyed by root, so the store cannot substitute a body for a root it does not
   belong to. The resolved body is validated (closed shape), its canonical root
   is recomputed, and recomputed root MUST equal the configured
   :risk-limit-policy/root. Fails closed (throws) on invalid configuration,
   absent body, malformed body, or root mismatch."
  [configuration policy-store]
  (let [policy-root (authoritative-policy-root configuration)
        policy (policy-store policy-root)]
    (when (nil? policy)
      (throw (ex-info "authoritative risk policy body is unavailable"
                      {:type :risk/policy-body-unavailable :policy-root policy-root})))
    (when-not (lp/policy-valid? policy)
      (throw (ex-info "authoritative risk policy body is malformed"
                      {:type :risk/policy-body-malformed
                       :policy-root policy-root
                       :errors (:errors (lp/validate-policy policy))})))
    (when-not (= (lp/policy-root policy) policy-root)
      (throw (ex-info "authoritative risk policy body root mismatch"
                      {:type :risk/policy-root-mismatch
                       :configured-root policy-root
                       :recomputed-root (lp/policy-root policy)})))
    {:policy policy :policy-root policy-root}))

(defn admit
  "Authoritative risk admission decision for one exact candidate.

   args:
     :configuration                — authoritative chain-configuration.v4 body
     :policy-store                 — fn root -> risk-limit-policy.v1 body | nil
     :projection                   — risk-projection.v1, exact-state-bound
     :exact-source-root            — the exact source (predecessor) state root
                                     the projection derives from; MUST equal
                                     :risk-projection/source-root
     :expected-configuration-root  — optional; when supplied the configuration
                                     body must recompute to this root (the
                                     authoritative lineage head)

   Returns {:admitted? true :evaluation <evaluation> :policy-root <root>}
   or {:admitted? false :reason <keyword> :detail <map>}.

   Fail-closed: every integrity failure is a rejection — invalid configuration,
   configuration-root mismatch, absent/malformed/wrong-rooted policy body,
   non-exact-state-bound projection, source-root mismatch, unit mismatch,
   projection root tampering, evaluation root tampering, evaluation policy-root
   mismatch with the authoritative root, and risk-limit violation."
  [configuration policy-store projection exact-source-root
   & {:keys [expected-configuration-root]}]
  (if-not (genesis/chain-configuration-v4? configuration)
    {:admitted? false :reason :configuration-invalid
     :detail {:schema (:configuration/schema configuration)
              :errors (:errors (genesis/validate-chain-configuration-v4 configuration))}}
    (if-not (configuration-root-check configuration expected-configuration-root)
      {:admitted? false :reason :configuration-root-mismatch
       :detail {:expected-configuration-root expected-configuration-root
                :computed-configuration-root (genesis/chain-configuration-root configuration)}}
      (let [policy-root (:risk-limit-policy/root configuration)
            policy (policy-store policy-root)]
        (cond
          (nil? policy)
          {:admitted? false :reason :risk-policy-body-unavailable
           :policy-root policy-root}

          (not (lp/policy-valid? policy))
          {:admitted? false :reason :risk-policy-body-malformed
           :policy-root policy-root
           :detail (:errors (lp/validate-policy policy))}

          (not= (lp/policy-root policy) policy-root)
          {:admitted? false :reason :risk-policy-root-mismatch
           :policy-root policy-root
           :recomputed-root (lp/policy-root policy)}

          (not (rp/exact-state-bound? projection))
          {:admitted? false :reason :projection-not-exact-state-bound
           :detail {:time-basis (:risk-projection/time-basis projection)}}

          (not= exact-source-root (:risk-projection/source-root projection))
          {:admitted? false :reason :source-root-mismatch
           :exact-source-root exact-source-root
           :projection-source-root (:risk-projection/source-root projection)}

          (not= (:risk-projection/unit-root projection)
                (:risk-limit-policy/unit-root policy))
          {:admitted? false :reason :unit-mismatch
           :projection-unit (:risk-projection/unit-root projection)
           :policy-unit (:risk-limit-policy/unit-root policy)}

          (not= :pass (:status (rp/verify-root projection)))
          {:admitted? false :reason :projection-root-invalid}

          :else
          (let [evaluation (le/evaluate-bound projection policy exact-source-root)]
            (cond
              (not= :pass (:status (le/verify-root evaluation)))
              {:admitted? false :reason :evaluation-root-invalid}

              (not= policy-root (:risk-limit-evaluation/policy-root evaluation))
              {:admitted? false :reason :evaluation-policy-root-mismatch
               :authorized-policy-root policy-root
               :evaluation-policy-root (:risk-limit-evaluation/policy-root evaluation)}

              (= :violation (:risk-limit-evaluation/status evaluation))
              {:admitted? false :reason :risk-limit-violation
               :policy-root policy-root
               :evaluation evaluation}

              :else
              {:admitted? true
               :policy-root policy-root
               :configuration-root (genesis/chain-configuration-root configuration)
               :evaluation evaluation})))))))

(defn admit-pro-rata-operation
  "Admit a staged pro-rata operation under the authoritative configuration.

   The risk projection is derived INTERNALLY from the exact :state-before and
   the exact :stages being admitted (canonical effect kernel replay), so the
   projection is structurally bound to the exact candidate operation: it cannot
   be transplanted from another candidate. The exact source root is the
   canonical state root of :state-before.

   args:
     :configuration       — authoritative chain-configuration.v4 body
     :policy-store        — fn root -> risk-limit-policy.v1 body | nil
     :state-before        — canonical quantity state {root integer}
     :stages              — vector of raw canonical-delta-effect vectors
     :attribution         — {quantity-root [{:risk/domain s :risk/member s?}]}
     :valuation-basis-root, :unit-root — projection basis roots
     :expected-configuration-root — optional (see admit)

   Returns the same decision map as admit."
  [{:keys [configuration policy-store state-before stages attribution
           valuation-basis-root unit-root expected-configuration-root]}]
  (when-not (and (map? state-before) (vector? stages))
    (throw (ex-info "pro-rata admission requires canonical :state-before map and :stages vector"
                    {})))
  (let [projection (producer/exposure-projection
                    {:time-basis {:basis :state-derived}
                     :state-before state-before
                     :stages stages
                     :attribution attribution}
                    {:valuation-basis-root valuation-basis-root
                     :unit-root unit-root})
        exact-source-root (:risk-projection/source-root projection)]
    (admit configuration policy-store projection exact-source-root
           (when expected-configuration-root
             {:expected-configuration-root expected-configuration-root}))))