(ns resolver-sim.benchmark.research-pack
  "Frozen, extension-resolved research benchmark pack planning.

   Composition is extensible before `freeze-pack`; the resulting plan is an
   immutable exact member set.  Execution remains owned by the shared benchmark
   runner and must consume only this frozen data."
  (:require [resolver-sim.composition.semantic :as composition]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "research-benchmark-pack.v1")
(def ^:const reducer-contract "research-pack-reducer.v1")

(defn- root? [x] (hash-ref/valid-sha256-ref? x))

(defn- canonical-members [members]
  (when-not (and (vector? members) (seq members)
                 (every? #(and (map? %) (keyword? (:member/id %))
                               (string? (:member/contract %))
                               (root? (:member/input-root %))
                               (root? (:member/parameters-root %))
                               (map? (:member/expected-outputs %)))
                         members))
    (throw (ex-info "Research pack members are malformed"
                    {:error :research-pack/invalid-member})))
  (let [ordered (vec (sort-by :member/id members))]
    (when-not (= (count ordered) (count (set (map :member/id ordered))))
      (throw (ex-info "Research pack member ids must be unique"
                      {:error :research-pack/duplicate-member-id})))
    ordered))

(defn pack-root [pack]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-benchmark-pack
                   (dissoc pack :research-pack/root :research-pack/composition :errors))))

(defn freeze-pack
  "Resolve requested extension contributions against the supplied immutable
   extension map, then freeze an exact pack-plan.  Callers cannot provide the
   composition root or provider identities: `compose-authoritative` derives
   both from authoritative resolution."
  [{:keys [pack-id command-root assignment-root plan-root members
           requested-capabilities profile resolution-options extension-map]}]
  (when-not (and (keyword? pack-id) (root? command-root) (root? assignment-root)
                 (root? plan-root) (map? extension-map))
    (throw (ex-info "Research pack requires rooted identity inputs and an extension map"
                    {:error :research-pack/invalid-input})))
  (let [members (canonical-members members)
        requested (vec (sort requested-capabilities))
        resolved (composition/compose-authoritative (or profile :development)
                                                    requested resolution-options extension-map)]
    (when-not (:valid? resolved)
      (throw (ex-info "Research pack extension resolution failed"
                      {:error :research-pack/unavailable-or-unsupported-extension
                       :violations (:violations resolved)})))
    (let [comp (:composition resolved)
          base {:schema-version schema-version
                :research-pack/id pack-id
                :research-pack/command-root command-root
                :research-pack/assignment-root assignment-root
                :research-pack/plan-root plan-root
                :research-pack/members members
                :research-pack/requested-capabilities requested
                :research-pack/reducer-contract reducer-contract
                :research-pack/composition-root (composition/composition-root comp)
                :research-pack/resolution-root (composition/resolution-root comp)}]
      (assoc base :research-pack/root (pack-root base)
             :research-pack/composition (into {} comp)))))

(defn validate-pack
  "Fail-closed verification of a frozen plan against the extension-map snapshot
   that it claims to use. Re-resolution detects removed, ambiguous, or
   substituted providers and extension members injected after freeze."
  [pack extension-map resolution-options]
  (let [errors (atom [])
        members (:research-pack/members pack)]
    (when-not (= schema-version (:schema-version pack))
      (swap! errors conj :research-pack/unsupported-schema))
    (try
      (canonical-members members)
      (catch Exception _ (swap! errors conj :research-pack/invalid-members)))
    (when-not (= reducer-contract (:research-pack/reducer-contract pack))
      (swap! errors conj :research-pack/unsupported-reducer))
    (when-not (= (:research-pack/root pack) (pack-root pack))
      (swap! errors conj :research-pack/root-mismatch))
    (try
      (let [result (composition/compose-authoritative :development
                                                      (:research-pack/requested-capabilities pack)
                                                      resolution-options extension-map)]
        (if-not (:valid? result)
          (swap! errors conj :research-pack/unavailable-or-unsupported-extension)
          (let [comp (:composition result)]
            (when-not (= (:research-pack/composition-root pack)
                         (composition/composition-root comp))
              (swap! errors conj :research-pack/composition-substitution))
            (when-not (= (:research-pack/resolution-root pack)
                         (composition/resolution-root comp))
              (swap! errors conj :research-pack/resolution-substitution)))))
      (catch Exception _
        (swap! errors conj :research-pack/unavailable-or-unsupported-extension)))
    {:valid? (empty? @errors) :errors (vec @errors)}))
