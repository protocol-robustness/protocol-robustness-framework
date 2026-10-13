(ns resolver-sim.resubmission.acceptance-authority-basis
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.resubmission.publisher-authority :as publisher]))

(def schema "attempt-acceptance-authority-basis.v1")
(def domain :prf-attempt-acceptance-authority-basis-v1)
(def fields
  #{:artifact/schema
    :authority-basis/verifier-registry-root
    :authority-basis/publisher-authority-root
    :authority-basis/extension-resolution-root
    :attempt-acceptance-authority-basis/root})

(defn projection
  "Canonical projection of the authority-basis identity fields.
   Strips the self-hash root and selects exactly the authority-selected
   verifier, publisher, and extension roots plus the schema."
  [basis]
  (hc/project-canonical-safe
   (select-keys basis [:artifact/schema
                       :authority-basis/verifier-registry-root
                       :authority-basis/publisher-authority-root
                       :authority-basis/extension-resolution-root])))

(defn root [basis]
  (ref/sha256-ref (hc/domain-hash domain (projection basis))))

(defn verifier-registry-root
  "Extract the verifier registry root from an authority basis."
  [basis]
  (:authority-basis/verifier-registry-root basis))

(defn publisher-authority-root
  "Extract the publisher-authority root from an authority basis."
  [basis]
  (:authority-basis/publisher-authority-root basis))

(defn valid?
  [basis]
  (and (map? basis)
       (= fields (set (keys basis)))
       (= schema (:artifact/schema basis))
       (every? ref/valid-sha256-ref?
               (map basis [:authority-basis/verifier-registry-root
                           :authority-basis/publisher-authority-root
                           :authority-basis/extension-resolution-root]))
       (= (:attempt-acceptance-authority-basis/root basis) (root basis))))

(defn registry-roots-valid?
  "Verify that all authority roots in the basis are valid sha256 references
   and that the basis itself is structurally valid."
  [basis]
  (and (valid? basis)
       (every? ref/valid-sha256-ref?
               [(verifier-registry-root basis)
                (publisher-authority-root basis)
                (:authority-basis/extension-resolution-root basis)])))

(defn resolve-registry-root
  "Resolve a registry root to its body using the resolver.
   Returns {:root <root> :body <body-or-nil>}.
   The resolver is {:resolve-artifact (fn [root] body-or-nil)}."
  [resolver root]
  {:root root
   :body (when-let [resolve-artifact (:resolve-artifact resolver)]
           (resolve-artifact root))})

(defn resolve-registries
  "Resolve all authority leaves named by an authority basis.
   Returns verifier, publisher, and extension-resolution bodies."
  [resolver basis]
  (when (valid? basis)
    {:verifier-registry (resolve-registry-root resolver (verifier-registry-root basis))
     :publisher-authority (resolve-registry-root resolver (publisher-authority-root basis))
     :extension-resolution (resolve-registry-root resolver
                                                  (:authority-basis/extension-resolution-root basis))}))

(defn build [basis]
  (let [built (assoc basis :artifact/schema schema)]
    (assoc built :attempt-acceptance-authority-basis/root (root built))))

(defn resolve-publisher-authority
  "Resolve the historically selected publisher authority leaf. The resolver
   retrieves content only; this function validates the rooted artifact."
  [resolve-artifact basis]
  (let [expected (:authority-basis/publisher-authority-root basis)
        body (when resolve-artifact (resolve-artifact expected))]
    (cond
      (nil? body) {:valid? false :reason :publisher-authority-unavailable}
      (not= expected (:attempt-publisher-authority/root body))
      {:valid? false :reason :publisher-authority-root-mismatch}
      (not (publisher/valid? body))
      {:valid? false :reason :publisher-authority-invalid}
      :else {:valid? true :authority body})))
