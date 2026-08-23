(ns resolver-sim.resubmission.authoritative-checkpoint
  "Canonical resubmission-authoritative-checkpoint.v1.

  The authoritative checkpoint is a canonical, content-addressed artifact that
  binds a chain state-root to an admitted authority context. It carries the
  accepted checkpoint head as store-owned state — separate from the protocol
  application state — so that an authenticated current checkpoint (not merely a
  recomputed state root) is required for authoritative admission.

  Canonical contract:

    checkpoint-root = \"sha256:\" + domain-hash(
        \"prf.resubmission-authoritative-checkpoint.v1\",
        canonical-bytes-v2(unsigned-checkpoint-projection))

  The unsigned projection excludes ONLY :checkpoint/root (the self-hash).
  The root is attached after the projection root is computed."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.resubmission.authority-context :as authority-context]))

;; ── Schema constants ──────────────────────────────────────────────────

(def ^:const checkpoint-schema
  "Schema identifier for resubmission-authoritative-checkpoint.v1."
  "resubmission-authoritative-checkpoint.v1")

(def ^:const checkpoint-domain
  :prf-resubmission-authoritative-checkpoint-v1)

(def ^:const authorization-mode-authoritative :authoritative)

(def ^:private checkpoint-sha256-fields
  "Fields that must be valid sha256 references (or nil for predecessor-root)."
  #{:checkpoint/chain-id
    :checkpoint/genesis-root
    :checkpoint/state-root
    :checkpoint/authority-context-root
    :checkpoint/configuration-root})

(defn- root [value]
  (hash-ref/sha256-ref
   (hc/domain-hash checkpoint-domain
                   (hc/project-resubmission-authoritative-checkpoint
                    value checkpoint-domain))))

;; ── Validation ───────────────────────────────────────────────────────

