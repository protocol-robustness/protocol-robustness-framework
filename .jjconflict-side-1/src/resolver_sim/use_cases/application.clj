(ns resolver-sim.use-cases.application
  "Portable, rooted use-case application assertions.

   Sequence framing commits ordered binding roots only. Application validation
   separately establishes declared-step correspondence, descriptor identity,
   and direct linear root continuity. It never resolves executable Vars."
  (:require [resolver-sim.extensions.manifest :as manifest]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.hash.sequence :as sequence]
            [resolver-sim.use-cases.registry :as use-cases]))

(def application-schema :prf/use-case-application.v1)
(def capability-binding-schema :prf/capability-invocation-binding.v1)
(def sequence-purpose :use-case-application/capability-bindings)
(def composition :consecutive)

(defn- root [domain value]
  (hash-ref/sha256-ref (canonical/domain-hash domain value)))

(defn capability-invocation-binding
  [{:keys [capability input-root output-root evidence-roots]}]
  (let [binding {:binding/schema capability-binding-schema
                 :capability/kind (:capability/kind capability)
                 :capability/id (:capability/id capability)
                 :capability/version (:capability/version capability)
                 :capability/descriptor-root
                 (hash-ref/sha256-ref (manifest/capability-descriptor-root capability))
                 :invocation/input-root input-root
                 :invocation/output-root output-root
                 :invocation/evidence-roots (vec (sort evidence-roots))}]
    (assoc binding :binding/root (root :capability-invocation-binding-v1 binding))))

(defn sequence-binding [binding-roots]
  (let [options {:purpose sequence-purpose
                 :expected-component-count (count binding-roots)}]
    {:bound-sequence (sequence/bound-sequence options binding-roots)
     :bound-sequence-root
     (hash-ref/sha256-ref (sequence/sequence-hash options binding-roots))}))

(defn application-root [application]
  (root :use-case-application-v1 (dissoc application :application/root)))

(defn- fail! [reason data]
  (throw (ex-info "Invalid use-case application construction" (assoc data :reason reason))))

(defn build-application
  "Build an assertion from positional invocation bindings and descriptors.
   The selected definition derives step IDs and its own root; callers cannot
   supply either as independent authority."
  [{:keys [loaded use-case-id application-id invocation-bindings capability-descriptors]}]
  (let [definition (get (use-cases/use-case-index loaded) use-case-id)
        steps (use-cases/implemented-step-ids definition)]
    (when-not definition (fail! :application/unknown-use-case {:use-case/id use-case-id}))
    (when-not (and (vector? invocation-bindings) (vector? capability-descriptors)
                   (seq invocation-bindings)
                   (= (count steps) (count invocation-bindings) (count capability-descriptors)))
      (fail! :application/companion-vector-cardinality-mismatch
             {:step-count (count steps) :binding-count (count invocation-bindings)
              :descriptor-count (count capability-descriptors)}))
    (let [wrappers (mapv (fn [step binding] {:application.step-id step
                                             :capability-binding-root (:binding/root binding)})
                         steps invocation-bindings)
          app {:application/schema application-schema
               :application/id application-id
               :application/use-case {:id use-case-id
                                      :registry-root (:use-case-registry/root loaded)
                                      :definition-root (use-cases/definition-root definition)}
               :application/composition composition
               :application/capability-bindings wrappers
               :application/sequence-binding
               (sequence-binding (mapv :capability-binding-root wrappers))}]
      (assoc app :application/root (application-root app)))))

(defn- issue [code & {:as data}] (assoc data :code code))
(defn- valid-root? [value] (hash-ref/valid-sha256-ref? value))
(def capability-identity-keys [:capability/kind :capability/id :capability/version])
(defn- capability-identity [value]
  (select-keys value capability-identity-keys))
(defn- valid-invocation-binding? [binding]
  (and (= capability-binding-schema (:binding/schema binding))
       (= (:binding/root binding)
          (root :capability-invocation-binding-v1 (dissoc binding :binding/root)))
       (valid-root? (:binding/root binding))
       (valid-root? (:invocation/input-root binding))
       (valid-root? (:invocation/output-root binding))
       (vector? (:invocation/evidence-roots binding))
       (= (count (:invocation/evidence-roots binding))
          (count (set (:invocation/evidence-roots binding))))
       (= (:invocation/evidence-roots binding)
          (vec (sort (:invocation/evidence-roots binding))))
       (every? valid-root? (:invocation/evidence-roots binding))))

