(ns resolver-sim.composition.comparability
  "Comparability: whether two capabilities or compiled compositions may be
   meaningfully compared. Distinct from composability — two capabilities may
   compose while producing outputs that are not comparable, and two may be
   comparable alternatives without being valid sequential stages.

   Comparability is derived from committed contracts and compiled plans; a
   capability cannot self-assert exact comparability. Classes:
     :exact                  — identical committed roots (enough evidence)
     :compatible-normalized  — reconciled by an explicitly committed
                               normalization root (enough evidence)
     :compatible-direct      — semantically substitutable on the committed
                               surface without a normalization (enough
                               evidence)
     :partial                — comparable on the identified output projection
     :incomparable           — demonstrated conflict on a gating dimension
     :unknown                — missing or malformed comparison evidence
                               (non-passing)

   Evidence rules:
     - a missing or malformed composition contract is :unknown;
     - the :composition/roles dimension is gating: a role conflict is
       :incomparable, and a non-exact comparison with roles that are not
       evaluable on both sides (empty role sets) is :unknown;
     - plan comparisons fail closed on absent or malformed output contracts or
       merge strategies (:unknown), never a successful class.

   Trust model vs the extension registry (register / register-capability):
   compare-capabilities evaluates caller-supplied sides and therefore trusts
   the caller's :capability-root. The registry bridge — side-from-entry,
   compare-entries, compare-registered — builds sides from registered
   extension-map entries and RE-DERIVES the committed descriptor-root from the
   stored (normalised) capability, failing closed on any mismatch. :exact is
   reachable through this bridge only between entries whose committed roots
   genuinely coincide, so a capability cannot self-assert exact comparability
   against a fabricated root."
  (:require [resolver-sim.composition.contract :as contract]
            [resolver-sim.extensions.manifest :as em]
            [resolver-sim.hash.canonical :as hc]))

(def comparability-domain-tag
  "COMPARABILITY_SHARED_V1")

(defn- side-contract
  [{:keys [capability]}]
  (let [c (:composition-contract capability)]
    (when (and (map? c) (:valid? (contract/validate-composition-contract c)))
      (contract/normalize-contract c))))

(defn- checks
  "Dimension checks between two capability sides."
  [l r]
  (let [ld (:capability l) rd (:capability r)
        lc (side-contract l) rc (side-contract r)]
    [{:check/id :identity
      :left (:capability/id ld) :right (:capability/id rd)
      :match? (= (:capability/id ld) (:capability/id rd))}
     {:check/id :version
      :left (:capability/version ld) :right (:capability/version rd)
      :match? (= (:capability/version ld) (:capability/version rd))}
     {:check/id :role
      :left (:composition/roles lc) :right (:composition/roles rc)
      :match? (and lc rc (= (:composition/roles lc) (:composition/roles rc)))}
     {:check/id :input-semantic
      :left (get-in lc [:composition/input :semantic-type])
      :right (get-in rc [:composition/input :semantic-type])
      :match? (and lc rc
                   (= (get-in lc [:composition/input :semantic-type])
                      (get-in rc [:composition/input :semantic-type])))}
     {:check/id :output-semantic
      :left (get-in lc [:composition/output :semantic-type])
      :right (get-in rc [:composition/output :semantic-type])
      :match? (and lc rc
                   (= (get-in lc [:composition/output :semantic-type])
                      (get-in rc [:composition/output :semantic-type])))}
     {:check/id :output-schema
      :left (:output-schema ld) :right (:output-schema rd)
      :match? (and (:output-schema ld) (:output-schema rd)
                   (= (:output-schema ld) (:output-schema rd)))}
     {:check/id :failure-mode
      :left (get-in lc [:composition/control :failure-mode])
      :right (get-in rc [:composition/control :failure-mode])
      :match? (and lc rc
                   (= (get-in lc [:composition/control :failure-mode])
                      (get-in rc [:composition/control :failure-mode])))}
     {:check/id :determinism
      :left (get-in lc [:composition/determinism :required?])
      :right (get-in rc [:composition/determinism :required?])
      :match? (and lc rc
                   (= (get-in lc [:composition/determinism :required?])
                      (get-in rc [:composition/determinism :required?])))}
     {:check/id :effects
      :left (get-in lc [:composition/effects :emits])
      :right (get-in rc [:composition/effects :emits])
      :match? (and lc rc
                   (= (get-in lc [:composition/effects :emits])
                      (get-in rc [:composition/effects :emits])))}]))

