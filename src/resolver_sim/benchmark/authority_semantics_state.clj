(ns resolver-sim.benchmark.authority-semantics-state
  "C4d/C4e store-owned configuration semantics admission and resolution."
  (:require [resolver-sim.benchmark.allocation-entitlement-policy :as entitlement-policy]
            [resolver-sim.benchmark.authority-semantics-policy :as policy]

            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.genesis :as genesis]))

(defn- verify-authority-semantics-admission!
  "Verify the shared E/H → C → authority-semantics P/S relation used by
  configuration V2 and V3. Version-specific callers add their own commitments."
  [envelope head-state configuration semantics-policy descriptor]
  (let [configuration-root (genesis/chain-configuration-root configuration)
        policy-selection (policy/verify-policy-selection semantics-policy descriptor)]
    (when-not (and (= state/envelope-v2-schema (:artifact/schema envelope))
                   (= configuration-root (:chain-configuration/root envelope))
                   (= configuration-root (:configuration/head-root head-state))
                   (= (:authority-semantics-policy/root configuration)
                      (:authority-semantics-policy/root semantics-policy))
                   (:valid? policy-selection))
      (throw (ex-info "authority semantics admission is invalid" {})))
    {:configuration-root configuration-root
     :policy-root (:authority-semantics-policy/root semantics-policy)
     :semantics-root (:governed-authority-semantics/root descriptor)}))

(defn new-store-v2-with-authority-semantics
  "Admit a V2 store only after deriving C/P/S roots from actual bodies and
   proving E/H → C → P → S. Bodies are retained before the new store escapes."
  [envelope head-state material configuration semantics-policy descriptor]
  (let [{:keys [configuration-root policy-root semantics-root]}
        (verify-authority-semantics-admission! envelope head-state configuration semantics-policy descriptor)
        store (state/new-store-v2 envelope head-state material)]
    (swap! (.state store)
           assoc :chain-configurations {configuration-root configuration}
           :authority-semantics-policies {policy-root semantics-policy}
           :governed-authority-semantics {semantics-root descriptor})
    store))

(defn publish-successor-v2-with-authority-semantics!
  "Atomically publish a C4 successor E/H/material/lineage and retain its actual
   C/P/S bodies. C/P/S are admitted against the exact successor E/H before the
   CAS, and no store state changes when the required predecessor is stale."
  [store expected-head envelope head-state material configuration semantics-policy
   descriptor lineage]
  (let [configuration-schema (:configuration/schema configuration)
        v1-configuration? (= genesis/chain-configuration-schema configuration-schema)
        v2-configuration? (= genesis/chain-configuration-v2-schema configuration-schema)
        supplied-semantics? (or semantics-policy descriptor)
        c4-publication? v2-configuration?
        {:keys [configuration-root policy-root semantics-root]}
        (when c4-publication?
          (verify-authority-semantics-admission! envelope head-state configuration semantics-policy descriptor))
        envelope (state/build-envelope-v2 envelope head-state)
        envelope-root (:authoritative-state-envelope/root envelope)
        state-root (:execution/state-root envelope)
        head-root (:configuration-head/root envelope)]
    (when-not (or v1-configuration? v2-configuration?)
      (throw (ex-info "V2 publisher accepts only V1 or V2 configurations" {})))
    (when (and v2-configuration? (not (and semantics-policy descriptor)))
      (throw (ex-info "V2 configuration requires successor authority semantics" {})))
    (when (and v1-configuration? supplied-semantics?)
      (throw (ex-info "V1 configuration cannot select authority semantics" {})))
    (state/new-store-v2 envelope head-state material)
    (when-not (and (state/verify-envelope-v2 envelope head-state)
                   (= (:chain-instance-genesis/root envelope)
                      (:chain-instance-genesis/root material))
                   (= (:chain-configuration/root envelope) (:chain-configuration/root material))
                   (or (not c4-publication?)
                       (= configuration-root (:chain-configuration/root material))))
      (throw (ex-info "V2 C4 successor envelope/material join is invalid" {})))
    (loop []
      (let [current @(.state store)]
        (cond
          (not= expected-head (:head current))
          {:published? false :reason :state-not-at-required-head}

          (not= expected-head (:publication/predecessor-root envelope))
          {:published? false :reason :authority-state-membership-unproven}

          :else
          (let [base (-> current
                         (assoc :head envelope-root)
                         (assoc-in [:envelopes envelope-root] envelope)
                         (assoc-in [:by-state state-root] envelope-root)
                         (assoc-in [:material state-root] material)
                         (assoc-in [:configuration-head-states head-root] head-state)
                         (assoc-in [:activation-lineage envelope-root] lineage))
                next (cond-> base
                       c4-publication?
                       (assoc-in [:chain-configurations configuration-root] configuration)

                       c4-publication?
                       (assoc-in [:authority-semantics-policies policy-root] semantics-policy)

                       c4-publication?
                       (assoc-in [:governed-authority-semantics semantics-root] descriptor))]
            (if (compare-and-set! (.state store) current next)
              {:published? true :envelope envelope}
              (recur))))))))

