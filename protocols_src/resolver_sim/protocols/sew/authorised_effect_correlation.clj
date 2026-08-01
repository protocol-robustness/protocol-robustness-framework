(ns resolver-sim.protocols.sew.authorised-effect-correlation
  "Sew adapter for deriving, rather than accepting, an authorised held-effect
   correlation. It does not mutate a world, consume a reservation, or grant
   terminal assurance."
  (:require [resolver-sim.assurance.authorised-effect-correlation :as correlation]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.accounting.held-adjustment :as held-adjustment]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.io.content-addressed-store :as store]))

(def ^:private input-keys
  #{:public-authorisation/id :held-adjustment/id :reservation/hash
    :reservation-registry :artifact-store})

(defn- fail! [reason data]
  (throw (ex-info "Cannot derive Sew authorised held-effect correlation"
                  (assoc data :reason reason))))

(defn- resolved-reservation [registry consumption-key]
  (let [state (cond
                (instance? clojure.lang.IDeref registry) @registry
                (map? registry) registry
                :else nil)]
    (get state consumption-key)))

(defn- valid-held-artifacts? [artifacts]
  (try
    ;; Verify the complete custody artifact chain: a non-genesis artifact is
    ;; invalid in isolation because its predecessor commitment is meaningful.
    (every? #(= :pass (:status %)) (custody/held-custody-closed-form-checks artifacts))
    (catch Exception _ false)))

(defn persist-authorised-held-effect
  "Resolve all correlation fields from the candidate Sew world, its
   consensus-bound public grant, and the external exact reservation registry.

   Caller input is intentionally limited to stable lookup identifiers. An
   optional `:reservation/hash` is a checked assertion, never an authoritative
   value. The returned correlation is persisted unlinked and remains only a
   reference anchor until a terminal verifier resolves its full chain."
  [world opts]
  (when-let [unknown (seq (remove input-keys (keys opts)))]
    (fail! :unknown-input-key {:keys unknown}))
  (let [auth-id (:public-authorisation/id opts)
        adjustment-id (:held-adjustment/id opts)
        declared-reservation-hash (:reservation/hash opts)
        reservation-registry (:reservation-registry opts)
        artifact-store (:artifact-store opts)]
    (when-not (and auth-id (string? auth-id))
      (fail! :invalid-public-authorisation-id {:authorization-id auth-id}))
    (when-not (and adjustment-id (string? adjustment-id))
      (fail! :invalid-held-adjustment-id {:held-adjustment-id adjustment-id}))
    (when-not artifact-store
      (fail! :artifact-store-unavailable {}))
    (let [grant (get-in world [:force-authorisations auth-id])
          adjustment (some #(when (= adjustment-id (:held-adjustment/id %)) %) (:held-adjustments world))
          provenance (:authorization/provenance grant)
          consumption-key (:researcher-force-authorisation/consumption-key provenance)
          reservation (resolved-reservation reservation-registry consumption-key)
          artifact (get-in world [:held-artifacts adjustment-id])
          recomputed-scope (when grant
                             (hash/domain-hash accounting/force-authorisation-scope-domain
                                               (:authorization/scope grant)))]
    (when-not grant (fail! :public-authorisation-not-found {:authorization-id auth-id}))
    (when-not adjustment (fail! :held-adjustment-not-found {:held-adjustment-id adjustment-id}))
    (when-not (= :consensus-grant-reserved (:authorization/assurance provenance))
      (fail! :grant-not-consensus-bound {:authorization-id auth-id}))
    (when-not (and (map? reservation) (= :reserved (:status reservation)))
      (fail! :reservation-not-found-or-not-reserved {:consumption-key consumption-key}))
    (when (and declared-reservation-hash
               (not= declared-reservation-hash (:reservation/hash reservation)))
      (fail! :reservation-hash-mismatch {:declared declared-reservation-hash :resolved (:reservation/hash reservation)}))
    (when-not (= auth-id (:sew/authorization-id reservation))
      (fail! :reservation-public-authorisation-mismatch {}))
    (when-not (= recomputed-scope (:authorization/scope-hash grant))
      (fail! :public-scope-hash-mismatch {}))
    (when-not (= recomputed-scope (:sew/scope-hash reservation))
      (fail! :reservation-scope-hash-mismatch {}))
    (when-not (= (:research-assignment/hash provenance) (:research-assignment/hash reservation))
      (fail! :reservation-assignment-mismatch {}))
    (when-not (= (:researcher-force-authorisation/hash provenance)
                 (:researcher-force-authorisation/hash reservation))
      (fail! :reservation-researcher-authorisation-mismatch {}))
    (when-not (= (:researcher-force-authorisation/reservation-hash provenance)
                 (:reservation/hash reservation))
      (fail! :grant-reservation-mismatch {}))
    (when-not (= auth-id (get-in adjustment [:authorization/provenance :authorization/id]))
      (fail! :adjustment-public-authorisation-mismatch {}))
    (when-not (= (:authorization/scope grant)
                 (held-adjustment/project-held-adjustment-scope
                  (assoc adjustment
                         :authorization/id auth-id
                         :authorization/type :force-authorisation)))
      (fail! :adjustment-scope-mismatch {}))
    (when-not (and artifact
                   (valid-held-artifacts? (vals (:held-artifacts world))))
      (fail! :held-artifact-invalid {:held-adjustment-id adjustment-id}))
    (let [built (correlation/build-correlation
                 {:protocol/id :sew
                  :research-assignment/hash (:research-assignment/hash provenance)
                  :researcher-force-authorisation/hash (:researcher-force-authorisation/hash provenance)
                  :reservation/hash (:reservation/hash reservation)
                  :reservation/execution-attempt-id (:reservation/execution-attempt-id reservation)
                  :public-authorisation/id auth-id
                  :public-authorisation/scope-hash (str "sha256:" recomputed-scope)
                  :effect/type :held-adjustment
                  :effect/id adjustment-id
                  :effect/artifact-hash (:artifact/hash artifact)})
          persisted (store/put-if-absent! artifact-store
                                           {:hash-reference (:correlation/hash built)
                                            :artifact built
                                            :verify correlation/valid-correlation?})]
      {:correlation built :persistence persisted}))))
