(ns resolver-sim.pro-rata.read-model
  "Read-only diagnostic surface for authoritative publication resolution.

   Provides typed failure reasons and a trace function that walks the full
   H -> B -> O -> R -> P -> output -> allocation -> transition ->
   (V2: compilation-binding -> E3 -> target-map -> semantics)
   chain with per-edge resolution status.

   This is a read-model only: no new domain tags or intent contracts are added
   to canonical.clj. These diagnostic projections are not themselves
   content-addressed artifacts."
  (:require [resolver-sim.hash.reference :as ref]
            [resolver-sim.io.content-addressed-store :as cas]
            [resolver-sim.pro-rata.publication :as publication]
            [resolver-sim.pro-rata.invocation-publication-binding :as binding]
            [resolver-sim.pro-rata.effect-compilation-binding-v2 :as compilation-binding]
            [resolver-sim.pro-rata.effect-compilation-v3 :as compilation]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.pro-rata.protocol-transaction-realization :as realization]
            [resolver-sim.pro-rata.application :as application]
            [resolver-sim.pro-rata.target-map :as target-map]
            [resolver-sim.pro-rata.effect-compilation-semantics :as semantics]
            [resolver-sim.pro-rata.allocation :as allocation]))

;; ── Status vocabulary (closed, per-dimension) ────────────────────────────────

