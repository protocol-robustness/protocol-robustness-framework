(ns resolver-sim.benchmark.governed-authority-result-receipt
  "Closed, self-root(ns resolver-sim.benchmark.governed-authority-result-receipt)ing receipt for a governed-authority finalisation."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const schema "governed-authority-result-receipt.v1")
(def ^:const domain :governed-authority-result-receipt-v1)

(def receipt-fields
  #{:artifact/schema
    :pre-authoritative-state-envelope/root
    :post-authoritative-state-envelope/root
    :transaction/state-before-root
    :transaction/state-after-root
    :authority-report/root
    :resolved-review-authority-context/root
    :governed-authority-transition-binding/root
    :pre-chain-configuration/root
    :pre-authority-semantics-policy/root
    :pre-governed-authority-semantics/root
    :successor-chain-configuration/root
    :successor-authority-semantics-policy/root
    :successor-governed-authority-semantics/root})

(def optional-roots
  #{:pre-authority-semantics-policy/root
    :pre-governed-authority-semantics/root
    :successor-authority-semantics-policy/root
    :successor-governed-authority-semantics/root})

(defn receipt-root [receipt]
  (ref/sha256-ref
   (hc/domain-hash domain
                   (hc/project-canonical-safe
                    (dissoc receipt :governed-authority-result-receipt/root)))))

(defn validate-receipt [receipt]
  (let [have (if (map? receipt) (set (keys receipt)) #{})
        allowed (conj receipt-fields :governed-authority-result-receipt/root)
        required (set/difference receipt-fields optional-roots)
        errors (cond-> []
                 (not (map? receipt)) (conj "receipt must be a map")
                 (and (map? receipt) (not= schema (:artifact/schema receipt)))
                 (conj "artifact/schema is invalid")
                 (and (map? receipt) (seq (set/difference have allowed)))
                 (conj "receipt has unknown keys")
                 (and (map? receipt) (seq (set/difference required have)))
                 (conj "receipt has missing keys")
                 (and (map? receipt)
                      (some #(and (some? (get receipt %))
                                  (not (ref/valid-sha256-ref? (get receipt %))))
                            (disj receipt-fields :artifact/schema)))
                 (conj "receipt root field is invalid")
                 (and (map? receipt)
                      (not (ref/valid-sha256-ref?
                            (:governed-authority-result-receipt/root receipt))))
                 (conj "receipt self root is invalid")
                 (and (map? receipt)
                      (ref/valid-sha256-ref?
                       (:governed-authority-result-receipt/root receipt))
                      (not= (:governed-authority-result-receipt/root receipt)
                            (receipt-root receipt)))
                 (conj "receipt self root does not match"))]
    {:valid? (empty? errors) :errors errors}))

(defn build-receipt [receipt]
  (let [receipt (assoc receipt :artifact/schema schema)
        candidate (assoc receipt :governed-authority-result-receipt/root
                         (receipt-root receipt))
        validation (validate-receipt candidate)]
    (when-not (:valid? validation)
      (throw (ex-info "governed-authority result receipt is invalid" validation)))
    candidate))

(defn verify-receipt [receipt]
  (:valid? (validate-receipt receipt)))