(defn validate-checkpoint
  "Strict, closed-shape validator for resubmission-authoritative-checkpoint.v1.

   Returns {:valid? bool :errors [...]}. Fails closed on:
     - non-map input;
     - wrong :checkpoint/schema;
     - unknown top-level keys (closed shape);
     - missing required keys;
     - sha256 reference fields that are nil or malformed;
     - :checkpoint/authorization-mode not :authoritative;
     - :checkpoint/epoch not a non-negative integer;
     - :checkpoint/sequence not a non-negative integer;
     - :checkpoint/predecessor-root not nil or a valid sha256 ref;
     - :checkpoint/root present but does not match recomputed root."
  ([ckpt] (validate-checkpoint ckpt nil))
  ([ckpt expected-root]
   (let [errors (atom [])
         report! (fn [& msgs] (swap! errors #(into % msgs)))]
     (when-not (map? ckpt)
       (report! "checkpoint must be a map"))
     (when (map? ckpt)
       (when-not (= checkpoint-schema (:checkpoint/schema ckpt))
         (report! (str "checkpoint/schema must be " checkpoint-schema
                       ", got " (pr-str (:checkpoint/schema ckpt)))))
       (let [have (set (keys ckpt))
             extra (set/difference have
                                   (set hc/resubmission-authoritative-checkpoint-fields)
                                   #{:checkpoint/root})
             missing (set/difference
                      (set hc/resubmission-authoritative-checkpoint-fields) have)]
         (when (seq extra)
           (report! (str "unknown checkpoint keys: " (sort extra))))
         (when (seq missing)
           (report! (str "missing checkpoint keys: " (sort missing))))
         (doseq [f checkpoint-sha256-fields
                 :let [v (get ckpt f)]
                 :when (contains? have f)]
           (cond
             (nil? v)
             (report! (str f " must not be nil"))
             (not (hash-ref/valid-sha256-ref? v))
             (report! (str f " must be a valid sha256 reference, got "
                           (pr-str v)))))
         (let [mode (:checkpoint/authorization-mode ckpt)]
           (when-not (= authorization-mode-authoritative mode)
             (report! (str :checkpoint/authorization-mode
                           " must be " authorization-mode-authoritative
                           ", got " (pr-str mode)))))
         (let [epoch (:checkpoint/epoch ckpt)]
           (when-not (and (integer? epoch) (>= epoch 0))
             (report! (str :checkpoint/epoch
                           " must be a non-negative integer, got "
                           (pr-str epoch)))))
         (let [seq-num (:checkpoint/sequence ckpt)]
           (when-not (and (integer? seq-num) (>= seq-num 0))
             (report! (str :checkpoint/sequence
                           " must be a non-negative integer, got "
                           (pr-str seq-num)))))
         (let [pred (:checkpoint/predecessor-root ckpt)]
           (when (and (contains? have :checkpoint/predecessor-root)
                      (not (or (nil? pred) (hash-ref/valid-sha256-ref? pred))))
             (report! (str :checkpoint/predecessor-root
                           " must be nil or a valid sha256 reference, got "
                           (pr-str pred)))))
         (let [ckpt-root (:checkpoint/root ckpt)]
           (when (contains? have :checkpoint/root)
             (cond
               (nil? ckpt-root)
               (report! (str :checkpoint/root " must not be nil"))
               (not (hash-ref/valid-sha256-ref? ckpt-root))
               (report! (str :checkpoint/root
                             " must be a valid sha256 reference, got "
                             (pr-str ckpt-root)))
               (and (hash-ref/valid-sha256-ref? ckpt-root)
                    (not= ckpt-root (root (dissoc ckpt :checkpoint/root))))
               (report! (str :checkpoint/root
                             " does not match recomputed checkpoint root"))))))
       (when (and expected-root
                  (hash-ref/valid-sha256-ref? expected-root)
                  (hash-ref/valid-sha256-ref? (:checkpoint/root ckpt))
                  (not= expected-root (:checkpoint/root ckpt)))
         (report! (str "expected checkpoint root " (pr-str expected-root)
                       " does not match declared "
                       (pr-str (:checkpoint/root ckpt))))))
     {:valid? (empty? @errors) :errors (vec @errors)})))

(defn checkpoint-valid?
  "Quick boolean structural validity check (without root cross-check)."
  [ckpt]
  (:valid? (validate-checkpoint ckpt)))

;; ── Root computation ─────────────────────────────────────────────────

(defn checkpoint-root
  "Compute the canonical SHA-256 root of a checkpoint projection.

   Validates strict closed-shape first (fail-closed)."
  [ckpt]
  (let [v (validate-checkpoint ckpt)]
    (when-not (:valid? v)
      (throw (ex-info "resubmission-authoritative-checkpoint.v1 is invalid"
                      {:type :checkpoint/invalid
                       :schema checkpoint-schema
                       :errors (:errors v)}))))
  (root (dissoc ckpt :checkpoint/root)))

(defn checkpoint-root*
  "Two-arity variant: compute root and reject on mismatch with expected."
  ([ckpt expected-root]
   (let [computed (checkpoint-root ckpt)]
     (when (and (some? expected-root) (not= computed expected-root))
       (throw (ex-info "caller-supplied checkpoint root does not match"
                       {:type :checkpoint/root-mismatch
                        :declared expected-root
                        :computed computed})))
     computed)))

;; ── Construction ────────────────────────────────────────────────────

(defn build-initial-checkpoint
  "Construct the genesis authoritative checkpoint C0.

   Conceptually:
     verified genesis
     + verified genesis authorization
     + derived authority context
     -> authenticated initial authoritative checkpoint

   The checkpoint binds the genesis-committed G0 state-root to the admitted
   authority context. predecessor-root is nil and sequence is 0."
  [genesis authz authority-context]
  (let [cfg (:configuration genesis)
        config-root (genesis/resubmission-chain-configuration-root cfg)
        genesis-root (genesis/resubmission-chain-genesis-root genesis)
        chain-id (:chain/id genesis)
        state-root (:initial-state/root genesis)
        ctx-root (:authority/context-root authority-context)
        checkpoint {:checkpoint/schema checkpoint-schema
                    :checkpoint/chain-id chain-id
                    :checkpoint/genesis-root genesis-root
                    :checkpoint/state-root state-root
                    :checkpoint/authority-context-root ctx-root
                    :checkpoint/configuration-root config-root
                    :checkpoint/epoch (:authority/epoch authority-context)
                    :checkpoint/authorization-mode authorization-mode-authoritative
                    :checkpoint/predecessor-root nil
                    :checkpoint/sequence 0}]
    (assoc checkpoint :checkpoint/root (root checkpoint))))

(defn build-successor-checkpoint
  "Construct a successor checkpoint C1 from a predecessor checkpoint C0 and a
   new state-root.

   The successor inherits chain-id, genesis-root, configuration-root, and
   epoch from the predecessor. predecessor-root is the predecessor's root,
   and sequence is predecessor-seq + 1.

   NOTE: this does NOT verify that the state-root transition is valid — it
   only binds the roots. The authoritative store is responsible for invoking
   the pure transition and verifying the resulting state-root before calling
   this constructor."
  [predecessor new-state-root]
  (let [checkpoint {:checkpoint/schema checkpoint-schema
                    :checkpoint/chain-id (:checkpoint/chain-id predecessor)
                    :checkpoint/genesis-root (:checkpoint/genesis-root predecessor)
                    :checkpoint/state-root new-state-root
                    :checkpoint/authority-context-root
                    (:checkpoint/authority-context-root predecessor)
                    :checkpoint/configuration-root
                    (:checkpoint/configuration-root predecessor)
                    :checkpoint/epoch (:checkpoint/epoch predecessor)
                    :checkpoint/authorization-mode authorization-mode-authoritative
                    :checkpoint/predecessor-root (:checkpoint/root predecessor)
                    :checkpoint/sequence (inc (:checkpoint/sequence predecessor))}]
    (assoc checkpoint :checkpoint/root (root checkpoint))))