(def status-vocabulary
  "Closed set of status keywords per diagnostic dimension."
  {:authority #{:committed :unavailable}
   :correspondence #{:verified :unverified}
   :semantic-recompilation #{:verified :unavailable :invalid :not-applicable}
   :reachability #{:complete :incomplete}})

;; ── Reason vocabulary (closed, per-dimension) ────────────────────────────────
;; Reasons apply when status is NOT the dimension's 'ok' value.

(def reason-vocabulary
  "Closed set of failure-reason keywords per diagnostic dimension."
  {:authority #{:missing :corrupt :root-mismatch}
   :correspondence #{:missing :corrupt :root-mismatch :correspondence-mismatch}
   :semantic-recompilation #{:missing :corrupt :root-mismatch
                             :unsupported-schema :correspondence-mismatch}
   :reachability #{:missing :corrupt :root-mismatch}})

(def all-reasons
  "Flat set of every failure-reason keyword across all dimensions."
  (into #{} (apply concat (vals reason-vocabulary))))

;; ── Self-root verification functions ────────────────────────────────────────

(defn- verify-head-root
  "Verify a publication head artifact recomputes to the requested root."
  [body root]
  (let [schema (:schema-version body)]
    (cond
      (= schema publication/application-publication-head-schema)
      (= root (publication/application-head-root body))

      (= schema publication/publication-head-schema)
      (= root (publication/head-root body))

      :else false)))

(defn- verify-ordering-root
  "Verify a transaction-ordering artifact recomputes to the requested root."
  [body root]
  (let [v (ordering/verify-ordering body)]
    (and (:valid? v) (= root (:transaction-ordering/hash body)))))

(defn- verify-binding-root
  "Verify a publication binding recomputes to the requested root."
  [body root]
  (and (= root (:pro-rata-invocation-publication-binding/root body))
       (= root (binding/binding-root body))))

(defn- verify-output-root
  "Verify a pro-rata capability output recomputes to the requested root."
  [body root]
  (and (= root (:pro-rata-output/root body))
       (= root (binding/output-root body))))

(defn- verify-compilation-root
  "Verify a V3 effect-compilation artifact recomputes to the requested root."
  [body root]
  (and (= root (:effect-compilation/root body))
       (= root (compilation/compilation-root body))))

(defn- verify-binding-v2-root
  "Verify an effect-compilation-binding v2 recomputes to the requested root."
  [body root]
  (and (= root (:effect-compilation-binding/root body))
       (= root (compilation-binding/binding-root body))))

(defn- verify-transition-root
  "Verify a canonical-effect-transition recomputes to the requested root."
  [body root]
  (and (= root (:canonical-effect-transition/root body))
       (= root (effects/transition-root body))))

(defn- verify-receipt-root
  "Verify an applied-effect-receipt recomputes to the requested root."
  [body root]
  (and (= root (:applied-effect-receipt/root body))
       (application/receipt-valid? body)))

(defn- verify-realization-root
  "Verify a protocol-transaction-realization recomputes to the requested root."
  [body root]
  (= root (realization/realization-root body)))

(defn- verify-semantics-root
  "Verify effect-compilation-semantics recomputes to the requested root."
  [body root]
  (and (= root (:effect-compilation-semantics/root body))
       (semantics/valid? body)))

(defn- verify-allocation-root
  "Verify a realized allocation recomputes to the requested root."
  [body root]
  (and (= root (:allocation/hash body))
       (allocation/allocation-hash-valid? body)))

(defn- verify-target-map-root
  "Verify a target-map artifact recomputes to the requested root."
  [body root]
  (= root (target-map/target-map-root body)))

(def ^:private self-verify-fns
  "Map of artifact role to self-root verification function."
  {:publication/head    verify-head-root
   :publication/ordering verify-ordering-root
   :publication/binding verify-binding-root
   :pro-rata-output     verify-output-root
   :effect-compilation  verify-compilation-root
   :effect-compilation-binding verify-binding-v2-root
   :canonical-transition verify-transition-root
   :applied-effect-receipt verify-receipt-root
   :protocol-transaction-realization verify-realization-root
   :effect-compilation-semantics verify-semantics-root
   :allocation          verify-allocation-root
   :target-map          verify-target-map-root})

;; ── Trace edge resolution ───────────────────────────────────────────────────

(defn- resolve-edge
  "Resolve a single chain edge. Returns a diagnostic edge map.

   role — the artifact role keyword
   root — the sha256 root to resolve (may be nil)
   resolution — CAS resolver function [root -> body-or-nil]"
  [root role resolution]
  (let [verify-fn (self-verify-fns role)]
    (cond
      (nil? root)
      {:artifact-role role :root nil :resolved? false :reason :root-mismatch :body nil}

      (not (ref/valid-sha256-ref? root))
      {:artifact-role role :root root :resolved? false :reason :root-mismatch :body nil}

      :else
      (let [body (try (resolution root) (catch Exception _ nil))]
        (cond
          (nil? body)
          {:artifact-role role :root root :resolved? false :reason :missing :body nil}

          (and verify-fn (not (verify-fn body root)))
          {:artifact-role role :root root :resolved? false :reason :corrupt :body nil}

          :else
          {:artifact-role role :root root :resolved? true :reason nil :body body})))))

(defn- optional-edge
  "Resolve an edge that may not have a root. Returns nil when root is nil."
  [root role resolution]
  (when (some? root)
    (resolve-edge root role resolution)))

(defn- resolve-edges
  "Resolve multiple edges from the same parent body, each with its own role and field."
  [resolution parent fields]
  (remove nil?
          (map (fn [[role field]]
                 (optional-edge (get parent field) role resolution))
               fields)))

(defn- extract-v2-chain
  "Trace V2 compilation sub-chain from a V2 output.
   output -> effect-compilation-binding -> E3 compilation -> target-map, semantics"
  [resolution output]
  (let [ecb-root (:effect-compilation-binding/root output)
        ecb-edge (optional-edge ecb-root :effect-compilation-binding resolution)]
    (when-let [ecb (:body ecb-edge)]
      (let [e3-root (:effect-compilation/root ecb)
            e3-edge (optional-edge e3-root :effect-compilation resolution)]
        (when-let [e3 (:body e3-edge)]
          (let [target-map-edge (optional-edge (:target-map/root e3) :target-map resolution)
                semantics-edge (optional-edge (:effect-compilation-semantics/root e3)
                                              :effect-compilation-semantics resolution)]
            (remove nil? [ecb-edge e3-edge target-map-edge semantics-edge])))))))

(defn trace-from-head
  "Trace the full authoritative publication chain starting from a resolved
   v2 application-publication-head artifact. CAS-only.

   Chain: H -> B -> O -> R -> P -> output -> allocation -> transition ->
   (V2: compilation-binding -> E3 -> target-map -> semantics)"
  [resolution head]
  (let [head-edge       (resolve-edge (:publication/head-root head) :publication/head resolution)
        ordering-edge   (resolve-edge (:publication/last-ordering-root head) :publication/ordering resolution)
        binding-edge    (resolve-edge (:publication/application-binding-root head) :publication/binding resolution)
        ordering        (:body ordering-edge)
        binding         (:body binding-edge)
        receipt-edge    (optional-edge (:transaction/application-receipt-root ordering)
                                       :applied-effect-receipt resolution)
        realization-edge (optional-edge (:transaction/realization-root ordering)
                                        :protocol-transaction-realization resolution)
        capability-edge (optional-edge (:capability-binding/root binding)
                                       :capability-binding resolution)
        capability-body (:body capability-edge)
        output-root     (some-> capability-body :invocation/output-root)
        output-edge     (optional-edge output-root :pro-rata-output resolution)
        output          (:body output-edge)
        v2?             (and output (= binding/output-v2-schema (:pro-rata-output/schema output)))
        allocation-edge (optional-edge (:allocation/root output) :allocation resolution)
        transition-edge (optional-edge (:canonical-transition/root output) :canonical-transition resolution)
        v2-edges        (when v2? (extract-v2-chain resolution output))]
    (into []
          (remove nil?)
          [head-edge ordering-edge binding-edge
           receipt-edge realization-edge
           (optional-edge (:application/root binding) :use-case-application resolution)
           (optional-edge (:executable-distribution/root binding) :executable-distribution resolution)
           capability-edge output-edge
           allocation-edge transition-edge
           v2-edges])))

(defn trace-from-ordering
  "Trace the chain forward from a resolved ordering artifact. CAS-only.
   Chain: O -> R -> P -> (canonical-transition via realization)"
  [resolution ordering]
  (let [ordering-edge     (resolve-edge (:transaction-ordering/hash ordering) :publication/ordering resolution)
        receipt-edge      (optional-edge (:transaction/application-receipt-root ordering)
                                         :applied-effect-receipt resolution)
        realization-edge  (optional-edge (:transaction/realization-root ordering)
                                         :protocol-transaction-realization resolution)
        realization       (:body realization-edge)
        transition-edge   (optional-edge (:canonical-transition/root realization)
                                         :canonical-transition resolution)]
    (into []
          (remove nil?)
          [ordering-edge receipt-edge realization-edge transition-edge])))

(defn- trace-status
  "Determine overall trace status from edge resolution results."
  [edges]
  (let [unresolved (filter #(not (:resolved? %)) edges)]
    (cond
      (empty? unresolved) :complete
      (every? #(= :missing (:reason %)) unresolved) :missing
      :else :corrupt)))

(defn- trace-reasons
  "Collect distinct failure reasons from unresolved trace edges."
  [edges]
  (distinct (keep :reason (remove :resolved? edges))))

(defn trace-authoritative-publication
  "Trace the authoritative publication chain. CAS-only.

   opts:
   :head-root — starting publication head root (sha256 ref)
   :ordering-root — starting ordering root (sha256 ref)

   Returns {:trace/entry-point keyword
            :trace/status :complete | :incomplete | :missing | :corrupt
            :trace/reasons [keyword] — distinct failure reasons from unresolved edges
            :trace/edges [...]}"
  [resolution opts]
  (let [head-root (:head-root opts)
        ordering-root (:ordering-root opts)]
    (cond
      (and head-root (ref/valid-sha256-ref? head-root))
      (let [head-edge (resolve-edge head-root :publication/head resolution)]
        (if-let [head-body (:body head-edge)]
          (let [downstream (trace-from-head resolution head-body)]
            {:trace/entry-point :head-root
             :trace/status (trace-status downstream)
             :trace/reasons (trace-reasons downstream)
             :trace/edges (cons head-edge downstream)})
          {:trace/entry-point :head-root
           :trace/status :incomplete
           :trace/reasons [:missing]
           :trace/edges [head-edge]}))

      (and ordering-root (ref/valid-sha256-ref? ordering-root))
      (let [ordering-edge (resolve-edge ordering-root :publication/ordering resolution)]
        (if-let [ordering-body (:body ordering-edge)]
          (let [downstream (trace-from-ordering resolution ordering-body)]
            {:trace/entry-point :ordering-root
             :trace/status (trace-status downstream)
             :trace/reasons (trace-reasons downstream)
             :trace/edges (cons ordering-edge downstream)})
          {:trace/entry-point :ordering-root
           :trace/status :incomplete
           :trace/reasons [:missing]
           :trace/edges [ordering-edge]}))

      :else
      {:trace/entry-point :unknown
       :trace/status :incomplete
       :trace/reasons [:missing]
       :trace/edges []})))

;; ── Diagnostic result assembly (used by resolve-authoritative-publication) ───

(defn diagnostic-result
  "Assemble the typed diagnostic result map from resolution components.

   ctx keys:
   :head, :binding, :ordering — resolved artifacts or nil
   :head-reason — nil, :missing, :corrupt
   :binding-reason — nil, :missing, :corrupt
   :ordering-reason — nil, :missing, :corrupt
   :authoritative? — boolean
   :correspondence? — boolean
   :reachable? — boolean
    :semantic-status — :verified | :unavailable | :invalid | :not-applicable
    :missing-roots — vector of unresolved CAS roots
    :roots — map of artifact-key to root extracted from binding
    :v2? — boolean (output is V2 schema)
    :v2-compilation-root — E3 compilation root (only when v2? and reachable)"
  [{:keys [head binding ordering head-reason binding-reason ordering-reason
           authoritative? correspondence? reachable? semantic-status
           missing-roots roots v2? v2-compilation-root]}]
  (let [authority-reason (cond
                           head-reason head-reason
                           binding-reason binding-reason
                           ordering-reason ordering-reason
                           (not authoritative?) :root-mismatch
                           :else nil)
        authority-status (if (and authoritative? head) :committed :unavailable)
        correspondence-reason (cond
                                (not reachable?) :missing
                                (not correspondence?) :correspondence-mismatch
                                :else nil)
        semantic-reason (cond
                          (not v2?) nil
                          (not reachable?) :missing
                          (= semantic-status :invalid) :correspondence-mismatch
                          :else nil)
        reachability-reason (if reachable? nil :missing)
        semantic-root (when v2? v2-compilation-root)]
    {:publication/head head
     :publication/binding binding
     :publication/ordering ordering
     :publication/roots roots
     :authority {:status authority-status
                 :reason authority-reason
                 :root (:publication/head-root head)
                 :artifact-role :publication/head}
     :correspondence {:status (if correspondence? :verified :unverified)
                      :reason correspondence-reason
                      :root (:pro-rata-invocation-publication-binding/root binding)
                      :artifact-role :binding/eligibility}
     :semantic-recompilation {:status semantic-status
                              :reason semantic-reason
                              :root semantic-root
                              :artifact-role (when v2? :effect-compilation)}
     :reachability {:status (if reachable? :complete :incomplete)
                    :reason reachability-reason
                    :root nil
                    :artifact-role nil
                    :missing-roots missing-roots}}))

;; ── Public trace serialization ─────────────────────────────────────────────

(defn public-trace
  "Strip internal bodies from a trace result for API output.
   Returns only roots, resolution status, and reasons — no artifact bodies."
  [trace-result]
  (update trace-result :trace/edges
          (partial mapv #(dissoc % :body))))
