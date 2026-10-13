(ns resolver-sim.verification.basis
  "Versioned, content-addressed input and output artifacts for independent
   verification.

   A verification basis carries the exact subject and authority/configuration
   references a verifier must consume. It deliberately does not execute the
   subject or select a verifier: selection is performed by the explicit,
   authorized verifier registry and its result is bound below."
  (:require [clojure.set :as set]
            [resolver-sim.extensions.registry :as registry]
            [resolver-sim.extensions.resolution :as res]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const basis-schema "verification-basis.v1")
(def ^:const result-schema "verification-result.v1")

(def basis-fields
  [:verification-basis/schema
   :verification-basis/subject
   :chain-configuration/root
   :currently-authorized-chain-configuration/root
   :verifier-registry/root
   :extensions/resolution-root
   :verification-basis/root])

(def result-fields
  [:verification-result/schema
   :verification-basis/root
   :verification/verifier-ref
   :verification/verifier-package-root
   :verification/status
   :verification/result-root])

(defn- valid-root? [v] (ref/valid-sha256-ref? v))

(defn- valid-extension-root?
  "Extension descriptor/package roots are raw lowercase SHA-256 hex digests in
   the existing extension registry; chain/configuration references use the
   external `sha256:` representation instead."
  [v]
  (boolean (and (string? v) (re-matches #"[0-9a-f]{64}" v))))

(declare basis-root result-root)

(defn- report-shape-errors [value fields schema-key expected-schema]
  (let [errors (atom [])
        report! #(swap! errors conj %)]
    (if-not (map? value)
      (report! "artifact must be a map")
      (let [allowed (set fields)
            actual (set (keys value))]
        (when-let [extra (seq (set/difference actual allowed))]
          (report! (str "unknown keys: " (sort extra))))
        (when-let [missing (seq (set/difference allowed actual))]
          (report! (str "missing keys: " (sort missing))))
        (when-not (= expected-schema (get value schema-key))
          (report! (str schema-key " must be " expected-schema)))))
    @errors))

(defn validate-basis
  "Validate a closed `verification-basis.v1` artifact. Optional temporal/risk
   facts intentionally live inside the committed subject map until their own
   versioned governance contract is introduced; this avoids pretending that a
   descriptive value-at-risk field is already authoritative."
  [basis]
  (let [errors (atom (report-shape-errors basis basis-fields
                                          :verification-basis/schema basis-schema))
        report! #(swap! errors conj %)]
    (when (map? basis)
      (let [subject (:verification-basis/subject basis)]
        (when-not (and (map? subject)
                       (qualified-keyword? (:capability/kind subject))
                       (qualified-keyword? (:capability/id subject))
                       (pos? (or (:capability/contract-version subject) 0))
                       (valid-root? (:subject/root subject)))
          (report! "verification-basis/subject must name a qualified capability, positive contract version, and subject/root")))
      (doseq [field [:chain-configuration/root
                     :currently-authorized-chain-configuration/root
                     :verifier-registry/root
                     :extensions/resolution-root]]
        (when-not (valid-root? (get basis field))
          (report! (str field " must be a valid sha256 reference"))))
      (when-not (= (:chain-configuration/root basis)
                   (:currently-authorized-chain-configuration/root basis))
        (report! "chain-configuration/root must equal currently-authorized-chain-configuration/root"))
      (let [declared (:verification-basis/root basis)
            computed (basis-root (dissoc basis :verification-basis/root))]
        (when-not (= declared computed)
          (report! "verification-basis/root does not match the canonical basis content"))))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn basis-root
  "Compute the canonical root of the basis content. The function accepts either
   a base map without `:verification-basis/root` or a complete artifact."
  [basis]
  (ref/sha256-ref
   (hc/domain-hash :prf-verification-basis-v1
                   (dissoc basis :verification-basis/root))))

(defn build-basis
  "Construct a self-validating verification basis from its non-derived fields."
  [basis]
  (let [complete (assoc basis :verification-basis/schema basis-schema)
        rooted (assoc complete :verification-basis/root (basis-root complete))
        validation (validate-basis rooted)]
    (when-not (:valid? validation)
      (throw (ex-info "verification-basis.v1 is invalid"
                      {:type :verification-basis/invalid :errors (:errors validation)})))
    rooted))

(defn result-root
  "Canonical identity of a verifier result. The root commits the selected
   verifier capability/package and the exact verification basis it assessed."
  [result]
  (ref/sha256-ref
   (hc/domain-hash :prf-verification-result-v1
                   (dissoc result :verification/result-root))))

(defn validate-result
  "Validate a closed verifier result. A result cannot call itself independent:
   callers must establish that its verifier ref was selected from the authorized
   registry before accepting `:verification/status :verified`."
  [result]
  (let [errors (atom (report-shape-errors result result-fields
                                          :verification-result/schema result-schema))
        report! #(swap! errors conj %)]
    (when (map? result)
      (when-not (valid-root? (:verification-basis/root result))
        (report! "verification-basis/root must be a valid sha256 reference"))
      (when-not (and (map? (:verification/verifier-ref result))
                     (qualified-keyword? (get-in result [:verification/verifier-ref :capability/kind]))
                     (qualified-keyword? (get-in result [:verification/verifier-ref :capability/id]))
                     (valid-extension-root? (get-in result [:verification/verifier-ref :descriptor-root])))
        (report! "verification/verifier-ref must name a qualified verifier capability and raw descriptor root"))
      (when-not (valid-extension-root? (:verification/verifier-package-root result))
        (report! "verification/verifier-package-root must be a raw lowercase SHA-256 digest"))
      (when-not (contains? #{:verified :not-verified :inconclusive :invalid-evidence :incompatible-verifier}
                           (:verification/status result))
        (report! "verification/status is invalid"))
      (when-not (= (:verification/result-root result)
                   (result-root (dissoc result :verification/result-root)))
        (report! "verification/result-root does not match canonical result content")))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn build-result [result]
  (let [complete (assoc result :verification-result/schema result-schema)
        rooted (assoc complete :verification/result-root (result-root complete))
        validation (validate-result rooted)]
    (when-not (:valid? validation)
      (throw (ex-info "verification-result.v1 is invalid"
                      {:type :verification-result/invalid :errors (:errors validation)})))
    rooted))

(defn- project-extension-registry-entry
  "Project an extension-map entry to its canonical identity form:
   descriptor-root, builtin classification, and provider package identities."
  [entry]
  {:descriptor-root (:descriptor-root entry)
   :builtin? (:builtin? entry)
   :providers (->> (:providers entry [])
                   (map (fn [p]
                          {:package/id (:package/id p)
                           :package/version (:package/version p)
                           :package-root (:package-root p)
                           :sealed (:sealed p)}))
                   (sort-by :package-root)
                   vec)})

(defn- project-extension-registry
  "Project an extension-map into a canonical-safe form for content-addressed
   hashing. Capability keys (vectors) are encoded by the canonical encoder;
   entries are projected to their identity-relevant fields only."
  [extension-map]
  (into {} (map (fn [[k entry]]
                  [k (project-extension-registry-entry entry)])
                extension-map)))

(defn verifier-registry-root
  "Canonical root of a supplied extension-map verifier registry.
   Returns a sha256 reference (sha256:<64-hex>) that can be compared to the
   :verifier-registry/root committed in a verification basis."
  [extension-map]
  (ref/sha256-ref
   (hc/domain-hash :extension-registry-v1
                   (project-extension-registry extension-map))))

(defn verify-basis-authority
  "Verify that the supplied verifier registry and extension resolution match
   the roots committed in `basis`.

   This is the authority-binding gate: a basis commits
   :verifier-registry/root and :extensions/resolution-root, and the exact
   extension-map and resolution used for selection must reproduce those roots.
   Substitution of either the registry or the resolution is rejected fail-closed.

   Returns {:valid? true, :basis basis} when both roots match,
   or {:valid? false, :reason <kw>, :expected <ref>, :actual <ref>} otherwise."
  [extension-map resolution basis]
  (let [registry-root (verifier-registry-root extension-map)
        raw-resolution-root (res/resolution-root resolution)
        resolution-root (when (valid-extension-root? raw-resolution-root)
                          (ref/sha256-ref raw-resolution-root))]
    (cond
      (nil? resolution-root)
      {:valid? false
       :reason :verification/invalid-resolution-root
       :actual raw-resolution-root}

      (not= registry-root (:verifier-registry/root basis))
      {:valid? false
       :reason :verification/registry-root-mismatch
       :expected (:verifier-registry/root basis)
       :actual registry-root}

      (not= resolution-root (:extensions/resolution-root basis))
      {:valid? false
       :reason :verification/resolution-root-mismatch
       :expected (:extensions/resolution-root basis)
       :actual resolution-root}

      :else
      {:valid? true :basis basis})))

(defn verify-result-selection
  "Verify that `result` was issued by the one verifier selected from the supplied
   frozen registry for the exact subject committed by `basis`.

   Before selection, enforces the authority-binding gate: the supplied
   `extension-map` (verifier registry) and `resolution` must reproduce the
   :verifier-registry/root and :extensions/resolution-root committed in
   `basis`. A result from another registry or resolution is rejected
   fail-closed.

   This is the acceptance gate between structural result validity and an
   accepted verifier claim; a self-consistent result from another package is
   rejected."
  [extension-map resolution basis result]
  (let [bv (validate-basis basis)
        rv (validate-result result)]
    (cond
      (not (:valid? bv))
      {:valid? false :reason :verification/invalid-basis :errors (:errors bv)}

      (not (:valid? rv))
      {:valid? false :reason :verification/invalid-result :errors (:errors rv)}

      (not= (:verification-basis/root basis) (:verification-basis/root result))
      {:valid? false :reason :verification/result-basis-mismatch}

      :else
      (let [authority (verify-basis-authority extension-map resolution basis)]
        (if-not (:valid? authority)
          authority
          (let [selected (registry/select-verifier extension-map
                                                   (:verification-basis/subject basis))]
            (if-not (:valid? selected)
              {:valid? false :reason (:reason selected) :selection selected}
              (let [entry (:entry selected)
                    provider (:provider selected)
                    supplied (:verification/verifier-ref result)]
                (if (and (= (:descriptor-root entry) (:descriptor-root supplied))
                         (= (:package-root provider) (:verification/verifier-package-root result))
                         (= (:capability/kind (:capability entry)) (:capability/kind supplied))
                         (= (:capability/id (:capability entry)) (:capability/id supplied)))
                  {:valid? true :basis basis :result result :selection selected}
                  {:valid? false :reason :verification/unselected-verifier
                   :expected {:descriptor-root (:descriptor-root entry)
                              :package-root (:package-root provider)}
                   :actual {:descriptor-root (:descriptor-root supplied)
                            :package-root (:verification/verifier-package-root result)}})))))))))
