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

(defn- capability-key?
  [value]
  (and (vector? value)
       (= 2 (count value))
       (every? keyword? value)))

(defn- extension-member?
  [member]
  (contains? member :member/capability))

(defn- valid-member?
  [member]
  (let [provider-roots (:member/provider-package-roots member)]
    (and (map? member)
         (keyword? (:member/id member))
         (string? (:member/contract member))
         (root? (:member/input-root member))
         (root? (:member/parameters-root member))
         (map? (:member/expected-outputs member))
         (or (not (extension-member? member))
             (and (capability-key? (:member/capability member))
                  (or (not (contains? member :member/provider-package-roots))
                      (and (vector? provider-roots)
                           (seq provider-roots)
                           (every? string? provider-roots))))))))

(defn- canonical-members [members]
  (when-not (and (vector? members) (seq members) (every? valid-member? members))
    (throw (ex-info "Research pack members are malformed"
                    {:error :research-pack/invalid-member})))
  (let [ordered (vec (sort-by :member/id members))]
    (when-not (= (count ordered) (count (set (map :member/id ordered))))
      (throw (ex-info "Research pack member ids must be unique"
                      {:error :research-pack/duplicate-member-id})))
    ordered))

(defn- resolved-members
  "Derive exact provider package roots for extension-contributed members from
   the authoritative capability-resolution snapshot. Member declarations name
   only a semantic capability; they never nominate a provider."
  [members capability-providers]
  (mapv (fn [member]
          (if-not (extension-member? member)
            member
            (let [providers (get-in capability-providers
                                    [(:member/capability member) :providers])
                  roots (mapv :package-root providers)]
              (when-not (seq roots)
                (throw (ex-info "Extension benchmark member capability is unresolved"
                                {:error :research-pack/unavailable-extension-member
                                 :member/id (:member/id member)
                                 :member/capability (:member/capability member)})))
              (assoc member :member/provider-package-roots roots))))
        members))

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
          members (canonical-members
                   (resolved-members members
                                     (:extensions/capability-providers (:resolution comp))))
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
             :research-pack/composition (composition/portable-body comp)))))

(defn validate-pack
  "Verify a persisted frozen plan using only its closed portable composition.
   Current provider availability is intentionally checked separately."
  [pack]
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
      (let [portable (composition/verify-portable! (:research-pack/composition pack))]
        (when-not (= (:research-pack/composition-root pack)
                     (:semantic-composition/root portable))
          (swap! errors conj :research-pack/composition-substitution))
        (when-not (= (:research-pack/resolution-root pack)
                     (:semantic-composition/resolution-root portable))
          (swap! errors conj :research-pack/resolution-substitution))
        (let [derived-members (canonical-members
                               (resolved-members members
                                                 (:semantic-composition/capability-providers portable)))]
          (when-not (= members derived-members)
            (swap! errors conj :research-pack/member-provider-substitution))))
      (catch Exception _
        (swap! errors conj :research-pack/invalid-portable-composition)))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn verify-execution-environment
  "Check whether an already-valid frozen pack can run in an explicitly supplied
   current extension environment. This never changes historical pack validity."
  [pack extension-map resolution-options]
  (let [artifact (validate-pack pack)]
    (if-not (:valid? artifact)
      {:ready? false :classification :invalid-pack :verification artifact}
      (let [result (composition/compose-authoritative :development
                                                      (:research-pack/requested-capabilities pack)
                                                      resolution-options extension-map)]
        (if-not (:valid? result)
          {:ready? false :classification :unavailable :verification artifact
           :violations (:violations result)}
          (let [members (canonical-members
                         (resolved-members (:research-pack/members pack)
                                           (:extensions/capability-providers
                                            (:resolution (:composition result)))))]
            (if (= members (:research-pack/members pack))
              {:ready? true :classification :ready :verification artifact}
              {:ready? false :classification :execution-environment-mismatch
               :verification artifact})))))))
