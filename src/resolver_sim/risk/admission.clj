(ns resolver-sim.risk.admission
  "Final risk-controlled pro-rata admission authority bridge.

   A candidate pro-rata operation is admitted and committed only against the
   exact risk-limit-policy.v1 committed by the CURRENTLY APPLICABLE
   authoritative chain configuration, with no stale-head or caller-asserted gap
   between authority resolution, evaluation, and commit.

   Authority-currentness is NOT re-derived here: it is the existing
   configuration-head machinery (resolver-sim.configuration-head) whose current
   head's :configuration/head-root IS the current configuration root, advanced
   only by a fenced CAS. Bodies are retained content-addressed (root-recomputed)
   so a caller cannot nominate a configuration or policy root. Stale-head /
   TOCTOU protection is the existing two-phase fence/CAS pattern
   (issue-fence then finalize-under-fence), re-checking the head in the same
   atomic update that finalizes.

   Lineage-conserving chain (every link recomputed/verified):
     authoritative head H
       -> committed configuration root C      (= :configuration/head-root H)
       -> canonical configuration body        (retained, root-recomputed)
       -> committed :risk-limit-policy/root   (mandatory in V4)
       -> canonical policy body               (retained, root-recomputed)
       -> exact candidate risk projection     (derived internally)
       -> passing evaluation
       -> successor business-state commit under the same valid head H.

   No risk-specific head/epoch/fence store is introduced; this is the final
   authority/currentness bridge over the existing authority model."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.configuration-head :as configuration-head]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.risk.limit-evaluation :as le]
            [resolver-sim.risk.limit-policy :as lp]
            [resolver-sim.risk.pro-rata-producer :as producer])
  (:import [java.nio.file Files StandardCopyOption]))

(def risk-admission-request-schema "risk-controlled-pro-rata-admission-request.v1")

(def risk-admission-request-fields
  "Closed field set of the canonical semantic request artifact."
  #{:risk-admission-request/schema
    :state-before/root
    :effects/root
    :risk-projection/root
    :risk-admission-request/root})

(def ^:private risk-admission-request-domain-tag
  "String domain tag for the semantic risk-controlled pro-rata request."
  "RISK_CONTROLLED_PRO_RATA_ADMISSION_REQUEST_V1")

(def risk-admission-schema "risk-controlled-pro-rata-admission.v1")
(def risk-admission-basis-schema "risk-controlled-pro-rata-admission-basis.v1")
(def risk-fence-schema "risk-admission-fence.v1")

(def ^:private risk-admission-domain-tag
  "String domain tag for the risk-controlled admission commitment. A string
   tag is used (as in resolver-sim.notebook-support.speds.risk) so this
   namespace does not mutate the shared domain-tags authority."
  "RISK_CONTROLLED_PRO_RATA_ADMISSION_V1")

(def ^:private risk-fence-domain-tag
  "String domain tag for the risk-admission-fence commitment."
  "RISK_ADMISSION_FENCE_V1")

(def ^:private risk-admission-basis-domain-tag
  "String domain tag for the complete semantic basis of an adopted pro-rata
   quantity-state successor."
  "RISK_CONTROLLED_PRO_RATA_ADMISSION_BASIS_V1")

(defn- admission-root
  [record]
  (hash-ref/sha256-ref
   (hc/domain-hash risk-admission-domain-tag
                   (dissoc record :risk-controlled-pro-rata-admission/root))))

(defn- fence-root
  [record]
  (hash-ref/sha256-ref
   (hc/domain-hash risk-fence-domain-tag
                   (dissoc record :risk-admission-fence/root))))

