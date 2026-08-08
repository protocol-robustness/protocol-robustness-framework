(ns resolver-sim.composition.evidence-contract
  "Explicit, content-addressed evidence-contract registry that the composition
   compiler resolves :combination/verification :evidence-contract-ref against.

   An evidence contract is a committed definition of what evidence must
   satisfy a compiled plan. The compiler resolves the symbolic ref to a
   resolved identity (:evidence-contract/id + :evidence-contract/root) and
   commits that identity into the plan, so a later registry mutation can never
   silently change what an already-compiled plan meant.

   The registry is an explicit caller-supplied map {<ref-id> <entry>}. Entries
   are kind-tagged so a ref can be rejected when it resolves to the wrong kind
   of definition — the seed of a general assurance-definitions registry rather
   than a flattened, untyped ID namespace.

   Identity model (settled, inherited by typed obligations): a committed
   evidence-contract identity is the (id, root) PAIR — DECLARED-CONTRACT
   semantics, not content-alias semantics. The ref id is semantic: it names
   WHICH declared contract the plan binds. Two different refs resolving to the
   same root commit different identities ({:id A :root X} ≠ {:id B :root X})
   and therefore different plan roots; the same (id, root) pair always commits
   identically."
  (:require [resolver-sim.hash.reference :as hash-ref]))

(def schema-version
  "evidence-contract.registry.v1")

(def supported-kind
  "The only kind an :evidence-contract-ref may resolve to."
  :evidence-contract)

(defn valid-root?
  "True when a root is a canonical sha256 reference string."
  [root]
  (and (string? root) (hash-ref/valid-sha256-ref? root)))

(defn validate-entry
  "Structural validation for a single registry entry.
   Returns {:valid? bool :violations [<violation-maps>]}."
  [entry]
  (let [id (:evidence-contract/id entry)
        kind (:evidence-contract/kind entry)
        root (:evidence-contract/root entry)
        violations (cond-> []
                     (not (map? entry))
                     (conj {:violation/id :violation/non-map-evidence-contract
                            :details {:entry entry}})

                     (and (map? entry) (not (keyword? id)))
                     (conj {:violation/id :violation/invalid-evidence-contract-id
                            :details {:id id}})

                     (and (map? entry) (not (keyword? kind)))
                     (conj {:violation/id :violation/invalid-evidence-contract-kind
                            :details {:kind kind}})

                     (and (map? entry) (not (valid-root? root)))
                     (conj {:violation/id :violation/invalid-evidence-contract-root
                            :details {:root root}}))]
    {:valid? (empty? violations)
     :violations violations}))

(defn committed-identity
  "The committed evidence-contract identity for a resolved ref: the (id, root)
   pair. Identity is DECLARED-CONTRACT based, not content-alias based — the ref
   id is semantic (it names which declared contract the plan binds), so two
   different refs resolving to the same root commit different identities."
  [ref entry]
  {:id ref
   :root (:evidence-contract/root entry)})

(defn resolve-ref
  "Resolve an :evidence-contract-ref against an evidence-contract registry.

   registry — {<ref-id> {:evidence-contract/id <kw>
                         :evidence-contract/kind :evidence-contract
                         :evidence-contract/root \"sha256:…\"}}

   Returns {:resolved? true :entry <entry>} or
           {:resolved? false :violation/id <kw> :ref <ref> ...}.

   Resolution fails closed on a missing ref, a ref that resolves to a
   non-evidence-contract kind (wrong-kind), or a malformed registry entry —
   a ref that cannot be resolved is never silently dropped."
  [registry ref]
  (if-let [entry (get registry ref)]
    (let [validation (validate-entry entry)]
      (if-not (:valid? validation)
        {:resolved? false
         :violation/id :violation/malformed-evidence-contract-entry
         :ref ref
         :violations (:violations validation)}
        (if (= supported-kind (:evidence-contract/kind entry))
          {:resolved? true :entry entry}
          {:resolved? false
           :violation/id :violation/evidence-contract-wrong-kind
           :ref ref
           :kind (:evidence-contract/kind entry)})))
    {:resolved? false
     :violation/id :violation/unresolved-evidence-contract
     :ref ref}))