(defn- verify-admission-v3!
  [envelope head-state configuration semantics-policy descriptor entitlement]
  (let [{:keys [configuration-root policy-root semantics-root]}
        (verify-authority-semantics-admission! envelope head-state configuration semantics-policy descriptor)
        entitlement-validation (entitlement-policy/validate-policy entitlement)
        entitlement-root (:allocation-entitlement-policy/root entitlement)]
    (when-not (and (= genesis/chain-configuration-v3-schema
                      (:configuration/schema configuration))
                   (= entitlement-root
                      (:allocation-entitlement-policy/root configuration))
                   (:valid? entitlement-validation))
      (throw (ex-info "v3 allocation entitlement admission is invalid" {})))
    {:configuration-root configuration-root
     :policy-root policy-root
     :semantics-root semantics-root
     :entitlement-policy-root entitlement-root}))

(defn new-store-v3-with-authority-semantics-and-allocation-entitlement
  "Admit a V3 store after proving retained E/H → C → P → S and C → entitlement
   policy joins. Resolution later uses these retained bodies, never caller input."
  [envelope head-state material configuration semantics-policy descriptor entitlement]
  (let [{:keys [configuration-root policy-root semantics-root entitlement-policy-root]}
        (verify-admission-v3! envelope head-state configuration semantics-policy descriptor entitlement)
        store (state/new-store-v2 envelope head-state material)]
    (swap! (.state store)
           assoc :chain-configurations {configuration-root configuration}
           :authority-semantics-policies {policy-root semantics-policy}
           :governed-authority-semantics {semantics-root descriptor}
           :allocation-entitlement-policies {entitlement-policy-root entitlement})
    store))

(defn publish-successor-v3-with-authority-semantics-and-allocation-entitlement!
  "Atomically publish a V3 successor and retain verified C/P/S and entitlement
   policy bodies. This is separate from the V2 publication path."
  [store expected-head envelope head-state material configuration semantics-policy
   descriptor entitlement lineage]
  (let [{:keys [configuration-root policy-root semantics-root entitlement-policy-root]}
        (verify-admission-v3! envelope head-state configuration semantics-policy descriptor entitlement)
        envelope (state/build-envelope-v2 envelope head-state)
        envelope-root (:authoritative-state-envelope/root envelope)
        state-root (:execution/state-root envelope)
        head-root (:configuration-head/root envelope)]
    (when-not (and (state/verify-envelope-v2 envelope head-state)
                   (= (:chain-instance-genesis/root envelope)
                      (:chain-instance-genesis/root material))
                   (= configuration-root (:chain-configuration/root material)))
      (throw (ex-info "v3 successor envelope/material join is invalid" {})))
    (loop []
      (let [current @(.state store)]
        (cond
          (not= expected-head (:head current))
          {:published? false :reason :state-not-at-required-head}

          (not= expected-head (:publication/predecessor-root envelope))
          {:published? false :reason :authority-state-membership-unproven}

          :else
          (let [next (-> current
                         (assoc :head envelope-root)
                         (assoc-in [:envelopes envelope-root] envelope)
                         (assoc-in [:by-state state-root] envelope-root)
                         (assoc-in [:material state-root] material)
                         (assoc-in [:configuration-head-states head-root] head-state)
                         (assoc-in [:activation-lineage envelope-root] lineage)
                         (assoc-in [:chain-configurations configuration-root] configuration)
                         (assoc-in [:authority-semantics-policies policy-root] semantics-policy)
                         (assoc-in [:governed-authority-semantics semantics-root] descriptor)
                         (assoc-in [:allocation-entitlement-policies entitlement-policy-root] entitlement))]
            (if (compare-and-set! (.state store) current next)
              {:published? true :envelope envelope}
              (recur))))))))

