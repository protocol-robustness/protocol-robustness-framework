(ns resolver-sim.pro-rata.publication
  "Economic application publication binding (DS9).

   Binds a realized economic application into authoritative lineage. A
   verified applied economic realization (applied-effect-receipt R and
   protocol-transaction-realization P) is only locally verifiable - it has no
   committed edge into any transaction ordering or store. This namespace adds
   the missing publication closure:

        pro-rata application A
          ├── R = applied-effect-receipt/root(A)          (derived)
          └── P = protocol-transaction-realization/root(A) (derived)
                   ▼
         economic publication ordering T (transaction-ordering.v3)
         commits exact R + P + exact state-before/after + effects
                   ▼
         authoritative successor head commits T            (cycle-free)

   APPLIED_EFFECT_PUBLICATION_CONSERVATION:
     for every authoritative economic application A, there exists exactly one
     authoritative publication transaction T such that T commits
     R = applied-effect-receipt/root(A) and P =
     protocol-transaction-realization/root(A), with
       T.state-before == R.state-before == canonical-transition.state-before
       T.state-after  == R.state-after  == canonical-transition.state-after
       P binds the same canonical-transition as R
     and the successor authoritative head commits T.

   No caller-supplied R, P, T, state-before, or state-after may substitute for
   the roots re-derived from the application being published. This closes
   transplantation: a perfectly valid proof object that never becomes part of
   authoritative state lineage does not count as an authoritative application."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.pro-rata.application :as application]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-binding-v2 :as compilation-binding]
            [resolver-sim.pro-rata.protocol-transaction-realization :as realization]
            [resolver-sim.transaction.ordering :as ordering]))

(def invariant-id
  "The publication conservation invariant."
  :application-effect-publication-conservation)

(def publication-head-schema
  "Schema of the authoritative economic publication head."
  "economic-publication-head.v1")

(def application-publication-head-schema
  "Versioned application-bound publication head. V1 roots remain unchanged."
  "economic-publication-head.v2")

(def head-domain
  "Domain tag of the authoritative economic publication head root."
  :economic-publication-head-v1)

;; ── root derivation (never caller-supplied) ───────────────────────────────

(defn derive-receipt-root
  "Derive R from an applied-effect-receipt artifact by structural verification
   (the committed root must recompute). A caller-supplied root is never taken
   as-is."
  [receipt]
  (when-not (application/receipt-valid? receipt)
    (throw (ex-info "publication requires a valid applied-effect-receipt"
                    {:type :publication/invalid-receipt})))
  (:applied-effect-receipt/root receipt))

(defn derive-realization-root
  "Derive P from a protocol-transaction-realization artifact by recomputing its
   root over its committed fields. A caller-supplied root is never taken as-is."
  [realization]
  (let [computed (realization/realization-root realization)
        stored (:protocol-transaction-realization/root realization)]
    (when-not (= computed stored)
      (throw (ex-info "protocol-transaction-realization root does not recompute"
                      {:type :publication/invalid-realization
                       :stored stored :computed computed})))
    computed))

;; ── conservation invariant ────────────────────────────────────────────────

(defn conservation-violations
  "Validate V1 publication closure, or V2 closure including a resolver-verified
  V2 compilation binding. V1 does not require V2 dependencies."
  ([receipt realization canonical-transition]
   (conservation-violations receipt realization canonical-transition nil nil nil nil))
  ([receipt realization canonical-transition compilation effect-compilation-binding output]
   (conservation-violations receipt realization canonical-transition compilation effect-compilation-binding output nil))
  ([receipt realization canonical-transition compilation effect-compilation-binding output resolve-body]
   (let [ct-root (effects/transition-root canonical-transition)
         v2? (= "applied-effect-receipt.v2" (:schema-version receipt))
         receipt-valid? (if v2?
                          (application/receipt-valid? receipt compilation canonical-transition resolve-body)
                          (application/receipt-valid? receipt))]
     (vec
      (concat
       (when-not receipt-valid? [{:violation/id :publication/invalid-receipt}])
       (when-not (= (:protocol-transaction-realization/root realization)
                    (realization/realization-root realization))
         [{:violation/id :publication/invalid-realization}])
       (when-not (= (:canonical-transition/root realization) ct-root)
         [{:violation/id :publication/transition-mismatch}])
       (when-not (= (:state-before/root receipt) (:state-before/root canonical-transition))
         [{:violation/id :publication/state-before-transplant}])
       (when-not (= (:state-after/root receipt) (:state-after/root canonical-transition))
         [{:violation/id :publication/state-after-transplant}])
       (when (and receipt-valid?
                  (not= (:protocol-effect-set/root receipt) (:executed-effect-set/root receipt)))
         [{:violation/id :publication/effect-set-inconsistent}])
       (when v2?
         (concat
          (when-not (and compilation effect-compilation-binding output (fn? resolve-body))
            [{:violation/id :publication/missing-compilation-dependencies}])
          (when-not (= "pro-rata-capability-output.v2" (:pro-rata-output/schema output))
            [{:violation/id :publication/receipt-output-schema-mismatch}])
          (when-not (= (:effect-compilation-binding/root receipt)
                       (:effect-compilation-binding/root output))
            [{:violation/id :publication/binding-root-mismatch}])
          (when-not (compilation-binding/valid? effect-compilation-binding compilation canonical-transition resolve-body)
            [{:violation/id :publication/invalid-compilation-binding}]))))))))

