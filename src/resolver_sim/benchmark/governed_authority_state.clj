(ns resolver-sim.benchmark.governed-authority-state
  "Stage B authenticated state view for governed-authority resolution.

  The store is an operational dependency and is deliberately not committed into
  resolution artifacts. Its atom is the publication boundary for an authority
  envelope and the state-addressed material it authenticates."
  (:require [clojure.set :as set]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-governance-evidence :as evidence]
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

(def ^:const signer-key-set-schema "governed-authority-signer-key-set.v1")

(defn- freeze-data [value]
  (cond
    (or (nil? value) (boolean? value) (string? value) (keyword? value)
        (integer? value) (ratio? value)) value
    (map? value) (into {} (map (fn [[k v]] [(freeze-data k) (freeze-data v)]) value))
    (vector? value) (mapv freeze-data value)
    (set? value) (into #{} (map freeze-data value))
    :else (throw (ex-info "authority material contains runtime or mutable value"
                          {:class (some-> value class .getName)}))))

(defn- freeze-material [material]
  (freeze-data material))

(defn signer-key-set-root [key-set]
  (let [base (select-keys key-set [:artifact/schema :signer-key-set/keys])]
    (when-not (and (= signer-key-set-schema (:artifact/schema base))
                   (vector? (:signer-key-set/keys base)))
      (throw (ex-info "governed signer-key-set is invalid" {})))
    (ref/sha256-ref
     (hc/domain-hash :governed-authority-signer-key-set-v1
                     (hc/project-canonical-safe base)))))

(def ^:const review-round-material-domain :governed-authority-review-round-v1)

(defn review-round-material-root
  "Canonical store-level root of an authenticated review-round body. Commits
   the round identity projection as a canonical sha256 reference so the root
   representation matches every other material root."
  [round]
  (when-not (map? round)
    (throw (ex-info "governed review-round material is invalid" {})))
  (let [base (select-keys round [:benchmark/content-root
                                 :review-round/members
                                 :review-round/membership-frozen-at
                                 :review-round/policy-root
                                 :review-round/purpose])]
    (ref/sha256-ref
     (hc/domain-hash review-round-material-domain
                     (hc/project-canonical-safe base)))))

(def ^:const evaluation-basis-schema "governed-authority-evaluation-basis.v1")

(def ^:const evaluation-basis-fields
  #{:resolved-review-authority-context/root :review-round/root
    :review-governance/root :position-time-basis/root :signer-key-set/root})

(defn evaluation-basis
  "Join the resolved semantic authority context with the authenticated
   verification/key basis. The join is versioned and domain-separated so
   historical evidence can name the exact evaluation basis that justified a
   decision instead of relying on a transient store registry."
  [parts]
  (let [base (select-keys parts [:resolved-review-authority-context/root
                                 :review-round/root :review-governance/root
                                 :position-time-basis/root :signer-key-set/root])]
    (when-not (and (= (count base) (count evaluation-basis-fields))
                   (every? #(ref/valid-sha256-ref? (get base %))
                           evaluation-basis-fields))
      (throw (ex-info "governed-authority-evaluation-basis is invalid"
                      {:missing (set/difference evaluation-basis-fields
                                                (set (keys base)))})))
    (let [schema-base (assoc base :artifact/schema evaluation-basis-schema)]
      (assoc schema-base :authority-evaluation-basis/root
             (ref/sha256-ref
              (hc/domain-hash :governed-authority-evaluation-basis-v1
                              (hc/project-canonical-safe schema-base)))))))

(defn- authenticated-material? [material]
  (and (map? material)
       (map? (:authority-material/review-round material))
       (map? (:authority-material/review-governance material))
       (map? (:authority-material/position-time-basis material))
       (map? (:authority-material/signer-key-set material))
       (= (:review-round/root material)
          (review-round-material-root (:authority-material/review-round material)))
       (= (:review-governance/root material)
          (governance/governance-root (:authority-material/review-governance material)))
       (= (:position-time-basis/root material)
          (evidence/position-time-basis-root (:authority-material/position-time-basis material)))
       (= (:signer-key-set/root material)
          (signer-key-set-root (:authority-material/signer-key-set material)))))

(defn- require-authenticated-material!
  "The single publication gate shared by initial construction and every
   successor path: freeze, then prove the closed authenticated shape and its
   recomputed roots before anything may enter the store."
  [material]
  (let [material (freeze-material material)]
    (when-not (authenticated-material? material)
      (throw (ex-info "authority material is not rooted and authenticated" {})))
    material))

(defn new-store
  "Construct an authenticated authority-state store from one published envelope
    and its active material. `material` contains the root-bearing fields required
    by resolved-review-authority-context.v1 plus :review-round/hash."
  [envelope material]
  (let [envelope (build-envelope envelope)
        material (require-authenticated-material! material)
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
    predecessor must be the exact current publication head. Successor material
    passes the same authenticated publication gate as initial construction
    before it is eligible for CAS."
  [store expected-head envelope material]
  (let [envelope (build-envelope envelope)
        material (require-authenticated-material! material)]
    (when-not (= (:chain-instance-genesis/root envelope)
                 (:chain-instance-genesis/root material))
      (throw (ex-info "successor material chain does not match successor envelope" {})))
    (let [root (:authoritative-state-envelope/root envelope)]
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
                      (recur)))))))))

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
                       {:resolution-basis/root (:resolution-basis/root basis)
                        :chain-instance-genesis/root (:chain-instance-genesis/root basis)
                        :resolution/state-before-root state-root
                        :authority-state/root envelope-root
                        :chain-configuration/root (:chain-configuration/root envelope)
                        :review-governance/root (:review-governance/root envelope)
                        :review-governance-activation/root (:review-governance-activation/root envelope)
                        :control-plane-evidence/root (:control-plane-evidence/root envelope)
                        :review-round/hash (:review-round/hash material)
                        :review-round/root (:review-round/root material)
                        :position-time-basis/root (:position-time-basis/root material)
                        :review-governance-admissibility/root (:review-governance-admissibility/root material)})})))

