(ns resolver-sim.protocols.sew.resolution-basis
  "Canonical, dispute-bound authority context for SEW resolution.

   This artifact answers under what authority, state, dispute, resolver, and
   immutable module context a decision may be made. It deliberately contains no
   decision outcome or decision-input payload; those belong to the subsequent
   sew-resolution-decision artifact."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema "sew-resolution-basis.v1")
(def ^:const domain :sew-resolution-basis-v1)
(def ^:const purpose :sew-dispute-resolution)

(def authority-modes #{:primary :fallback})

(def ^:private required-fields
  #{:artifact/schema
    :resolution/purpose
    :workflow/id
    :escrow/root
    :state-before/root
    :dispute/question-root
    :dispute/root
    :dispute/evidence-set-root
    :authority/resolution-basis-root
    :authority/context-root
    :resolver/root
    :resolver/kind
    :resolver/authorization-root
    :resolution-module/root
    :resolution-module/snapshot-root
    :configuration/root
    :resolution/authority-mode})

(def ^:private root-key :sew-resolution-basis/root)

(declare validate-resolution-basis)

(def ^:private root-fields
  (disj required-fields :artifact/schema :resolution/purpose :workflow/id
        :resolver/kind :resolution/authority-mode))

(defn basis-payload
  "Exact, outcome-free projection committed by a SEW resolution basis."
  [basis]
  (dissoc basis root-key))

(defn resolution-basis-root
  "Return the canonical root after validating a complete basis artifact."
  [basis]
  (let [validation (validate-resolution-basis basis)]
    (when-not (:valid? validation)
      (throw (ex-info "sew-resolution-basis.v1 is invalid"
                      {:type :sew-resolution-basis/invalid
                       :errors (:errors validation)})))
    (ref/sha256-ref
     (hc/domain-hash domain
                     (hc/project-canonical-safe (basis-payload basis))))))

(defn basis-errors
  "Return closed-shape, type, and root-integrity errors for a basis.

   External reference membership and authorization proof verification are
   intentionally separate admission steps; this artifact validates the exact
   commitments that those verifiers must resolve."
  [basis]
  (if-not (map? basis)
    [:invalid-basis]
    (let [have (set (keys basis))
          allowed (conj required-fields root-key)
          missing (set/difference required-fields have)
          extra (set/difference have allowed)
          root (:sew-resolution-basis/root basis)]
      (cond-> []
        (seq missing) (conj :missing-required-field)
        (seq extra) (conj :unknown-basis-field)
        (not= schema (:artifact/schema basis)) (conj :invalid-schema)
        (not= purpose (:resolution/purpose basis)) (conj :invalid-purpose)
        (not (and (integer? (:workflow/id basis))
                  (not (neg? (:workflow/id basis)))))
        (conj :invalid-workflow-id)
        (not (keyword? (:resolver/kind basis))) (conj :invalid-resolver-kind)
        (not (contains? authority-modes (:resolution/authority-mode basis)))
        (conj :invalid-authority-mode)
        (not (every? #(ref/valid-sha256-ref? (get basis %)) root-fields))
        (conj :invalid-committed-root)
        (not (ref/valid-sha256-ref? root)) (conj :invalid-basis-root)
        (and (ref/valid-sha256-ref? root)
             (empty? missing)
             (empty? extra)
             (every? #(ref/valid-sha256-ref? (get basis %)) root-fields)
             (not= root
                   (ref/sha256-ref
                    (hc/domain-hash domain
                                    (hc/project-canonical-safe
                                     (basis-payload basis))))))
        (conj :basis-root-mismatch)))))

(defn validate-resolution-basis
  [basis]
  (let [errors (basis-errors basis)]
    {:valid? (empty? errors) :errors errors}))

(defn valid-resolution-basis?
  [basis]
  (:valid? (validate-resolution-basis basis)))

(defn build-resolution-basis
  "Build a closed, canonical `sew-resolution-basis.v1` artifact.

   The caller supplies all authority and dispute bindings. Outcome fields are
   not accepted because they are intentionally outside basis identity."
  [basis]
  (let [base (assoc basis
                    :artifact/schema schema
                    :resolution/purpose purpose)
        root (ref/sha256-ref
              (hc/domain-hash domain
                              (hc/project-canonical-safe (basis-payload base))))
        artifact (assoc base root-key root)
        validation (validate-resolution-basis artifact)]
    (when-not (:valid? validation)
      (throw (ex-info "Cannot build invalid sew-resolution-basis.v1"
                      {:type :sew-resolution-basis/invalid
                       :errors (:errors validation)})))
    artifact))