(defn conservation-holds?
  [& args]
  (empty? (apply conservation-violations args)))

;; ── publication ordering construction ─────────────────────────────────────

(defn build-publication-ordering
  "Build the economic publication ordering T (transaction-ordering.v3) for an
  applied economic application. V2 receipts require their resolver-verified
  compilation dependencies; V1 construction remains self-contained."
  [{:keys [receipt realization canonical-transition compilation effect-compilation-binding output resolve-body
           action scope conflict-key commit-index previous-transaction-hash input-root]}]
  (let [violations (conservation-violations receipt realization canonical-transition
                                            compilation effect-compilation-binding output resolve-body)]
    (when (seq violations)
      (throw (ex-info "economic application publication conservation violated"
                      {:type :publication/conservation-violation
                       :invariant invariant-id
                       :violations violations})))
    (let [R (:applied-effect-receipt/root receipt)
          P (:protocol-transaction-realization/root realization)
          state-before (ref/sha256-ref (:state-before/root receipt))
          state-after (ref/sha256-ref (:state-after/root receipt))
          effects-root (ref/sha256-ref (:effects/root canonical-transition))
          receipt-root (ref/sha256-ref R)
          realization-root (ref/sha256-ref P)]
      (ordering/transaction-ordering
       {:transaction-ordering/schema ordering/ordering-v3-schema
        :transaction/action action
        :transaction/scope scope
        :transaction/conflict-key conflict-key
        :transaction/commit-index commit-index
        :transaction/previous-transaction-hash previous-transaction-hash
        :transaction/input-root input-root
        :transaction/state-before-root state-before
        :transaction/state-after-root state-after
        :transaction/effects-root effects-root
        :transaction/application-receipt-root receipt-root
        :transaction/realization-root realization-root}))))

;; ── authoritative successor head ──────────────────────────────────────────