(defn resolve-authority-material
  "Issue a store-owned fence only for a current-admission V2 resolution. The
    fence binds the resolved semantic context, the exact issuance material
    roots, and the governed-authority evaluation basis joining them."
  [store basis]
  (let [result (resolve-governed-authority-context store basis)]
    (if-not (and (:resolved? result) (= :current-admission (:resolution/purpose basis))
                 (= resolution/resolution-basis-v2-schema (:artifact/schema basis)))
      (assoc result :reason (or (:reason result) :current-admission-fence-required))
      (loop []
        (let [current @(.state store)
              context (:context result)
              state-root (:resolution/state-before-root context)
              material (get-in current [:material state-root])
              evaluation-basis (evaluation-basis
                                {:resolved-review-authority-context/root
                                 (:resolved-review-authority-context/root context)
                                 :review-round/root (:review-round/root context)
                                 :review-governance/root (:review-governance/root context)
                                 :position-time-basis/root (:position-time-basis/root context)
                                 :signer-key-set/root (:signer-key-set/root material)})
              fence-id (str (java.util.UUID/randomUUID))
              record {:authority-state-envelope/root (:authority-state/root context)
                      :execution/state-root state-root
                      :publication/sequence (get-in current [:envelopes (:head current) :publication/sequence])
                      :resolved-review-authority-context/root (:resolved-review-authority-context/root context)
                      :resolution-basis/root (:resolution-basis/root basis)
                      :review-round/root (:review-round/root context)
                      :review-governance/root (:review-governance/root context)
                      :position-time-basis/root (:position-time-basis/root context)
                      :signer-key-set/root (:signer-key-set/root material)
                      :authority-evaluation-basis/root (:authority-evaluation-basis/root evaluation-basis)
                      :purpose :current-admission :status :issued}]
          (if (not= (:head current) (:authority-state/root context))
            {:resolved? false :reason :state-not-at-required-head}
            (let [next (assoc-in current [:issued-fences fence-id] record)]
              (if (compare-and-set! (.state store) current next)
                {:resolved? true :context context :authenticated-material material
                 :evaluation-basis evaluation-basis
                 :fence {:fence/id fence-id}}
                (recur)))))))))

(defn finalise-under-authority-fence!
  "Atomically consume an issued fence with successor and authority binding.
    Exact retry returns the original terminal result; conflicting reuse rejects.
    The successor material passes the shared authenticated publication gate
    before the single authoritative CAS update."
  [store fence binding successor-envelope successor-material]
  (loop []
    (let [current @(.state store) fence-id (:fence/id fence) record (get-in current [:issued-fences fence-id])]
      (cond
        (nil? record) {:finalised? false :reason :unknown-fence}
        (= :consumed (:status record))
        (if (= (:transition-binding/root record) (:governed-authority-transition-binding/root binding))
          (:result record) {:finalised? false :reason :fence-already-consumed})
        (not (:valid? (resolution/validate-transition-binding binding))) {:finalised? false :reason :authority-transition-binding-invalid}
        (not= (:head current) (:authority-state-envelope/root record)) {:finalised? false :reason :state-not-at-required-head}
        (not= (:execution/state-root record) (:transaction/state-before-root binding)) {:finalised? false :reason :fence-pre-state-mismatch}
        (not= (:resolved-review-authority-context/root record) (:resolved-review-authority-context/root binding)) {:finalised? false :reason :authority-context-mismatch}
        :else (let [envelope (build-envelope successor-envelope)
                    successor-material (require-authenticated-material! successor-material)
                    _ (when-not (= (:chain-instance-genesis/root envelope)
                                   (:chain-instance-genesis/root successor-material))
                        (throw (ex-info "successor material chain does not match successor envelope" {})))
                    root (:authoritative-state-envelope/root envelope)
                    result {:finalised? true :envelope envelope :authority-binding binding}
                    next (-> current (assoc :head root) (assoc-in [:envelopes root] envelope)
                             (assoc-in [:by-state (:execution/state-root envelope)] root)
                             (assoc-in [:material (:execution/state-root envelope)] successor-material)
                             (assoc-in [:authority-bindings root] binding)
                             (assoc-in [:issued-fences fence-id] (assoc record :status :consumed :transition-binding/root (:governed-authority-transition-binding/root binding) :successor-envelope/root root :result result)))]
                (if (compare-and-set! (.state store) current next) result (recur)))))))
