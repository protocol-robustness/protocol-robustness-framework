(ns resolver-sim.composition.comparability
  "Comparability: whether two capabilities or compiled compositions may be
   meaningfully compared. Distinct from composability — two capabilities may
   compose while producing outputs that are not comparable, and two may be
   comparable alternatives without being valid sequential stages.

   Comparability is derived from committed contracts and compiled plans; a
   capability cannot self-assert exact comparability. Classes: :exact,
   :compatible (through an explicit normalization), :partial (with an
   identified projection), :incomparable, and :unknown (missing or malformed
   comparison evidence — non-passing)."
  (:require [resolver-sim.composition.contract :as contract]
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
    (:compatible :partial)
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
      :comparability/class :exact|:compatible|:partial|:incomparable|:unknown
      :left/root ... :right/root ...
      :shared-contract-root <hash-or-nil>
      :normalization-root <opts-or-nil>
      :checks [...] :reason <string-or-nil>}

   Malformed or missing comparison evidence is non-passing (:unknown)."
  [l r & [opts]]
  (let [lc (side-contract l) rc (side-contract r)
        checks (checks l r)
        semantic (:match? (first (filter #(= :output-semantic (:check/id %)) checks)))
        schema (:match? (first (filter #(= :output-schema (:check/id %)) checks)))
        effects (:match? (first (filter #(= :effects (:check/id %)) checks)))
        failure (:match? (first (filter #(= :failure-mode (:check/id %)) checks)))
        determinism (:match? (first (filter #(= :determinism (:check/id %)) checks)))
        input (:match? (first (filter #(= :input-semantic (:check/id %)) checks)))
        {:keys [status class reason]}
        (cond
          (or (nil? lc) (nil? rc))
          {:status :unknown :class :unknown :reason "missing or malformed composition contract"}

          (= (:capability-root l) (:capability-root r))
          {:status :evaluated :class :exact :reason nil}

          ;; :compatible = fully substitutable on the committed semantic
          ;; surface; identity differs only in name, not semantics
          (and input semantic schema effects failure determinism)
          {:status :evaluated :class :compatible
           :reason (if (:normalization-root opts)
                     nil
                     "semantically compatible; no explicit normalization declared")}

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
   graph output contracts and effect merge strategy."
  [l r]
  (let [l-root (:plan/root l) r-root (:plan/root r)
        l-out (:plan/output-contract l) r-out (:plan/output-contract r)
        l-sem (get-in l-out [:semantic-type]) r-sem (get-in r-out [:semantic-type])
        l-merge (:plan/effect-merge-strategy l) r-merge (:plan/effect-merge-strategy r)]
    (cond
      (= l-root r-root)
      {:comparability/status :evaluated
       :comparability/class :exact
       :left/root l-root :right/root r-root
       :shared-contract-root l-root :normalization-root nil :checks [] :reason nil}

      (and (= l-sem r-sem) (= l-merge r-merge))
      {:comparability/status :evaluated
       :comparability/class :compatible
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
