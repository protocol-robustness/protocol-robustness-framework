(ns resolver-sim.resubmission.authority-context
  "Canonical resubmission-disposition-authority-context.v1.

  The authority context is a canonical, content-addressed artifact that admits
  a disposition authority key for a chain epoch. It is derived only after a
  verified genesis realization binds an exact genesis root to an authenticated
  three-member researcher authority decision (Stage 3 authorization).

  A self-consistent authority context establishes the integrity of its contents.
  It does NOT by itself establish that the context is the currently accepted
  authoritative head — authority is established by the authoritative checkpoint
  head (see resubmission.authoritative-checkpoint).

  Canonical contract:

    context-root = \"sha256:\" + domain-hash(
        \"prf.resubmission-disposition-authority-context.v1\",
        canonical-bytes-v2(unsigned-context-projection))

  The unsigned projection excludes ONLY :authority/context-root (the self-hash).
  The context-root is attached after the projection root is computed."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.genesis :as genesis]))

;; ── Schema constants ──────────────────────────────────────────────────

(def ^:const authority-context-schema
  "Schema identifier for resubmission-disposition-authority-context.v1."
  "resubmission-disposition-authority-context.v1")

(def ^:const authority-context-domain
  :prf-resubmission-authority-context-v1)

(def ^:const authority-epoch-0 0)

(def ^:const permitted-action-vocabulary
  "The full set of actions that may appear in
   :authority/permitted-actions across all epochs."
  #{:prf.resubmission/apply-disposition})

(def ^:const genesis-permitted-actions
  "The canonical, ordered permitted-actions set for epoch 0."
  [:prf.resubmission/apply-disposition])

(def ^:private authority-context-sha256-fields
  "Fields that must be valid sha256 references when present."
  #{:authority/genesis-root
    :authority/configuration-root
    :authority/authorization-root})

(defn- sha256-ref-ok?
  "True when v is a valid sha256 reference string."
  [v]
  (hash-ref/valid-sha256-ref? v))

(defn- root [value]
  (hash-ref/sha256-ref
   (hc/domain-hash authority-context-domain
                   (hc/project-resubmission-authority-context value authority-context-domain))))

;; ── Validation ───────────────────────────────────────────────────────

