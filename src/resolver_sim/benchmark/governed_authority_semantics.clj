(ns resolver-sim.benchmark.governed-authority-semantics
  "C4a explicit, closed identity for the existing frozen-material C1 semantics."
  (:require [resolver-sim.benchmark.governed-authority-resolution :as resolution]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema "governed-authority-semantics.v1")
(def ^:const domain :governed-authority-semantics-v1)
(def fields #{:artifact/schema :authority-resolver/root :evaluator/profile
              :signature-verification/profile :position-time/profile
              :governance-evaluation/profile})
(def v1-profiles {:evaluator/profile :governed-authority/frozen-material-v1
                  :signature-verification/profile :ed25519-signer-key-set-v1
                  :position-time/profile :governed-authority-position-time-index-v1
                  :governance-evaluation/profile :review-governance-frozen-v1})

(defn semantics-root [descriptor]
  (ref/sha256-ref (hc/domain-hash domain
                                  (hc/project-canonical-safe
                                   (dissoc descriptor :governed-authority-semantics/root)))))

(defn validate-semantics [descriptor]
  (let [root (:authority-resolver/root descriptor)
        errors (cond-> []
                 (not= schema (:artifact/schema descriptor)) (conj "invalid semantics schema")
                 (not= fields (set (keys (dissoc descriptor :governed-authority-semantics/root)))) (conj "semantics has missing or unknown keys")
                 (nil? (resolution/recognized-resolver-descriptor root)) (conj "resolver is not recognized")
                 (not= v1-profiles (select-keys descriptor (keys v1-profiles))) (conj "unsupported evaluator semantics profile")
                 (and (contains? descriptor :governed-authority-semantics/root)
                      (not= (:governed-authority-semantics/root descriptor) (semantics-root descriptor)))
                 (conj "semantics root mismatch"))]
    {:valid? (empty? errors) :errors (vec errors)}))

(defn build-semantics [descriptor]
  (let [base (assoc descriptor :artifact/schema schema)
        result (validate-semantics base)]
    (when-not (:valid? result)
      (throw (ex-info "governed-authority semantics are invalid" result)))
    (assoc base :governed-authority-semantics/root (semantics-root base))))

(def default-semantics
  (build-semantics (assoc v1-profiles
                          :authority-resolver/root
                          (:governed-authority-resolver/root resolution/default-resolver))))

(defn- supported-v1-semantics? [semantics]
  (= default-semantics semantics))

(defn evaluate-authority-with-semantics
  "Fail-closed semantics dispatcher. The only supported V1 descriptor invokes
   the existing frozen-material evaluator; no callback or runtime resolver can
   alter evaluation behavior."
  [semantics inputs]
  (let [validation (validate-semantics semantics)
        dispatchable? (and (:valid? validation)
                           (contains? semantics :governed-authority-semantics/root)
                           (supported-v1-semantics? semantics))]
    (if-not dispatchable?
      {:authority-status :not-authorised
       :authority-semantics-valid? false
       :authority/reasons [:authority-semantics-unavailable]}
      (assoc (state/evaluate-authority-with-frozen-material inputs)
             :authority-semantics/root (:governed-authority-semantics/root semantics)
             :authority-semantics-valid? true))))
