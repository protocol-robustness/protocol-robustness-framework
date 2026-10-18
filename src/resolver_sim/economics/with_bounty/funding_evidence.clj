(ns resolver-sim.economics.with-bounty.funding-evidence
  "Authenticated source evidence for with-bounty reserve funding.

   The signed source assertion is authority evidence for a modelled available
   reserve. It does not establish chain custody unless the signer itself is a
   trusted chain observer and the source scope says so."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.signed-external-decision :as sed]))

(def domain "PRF_BOUNTY_FUNDING_SOURCE_V1")
(def authority-role :bounty-funding-authority)
(def schema "bounty-funding-source.v1")

(def committed-fields
  [:funding/schema-version :funding/source-id :funding/source-root
   :funding/source-kind :funding/account :funding/token :funding/available
   :funding/operation-root :funding/beneficiary
   :funding/parameter-context-root :funding/parameter-address
   :funding/evidence-class])

(defn source-root [source]
  (hc/domain-hash :bounty-funding-source-v1
                  (select-keys source (remove #{:funding/source-root}
                                              committed-fields))))

(defn build-source
  "Build and sign an available-reserve assertion. `private-key` is held by the
   funding authority; consumers must verify against an out-of-band trust policy."
  [{:keys [source-id source-kind account token available operation-root
           beneficiary parameter-context-root parameter-address evidence-class
           private-key key-id]}]
  (let [base {:funding/schema-version schema
              :funding/source-id source-id
              :funding/source-kind source-kind
              :funding/account account
              :funding/token token
              :funding/available available
              :funding/operation-root operation-root
              :funding/beneficiary beneficiary
              :funding/parameter-context-root parameter-context-root
              :funding/parameter-address parameter-address
              :funding/evidence-class (or evidence-class :authority-attested)}
        source (assoc base :funding/source-root (source-root base))]
    (sed/sign-envelope source domain private-key key-id)))

(defn verify-source
  "Authenticate source assertion and bind it to the requested obligation.
   Returns a verified source only when every source identity field matches."
  [source trust-policy expected]
  (let [signature (sed/verify-envelope source domain trust-policy authority-role)
        structural? (and (map? source)
                         (= (set (conj committed-fields :signature)) (set (keys source)))
                         (= schema (:funding/schema-version source))
                         (and (string? (:funding/source-id source))
                              (seq (:funding/source-id source)))
                         (string? (:funding/source-root source))
                         (= (:funding/source-root source) (source-root source))
                         (= :authority-attested (:funding/evidence-class source))
                         (integer? (:funding/available source))
                         (not (neg? (:funding/available source))))
        matches? (every? (fn [[k v]] (= v (get source k))) expected)]
    (cond
      (not structural?) {:valid? false :reason :invalid-funding-source}
      (not (:valid? signature)) signature
      (not matches?) {:valid? false :reason :funding-source-binding-mismatch}
      :else {:valid? true :key-id (:key-id signature)
             :source-root (:funding/source-root source)
             :available (:funding/available source)})))
