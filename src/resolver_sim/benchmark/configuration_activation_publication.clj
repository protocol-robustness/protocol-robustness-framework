(ns resolver-sim.benchmark.configuration-activation-publication
  "C3b single-owner V2 configuration activation publication."
  (:require [resolver-sim.benchmark.configuration-transition-authorization :as c3a]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.authority-semantics-state :as semantics-state]
            [resolver-sim.configuration-head :as head]
            [resolver-sim.genesis :as genesis]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(defn- lineage-root [lineage]
  (ref/sha256-ref
   (hc/domain-hash :configuration-activation-lineage-v1
                   (hc/project-canonical-safe
                    (dissoc lineage :configuration-activation-lineage/root)))))

(defn verify-activation-lineage
  "Independently recompute C3b lineage conservation from rooted E0/H0, verified
   C3a evidence, T, canonical H1, and E1."
  [{:keys [lineage predecessor-envelope predecessor-head-state authorization-evidence
           authorization-witness transition parent-configuration successor-configuration
           successor-envelope successor-head-state]}]
  (try
    (let [verified (c3a/verify-evidence (assoc authorization-witness
                                               :predecessor-envelope predecessor-envelope
                                               :predecessor-head-state predecessor-head-state
                                               :evidence authorization-evidence))
          derived (head/derive-successor-head predecessor-head-state transition parent-configuration successor-configuration)
          e1 (state/build-envelope-v2 successor-envelope successor-head-state)]
      {:valid? (and (:valid? verified)
                    (= (:configuration-transition/root authorization-evidence)
                       (genesis/chain-configuration-transition-root transition))
                    (= :committed (:status derived))
                    (= (:configuration/head derived) successor-head-state)
                    (state/verify-envelope-v2 predecessor-envelope predecessor-head-state)
                    (state/verify-envelope-v2 e1 successor-head-state)
                    (= (:predecessor-authoritative-state/root lineage)
                       (:authoritative-state-envelope/root predecessor-envelope))
                    (= (:predecessor-configuration-head/root lineage)
                       (:configuration-head-state/root predecessor-head-state))
                    (= (:configuration-transition-authorization-evidence/root lineage)
                       (:configuration-transition-authorization-evidence/root authorization-evidence))
                    (= (:configuration-transition/root lineage)
                       (genesis/chain-configuration-transition-root transition))
                    (= (:successor-configuration-head/root lineage)
                       (:configuration-head-state/root successor-head-state))
                    (= (:successor-authoritative-state/root lineage)
                       (:authoritative-state-envelope/root e1))
                    (= (:configuration-activation-lineage/root lineage) (lineage-root lineage)))})
    (catch Exception _ {:valid? false})))

(defn activate-under-verified-transition-authorization!
  "Verify C3a against the exact current V2 AuthorityStateStore predecessor and
   atomically publish E1/H1/material/lineage. C3b publication is
   predecessor-current and single-use: both exact and conflicting replays reject
   after E0/H0 advances. ConfigurationHeadStore is not consulted or mutated by
   this authoritative path."
  [authority-store {:keys [authorization-evidence authorization-witness transition
                           parent-configuration successor-configuration
                           successor-envelope successor-material
                           successor-semantics-policy successor-semantics]}]
  (let [current @(.state authority-store)
        e0-root (:head current)
        e0 (get-in current [:envelopes e0-root])
        h0-root (:configuration-head/root e0)
        h0 (get-in current [:configuration-head-states h0-root])
        version (:publication/sequence e0)
        witness (assoc authorization-witness
                       :predecessor-envelope e0
                       :predecessor-head-state h0)
        verified (c3a/verify-evidence (assoc witness :evidence authorization-evidence))
        transition-root (try (genesis/chain-configuration-transition-root transition)
                             (catch Exception _ nil))
        c4-request? (or successor-semantics-policy successor-semantics)
        derived (when (and (:valid? verified)
                           (= transition-root (:configuration-transition/root authorization-evidence)))
                  (head/derive-successor-head h0 transition parent-configuration successor-configuration))]
    (cond
      (not= state/envelope-v2-schema (:artifact/schema e0))
      {:activated? false :reason :authoritative-v2-predecessor-required}
      (not (state/verify-envelope-v2 e0 h0))
      {:activated? false :reason :authoritative-head-state-invalid}
      (not (:valid? verified))
      {:activated? false :reason :configuration-transition-authorization-invalid}
      (not= transition-root (:configuration-transition/root authorization-evidence))
      {:activated? false :reason :configuration-transition-authorization-mismatch}
      (and c4-request? (not (and successor-semantics-policy successor-semantics)))
      {:activated? false :reason :successor-authority-semantics-incomplete}
      (not= :committed (:status derived))
      {:activated? false :reason (:reason derived)}
      :else
      (let [h1 (:configuration/head derived)
            e1 (state/build-envelope-v2
                (assoc successor-envelope
                       :publication/predecessor-root e0-root
                       :publication/sequence (inc version)
                       :chain-configuration/root (:configuration/head-root h1))
                h1)
            _ (state/new-store-v2 e1 h1 successor-material)
            e1-root (:authoritative-state-envelope/root e1)
            lineage-base {:predecessor-authoritative-state/root e0-root
                          :predecessor-configuration-head/root h0-root
                          :configuration-transition-authorization-evidence/root
                          (:configuration-transition-authorization-evidence/root authorization-evidence)
                          :configuration-transition/root transition-root
                          :successor-configuration-head/root (:configuration-head-state/root h1)
                          :successor-authoritative-state/root e1-root}
            lineage (assoc lineage-base :configuration-activation-lineage/root (lineage-root lineage-base))
            published (try
                        (semantics-state/publish-successor-v2-with-authority-semantics!
                         authority-store e0-root e1 h1 successor-material
                         (when c4-request? successor-configuration)
                         successor-semantics-policy successor-semantics lineage)
                        (catch Exception _
                          {:published? false :reason :successor-authority-semantics-invalid}))]
        (if (:published? published)
          {:activated? true :envelope e1 :head-state h1 :lineage lineage}
          (if (= :state-not-at-required-head (:reason published))
            {:activated? false :reason :state-not-at-required-head}
            {:activated? false :reason (:reason published)}))))))
