(ns resolver-sim.risk.limit-policy
  "risk-limit-policy.v1 — the ONLY authoritative artifact of the phase-1 risk
   kernel. It is governance-controlled: it must be committed (directly or via
   a chain-configuration sub-root) by existing authority machinery; risk
   projections and evaluations are derived evidence and never grant authority.

   The V1 policy language is deliberately small. Exactly three limit kinds:

     :global        — total exposure (selected basis) <= :limit/amount
     :domain        — exposure attributed to the EXACT :risk/domain identity
                      <= :limit/amount
     :concentration — per-member exposure within :risk/domain: each distinct
                      member's attributed exposure <= :limit/amount

   The basis is :conservative-peak or :after. Prefer :conservative-peak: a
   transition ending at $700k must not pass an $800k limit if it temporarily
   exposes $950k. :conservative-peak is the SUM OF PER-ROW INDIVIDUAL PEAKS —
   an upper bound on the exact aggregate exposure at any path point, never an
   exact simultaneous peak (exact path peak is a future measure). :after is
   the exact final-state aggregate.

   No expression language. The policy commits to exact domain identities it
   constrains, even though the domain taxonomy itself is not authoritative.

   Canonical policy data uses vectors, never sets."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def schema-version "risk-limit-policy.v1")

(def ^:private sha256-ref-re #"sha256:[0-9a-f]{64}")

(def policy-fields
  "Closed field set of risk-limit-policy.v1."
  [:risk-limit-policy/schema
   :risk-limit-policy/id
   :risk-limit-policy/unit-root
   :risk-limit-policy/basis
   :risk-limit-policy/limits
   :risk-limit-policy/root])

(def limit-kinds #{:global :domain :concentration})
(def policy-bases #{:conservative-peak :after})

(def ^:private kind-fields
  {:global [:limit/id :limit/kind :limit/amount]
   :domain [:limit/id :limit/kind :limit/amount :risk/domain]
   :concentration [:limit/id :limit/kind :limit/amount :risk/domain]})

(defn- validate-limit!
  [errors {:limit/keys [id kind amount] :as limit}]
  (if-not (map? limit)
    (swap! errors conj "limit not a map")
    (do
      (when-not (contains? limit-kinds kind)
        (swap! errors conj (str "unknown limit kind " kind)))
      (let [expected (get kind-fields kind)]
        (when (and expected (not= (set expected) (set (keys limit))))
          (swap! errors conj (str "limit fields not closed for " id))))
      (when-not (and (string? id) (seq id))
        (swap! errors conj "limit requires string :limit/id"))
      (when-not (and (int? amount) (pos? amount))
        (swap! errors conj (str "limit amount must be positive integer for " id)))
      (when (contains? #{:domain :concentration} kind)
        (when-not (and (string? (:risk/domain limit)) (seq (:risk/domain limit)))
          (swap! errors conj (str "limit requires :risk/domain for " id)))))))

(defn policy-root
  [p]
  (hash-ref/sha256-ref
   (hc/domain-hash :prf-risk-limit-policy-v1
                   {:schema-version (:risk-limit-policy/schema p)
                    :id (:risk-limit-policy/id p)
                    :unit/root (:risk-limit-policy/unit-root p)
                    :basis (:risk-limit-policy/basis p)
                    :limits (:risk-limit-policy/limits p)})))

(defn policy
  "Build a validated authoritative risk-limit-policy.v1. `limits` is a vector
   (canonical order matters and is committed) of:
     {:limit/kind :global        :limit/id s :limit/amount n}
     {:limit/kind :domain        :limit/id s :risk/domain s :limit/amount n}
     {:limit/kind :concentration :limit/id s :risk/domain s :limit/amount n}
   Duplicate :limit/ids are rejected. The root is always derived."
  [{:keys [id unit/root basis limits]}]
  (when-not (and (string? id) (seq id))
    (throw (ex-info "policy requires string :risk-limit-policy/id" {:id id})))
  (when-not (and (string? root) (re-matches sha256-ref-re root))
    (throw (ex-info "policy requires :risk-limit-policy/unit-root sha256 ref"
                    {:unit/root root})))
  (when-not (contains? policy-bases basis)
    (throw (ex-info "policy basis must be :conservative-peak or :after" {:basis basis})))
  (when-not (vector? limits)
    (throw (ex-info "policy limits must be a vector" {})))
  (let [errors (atom [])]
    (doseq [l limits] (validate-limit! errors l))
    (let [ids (map :limit/id limits)]
      (when-not (= (count ids) (count (distinct ids)))
        (swap! errors conj "duplicate :limit/id")))
    (when (seq @errors)
      (throw (ex-info "invalid risk-limit-policy limits" {:errors @errors}))))
  (let [base {:risk-limit-policy/schema schema-version
              :risk-limit-policy/id id
              :risk-limit-policy/unit-root root
              :risk-limit-policy/basis basis
              :risk-limit-policy/limits limits}]
    (assoc base :risk-limit-policy/root (policy-root base))))

(defn validate-policy
  "Strict fail-closed closed-shape validation. Returns {:valid? :errors}."
  [p]
  (let [errors (atom [])]
    (if-not (map? p)
      {:valid? false :errors ["not a map"]}
      (do
        (doseq [k (keys p)]
          (when-not (contains? (set policy-fields) k)
            (swap! errors conj (str "unknown key " k))))
        (doseq [k policy-fields]
          (when-not (contains? p k)
            (swap! errors conj (str "missing key " k))))
        (when (= schema-version (:risk-limit-policy/schema p))
          (when-not (and (string? (:risk-limit-policy/id p)) (seq (:risk-limit-policy/id p)))
            (swap! errors conj "policy :id not a string"))
          (when-not (re-matches sha256-ref-re (str (:risk-limit-policy/unit-root p)))
            (swap! errors conj "malformed :risk-limit-policy/unit-root"))
          (when-not (contains? policy-bases (:risk-limit-policy/basis p))
            (swap! errors conj "invalid :risk-limit-policy/basis"))
          (let [limits (:risk-limit-policy/limits p)]
            (if-not (vector? limits)
              (swap! errors conj ":risk-limit-policy/limits must be a vector")
              (do
                (doseq [l limits] (validate-limit! errors l))
                (let [ids (map :limit/id limits)]
                  (when-not (= (count ids) (count (distinct ids)))
                    (swap! errors conj "duplicate :limit/id")))))))
        {:valid? (empty? @errors) :errors @errors}))))

(defn policy-valid?
  [p]
  (:valid? (validate-policy p)))

(defn verify-root
  [p]
  (cond
    (not (policy-valid? p)) {:status :fail :reason :invalid-policy}
    (not= (:risk-limit-policy/root p) (policy-root p))
    {:status :fail :reason :root-mismatch}
    :else {:status :pass}))
