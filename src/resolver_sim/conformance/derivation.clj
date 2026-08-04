(ns resolver-sim.conformance.derivation
  "Generic artifact derivation receipts and chains.

   Every boundary in a derivation (export, sync, replay, reconciliation,
   attestation) produces a receipt that links an input root to an output root
   under a named transformation, together with the validation results that ran
   at that boundary.  Receipts are linked into a chain where each receipt's
   output root is the next receipt's input root.

   This lets a verifier distinguish, for example:
     - invalid at source export;
     - valid export but corrupted generated artifact;
     - valid generated fixture but stale sync copy;
     - valid synced fixture unsupported by the replay profile;
     - valid replay input but invariant failure.

   The pattern is reusable for any generated PRF evidence, not only trace
   fixtures."
  (:require [resolver-sim.hash.canonical :as hc]))

(def derivation-schema-version "conformance-derivation.v1")

(defn derivation-receipt
  "Build a derivation-boundary receipt.

   Input map keys:
     :boundary/id          keyword e.g. :export, :sync, :replay
     :input/root           sha256 (or content id) of the boundary input
     :output/root          sha256 (or content id) of the boundary output
     :fixture-contract/id  e.g. :trace-fixture.v2
     :transformation/id    e.g. :cdrs-export, :byte-preserving-copy
     :validation-results   seq of {:validation/status :pass|:rejected ...}

   The receipt status is :pass only when every validation result is :pass."
  [m]
  (let [results (vec (or (:validation-results m) []))]
    {:boundary/id (:boundary/id m)
     :input/root (or (:input/root m) "")
     :output/root (or (:output/root m) "")
     :fixture-contract/id (:fixture-contract/id m)
     :transformation/id (or (:transformation/id m) :unknown)
     :validation-results results
     :status (if (every? #(= :pass (:validation/status %)) results) :pass :fail)}))

(defn- chain-hash
  "Deterministic content hash of a chain, over the stable receipt projection."
  [receipts]
  (hc/domain-hash "conformance.derivation-chain.v1"
                  (mapv #(select-keys %
                                     [:boundary/id :input/root :output/root
                                      :transformation/id :status])
                        receipts)))

(defn derivation-chain
  "Link boundary receipts in derivation order.

   Enforces that each receipt's :output/root equals the next receipt's
   :input/root.  Returns {:chain [...] :root <sha256> :links-ok? bool
                          :violations [...] :status :pass|:fail}."
  [receipts]
  (let [receipts (vec receipts)
        pairs (map vector receipts (rest receipts))
        bad-index (first (keep-indexed (fn [i [a b]]
                                         (when (not= (:output/root a) (:input/root b)) i))
                                       pairs))
        links-ok? (nil? bad-index)
        statuses (map :status receipts)
        all-pass? (every? #(= :pass %) statuses)
        v (cond-> []
            (not links-ok?)
            (conj {:violation/id :violation/chain-link-mismatch
                   :details {:reason "consecutive receipts do not link output/root -> input/root"
                             :index bad-index}})
            (not all-pass?)
            (conj {:violation/id :violation/chain-boundary-failed
                   :details {:reason "one or more derivation boundaries did not pass"
                             :statuses statuses}}))]
    {:chain receipts
     :root (chain-hash receipts)
     :links-ok? links-ok?
     :violations v
     :status (if (and links-ok? all-pass?) :pass :fail)}))
