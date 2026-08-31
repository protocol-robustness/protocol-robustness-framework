(ns resolver-sim.benchmark.research-observation
  "Minimal governed researcher attestations over one exact custody artifact.

   This intentionally commits no new observation-basis, registry, query, or
   composition abstraction: the subject is the existing held-custody artifact
   root and signer identity is resolved from the existing frozen governed
   signer-key-set."
  (:require [resolver-sim.benchmark.governed-authority-state :as authority-state]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.signed-external-decision :as sed]))

(def ^:const schema-version "research-observation.v1")
(def ^:const subject-kind :sew/held-custody-artifact)

(def ^:private observation-fields
  #{:artifact/schema :researcher/id :signing-key/id
    :observation/subject-kind :observation/subject-root :position/value})

(defn observation-statement
  "Build the closed, canonical statement a researcher signs."
  [input]
  {:artifact/schema schema-version
   :researcher/id (:researcher/id input)
   :signing-key/id (:signing-key/id input)
   :observation/subject-kind subject-kind
   :observation/subject-root (:observation/subject-root input)
   :position/value (:position/value input)})

(defn valid-observation-statement?
  "True only for the deliberately tiny, closed Iteration 1 statement shape."
  [statement]
  (and (= observation-fields (set (keys statement)))
       (= schema-version (:artifact/schema statement))
       (string? (:researcher/id statement))
       (string? (:signing-key/id statement))
       (= subject-kind (:observation/subject-kind statement))
       (boolean (re-matches #"sha256:[0-9a-f]{64}" (:observation/subject-root statement)))
       (integer? (:position/value statement))))

(defn signing-bytes
  "Canonical bytes of the complete unsigned statement, never a caller hash."
  [statement]
  (when-not (valid-observation-statement? statement)
    (throw (ex-info "invalid research observation statement" {:statement statement})))
  (hc/canonical-bytes statement))

(defn sign-observation
  "Attach an Ed25519 signature to a closed observation statement.

   `private-key` is intentionally only used by the local signing caller; the
   verifier resolves public material from the governed signer-key-set."
  [statement private-key]
  (assoc statement :signature {:algorithm :ed25519
                               :value (sed/ed25519-sign-bytes
                                       (signing-bytes statement) private-key)}))

(defn verify-observation
  "Verify statement shape, governed researcher/key resolution, and signature.

   The signer-key-set must itself be a valid frozen governed artifact."
  [signer-key-set observation]
  (let [statement (dissoc observation :signature)
        key-set-validation (authority-state/validate-signer-key-set signer-key-set)
        public-key (when (:valid? key-set-validation)
                     (authority-state/lookup-signing-public-key
                      signer-key-set (:researcher/id statement) (:signing-key/id statement)))
        signature (get-in observation [:signature :value])]
    (cond
      (not (valid-observation-statement? statement))
      {:valid? false :reason :invalid-statement}

      (not= #{:algorithm :value} (set (keys (:signature observation))))
      {:valid? false :reason :invalid-signature}

      (not= :ed25519 (get-in observation [:signature :algorithm]))
      {:valid? false :reason :unsupported-signature-algorithm}

      (not (:valid? key-set-validation))
      {:valid? false :reason :invalid-governed-signer-key-set
       :errors (:errors key-set-validation)}

      (nil? public-key)
      {:valid? false :reason :researcher-key-not-governed}

      (not (sed/ed25519-verify-bytes (signing-bytes statement) signature public-key))
      {:valid? false :reason :signature-invalid}

      :else
      {:valid? true :researcher/id (:researcher/id statement)
       :signing-key/id (:signing-key/id statement)})))

(defn aggregate-observations
  "Evaluate exactly two governed observations of one artifact.

   Different subject roots produce `:not-comparable`, never a disagreement.
   Invalid signatures or researcher/key bindings produce `:invalid` before any
   position comparison."
  [signer-key-set observations]
  (let [observations (vec observations)
        verifications (mapv #(verify-observation signer-key-set %) observations)
        statements (mapv #(dissoc % :signature) observations)
        researcher-ids (mapv :researcher/id statements)
        roots (set (map :observation/subject-root statements))]
    (cond
      (not= 2 (count observations))
      {:status :invalid :reason :requires-exactly-two-observations
       :verifications verifications}

      (not (every? :valid? verifications))
      {:status :invalid :reason :invalid-observation :verifications verifications}

      (not= 2 (count (set researcher-ids)))
      {:status :invalid :reason :researchers-must-differ :verifications verifications}

      (not= 1 (count roots))
      {:status :not-comparable :reason :different-subject-roots
       :verifications verifications}

      :else
      (let [positions (mapv :position/value statements)]
        {:status (if (apply = positions) :agreement :disagreement)
         :subject-root (first roots)
         :positions positions
         :verifications verifications}))))
