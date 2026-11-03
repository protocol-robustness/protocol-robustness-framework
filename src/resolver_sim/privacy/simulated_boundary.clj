(ns resolver-sim.privacy.simulated-boundary
  "Deterministic in-memory privacy operation boundary for qualification fixtures.

   This appends a simulated effect only. It does not integrate with live
   disclosure, token, issuer, storage, or deletion systems."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.privacy.policy :as policy]
            [resolver-sim.privacy.resolution :as resolution]))

(defn new-boundary
  ([head-root] (new-boundary head-root 0))
  ([head-root sequence]
   (when-not (ref/valid-sha256-ref? head-root)
     (throw (ex-info "simulated boundary requires a sha256 head root" {})))
   (when-not (and (integer? sequence) (not (neg? sequence)))
     (throw (ex-info "simulated boundary requires a non-negative head sequence" {})))
   (atom {:schema "stablecoin-privacy-simulated-boundary.v1"
          :head/root head-root
          :head/sequence sequence
          :consumed-request-roots #{}
          :effects []})))

(defn state [boundary] @boundary)

(defn advance-head!
  "Advance the fixture head. Used to test stale-resolution fencing."
  [boundary next-head-root]
  (when-not (ref/valid-sha256-ref? next-head-root)
    (throw (ex-info "simulated head must be a sha256 root" {})))
  (swap! boundary (fn [current]
                    (-> current
                        (assoc :head/root next-head-root)
                        (update :head/sequence inc)))))

(defn- effect-root [effect]
  (ref/sha256-ref (hc/domain-hash "prf.stablecoin-privacy-simulated-effect.v1" effect)))

(defn apply-simulated!
  "Re-resolve and re-evaluate immediately before appending a fixture effect.

   The atom compare-and-set is the in-memory fence: if the head changes while
   evaluating, retry observes the new head and rejects the stale observation.
   Results always state that no real operation was authorized."
  [{:keys [boundary binding policy request head-observation effect
           authority-trust-policy observer-trust-policy evaluation-options
           now max-observation-age-seconds]}]
  (when-not (and (instance? clojure.lang.Atom boundary)
                 (map? effect)
                 (integer? now)
                 (= now (:request/at request)))
    (throw (ex-info "invalid simulated privacy operation input"
                    {:reason :invalid-simulation-input})))
  (loop []
    (let [before @boundary
          resolved (resolution/resolve-policy
                    {:binding binding :policy policy :request request
                     :head-observation head-observation
                     :authority-trust-policy authority-trust-policy
                     :observer-trust-policy observer-trust-policy
                     :now now :max-observation-age-seconds max-observation-age-seconds})
          evaluated (policy/evaluate policy request evaluation-options)
          current-head? (and (= (:head/root before)
                                (:privacy-resolution/head-root resolved))
                             (= (:head/sequence before)
                                (:privacy-resolution/head-sequence resolved)))
          replayed? (contains? (:consumed-request-roots before)
                               (:request/root request))
          permitted? (and (:privacy-resolution/valid? resolved)
                          current-head?
                          (not replayed?)
                          (policy/verify-evaluation evaluated)
                          (= (:request/root request)
                             (:privacy-evaluation/request-root evaluated))
                          (= :allow (:privacy-evaluation/decision evaluated))
                          (= :verified (:privacy-evaluation/classification-status evaluated))
                          (= :verified (:privacy-evaluation/evidence-status evaluated))
                          (false? (:privacy-evaluation/usable? evaluated)))
          reason (cond
                   (not (:privacy-resolution/valid? resolved)) :resolution-invalid
                   (not current-head?) :head-changed
                   replayed? :request-already-applied
                   (not (policy/verify-evaluation evaluated)) :evaluation-receipt-invalid
                   (not= :allow (:privacy-evaluation/decision evaluated)) :privacy-policy-not-allow
                   (not= :verified (:privacy-evaluation/classification-status evaluated))
                   :classification-unverified
                   (not= :verified (:privacy-evaluation/evidence-status evaluated))
                   :prerequisite-evidence-unverified
                   :else nil)]
      (if-not permitted?
        {:status :blocked :reason reason
         :simulation-only? true :real-operation-authorized? false
         :resolution resolved :evaluation evaluated}
        (let [entry {:effect/root (effect-root effect)
                     :request/root (:request/root request)
                     :policy/root (:privacy-policy/root policy)
                     :binding/root (:privacy-resolution/binding-root resolved)
                     :head/root (:head/root before)
                     :head/sequence (:head/sequence before)
                     :effect effect}
              after (-> before
                        (update :effects conj entry)
                        (update :consumed-request-roots conj (:request/root request)))]
          (if (compare-and-set! boundary before after)
            {:status :simulated
             :effect/root (:effect/root entry)
             :head/root (:head/root before)
             :request/root (:request/root request)
             :policy/root (:privacy-policy/root policy)
             :simulation-only? true
             :real-operation-authorized? false}
            (recur)))))))
