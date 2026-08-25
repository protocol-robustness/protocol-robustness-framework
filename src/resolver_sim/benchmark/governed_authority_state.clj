(ns resolver-sim.benchmark.governed-authority-state
  "Stage B authenticated state view for governed-authority resolution.

  The store is an operational dependency and is deliberately not committed into
  resolution artifacts. Its atom is the publication boundary for an authority
  envelope and the state-addressed material it authenticates."
  (:require [clojure.set :as set]
            [resolver-sim.benchmark.governed-authority-resolution :as resolution]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const envelope-schema "authoritative-state-envelope.v1")
(def ^:const envelope-domain :authoritative-state-envelope-v1)

(def ^:private envelope-fields
  #{:artifact/schema :chain-instance-genesis/root :execution/state-root
    :chain-configuration/root :review-governance/root
    :review-governance-activation/root :configuration-head/root
    :control-plane-evidence/root :publication/sequence
    :publication/predecessor-root})

(defn- root [value]
  (ref/sha256-ref
   (hc/domain-hash envelope-domain
                   (hc/project-canonical-safe
                    (dissoc value :authoritative-state-envelope/root)))))

(defn validate-envelope [envelope]
  (let [have (if (map? envelope) (set (keys envelope)) #{})
        errors (cond-> []
                 (not (map? envelope)) (conj "envelope must be a map")
                 (and (map? envelope) (not= envelope-schema (:artifact/schema envelope)))
                 (conj "artifact/schema is invalid")
                 (and (map? envelope)
                      (seq (set/difference have envelope-fields
                                           #{:authoritative-state-envelope/root})))
                 (conj "envelope has unknown keys")
                 (and (map? envelope) (seq (set/difference envelope-fields have)))
                 (conj "envelope has missing keys")
                 (and (map? envelope)
                      (not (and (integer? (:publication/sequence envelope))
                                (not (neg? (:publication/sequence envelope))))))
                 (conj "publication/sequence must be non-negative")
                 (and (map? envelope)
                      (not (or (nil? (:publication/predecessor-root envelope))
                               (ref/valid-sha256-ref? (:publication/predecessor-root envelope)))))
                 (conj "publication/predecessor-root is invalid"))]
    {:valid? (empty? errors) :errors errors}))

(defn envelope-root [envelope]
  (let [result (validate-envelope envelope)]
    (when-not (:valid? result)
      (throw (ex-info "authoritative-state-envelope.v1 is invalid" result)))
    (root envelope)))

(defn build-envelope [envelope]
  (let [base (assoc envelope :artifact/schema envelope-schema)]
    (doseq [field (disj envelope-fields :artifact/schema :publication/sequence
                        :publication/predecessor-root)]
      (when-not (ref/valid-sha256-ref? (get base field))
        (throw (ex-info "authority envelope root field is invalid" {:field field}))))
    (assoc base :authoritative-state-envelope/root (root base))))

(deftype AuthorityStateStore [state])

(defn new-store
  "Construct an authenticated authority-state store from one published envelope
   and its active material. `material` contains the root-bearing fields required
   by resolved-review-authority-context.v1 plus :review-round/hash."
  [envelope material]
  (let [envelope (build-envelope envelope)
        state-root (:execution/state-root envelope)]
    (when-not (= (:chain-instance-genesis/root envelope)
                 (:chain-instance-genesis/root material))
      (throw (ex-info "material chain does not match envelope" {})))
    (AuthorityStateStore. (atom {:head (:authoritative-state-envelope/root envelope)
                                 :envelopes {(:authoritative-state-envelope/root envelope) envelope}
                                 :by-state {state-root (:authoritative-state-envelope/root envelope)}
                                 :material {state-root material}}))))

(defn publish-successor!
  "Atomically publish a successor envelope and its active material. The supplied
   predecessor must be the exact current publication head."
  [store expected-head envelope material]
  (let [envelope (build-envelope envelope)
        root (:authoritative-state-envelope/root envelope)]
    (loop []
      (let [current @(.state store)]
        (cond
          (not= expected-head (:head current)) {:published? false :reason :state-not-at-required-head}
          (not= expected-head (:publication/predecessor-root envelope)) {:published? false :reason :authority-state-membership-unproven}
          :else (let [next (-> current
                               (assoc :head root)
                               (assoc-in [:envelopes root] envelope)
                               (assoc-in [:by-state (:execution/state-root envelope)] root)
                               (assoc-in [:material (:execution/state-root envelope)] material))]
                  (if (compare-and-set! (.state store) current next)
                    {:published? true :envelope envelope}
                    (recur))))))))

(defn- ancestor? [envelopes anchor candidate]
  (loop [root anchor seen #{}]
    (cond (nil? root) false
          (= root candidate) true
          (contains? seen root) false
          :else (recur (:publication/predecessor-root (get envelopes root))
                       (conj seen root)))))

(defn resolve-governed-authority-context
  "Resolve a Stage A basis through an authenticated envelope store. Current
   admission requires the requested state to remain the current head; replay and
   audit require it to be an ancestor of the selected anchor."
  [store basis]
  (let [basis-result (resolution/validate-resolution-basis-any basis)
        snapshot @(.state store)
        state-root (:resolution/state-before-root basis)
        envelope-root (get-in snapshot [:by-state state-root])
        envelope (get-in snapshot [:envelopes envelope-root])
        material (get-in snapshot [:material state-root])
        current? (= envelope-root (:head snapshot))
        anchored? (ancestor? (:envelopes snapshot) (:resolution/anchor-root basis) envelope-root)
        purpose (:resolution/purpose basis)]
    (cond
      (not (:valid? basis-result)) {:resolved? false :reason :resolution-basis-invalid}
      (nil? envelope) {:resolved? false :reason :state-unavailable}
      (not= (:chain-instance-genesis/root basis) (:chain-instance-genesis/root envelope)) {:resolved? false :reason :state-wrong-chain}
      (and (= purpose :current-admission) (not current?)) {:resolved? false :reason :state-not-at-required-head}
      (and (not= purpose :current-admission) (not anchored?)) {:resolved? false :reason :state-not-authoritative}
      (not= (:review-round/hash basis) (:review-round/hash material)) {:resolved? false :reason :round-not-found-at-state}
      (not (every? true? [(= (:chain-configuration/root envelope) (:chain-configuration/root material))
                          (= (:review-governance/root envelope) (:review-governance/root material))
                          (= (:review-governance-activation/root envelope) (:review-governance-activation/root material))
                          (= (:control-plane-evidence/root envelope) (:control-plane-evidence/root material))]))
      {:resolved? false :reason :authority-state-membership-unproven}
      :else {:resolved? true
             :context (resolution/build-resolved-context
                       (assoc material
                              :resolution-basis/root (:resolution-basis/root basis)
                              :chain-instance-genesis/root (:chain-instance-genesis/root envelope)
                              :resolution/state-before-root state-root
                              :authority-state/root envelope-root))})))