(defn validate-authority-context
  "Strict, closed-shape validator for resubmission-disposition-authority-context.v1.

   Returns {:valid? bool :errors [...]}. Fails closed on:
     - non-map input;
     - wrong :authority/context-schema;
     - unknown top-level keys (closed shape);
     - missing required keys;
     - sha256 reference fields that are nil or malformed;
     - :authority/public-key that is not a string or nil;
     - :authority/epoch that is not a non-negative integer;
     - :authority/permitted-actions that is not a vector;
     - noncanonical action order or duplicates;
     - unsupported actions;
     - :authority/context-root that is present but does not match
       the recomputed root (when supplied).

   When `expected-root` is supplied it is rejected on mismatch rather than
   trusted."
  ([ctx] (validate-authority-context ctx nil))
  ([ctx expected-root]
   (let [errors (atom [])
         report! (fn [& msgs] (swap! errors #(into % msgs)))]
     (when-not (map? ctx)
       (report! "authority-context must be a map"))
     (when (map? ctx)
       (when-not (= authority-context-schema
                    (:authority/context-schema ctx))
         (report! (str "authority/context-schema must be "
                       authority-context-schema
                       ", got " (pr-str (:authority/context-schema ctx)))))
       (let [have (set (keys ctx))
             extra (set/difference have (set hc/resubmission-authority-context-fields)
                                   #{:authority/context-root})
             missing (set/difference (set hc/resubmission-authority-context-fields)
                                     have)]
         (when (seq extra)
           (report! (str "unknown authority-context keys: " (sort extra))))
         (when (seq missing)
           (report! (str "missing authority-context keys: " (sort missing))))
         (doseq [f authority-context-sha256-fields
                 :let [v (get ctx f)]
                 :when (contains? have f)]
           (cond
             (nil? v)
             (report! (str f " must not be nil"))
             (not (sha256-ref-ok? v))
             (report! (str f " must be a valid sha256 reference, got "
                           (pr-str v)))))
         (let [pk (:authority/public-key ctx)]
           (when (and (contains? have :authority/public-key)
                      (not (or (nil? pk) (string? pk))))
             (report! (str :authority/public-key
                           " must be a string or nil, got "
                           (some-> pk class .getName)))))
         (let [epoch (:authority/epoch ctx)]
           (when (and (contains? have :authority/epoch)
                      (not (and (integer? epoch) (>= epoch 0))))
             (report! (str :authority/epoch
                           " must be a non-negative integer, got "
                           (pr-str epoch)))))
         (let [actions (:authority/permitted-actions ctx)]
           (when (and (contains? have :authority/permitted-actions)
                      (not (vector? actions)))
             (report! (str :authority/permitted-actions
                           " must be a vector, got "
                           (some-> actions class .getName))))
           (when (vector? actions)
             (when (not= (count actions) (count (set actions)))
               (report! (str :authority/permitted-actions
                             " contains duplicates: " (pr-str actions))))
             (doseq [a actions]
               (when-not (contains? permitted-action-vocabulary a)
                 (report! (str :authority/permitted-actions
                               " contains unsupported action: " (pr-str a)))))))
         (let [ctx-root (:authority/context-root ctx)]
           (when (contains? have :authority/context-root)
             (cond
               (nil? ctx-root)
               (report! (str :authority/context-root " must not be nil"))
               (not (sha256-ref-ok? ctx-root))
               (report! (str :authority/context-root
                             " must be a valid sha256 reference, got "
                             (pr-str ctx-root)))
               (and (sha256-ref-ok? ctx-root)
                    (not= ctx-root (root (dissoc ctx :authority/context-root))))
               (report! (str :authority/context-root
                             " does not match recomputed context root"))))))
       (when (and expected-root (sha256-ref-ok? expected-root)
                  (sha256-ref-ok? (:authority/context-root ctx))
                  (not= expected-root (:authority/context-root ctx)))
         (report! (str "expected context root " (pr-str expected-root)
                       " does not match declared "
                       (pr-str (:authority/context-root ctx))))))
     {:valid? (empty? @errors) :errors (vec @errors)})))

(defn authority-context-valid?
  "Quick boolean structural validity check (without root cross-check)."
  [ctx]
  (:valid? (validate-authority-context ctx)))

;; ── Root computation ─────────────────────────────────────────────────

(defn authority-context-root
  "Compute the canonical SHA-256 root of an authority-context projection.

   Validates strict closed-shape first (fail-closed)."
  [ctx]
  (let [v (validate-authority-context ctx)]
    (when-not (:valid? v)
      (throw (ex-info "resubmission-disposition-authority-context.v1 is invalid"
                      {:type :authority-context/invalid
                       :schema authority-context-schema
                       :errors (:errors v)}))))
  (root (dissoc ctx :authority/context-root)))

(defn authority-context-root*
  "Two-arity variant: compute root and reject on mismatch with expected."
  ([ctx expected-root]
   (let [computed (authority-context-root ctx)]
     (when (and (some? expected-root) (not= computed expected-root))
       (throw (ex-info "caller-supplied authority-context root does not match"
                       {:type :authority-context/root-mismatch
                        :declared expected-root
                        :computed computed})))
     computed)))

;; ── Construction ──────────────────────────────────────────────────────

(defn build-authority-context
  "Construct a resubmission-disposition-authority-context.v1 from a validated
   genesis and the canonical root of a verified genesis authorization.

   The authority context admits the genesis's disposition-authority public key
   for epoch 0 with the single permitted action :prf.resubmission/apply-disposition.
   The :authority/configuration-root is the canonical root of the genesis's
   configuration; :authority/authorization-root is the canonical root of the
   genesis authorization; both transitively commit all sub-artifacts.

   The returned context carries its own :authority/context-root (self-hash).

   Args:
     genesis     — a structurally valid resubmission-chain-genesis.v1
     authz-root  — the canonical SHA-256 root of a verified
                   resubmission-chain-genesis-authorization.v1"
  [genesis authz-root]
  (let [cfg (:configuration genesis)
        config-root (genesis/resubmission-chain-configuration-root cfg)
        genesis-root (genesis/resubmission-chain-genesis-root genesis)
        public-key (:disposition-authority/public-key cfg)
        ctx {:authority/context-schema authority-context-schema
             :authority/genesis-root genesis-root
             :authority/configuration-root config-root
             :authority/authorization-root authz-root
             :authority/public-key public-key
             :authority/epoch authority-epoch-0
             :authority/permitted-actions genesis-permitted-actions}]
    (assoc ctx :authority/context-root (root ctx))))
