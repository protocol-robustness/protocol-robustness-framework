(ns resolver-sim.use-cases.application
  "Rooted, declarative use-case applications. Sequence framing establishes order;
   this namespace separately validates declared application continuity."
  (:require [resolver-sim.extensions.manifest :as manifest]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.hash.sequence :as sequence]
            [resolver-sim.use-cases.registry :as use-cases]))

(def application-schema :prf/use-case-application.v1)
(def capability-binding-schema :prf/capability-invocation-binding.v1)
(def sequence-purpose :use-case-application/sequence)
(def composition :consecutive)

(defn- root [domain value]
  (hash-ref/sha256-ref (canonical/domain-hash domain value)))

(defn capability-invocation-binding [{:keys [capability input-root output-root evidence-roots]}]
  (let [binding {:binding/schema capability-binding-schema
                 :capability/kind (:capability/kind capability)
                 :capability/id (:capability/id capability)
                 :capability/version (:capability/version capability)
                 :capability/descriptor-root (hash-ref/sha256-ref (manifest/capability-descriptor-root capability))
                 :invocation/input-root input-root
                 :invocation/output-root output-root
                 :invocation/evidence-roots (vec (sort evidence-roots))}]
    (assoc binding :binding/root (root :capability-invocation-binding-v1 binding))))

(defn sequence-binding [binding-roots]
  (let [options {:purpose sequence-purpose :expected-component-count (count binding-roots)}
        bound (sequence/bound-sequence options binding-roots)]
    {:bound-sequence bound
     :bound-sequence-root (hash-ref/sha256-ref (sequence/sequence-hash options binding-roots))}))

(defn application-root [application]
  (root :use-case-application-v1 (dissoc application :application/root)))

(defn build-application [{:keys [loaded use-case-id application-id capability-invocations]}]
  (let [bindings (mapv capability-invocation-binding capability-invocations)
        app {:application/schema application-schema
             :application/id application-id
             :application/use-case {:id use-case-id :registry-root (:use-case-registry/root loaded)}
             :application/composition composition
             :application/capability-bindings bindings
             :application/sequence-binding (sequence-binding (mapv :binding/root bindings))}]
    (assoc app :application/root (application-root app))))

(defn- issue [code & {:as data}] (assoc data :code code))
(defn- valid-root? [x] (hash-ref/valid-sha256-ref? x))
(defn- allowed? [definition binding]
  (some #(= (select-keys % [:capability/kind :capability/id :capability/version])
            (select-keys binding [:capability/kind :capability/id :capability/version]))
        (:use-case/allowed-capabilities definition)))
(defn- continuity-issues [bindings]
  (vec (keep-indexed (fn [index [left right]]
                       (when-not (= (:invocation/output-root left) (:invocation/input-root right))
                         (issue :application/consecutive-invocation-root-mismatch
                                :left-index index :right-index (inc index))))
                     (partition 2 1 bindings))))

(defn valid-application [loaded capability-descriptors application]
  (let [definition (get (use-cases/use-case-index loaded) (get-in application [:application/use-case :id]))
        bindings (:application/capability-bindings application)
        supplied-sequence-binding (:application/sequence-binding application)
        binding-issues (mapcat (fn [binding descriptor]
                                 (let [expected (try (capability-invocation-binding {:capability descriptor :input-root (:invocation/input-root binding) :output-root (:invocation/output-root binding) :evidence-roots (:invocation/evidence-roots binding)}) (catch Exception _ nil))]
                                   (concat (when-not expected [(issue :application/invalid-capability-binding)])
                                           (when (and expected (not= expected binding)) [(issue :application/capability-binding-mismatch)])
                                           (when (and definition (not (allowed? definition binding))) [(issue :application/capability-not-allowed)]))))
                               bindings capability-descriptors)
        roots (mapv :binding/root bindings)
        expected-sequence (try (sequence-binding roots) (catch Exception _ nil))
        issues (vec (concat
                     (when-not (= application-schema (:application/schema application)) [(issue :application/unsupported-schema)])
                     (when-not (= composition (:application/composition application)) [(issue :application/unsupported-composition)])
                     (when-not definition [(issue :application/unknown-use-case)])
                     (when-not (= (:use-case-registry/root loaded) (get-in application [:application/use-case :registry-root])) [(issue :application/registry-root-mismatch)])
                     (when-not (and (vector? bindings) (seq bindings)) [(issue :application/invalid-capability-bindings)])
                     binding-issues
                     (when-not (every? #(and (valid-root? (:invocation/input-root %)) (valid-root? (:invocation/output-root %)) (every? valid-root? (:invocation/evidence-roots %))) bindings) [(issue :application/invalid-invocation-root)])
                     (continuity-issues bindings)
                     (when-not (= expected-sequence supplied-sequence-binding) [(issue :application/sequence-binding-mismatch)])
                     (when-not (= (application-root application) (:application/root application)) [(issue :application/root-mismatch)])))]
    {:valid? (empty? issues) :issues issues}))

(defn validate-completeness-at-root [loaded capability-descriptors application]
  (let [validation (valid-application loaded capability-descriptors application)]
    {:complete? (:valid? validation) :status (if (:valid? validation) :complete :incomplete) :reasons (:issues validation)}))