(defn- shared-root
  [class l checks]
  (case class
    :exact (:capability-root l)
    (:compatible-direct :compatible-normalized :partial)
    (hc/domain-hash
     comparability-domain-tag
     (into [] (keep (fn [{:keys [check/id match?]}]
                      (when match? id)))
           checks))
    nil))

(defn compare-capabilities
  "Evaluate comparability between two capability sides.

   Each side is {:capability <descriptor> :capability-root <descriptor-root>}.
   opts — {:normalization-root <optional content address>}

   Returns
     {:comparability/status :evaluated|:unknown
      :comparability/class :exact|:compatible-normalized|:compatible-direct|
                            :partial|:incomparable|:unknown
      :left/root ... :right/root ...
      :shared-contract-root <hash-or-nil>
      :normalization-root <opts-or-nil>
      :checks [...] :reason <string-or-nil>}

   :compatible-normalized requires an explicitly committed
   :normalization-root; :compatible-direct does not. Both require the role
   dimension to be evaluable on both sides and every semantic dimension to
   match. A role conflict is :incomparable. Malformed or missing comparison
   evidence is non-passing (:unknown)."
  [l r & [opts]]
  (let [lc (side-contract l) rc (side-contract r)
        checks (checks l r)
        semantic (:match? (first (filter #(= :output-semantic (:check/id %)) checks)))
        schema (:match? (first (filter #(= :output-schema (:check/id %)) checks)))
        effects (:match? (first (filter #(= :effects (:check/id %)) checks)))
        failure (:match? (first (filter #(= :failure-mode (:check/id %)) checks)))
        determinism (:match? (first (filter #(= :determinism (:check/id %)) checks)))
        input (:match? (first (filter #(= :input-semantic (:check/id %)) checks)))
        role (:match? (first (filter #(= :role (:check/id %)) checks)))
        l-roles (get-in lc [:composition/roles]) r-roles (get-in rc [:composition/roles])
        role-conflict? (and (seq l-roles) (seq r-roles) (not role))
        role-unevaluable? (or (empty? l-roles) (empty? r-roles))
        {:keys [status class reason]}
        (cond
          (or (nil? lc) (nil? rc))
          {:status :unknown :class :unknown :reason "missing or malformed composition contract"}

          (= (:capability-root l) (:capability-root r))
          {:status :evaluated :class :exact :reason nil}

          role-conflict?
          {:status :evaluated :class :incomparable
           :reason "role dimension conflict"}

          ;; :compatible-* = fully substitutable on the committed semantic
          ;; surface; identity differs only in name, not semantics
          (and input semantic schema effects failure determinism)
          (if role-unevaluable?
            {:status :unknown :class :unknown
             :reason "role dimension unevaluable; roles must be declared for a compatible classification"}
            (if (:normalization-root opts)
              {:status :evaluated :class :compatible-normalized :reason nil}
              {:status :evaluated :class :compatible-direct
               :reason "semantically compatible; no explicit normalization declared"}))

          (and semantic schema)
          {:status :evaluated :class :partial
           :reason "comparable on the output projection; other dimensions differ"}

          :else
          {:status :evaluated :class :incomparable
           :reason "input/output domain or schema mismatch"})]
    {:comparability/status status
     :comparability/class class
     :left/root (:capability-root l)
     :right/root (:capability-root r)
     :shared-contract-root (shared-root class l checks)
     :normalization-root (:normalization-root opts)
     :checks checks
     :reason reason}))

(defn compare-plans
  "Evaluate comparability between two compiled composition plans.
   Exact when the plan roots are identical; otherwise compares the committed
   graph output contracts and effect merge strategy. Fails closed: absent or
   malformed output contracts or merge strategies are :unknown, never a
   successful class. Plans carry no normalization concept, so the non-exact
   successful class is :compatible-direct."
  [l r]
  (let [l-root (:plan/root l) r-root (:plan/root r)
        l-out (:plan/output-contract l) r-out (:plan/output-contract r)
        l-sem (get-in l-out [:semantic-type]) r-sem (get-in r-out [:semantic-type])
        l-merge (:plan/effect-merge-strategy l) r-merge (:plan/effect-merge-strategy r)
        evidence-ok? (and (map? l) (map? r)
                          (map? l-out) (map? r-out)
                          (some? l-sem) (some? r-sem)
                          (some? l-merge) (some? r-merge))]
    (cond
      (and (map? l) (map? r) (= l-root r-root))
      {:comparability/status :evaluated
       :comparability/class :exact
       :left/root l-root :right/root r-root
       :shared-contract-root l-root :normalization-root nil :checks [] :reason nil}

      (not evidence-ok?)
      {:comparability/status :unknown
       :comparability/class :unknown
       :left/root l-root :right/root r-root
       :shared-contract-root nil :normalization-root nil :checks []
       :reason "missing or malformed comparison evidence"}

      (and (= l-sem r-sem) (= l-merge r-merge))
      {:comparability/status :evaluated
       :comparability/class :compatible-direct
       :left/root l-root :right/root r-root
       :shared-contract-root nil :normalization-root nil :checks [] :reason nil}

      (= l-sem r-sem)
      {:comparability/status :evaluated
       :comparability/class :partial
       :left/root l-root :right/root r-root
       :shared-contract-root nil :normalization-root nil :checks [] :reason "merge strategy differs"}

      :else
      {:comparability/status :evaluated
       :comparability/class :incomparable
       :left/root l-root :right/root r-root
       :shared-contract-root nil :normalization-root nil :checks [] :reason "output contract differs"})))

;; ── registry bridge (register / register-capability) ──────────────────────

(defn side-from-entry
  "Build a comparability side from a registered extension-map entry
   ({:capability <normalised descriptor> :descriptor-root <committed root>}).

   The committed descriptor-root is RE-DERIVED from the stored (normalised)
   capability and must match the entry's :descriptor-root; a mismatch means the
   registry holds an inconsistent root and fails closed
   (:comparability/error-inconsistent-entry). This is the path that guarantees
   a capability cannot self-assert exact comparability: :exact is reachable
   only between entries whose committed roots genuinely coincide."
  [entry]
  (let [capability (:capability entry)
        committed (:descriptor-root entry)
        re-derived (em/capability-descriptor-root capability)]
    (when-not (= committed re-derived)
      (throw (ex-info "extension: registered entry has an inconsistent descriptor root"
                      {:error :comparability/error-inconsistent-entry
                       :committed committed
                       :re-derived re-derived})))
    {:capability capability
     :capability-root committed}))

(defn compare-entries
  "Evaluate comparability between two registered extension-map entries,
   building committed sides (never caller-supplied roots)."
  [l r]
  (compare-capabilities (side-from-entry l) (side-from-entry r)))

(defn compare-registered
  "Evaluate comparability between two registered capabilities in an
   extension-map, resolved by [capability-kind capability-id] key.

   Throws :comparability/error-unresolved-capability when a key does not
   resolve in the extension-map."
  [extension-map l-key r-key]
  (let [l (get extension-map l-key)
        r (get extension-map r-key)]
    (when-not (and l r)
      (throw (ex-info "extension: unresolvable capability key for comparability"
                      {:error :comparability/error-unresolved-capability
                       :left l-key :right r-key
                       :left-resolved? (some? l)
                       :right-resolved? (some? r)})))
    (compare-entries l r)))