(defn head-root
  "Content-addressed root of an authoritative economic publication head. The
   head commits the last-published ordering root T; T does NOT commit the head
   (the head is outside the ordering's closure), so committing T here is
   cycle-free — unlike the chain-state root, which must exclude its own
   ordering hash."
  [head]
  (hc/domain-hash head-domain
                  (select-keys head [:schema-version
                                     :publication/last-ordering-root
                                     :publication/sequence
                                     :publication/predecessor-root])))

(defn initial-head
  "The genesis economic publication head: no ordering published yet."
  []
  (let [base {:schema-version publication-head-schema
              :publication/last-ordering-root nil
              :publication/sequence 0
              :publication/predecessor-root nil}]
    (assoc base :publication/head-root (head-root base))))

(defn successor-head
  "The successor authoritative head committing `ordering` as its
   last-published ordering root. Cycle-free: the ordering never references the
   head."
  [current ordering]
  (let [base {:schema-version publication-head-schema
              :publication/last-ordering-root (:transaction-ordering/hash ordering)
              :publication/sequence (inc (:publication/sequence current))
              :publication/predecessor-root (:publication/head-root current)}]
    (assoc base :publication/head-root (head-root base))))

(defn new-head-store
  "A fresh in-memory authoritative economic publication head store."
  []
  (atom {:publication/head (initial-head)
         :publication/store-version 0}))

(defn application-head-root
  [head]
  (hc/domain-hash "PRF_ECONOMIC_PUBLICATION_HEAD_V2"
                  (select-keys head [:schema-version
                                     :publication/last-ordering-root
                                     :publication/application-binding-root
                                     :publication/sequence
                                     :publication/predecessor-root])))

(defn initial-application-head []
  (let [base {:schema-version application-publication-head-schema
              :publication/last-ordering-root nil
              :publication/application-binding-root nil
              :publication/sequence 0
              :publication/predecessor-root nil}]
    (assoc base :publication/head-root (application-head-root base))))

(defn new-application-head-store
  "CAS-owned v2 state. Binding bodies enter this map only in the successful CAS
   that advances the authoritative head."
  []
  (atom {:publication/head (initial-application-head)
         :publication/bindings {}
         :publication/store-version 0}))

(defn- application-successor-head [current ordering binding-root]
  (let [base {:schema-version application-publication-head-schema
              :publication/last-ordering-root (:transaction-ordering/hash ordering)
              :publication/application-binding-root binding-root
              :publication/sequence (inc (:publication/sequence current))
              :publication/predecessor-root (:publication/head-root current)}]
    (assoc base :publication/head-root (application-head-root base))))

(defn retained-application-binding
  "Return B only when the v2 head commits B and B commits the same ordering."
  [store]
  (let [state @store
        head (:publication/head state)
        binding-root (:publication/application-binding-root head)
        binding (get-in state [:publication/bindings binding-root])]
    (when (and (= application-publication-head-schema (:schema-version head))
               binding
               (= binding-root (:pro-rata-invocation-publication-binding/root binding))
               (= (:publication/last-ordering-root head)
                  (:publication-ordering/root binding)))
      binding)))

(defn publish-application-bound!
  "Validate B without current-head lookup, then atomically retain B and advance
   the v2 authoritative head. Dynamic resolution avoids a publication -> binding
   compile-time cycle; a missing validator fails closed."
  [store ordering binding resolved expected-version]
  (let [eligible? (requiring-resolve
                   'resolver-sim.pro-rata.invocation-publication-binding/binding-eligible?)]
    (when-not (and eligible?
                   (eligible? binding (assoc resolved :publication-ordering ordering))
                   (= (:publication-ordering/root binding) (:transaction-ordering/hash ordering)))
      (throw (ex-info "Invalid application-bound publication" {:reason :invalid-application-binding})))
    (loop []
      (let [current @store
            version (:publication/store-version current)
            head (:publication/head current)]
        (cond
          (not= application-publication-head-schema (:schema-version head))
          {:status :rejected :reason :application-bound-publication-requires-v2-head}

          (and (some? expected-version) (not= expected-version version))
          {:status :contention :reason :version-mismatch
           :expected-version expected-version :observed-version version}

          :else
          (let [binding-root (:pro-rata-invocation-publication-binding/root binding)
                successor (application-successor-head head ordering binding-root)
                next-state {:publication/head successor
                            :publication/bindings (assoc (:publication/bindings current)
                                                         binding-root binding)
                            :publication/store-version (inc version)}]
            (if (compare-and-set! store current next-state)
              {:status :committed :publication/head successor
               :publication/ordering-root (:transaction-ordering/hash ordering)
               :publication/application-binding-root binding-root}
              (recur))))))))

(defn current-head
  [store]
  (get-in @store [:publication/head]))

(defn published-ordering-root
  "The ordering root currently committed by the authoritative head, or nil if
   nothing has been published."
  [store]
  (get-in @store [:publication/head :publication/last-ordering-root]))

(defn published?
  "True only when `ordering` is the exact ordering committed by the
   authoritative head. A valid ordering that was built but never published
   (or retained without a committed root) is not authoritative."
  [store ordering]
  (and ordering
       (= (:transaction-ordering/hash ordering)
          (published-ordering-root store))))

(defn publish!
  "Atomically publish `ordering` to the authoritative head store, advancing the
   head to a successor that commits the ordering root. Returns
   {:status :committed ...} on success or {:status :contention ...} on a
   version mismatch. Only a :committed publish makes the ordering
   authoritative."
  [store ordering expected-version]
  (loop []
    (let [current @store
          version (:publication/store-version current)
          head (:publication/head current)]
      (if (and (some? expected-version) (not= expected-version version))
        {:status :contention :reason :version-mismatch
         :expected-version expected-version :observed-version version}
        (let [successor (successor-head head ordering)
              next-state {:publication/head successor
                          :publication/store-version (inc version)}]
          (if (compare-and-set! store current next-state)
            {:status :committed
             :publication/head successor
             :publication/ordering-root (:transaction-ordering/hash ordering)}
            (recur)))))))