(defn- continuity-issues [bindings]
  (vec (keep-indexed
        (fn [index [left right]]
          (when-not (= (:invocation/output-root left) (:invocation/input-root right))
            (issue :application/consecutive-invocation-root-mismatch
                   :left-index index :right-index (inc index))))
        (partition 2 1 bindings))))

(defn valid-application
  "Validate application wrappers, invocation bindings, and descriptors by exact
   shared vector position. No collection is searched or silently truncated."
  [loaded invocation-bindings capability-descriptors application]
  (let [use-case-id (get-in application [:application/use-case :id])
        definition (get (use-cases/use-case-index loaded) use-case-id)
        steps (when definition (use-cases/implemented-step-ids definition))
        requirements (when definition (:use-case/step-capabilities definition))
        wrappers (:application/capability-bindings application)
        expected-sequence (when (vector? wrappers)
                            (try (sequence-binding (mapv :capability-binding-root wrappers))
                                 (catch Exception _ nil)))
        counts [(count wrappers) (count invocation-bindings) (count capability-descriptors)]
        cardinal? (and (vector? wrappers) (vector? invocation-bindings)
                       (vector? capability-descriptors) (seq wrappers)
                       (= 1 (count (set counts))))
        positional-issues
        (when cardinal?
          (mapcat (fn [wrapper binding descriptor requirement]
                    (concat
                     (when-not (= (:capability-binding-root wrapper) (:binding/root binding))
                       [(issue :application/wrapper-binding-root-mismatch)])
                     (when-not (:valid? (manifest/validate-capability descriptor))
                       [(issue :application/invalid-capability-descriptor)])
                     (when-not (= (hash-ref/sha256-ref (manifest/capability-descriptor-root descriptor))
                                  (:capability/descriptor-root binding))
                       [(issue :application/capability-descriptor-root-mismatch)])
                     (when-not (= (capability-identity descriptor) (capability-identity binding))
                       [(issue :application/descriptor-binding-identity-mismatch)])
                     (when-not (= (capability-identity (:capability requirement))
                                  (capability-identity binding))
                       [(issue :application/step-capability-mismatch)])))
                  wrappers invocation-bindings capability-descriptors requirements))
        issues (vec (concat
                     (when-not (= application-schema (:application/schema application))
                       [(issue :application/unsupported-schema)])
                     (when-not (= composition (:application/composition application))
                       [(issue :application/unsupported-composition)])
                     (when-not definition [(issue :application/unknown-use-case)])
                     (when-not (= (:use-case-registry/root loaded)
                                  (get-in application [:application/use-case :registry-root]))
                       [(issue :application/registry-root-mismatch)])
                     (when (and definition
                                (not= (use-cases/definition-root definition)
                                      (get-in application [:application/use-case :definition-root])))
                       [(issue :application/definition-root-mismatch)])
                     (when-not cardinal? [(issue :application/companion-vector-cardinality-mismatch
                                                 :counts counts)])
                     (when (and cardinal? (not= steps (mapv :application.step-id wrappers)))
                       [(issue :application/step-binding-order-mismatch)])
                     positional-issues
                     (when-not (every? valid-invocation-binding? invocation-bindings)
                       [(issue :application/invalid-invocation-binding)])
                     (continuity-issues invocation-bindings)
                     (when-not (= expected-sequence (:application/sequence-binding application))
                       [(issue :application/sequence-binding-mismatch)])
                     (when-not (= (application-root application) (:application/root application))
                       [(issue :application/root-mismatch)])))]
    {:valid? (empty? issues) :issues issues}))

(defn validate-completeness-at-root [loaded invocation-bindings capability-descriptors application]
  (let [validation (valid-application loaded invocation-bindings capability-descriptors application)]
    {:complete? (:valid? validation)
     :status (if (:valid? validation) :complete :incomplete)
     :reasons (:issues validation)}))
