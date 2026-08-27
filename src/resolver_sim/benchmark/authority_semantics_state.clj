(ns resolver-sim.benchmark.authority-semantics-state
  "C4d/C4e store-owned configuration semantics admission and resolution."
  (:require [resolver-sim.benchmark.authority-semantics-policy :as policy]

            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.genesis :as genesis]))

(defn- verify-admission! [envelope head-state configuration semantics-policy descriptor]
  (let [configuration-root (genesis/chain-configuration-root configuration)
        policy-selection (policy/verify-policy-selection semantics-policy descriptor)]
    (when-not (and (= state/envelope-v2-schema (:artifact/schema envelope))
                   (= configuration-root (:chain-configuration/root envelope))
                   (= configuration-root (:configuration/head-root head-state))
                   (= (:authority-semantics-policy/root configuration)
                      (:authority-semantics-policy/root semantics-policy))
                   (:valid? policy-selection))
      (throw (ex-info "v2 authority semantics admission is invalid" {})))
    {:configuration-root configuration-root
     :policy-root (:authority-semantics-policy/root semantics-policy)
     :semantics-root (:governed-authority-semantics/root descriptor)}))

(defn new-store-v2-with-authority-semantics
  "Admit a V2 store only after deriving C/P/S roots from actual bodies and
   proving E/H → C → P → S. Bodies are retained before the new store escapes."
  [envelope head-state material configuration semantics-policy descriptor]
  (let [{:keys [configuration-root policy-root semantics-root]}
        (verify-admission! envelope head-state configuration semantics-policy descriptor)
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
  (let [c4-publication? (or semantics-policy descriptor)
        {:keys [configuration-root policy-root semantics-root]}
        (when c4-publication?
          (verify-admission! envelope head-state configuration semantics-policy descriptor))
        envelope (state/build-envelope-v2 envelope head-state)
        envelope-root (:authoritative-state-envelope/root envelope)
        state-root (:execution/state-root envelope)
        head-root (:configuration-head/root envelope)]
    (when (and c4-publication?
               (not (and semantics-policy descriptor)))
      (throw (ex-info "successor authority semantics are incomplete" {})))
    (state/new-store-v2 envelope head-state material)
    (when-not (and (state/verify-envelope-v2 envelope head-state)
                   (= (:chain-instance-genesis/root envelope)
                      (:chain-instance-genesis/root material))
                   (= (:chain-configuration/root envelope) (:chain-configuration/root material))
                   (or (not c4-publication?)
                       (= configuration-root (:chain-configuration/root material))))
      (throw (ex-info "v2 C4 successor envelope/material join is invalid" {})))
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
          admission (verify-admission! envelope head-state configuration semantics-policy descriptor)]
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
