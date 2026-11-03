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
(def observation-schema "stablecoin-privacy-head-observation.v1")
(def binding-domain "PRF_STABLECOIN_PRIVACY_BINDING_V1")
(def observation-domain "PRF_STABLECOIN_PRIVACY_HEAD_OBSERVATION_V1")
(def resolution-schema "stablecoin-privacy-resolution.v1")

(def ^:private binding-fields
  #{:privacy-binding/schema :privacy-binding/issuer :privacy-binding/asset-root
    :privacy-binding/jurisdiction :privacy-binding/operation
    :privacy-binding/policy-root :privacy-binding/authority-ref
    :privacy-binding/root :signature})
(def ^:private observation-fields
  #{:privacy-head-observation/schema :privacy-head-observation/binding-root
    :privacy-head-observation/policy-root :privacy-head-observation/head-root
    :privacy-head-observation/sequence :privacy-head-observation/observed-at
    :privacy-head-observation/root :signature})

(defn binding-root [binding]
  (ref/sha256-ref
   (hc/domain-hash "prf.stablecoin-privacy-binding.v1"
                   (dissoc binding :privacy-binding/root :signature))))

(defn observation-root [observation]
  (ref/sha256-ref
   (hc/domain-hash "prf.stablecoin-privacy-head-observation.v1"
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
           observer-trust-policy now max-observation-age-seconds]}]
  (let [binding-structure? (binding-structure-valid? binding)
        binding-signature (when binding-structure?
                            (signed-decision/verify-envelope
                             binding binding-domain authority-trust-policy
                             :privacy-policy-authority))
        policy-check (policy/validate-policy policy)
        request-check (policy/validate-request request)
        observation-structure? (observation-structure-valid? head-observation)
        observation-signature (when observation-structure?
                                (signed-decision/verify-envelope
                                 head-observation observation-domain observer-trust-policy
                                 :privacy-head-observer))
        scope? (and binding-structure? policy-check request-check
                    (:valid? policy-check) (:valid? request-check)
                    (= (:privacy-binding/issuer binding) (:privacy-policy/issuer policy))
                    (= (:privacy-binding/asset-root binding) (:privacy-policy/asset-root policy))
                    (= (:privacy-binding/policy-root binding) (:privacy-policy/root policy))
                    (= (:privacy-binding/issuer binding) (:request/issuer request))
                    (= (:privacy-binding/asset-root binding) (:request/asset-root request))
                    (= (:privacy-binding/jurisdiction binding) (:request/jurisdiction request))
                    (= (:privacy-binding/operation binding) (:request/operation request))
                    (some #{(:privacy-binding/jurisdiction binding)}
                          (:privacy-policy/jurisdictions policy)))
        policy-current? (and scope?
                             (<= (:privacy-policy/valid-from policy) now)
                             (< now (:privacy-policy/valid-until policy)))
        observation-context? (and observation-structure?
                                  (= (:privacy-binding/root binding)
                                     (:privacy-head-observation/binding-root head-observation))
                                  (= (:privacy-binding/policy-root binding)
                                     (:privacy-head-observation/policy-root head-observation)))
        fresh? (and observation-context?
                    (integer? now) (not (neg? now))
                    (integer? max-observation-age-seconds)
                    (not (neg? max-observation-age-seconds))
                    (<= (:privacy-head-observation/observed-at head-observation) now)
                    (<= (- now (:privacy-head-observation/observed-at head-observation))
                        max-observation-age-seconds))
        authority-verified? (true? (:valid? binding-signature))
        observer-verified? (true? (:valid? observation-signature))
        valid? (and authority-verified? scope? policy-current? observation-context?
                    observer-verified? fresh?)
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
                :privacy-resolution/errors
                (cond-> []
                  (not binding-structure?) (conj :binding-invalid)
                  (and binding-structure? (not authority-verified?))
                  (conj [:binding-signature-invalid (:reason binding-signature)])
                  (not scope?) (conj :policy-or-request-scope-mismatch)
                  (and scope? (not policy-current?)) (conj :policy-not-valid-at-now)
                  (not observation-structure?) (conj :head-observation-invalid)
                  (and observation-context? (not observer-verified?))
                  (conj [:head-observation-signature-invalid (:reason observation-signature)])
                  (not observation-context?) (conj :head-observation-binding-mismatch)
                  (and observation-context? (not fresh?)) (conj :head-observation-stale-or-future))}]
    (assoc result :privacy-resolution/root
           (ref/sha256-ref
            (hc/domain-hash "prf.stablecoin-privacy-resolution.v1" result)))))

(defn verify-resolution
  "Reconstruct a resolution from its signed inputs and compare the full receipt."
  [result inputs]
  (and (map? result)
       (= resolution-schema (:privacy-resolution/schema result))
       (= (:privacy-resolution/root result)
          (ref/sha256-ref
           (hc/domain-hash "prf.stablecoin-privacy-resolution.v1"
                           (dissoc result :privacy-resolution/root))))
       (= result (resolve-policy inputs))))
