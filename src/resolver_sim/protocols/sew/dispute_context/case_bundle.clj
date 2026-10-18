(ns resolver-sim.protocols.sew.dispute-context.case-bundle
  "Immutable, reconstructible case bundle and non-authoritative case view."
  (:require [resolver-sim.contract-model.replay.frames :as frames]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.io.content-addressed-store :as cas]))

(def schema :prf/sew-dispute-case-bundle.v1)
(defn- sha256-ref [value]
  (ref/sha256-ref (canonical/domain-hash :sew-dispute-case-bundle-v1 value)))

(defn research-history-root [history]
  (ref/sha256-ref (canonical/domain-hash :sew-dispute-case-research-history-v1 history)))

(defn current-pointer-root [pointer]
  (ref/sha256-ref (canonical/domain-hash :sew-dispute-case-current-pointer-v1 pointer)))

(defn bundle-root [bundle]
  (sha256-ref (dissoc bundle :bundle/root)))

(defn verify-bundle
  "Check canonical identity, cross-history bindings, frame lineage when
   retained, and optional incentive links. Missing live/external evidence is
   reported as unverified; it is never promoted from the case alias."
  [bundle]
  (let [identity (:case/identity bundle)
        protocol-history (:case/protocol-history bundle)
        research-history (:case/research-history bundle)
        protocol-roots (set (map :basis/root protocol-history))
        research-targets (set (map :basis/root research-history))
        protocol-rounds (set (map :round/id protocol-history))
        bad-predecessor? (some (fn [[index round]]
                                 (let [expected (when (pos? index)
                                                  (:round/root (nth protocol-history (dec index))))]
                                   (or (not (ref/valid-sha256-ref? (:round/root round)))
                                       (not= expected (:predecessor/root round)))))
                               (map-indexed vector protocol-history))
        bad-research-round? (some #(not (contains? protocol-rounds (:round/id %)))
                                  research-history)
        pointer (:case/current-pointer bundle)
        latest-round (last protocol-history)
        bad-pointer? (and pointer
                          (or (not= (:round/id latest-round) (:round/id pointer))
                              (not= (:round/root latest-round) (:round/root pointer))
                              (not= (:basis/root latest-round) (:basis/root pointer))
                              (not= (:case/current-pointer-root bundle)
                                    (current-pointer-root pointer))))
        frames (:case/frames bundle)
        frame-check (when frames (frames/validate-frame-lineage frames))
        errors (cond-> []
                 (not= schema (:bundle/schema bundle)) (conj :invalid-schema)
                 (not (and (string? (:case/alias bundle))
                           (re-matches #"[a-zA-Z0-9][a-zA-Z0-9._-]*" (:case/alias bundle))))
                 (conj :invalid-navigational-alias)
                 (not (and (map? identity) (:case/id identity)
                           (:workflow/id identity)))
                 (conj :missing-case-identity)
                 (not= (:case/alias bundle) (:case/id identity))
                 (conj :case-alias-projection-mismatch)
                 (not= (:case/identity-root bundle) (sha256-ref identity))
                 (conj :case-identity-root-mismatch)
                 (not (seq protocol-history)) (conj :missing-protocol-history)
                 (not (seq research-history)) (conj :missing-research-history)
                 (some #(or (not= (:workflow/id identity) (:workflow/id %))
                            (not (ref/valid-sha256-ref? (:basis/root %))))
                       protocol-history)
                 (conj :invalid-protocol-history-binding)
                 (or bad-predecessor? (not= (count protocol-rounds) (count protocol-history)))
                 (conj :invalid-round-predecessor-chain)
                 (some #(or (not= (:workflow/id identity) (:workflow/id %))
                            (not (ref/valid-sha256-ref? (:basis/root %))))
                       research-history)
                 (conj :invalid-research-history-binding)
                 bad-research-round? (conj :research-round-not-in-protocol-history)
                 (not (every? protocol-roots research-targets))
                 (conj :research-target-not-in-protocol-history)
                 (not= (:case/research-history-anchor bundle)
                       (research-history-root research-history))
                 (conj :research-history-anchor-mismatch)
                 bad-pointer? (conj :invalid-current-pointer)
                 (and frames (not (:valid? frame-check)))
                 (conj :invalid-frame-lineage)
                 (not= (:bundle/root bundle) (bundle-root bundle))
                 (conj :bundle-root-mismatch))
        incentive-links (:case/incentive-links bundle)
        invalid-incentive-links (when incentive-links
                                  (seq (for [[_ root] incentive-links
                                             :when (not (ref/valid-sha256-ref? root))]
                                         root)))
        errors (cond-> errors invalid-incentive-links
                 (conj :invalid-incentive-link))]
    {:valid? (empty? errors)
     :errors errors
     :verification/status (if (empty? errors) :verified :invalid)
     :verification/frames (cond
                             (nil? frames) :unverified
                             (:valid? frame-check) :verified
                             :else :invalid)
     :verification/live-execution :unverified
     :verification/authority :unverified
     :verification/incentives (if (seq incentive-links) :linked-unverified :absent)}))

(defn build-bundle [bundle]
  (let [bundle (assoc bundle :bundle/schema schema)
        bundle (assoc bundle :case/identity-root (sha256-ref (:case/identity bundle)))
        bundle (assoc bundle :bundle/root (bundle-root bundle))
        check (verify-bundle bundle)]
    (when-not (:valid? check)
      (throw (ex-info "SEW case bundle failed verification" {:errors (:errors check)})))
    bundle))

(defn persist-bundle! [store bundle]
  (let [check (verify-bundle bundle)]
    (when-not (:valid? check)
      (throw (ex-info "Refusing to persist invalid SEW case bundle"
                      {:errors (:errors check)})))
    (cas/put-if-absent! store
                        {:hash-reference (:bundle/root bundle)
                         :artifact bundle
                         :verify #(-> (verify-bundle %) :valid?)})))

(defn resolve-bundle [store root]
  (let [artifact (cas/resolve-artifact store root)
        check (when artifact (verify-bundle artifact))]
    {:present? (some? artifact)
     :valid? (boolean (:valid? check))
     :verification (or check {:verification/status :unresolved})
     :bundle (when (:valid? check) artifact)}))

(defn case-view
  "Human-readable projection. The alias is a label; roots remain the identity."
  [bundle]
  (let [check (verify-bundle bundle)]
    (when-not (:valid? check)
      (throw (ex-info "Cannot project an invalid case bundle" {:errors (:errors check)})))
    {:case/alias (:case/alias bundle)
     :case/identity (:case/identity bundle)
     :case/identity-root (:case/identity-root bundle)
     :case/research-history-root (research-history-root (:case/research-history bundle))
     :case/current-pointer (:case/current-pointer bundle)
     :bundle/root (:bundle/root bundle)
     :protocol/history (:case/protocol-history bundle)
     :research/history (:case/research-history bundle)
     :verification (dissoc check :valid? :errors)}))
