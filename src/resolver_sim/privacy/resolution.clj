(ns resolver-sim.privacy.resolution
  "Offline verification of signed privacy-policy bindings and head observations.

   This adapter reports currentness only relative to the explicit trust
   policies supplied by its caller. It is not connected to an authorized PRF
   configuration binding or a production head-observation service."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.privacy.policy :as policy]
            [resolver-sim.signed-external-decision :as signed-decision]))

(def binding-schema "stablecoin-privacy-binding.v1")
(def observation-schema "stablecoin-privacy-head-observation.v2")
(def binding-domain "PRF_STABLECOIN_PRIVACY_BINDING_V1")
(def observation-domain "PRF_STABLECOIN_PRIVACY_HEAD_OBSERVATION_V2")
(def resolution-schema "stablecoin-privacy-resolution.v2")

(def ^:private binding-fields
  #{:privacy-binding/schema :privacy-binding/issuer :privacy-binding/asset-root
    :privacy-binding/jurisdiction :privacy-binding/operation
    :privacy-binding/policy-root :privacy-binding/authority-ref
    :privacy-binding/root :signature})
(def ^:private observation-fields
  #{:privacy-head-observation/schema :privacy-head-observation/binding-root
    :privacy-head-observation/policy-root :privacy-head-observation/head-root
    :privacy-head-observation/previous-root
    :privacy-head-observation/sequence :privacy-head-observation/observed-at
    :privacy-head-observation/root :signature})

(defn trust-policy-root [trust-policy]
  (when (map? trust-policy)
    (ref/sha256-ref (hc/domain-hash "prf.stablecoin-privacy-caller-trust-policy.v1"
                                    trust-policy))))

(defn binding-root [binding]
  (ref/sha256-ref
   (hc/domain-hash "prf.stablecoin-privacy-binding.v1"
                   (dissoc binding :privacy-binding/root :signature))))

(defn observation-root [observation]
  (ref/sha256-ref
   (hc/domain-hash "prf.stablecoin-privacy-head-observation.v2"
                   (dissoc observation :privacy-head-observation/root :signature))))

(defn- binding-structure-valid? [binding]
  (and (map? binding)
       (= binding-fields (set (keys binding)))
       (= binding-schema (:privacy-binding/schema binding))
       (every? #(and (string? %) (seq %))
               [(:privacy-binding/issuer binding)
                (:privacy-binding/jurisdiction binding)
                (:privacy-binding/operation binding)
                (:privacy-binding/authority-ref binding)])
       (ref/valid-sha256-ref? (:privacy-binding/asset-root binding))
       (ref/valid-sha256-ref? (:privacy-binding/policy-root binding))
       (= (:privacy-binding/root binding) (binding-root binding))))

(defn- observation-structure-valid? [observation]
  (and (map? observation)
       (= observation-fields (set (keys observation)))
       (= observation-schema (:privacy-head-observation/schema observation))
       (ref/valid-sha256-ref? (:privacy-head-observation/binding-root observation))
       (ref/valid-sha256-ref? (:privacy-head-observation/policy-root observation))
       (ref/valid-sha256-ref? (:privacy-head-observation/head-root observation))
       (or (nil? (:privacy-head-observation/previous-root observation))
           (ref/valid-sha256-ref? (:privacy-head-observation/previous-root observation)))
       (integer? (:privacy-head-observation/sequence observation))
       (not (neg? (:privacy-head-observation/sequence observation)))
       (integer? (:privacy-head-observation/observed-at observation))
       (not (neg? (:privacy-head-observation/observed-at observation)))
       (= (:privacy-head-observation/root observation) (observation-root observation))))