(declare resolve-current-authority-semantics)

(defn evaluate-and-issue-current-authority-fence!
  "C4f authoritative issuer. Resolves S from current retained C/P/S and passes
   it into the store-owned issuer so report and fence bind S in one CAS."
  [store basis authorisation]
  (let [resolved (resolve-current-authority-semantics store)]
    (if-not (:resolved? resolved)
      {:valid? false :reason (:reason resolved)}
      (state/evaluate-and-issue-finalizable-authority-fence!
       store basis authorisation (:semantics resolved)
       {:chain-configuration/root (:configuration/root resolved)
        :authority-semantics-policy/root (:policy/root resolved)}))))

(defn resolve-current-authority-semantics
  "Resolve C → P → S only from bodies retained with the current V2 authority
   state. No configuration, policy, or semantics body is caller-supplied."
  [store]
  (try
    (let [snapshot @(.state store)
          envelope-root (:head snapshot)
          envelope (get-in snapshot [:envelopes envelope-root])
          head-state (get-in snapshot [:configuration-head-states (:configuration-head/root envelope)])
          configuration-root (:chain-configuration/root envelope)
          configuration (get-in snapshot [:chain-configurations configuration-root])
          policy-root (:authority-semantics-policy/root configuration)
          semantics-policy (get-in snapshot [:authority-semantics-policies policy-root])
          semantics-root (:authority-semantics/root semantics-policy)
          descriptor (get-in snapshot [:governed-authority-semantics semantics-root])
          admission (verify-authority-semantics-admission! envelope head-state configuration semantics-policy descriptor)]
      {:resolved? true
       :authoritative-state/root envelope-root
       :configuration configuration
       :configuration/root configuration-root
       :policy semantics-policy
       :policy/root (:policy-root admission)
       :semantics descriptor
       :semantics/root (:semantics-root admission)})
    (catch Exception _
      {:resolved? false :reason :authority-semantics-unavailable})))

(defn resolve-current-authority-semantics-and-allocation-entitlement
  "Resolve V3 C → authority-semantics P/S plus the entitlement-policy body only
  from retained bodies associated with the current authoritative envelope."
  [store]
  (try
    (let [snapshot @(.state store)
          envelope-root (:head snapshot)
          envelope (get-in snapshot [:envelopes envelope-root])
          head-state (get-in snapshot [:configuration-head-states (:configuration-head/root envelope)])
          configuration-root (:chain-configuration/root envelope)
          configuration (get-in snapshot [:chain-configurations configuration-root])
          authority-policy-root (:authority-semantics-policy/root configuration)
          semantics-policy (get-in snapshot [:authority-semantics-policies authority-policy-root])
          semantics-root (:authority-semantics/root semantics-policy)
          descriptor (get-in snapshot [:governed-authority-semantics semantics-root])
          entitlement-root (:allocation-entitlement-policy/root configuration)
          entitlement (get-in snapshot [:allocation-entitlement-policies entitlement-root])
          admission (verify-admission-v3! envelope head-state configuration semantics-policy descriptor entitlement)]
      {:resolved? true
       :authoritative-state/root envelope-root
       :configuration configuration
       :configuration/root (:configuration-root admission)
       :policy semantics-policy
       :policy/root (:policy-root admission)
       :semantics descriptor
       :semantics/root (:semantics-root admission)
       :allocation-entitlement-policy entitlement
       :allocation-entitlement-policy/root (:entitlement-policy-root admission)})
    (catch Exception _
      {:resolved? false :reason :allocation-entitlement-unavailable})))