(defn- admission-basis-root
  [basis]
  (hash-ref/sha256-ref
   (hc/domain-hash risk-admission-basis-domain-tag
                   (dissoc basis :risk-controlled-pro-rata-admission-basis/root))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Store
;; ──────────────────────────────────────────────────────────────────────────────

(deftype RiskAdmissionStore [state-atom])

(defn new-store
  "Create an empty risk-controlled admission store with an initial configuration
   head for `initial-configuration-root` at `initial-epoch` over the given
   `business-state` (a canonical quantity map). Configuration and risk-policy
   bodies are added via retain-configuration!/retain-risk-policy! before
   admission."
  [initial-configuration-root initial-epoch business-state]
  (RiskAdmissionStore.
   (atom {:configuration/current-head
          (configuration-head/initial-head initial-configuration-root initial-epoch)
          :chain-configurations {}
          :risk-limit-policies {}
          :business/state business-state
          :risk-fences {}
          :risk-admissions {}
          :configuration/commit-index 0
          :configuration/store-version 0})))

(defn current-head
  [store]
  (:configuration/current-head @(.state-atom store)))

(defn state-snapshot
  [store]
  @(.state-atom store))

;; ──────────────────────────────────────────────────────────────────────────────
;; Content-addressed retention (no caller-asserted root authority)
;; ──────────────────────────────────────────────────────────────────────────────

(defn retain-configuration!
  "Retain a configuration body under its own recomputed root. Fails closed
   (throws) unless the body is a supported chain configuration whose recomputed
   root matches its schema. The body can later only be resolved by that root —
   a caller cannot attach a body to an arbitrary root."
  [store configuration]
  (let [root (genesis/chain-configuration-root configuration)]
    (swap! (.state-atom store)
           update :chain-configurations assoc root configuration)
    root))

(defn retain-risk-policy!
  "Retain a risk-limit-policy body under its own recomputed root. Fails closed
   (throws) on a malformed policy or a root mismatch. Content-addressed: the
   body is only resolvable by the root it actually commits."
  [store policy]
  (when-not (lp/policy-valid? policy)
    (throw (ex-info "cannot retain malformed risk policy"
                    {:errors (:errors (lp/validate-policy policy))})))
  (let [root (lp/policy-root policy)]
    (swap! (.state-atom store) update :risk-limit-policies assoc root policy)
    root))

;; ──────────────────────────────────────────────────────────────────────────────
;; Configuration activation (advances the authoritative head via fenced CAS)
;; ──────────────────────────────────────────────────────────────────────────────

(defn activate-configuration!
  "Advance the authoritative configuration head to a successor, retaining the
   new configuration body and its risk-policy body. Fenced by the exact
   expected current head root and store version (CAS); a stale caller is
   rejected rather than installing a non-current successor.

   `command` :: {:transition .. :parent-configuration .. :new-configuration ..
                 :new-risk-policy <risk-limit-policy.v1 body> ..
                 :expected-head-root .. :expected-store-version ..}

   Returns {:status :committed ...} or a rejection reason."
  [store command]
  (let [{:keys [transition parent-configuration new-configuration new-risk-policy
                expected-head-root expected-store-version]} command]
    (loop []
      (let [current @(.state-atom store)
            head (:configuration/current-head current)
            derived (try
                      (configuration-head/derive-successor-head
                       head transition parent-configuration new-configuration)
                      (catch Exception _ {:status :rejected :reason :configuration-transition-invalid}))]
        (cond
          (and (some? expected-store-version)
               (not= expected-store-version (:configuration/store-version current)))
          {:status :rejected :reason :version-mismatch}

          (and (some? expected-head-root)
               (not= expected-head-root (:configuration-head-state/root head)))
          {:status :rejected :reason :configuration-head-mismatch}

          (not= :committed (:status derived))
          derived

          :else
          (let [next-head (:configuration/head derived)
                config-root (:configuration/head-root next-head)
                policy-root (:risk-limit-policy/root new-configuration)
                next (-> current
                         (assoc :configuration/current-head next-head)
                         (update :chain-configurations assoc
                                 config-root new-configuration)
                         (update :risk-limit-policies assoc
                                 policy-root new-risk-policy)
                         (assoc :configuration/store-version
                                (inc (:configuration/store-version current))))]
            (if (compare-and-set! (.state-atom store) current next)
              {:status :committed
               :configuration/head next-head
               :configuration/root config-root}
              (recur))))))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Current authority resolution (derived, never caller-supplied)
;; ──────────────────────────────────────────────────────────────────────────────

(defn resolve-current
  "Resolve the currently applicable authoritative configuration and risk policy
   ONLY from retained bodies of the current head:

     head -> :configuration/head-root -> retained configuration body
          -> :risk-limit-policy/root -> retained policy body

   Every link is recomputed/validated. Returns {:resolved? true ...} or
   {:resolved? false :reason ...}. A caller cannot supply a configuration body,
   configuration root, or risk-policy root — nothing here is caller-asserted."
  [state]
  (let [head (:configuration/current-head state)]
    (if-not (configuration-head/valid-head-state? head)
      {:resolved? false :reason :configuration-head-invalid}
      (let [config-root (:configuration/head-root head)
            config (get-in state [:chain-configurations config-root])]
        (cond
          (nil? config)
          {:resolved? false :reason :configuration-body-unavailable}

          (not (genesis/chain-configuration-v4? config))
          {:resolved? false :reason :configuration-not-v4}

          (not= config-root (genesis/chain-configuration-root config))
          {:resolved? false :reason :configuration-root-mismatch}

          :else
          (let [policy-root (:risk-limit-policy/root config)
                policy (get-in state [:risk-limit-policies policy-root])]
            (cond
              (nil? policy)
              {:resolved? false :reason :risk-policy-body-unavailable}

              (not (lp/policy-valid? policy))
              {:resolved? false :reason :risk-policy-body-malformed}

              (not= policy-root (lp/policy-root policy))
              {:resolved? false :reason :risk-policy-root-mismatch}

              :else
              {:resolved? true
               :head head
               :head-state-root (:configuration-head-state/root head)
               :configuration config
               :configuration/root config-root
               :risk-policy policy
               :risk-policy/root policy-root})))))))

(defn current-resolved
  "Convenience: resolve the current authority from a store."
  [store]
  (resolve-current (state-snapshot store)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Exact candidate projection derivation (internal, non-transplantable)
;; ──────────────────────────────────────────────────────────────────────────────

(defn derive-candidate-projection
  "Derive the exact risk projection for a candidate pro-rata operation from its
   :state-before and :stages, using :attribution and :valuation-basis-root /
   :unit-root. The projection is bound to the exact state-before (source) and
   the exact staged effects (candidate); it is never caller-supplied."
  [{:keys [state-before stages attribution valuation-basis-root unit-root]}]
  (producer/exposure-projection
   {:time-basis {:basis :state-derived}
    :state-before state-before
    :stages stages
    :attribution attribution}
   {:valuation-basis-root valuation-basis-root
    :unit-root unit-root}))

(defn- candidate-state-after
  [candidate-op]
  (reduce (fn [state stage]
            (:state-after (effects/transition state stage)))
          (:state-before candidate-op)
          (:stages candidate-op)))

(defn- candidate-effects
  [candidate-op]
  (effects/normalize-effects (mapcat identity (:stages candidate-op))))

(defn risk-admission-request-root
  "Recompute the domain-separated root of a canonical request artifact."
  [request]
  (hash-ref/sha256-ref
   (hc/domain-hash
    risk-admission-request-domain-tag
    (select-keys request
                 [:risk-admission-request/schema
                  :state-before/root
                  :effects/root
                  :risk-projection/root]))))

(defn risk-admission-request
  "Derive the closed semantic identity of a candidate operation.

   The predecessor root distinguishes retry from rebase. The normalized effects
   root identifies the requested economic successor, while the projection root
   additionally commits staged-path risk, canonical attribution, valuation, and
   unit semantics. Runtime fence and storage metadata are never inputs."
  [candidate-op]
  (let [projection (derive-candidate-projection candidate-op)
        request {:risk-admission-request/schema risk-admission-request-schema
                 :state-before/root (effects/state-root (:state-before candidate-op))
                 :effects/root (effects/effect-root (candidate-effects candidate-op))
                 :risk-projection/root (:risk-projection/root projection)}]
    (assoc request :risk-admission-request/root
           (risk-admission-request-root request))))

(defn valid-risk-admission-request?
  "True when a request artifact has the exact v1 shape and a valid root."
  [request]
  (let [canonical-root? #(and (string? %)
                              (boolean
                               (re-matches #"(?:sha256:)?[0-9a-f]{64}" %)))]
    (and (map? request)
         (= risk-admission-request-fields (set (keys request)))
         (= risk-admission-request-schema (:risk-admission-request/schema request))
         (every? canonical-root?
                 ((juxt :state-before/root :effects/root :risk-projection/root
                        :risk-admission-request/root)
                  request))
         (= (:risk-admission-request/root request)
            (risk-admission-request-root request)))))

(defn- admission-basis
  "Derive the complete semantic basis for the exact quantity-state successor
   about to be adopted. This is deliberately independent of runtime fence IDs:
   a fence authorizes finalization, while the basis commits the state change."
  [resolved record candidate-op state-before state-after projection evaluation]
  (let [effects (candidate-effects candidate-op)
        reconstructed-after (effects/apply-effects state-before effects)
        basis {:schema-version risk-admission-basis-schema
               :configuration/root (:configuration/root resolved)
               :head-state-root (:head-state-root resolved)
               :risk-policy/root (:risk-policy/root resolved)
               :state-before/root (effects/state-root state-before)
               :effects/root (effects/effect-root effects)
               :state-after/root (effects/state-root state-after)
               :risk-projection/root (:risk-projection/root projection)
               :evaluation/root (:risk-limit-evaluation/root evaluation)}]
    (when-not (= state-after reconstructed-after)
      (throw (ex-info "candidate effects do not derive adopted state"
                      {:reason :admission/effect-closure-mismatch})))
    (when-not (= (:state-before/root basis)
                 (:business/state-before-root record))
      (throw (ex-info "fence state-before root does not match candidate"
                      {:reason :admission/state-before-root-mismatch})))
    (when-not (= (:risk-projection/root basis) (:risk-projection/root record))
      (throw (ex-info "fence projection root does not match candidate"
                      {:reason :admission/risk-projection-root-mismatch})))
    (when-not (= (:evaluation/root basis) (:evaluation/root record))
      (throw (ex-info "fence evaluation root does not match candidate"
                      {:reason :admission/evaluation-root-mismatch})))
    (assoc basis :risk-controlled-pro-rata-admission-basis/root
           (admission-basis-root basis))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Two-phase fence/CAS (mirrors evaluate-and-issue / finalise-under-fence)
;; ──────────────────────────────────────────────────────────────────────────────

(defn issue-fence-transition
  "Pure fence issuance transition. `fence-id` is generated by the storage
   adapter; every semantic and authority value is derived from `state` and
   `candidate-op`. Returns `:state` only when issuance succeeds."
  [state fence-id candidate-op]
  (let [resolved (resolve-current state)]
    (cond
      (not (:resolved? resolved))
      {:status :rejected :reason (:reason resolved)}

      (not= (:state-before candidate-op) (:business/state state))
      {:status :rejected :reason :candidate-state-not-current}

      :else
      (let [projection (derive-candidate-projection candidate-op)
            request (risk-admission-request candidate-op)
            policy (:risk-policy resolved)
            exact-source-root (:risk-projection/source-root projection)
            eval (le/evaluate-bound projection policy exact-source-root)]
        (cond
          (not= (:risk-limit-policy/root policy)
                (:risk-limit-evaluation/policy-root eval))
          {:status :rejected :reason :evaluation-policy-root-mismatch}

          (not= :pass (:status (le/verify-root eval)))
          {:status :rejected :reason :evaluation-root-invalid}

          (= :violation (:risk-limit-evaluation/status eval))
          {:status :rejected :reason :risk-limit-violation
           :evaluation eval}

          :else
          (let [record {:schema-version risk-fence-schema
                        :configuration/head-root (:configuration/root resolved)
                        :head-state-root (:head-state-root resolved)
                        :business/state-before-root
                        (effects/state-root (:business/state state))
                        :risk-policy/root (:risk-policy/root resolved)
                        :exact-source-root exact-source-root
                        :risk-admission-request/root
                        (:risk-admission-request/root request)
                        :risk-projection/root (:risk-projection/root projection)
                        :evaluation/root (:risk-limit-evaluation/root eval)
                        :evaluation/status (:risk-limit-evaluation/status eval)
                        :status :issued
                        :candidate-op candidate-op}
                record (assoc record :risk-admission-fence/root (fence-root record))]
            {:status :issued
             :state (assoc-in state [:risk-fences fence-id] record)
             :fence/id fence-id
             :risk-admission-request request
             :risk-admission-request/root (:risk-admission-request/root request)
             :risk-policy/root (:risk-policy/root resolved)
             :configuration/root (:configuration/root resolved)
             :evaluation eval}))))))

(defn issue-risk-fence!
  "Atom-backed adapter for issue-fence-transition. Generates the runtime fence
   token, then CAS-retries the pure successor without owning domain semantics."
  [store candidate-op]
  (let [fence-id (str "fence-" (System/nanoTime) "-" (rand-int 1000000))]
    (loop []
      (let [current @(.state-atom store)
            result (issue-fence-transition current fence-id candidate-op)]
        (if-not (= :issued (:status result))
          result
          (if (compare-and-set! (.state-atom store) current (:state result))
            (dissoc result :state)
            (recur)))))))

(defn finalise-fence-transition
  "Pure authoritative finalization transition. Revalidates the fence against
   the explicit current state, derives all semantic roots and the successor, and
   returns the complete successor state. It performs no storage operation."
  [state fence-id]
  (let [record (get-in state [:risk-fences fence-id])]
    (cond
      (nil? record) {:status :rejected :reason :unknown-fence}

      (= :consumed (:status record))
      (or (:result record) {:status :rejected :reason :fence-already-consumed})

      :else
      (let [resolved (resolve-current state)]
        (cond
          (not (:resolved? resolved))
          {:status :rejected :reason (:reason resolved)}

          (not= (:head-state-root resolved) (:head-state-root record))
          {:status :rejected :reason :state-not-at-required-head}

          (not= (:configuration/root resolved) (:configuration/head-root record))
          {:status :rejected :reason :state-not-at-required-head}

          (not= (:risk-policy/root resolved) (:risk-policy/root record))
          {:status :rejected :reason :risk-policy-not-current}

          (not= (:state-before (:candidate-op record)) (:business/state state))
          {:status :rejected :reason :candidate-state-not-current}

          :else
          (let [candidate-op (:candidate-op record)
                request (risk-admission-request candidate-op)
                projection (derive-candidate-projection candidate-op)
                eval (le/evaluate-bound projection (:risk-policy resolved)
                                        (:exact-source-root record))
                pass? (and (= (:risk-policy/root resolved)
                              (:risk-limit-evaluation/policy-root eval))
                           (= :pass (:status (le/verify-root eval)))
                           (= :pass (:risk-limit-evaluation/status eval)))]
            (cond
              (not= (:risk-admission-request/root request)
                    (:risk-admission-request/root record))
              {:status :rejected :reason :risk-admission-request-root-mismatch}

              (not pass?)
              {:status :rejected :reason :risk-limit-violation}

              :else
              (let [state-after (candidate-state-after candidate-op)
                    basis (try
                            (admission-basis resolved record candidate-op
                                             (:business/state state) state-after
                                             projection eval)
                            (catch clojure.lang.ExceptionInfo error
                              {:error error}))]
                (if-let [error (:error basis)]
                  {:status :rejected :reason (:reason (ex-data error))}
                  (let [admission {:schema-version risk-admission-schema
                                   :configuration/head-root (:configuration/root resolved)
                                   :head-state-root (:head-state-root resolved)
                                   :risk-policy/root (:risk-policy/root resolved)
                                   :exact-source-root (:exact-source-root record)
                                   :risk-projection/root (:risk-projection/root record)
                                   :evaluation/root (:evaluation/root record)
                                   :risk-admission-fence/root (:risk-admission-fence/root record)
                                   :state-before/root (effects/state-root (:business/state state))
                                   :state-after/root (effects/state-root state-after)
                                   :risk-controlled-pro-rata-admission-basis/root
                                   (:risk-controlled-pro-rata-admission-basis/root basis)
                                   :status :applied}
                        admission-root (admission-root admission)
                        result {:status :committed
                                :risk-admission/root admission-root
                                :risk-admission-request/root
                                (:risk-admission-request/root request)
                                :risk-policy/root (:risk-policy/root resolved)
                                :evaluation eval}
                        next-state (-> state
                                       (assoc :business/state state-after)
                                       (assoc-in [:risk-admissions admission-root]
                                                 (assoc admission :risk-controlled-pro-rata-admission/root admission-root))
                                       (assoc-in [:risk-request-admissions
                                                  (:risk-admission-request/root request)]
                                                 admission-root)
                                       (assoc-in [:risk-fences fence-id]
                                                 (assoc record :status :consumed
                                                        :result result))
                                       (assoc :configuration/commit-index
                                              (inc (:configuration/commit-index state))))]
                    (assoc result :state next-state)))))))))))

(defn finalise-risk-fence!
  "Atom-backed adapter for finalise-fence-transition. CAS-retries the exact pure
   successor and returns the committed semantic result without adapter state."
  [store fence-id]
  (loop []
    (let [current @(.state-atom store)
          result (finalise-fence-transition current fence-id)]
      (if-not (= :committed (:status result))
        result
        (if-let [next-state (:state result)]
          (if (compare-and-set! (.state-atom store) current next-state)
            (dissoc result :state)
            (recur))
          result)))))

(defn admit-and-commit-pro-rata!
  "Single-call convenience: issue a fence under the current authoritative
   H -> C -> P and immediately finalize it under the same head. Equivalent to
   issue then finalize, and therefore rejected (without mutation) whenever the
   candidate does not satisfy the currently applicable risk policy or the head
   is stale."
  [store candidate-op]
  (let [issued (issue-risk-fence! store candidate-op)]
    (if (= :issued (:status issued))
      (finalise-risk-fence! store (:fence/id issued))
      issued)))

(defn committed-admissions
  "The committed risk-controlled admissions keyed by their roots."
  [store]
  (:risk-admissions (state-snapshot store)))

(defn admission-by-root
  "Resolve a committed admission by its content-addressed root."
  [store root]
  (get (committed-admissions store) root))

(defn- durable-state-valid?
  [state]
  (and (map? state)
       (= (:business/state state)
          (:business/state state))
       (every? (fn [[root admission]]
                 (and (= root (:risk-controlled-pro-rata-admission/root admission))
                      (= root (admission-root (dissoc admission
                                                      :risk-controlled-pro-rata-admission/root)))))
               (:risk-admissions state))))

(defn- atomic-write-edn!
  [path value]
  (let [target (io/file path)
        temp (io/file (str path ".tmp-" (System/nanoTime)))]
    (io/make-parents target)
    (spit temp (pr-str value))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))
    value))

(defn persist-snapshot!
  "Persist the complete semantic admission snapshot. Runtime fence IDs remain
   in the snapshot only because consumed-fence results provide retry behavior;
   no runtime controls enter the rooted admission basis."
  [store path]
  (atomic-write-edn! path (state-snapshot store)))

(defn open-durable-store
  "Open a RiskAdmissionStore from a snapshot written by persist-snapshot!.
   Invalid or tampered snapshots fail closed before the store is returned."
  [path]
  (let [state (edn/read-string (slurp (io/file path)))]
    (when-not (durable-state-valid? state)
      (throw (ex-info "invalid durable risk admission snapshot"
                      {:reason :risk-admission/durable-snapshot-invalid})))
    (RiskAdmissionStore. (atom state))))