(defn resolve-policy
  "Resolve a policy for a rooted request using signed, retained fixture inputs.

   `now` and `max-observation-age-seconds` are injected. The returned
   `:current-observed` means only that the binding and observation verify
   against these supplied trust policies and satisfy the freshness bound. The
   trust policies themselves must be authenticated out of band by a caller."
  [{:keys [binding policy request head-observation authority-trust-policy
           observer-trust-policy head-checkpoint now max-observation-age-seconds]}]
  (let [binding-structure? (binding-structure-valid? binding)
        binding-signature (when binding-structure?
                            (try
                              (signed-decision/verify-envelope
                               binding binding-domain authority-trust-policy
                               :privacy-policy-authority)
                              (catch Exception _ {:valid? false :reason :invalid-trust-input})))
        policy-check (policy/validate-policy policy)
        request-check (policy/validate-request request)
        observation-structure? (observation-structure-valid? head-observation)
        observation-signature (when observation-structure?
                                (try
                                  (signed-decision/verify-envelope
                                   head-observation observation-domain observer-trust-policy
                                   :privacy-head-observer)
                                  (catch Exception _ {:valid? false :reason :invalid-trust-input})))
        scope? (and binding-structure? policy-check request-check
                    (:valid? policy-check) (:valid? request-check)
                    (= (:privacy-binding/issuer binding) (:privacy-policy/issuer policy))
                    (= (:privacy-binding/asset-root binding) (:privacy-policy/asset-root policy))
                    (= (:privacy-binding/policy-root binding) (:privacy-policy/root policy))
                    (= (:privacy-binding/authority-ref binding)
                       (:privacy-policy/authority-ref policy))
                    (= (:privacy-binding/authority-ref binding)
                       (:authority/ref authority-trust-policy))
                    (= (:privacy-binding/issuer binding) (:request/issuer request))
                    (= (:privacy-binding/asset-root binding) (:request/asset-root request))
                    (= (:privacy-binding/jurisdiction binding) (:request/jurisdiction request))
                    (= (:privacy-binding/operation binding) (:request/operation request))
                    (some #{(:privacy-binding/jurisdiction binding)}
                          (:privacy-policy/jurisdictions policy)))
        time-valid? (and (integer? now) (<= 0 now Long/MAX_VALUE)
                         (integer? max-observation-age-seconds)
                         (<= 0 max-observation-age-seconds Long/MAX_VALUE))
        policy-current? (and scope? time-valid?
                             (<= (:privacy-policy/valid-from policy) now)
                             (< now (:privacy-policy/valid-until policy)))
        policy-valid-at-request? (and scope? request-check (:valid? request-check)
                                      (<= (:privacy-policy/valid-from policy)
                                          (:request/at request))
                                      (< (:request/at request)
                                         (:privacy-policy/valid-until policy)))
        observation-context? (and observation-structure?
                                  (= (:privacy-binding/root binding)
                                     (:privacy-head-observation/binding-root head-observation))
                                  (= (:privacy-binding/policy-root binding)
                                     (:privacy-head-observation/policy-root head-observation)))
        fresh? (and observation-context? time-valid?
                    (<= (:privacy-head-observation/observed-at head-observation) now)
                    (<= (- now (:privacy-head-observation/observed-at head-observation))
                        max-observation-age-seconds))
        checkpoint-valid? (and (map? head-checkpoint)
                               (integer? (:sequence head-checkpoint))
                               (<= 0 (:sequence head-checkpoint) Long/MAX_VALUE)
                               (ref/valid-sha256-ref? (:root head-checkpoint)))
        checkpoint-seq (when checkpoint-valid? (:sequence head-checkpoint))
        observed-seq (:privacy-head-observation/sequence head-observation)
        observed-root (:privacy-head-observation/head-root head-observation)
        checkpoint-root (when checkpoint-valid? (:root head-checkpoint))
        retained-conflicts (when (map? head-checkpoint) (:conflicts head-checkpoint))
        head-status (cond
                      (not observation-structure?) :invalid-observation
                      (seq retained-conflicts) :retained-equivocation
                      (not checkpoint-valid?) :checkpoint-missing-or-invalid
                      (< observed-seq checkpoint-seq) :stale-sequence
                      (= observed-seq checkpoint-seq)
                      (if (= observed-root checkpoint-root) :idempotent :equivocation)
                      (not= observed-seq (inc checkpoint-seq)) :sequence-gap
                      (not= (:privacy-head-observation/previous-root head-observation)
                            checkpoint-root) :continuity-mismatch
                      :else :advanced)
        head-valid? (contains? #{:idempotent :advanced} head-status)
        authority-verified? (true? (:valid? binding-signature))
        observer-verified? (true? (:valid? observation-signature))
        valid? (and authority-verified? scope? policy-current?
                    policy-valid-at-request? observation-context?
                    observer-verified? fresh? head-valid?)
        result {:privacy-resolution/schema resolution-schema
                :privacy-resolution/valid? (boolean valid?)
                :privacy-resolution/policy-root (:privacy-binding/policy-root binding)
                :privacy-resolution/binding-root (:privacy-binding/root binding)
                :privacy-resolution/head-root (:privacy-head-observation/head-root head-observation)
                :privacy-resolution/head-sequence (:privacy-head-observation/sequence head-observation)
                :privacy-resolution/observation-root (:privacy-head-observation/root head-observation)
                :privacy-resolution/observed-at (:privacy-head-observation/observed-at head-observation)
                :privacy-resolution/authority-status (if authority-verified?
                                                       :verified-relative-to-supplied-trust
                                                       :unverified)
                :privacy-resolution/observer-status (if observer-verified?
                                                      :verified-relative-to-supplied-trust
                                                      :unverified)
                :privacy-resolution/currentness (if valid? :current-observed :unknown)
                :privacy-resolution/trust-provenance :caller-supplied-trust-policies
                :privacy-resolution/authority-trust-policy-root
                (trust-policy-root authority-trust-policy)
                :privacy-resolution/authority-trust-policy-id
                (:trust/id authority-trust-policy)
                :privacy-resolution/observer-trust-policy-root
                (trust-policy-root observer-trust-policy)
                :privacy-resolution/observer-trust-policy-id
                (:trust/id observer-trust-policy)
                :privacy-resolution/observation-provenance
                {:observation-root (:privacy-head-observation/root head-observation)
                 :observer-key-id (get-in observation-signature [:key-id])
                 :observed-at (:privacy-head-observation/observed-at head-observation)
                 :trust-status :verified-relative-to-caller-supplied-policy}
                :privacy-resolution/head-status head-status
                :privacy-resolution/errors
                (cond-> []
                  (not binding-structure?) (conj :binding-invalid)
                  (and binding-structure? (not authority-verified?))
                  (conj [:binding-signature-invalid (:reason binding-signature)])
                  (not scope?) (conj :policy-or-request-scope-mismatch)
                  (and binding-structure? policy-check
                       (not= (:privacy-binding/authority-ref binding)
                             (:privacy-policy/authority-ref policy)))
                  (conj :authority-reference-mismatch)
                  (and binding-structure? policy-check
                       (not= (:privacy-binding/authority-ref binding)
                             (:authority/ref authority-trust-policy)))
                  (conj :signing-authority-mismatch)
                  (not time-valid?) (conj :invalid-freshness-input)
                  (and scope? (not policy-current?)) (conj :policy-not-valid-at-now)
                  (and scope? (not policy-valid-at-request?))
                  (conj :policy-not-valid-at-request-time)
                  (not observation-structure?) (conj :head-observation-invalid)
                  (and observation-context? (not observer-verified?))
                  (conj [:head-observation-signature-invalid (:reason observation-signature)])
                  (not observation-context?) (conj :head-observation-binding-mismatch)
                  (not head-valid?) (conj [:head-history-invalid head-status])
                  (and observation-context? (not fresh?)) (conj :head-observation-stale-or-future))}]
    (assoc result :privacy-resolution/root
           (ref/sha256-ref
            (hc/domain-hash "prf.stablecoin-privacy-resolution.v2" result)))))

(defn verify-resolution
  "Reconstruct a resolution from its signed inputs and compare the full receipt."
  [result inputs]
  (and (map? result)
       (= resolution-schema (:privacy-resolution/schema result))
       (= (:privacy-resolution/root result)
          (ref/sha256-ref
           (hc/domain-hash "prf.stablecoin-privacy-resolution.v2"
                           (dissoc result :privacy-resolution/root))))
       (= result (resolve-policy inputs))))
