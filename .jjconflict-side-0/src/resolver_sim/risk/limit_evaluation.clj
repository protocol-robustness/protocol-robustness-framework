(ns resolver-sim.risk.limit-evaluation
  "risk-limit-evaluation.v1 — derived evidence, never authoritative.

   Evaluates one risk-projection.v1 against one risk-limit-policy.v1:

     current state + proposed operation
       -> candidate/transition risk projection
       -> risk-limit evaluation

   Attribution semantics (shared with risk-projection):
     - :global limits are evaluated against the projection summary (sum of
       per-row basis amounts — rows of one projection ARE aggregateable).
     - :domain limits sum per-row basis amounts over rows whose
       :exposure/domains contain the EXACT :risk/domain identity committed by
       the policy. Different domains are never summed together.
     - :concentration limits group, per distinct member (the attribution
       entry's :risk/member, defaulting to the row's :exposure/subject-root),
       the basis amounts of rows carrying the domain; the limit binds the
       maximum member total.

   Basis semantics:
     - :conservative-peak — the SUM OF PER-ROW INDIVIDUAL PEAKS. For :global,
       :domain, and :concentration alike this is an UPPER BOUND on the exact
       path-level peak (actual path peak <= evaluated amount), never an exact
       simultaneous peak. Using it as a policy basis is therefore safe for
       admission (harder to pass than exact path peak).
     - :after — the exact final-state aggregate (evaluated/after).

   Live-consumption state binding:

     A live admission evaluation must be evaluated with evaluate-bound, which
     fails closed unless the projection is exact-state-bound (time-basis
     :state-derived) AND :risk-projection/source-root equals the exact SOURCE
     (predecessor) state root the projection derives from. evaluate (unbound)
     remains available for observation/history/research; its results are not
     live admission evidence. Candidate (post-transition) binding is provided
     by deriving the projection from the exact transition being admitted.

   Fail-closed: evaluation refuses (throws) on invalid projections or
   policies, unit mismatch, or policy roots that do not verify. A passing
   evaluation commits both roots, so a consumer can independently re-verify
   what evidence was evaluated against which authorized policy."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.risk.limit-policy :as lp]
            [resolver-sim.risk.projection :as rp]))

(def schema-version "risk-limit-evaluation.v1")

(def evaluation-fields
  "Closed field set of risk-limit-evaluation.v1."
  [:risk-limit-evaluation/schema
   :risk-limit-evaluation/policy-root
   :risk-limit-evaluation/projection-root
   :risk-limit-evaluation/basis
   :risk-limit-evaluation/status
   :risk-limit-evaluation/results
   :risk-limit-evaluation/root])

(defn- basis-amount
  [{:exposure/keys [after peak]} basis]
  (case basis
    :conservative-peak peak
    :after after
    (throw (ex-info "unknown basis" {:basis basis}))))

(defn- domain-entries
  "Domain entries of a row matching the exact policy domain identity."
  [row domain]
  (filterv #(= (:risk/domain %) domain) (:exposure/domains row)))

(defn- member-key
  "Concentration member identity: the attribution entry's :risk/member, or the
   row's :exposure/subject-root when omitted. Canonical domain entries never
   carry a member equal to the subject root (see risk.projection), so this
   fallback is deterministic and unambiguous."
  [row entry]
  (or (:risk/member entry) (:exposure/subject-root row)))

(defn- conservative-peak-sum
  [rows]
  (reduce + 0 (map #(basis-amount % :conservative-peak) rows)))

(defn- after-sum
  [rows]
  (reduce + 0 (map #(basis-amount % :after) rows)))

(defn- member-totals
  "Per-member sums of the basis amount over the rows' matching domain entries,
   sorted by member. Each member's total is the conservative sum of that
   member's per-row peaks (basis :conservative-peak) or its exact aggregate
   (basis :after)."
  [rows basis domain]
  (->> rows
       (mapcat (fn [row]
                 (map (fn [entry]
                        {:member (member-key row entry)
                         :amount (basis-amount row basis)})
                      (domain-entries row domain))))
       (group-by :member)
       (map (fn [[m ms]] {:risk/member m
                          :exposure/amount (reduce + 0 (map :amount ms))}))
       (sort-by :risk/member)
       vec))

(defn- evaluate-limit
  [rows basis {:limit/keys [id kind amount] :risk/keys [domain]}]
  (let [selected-rows (if (= kind :global)
                        rows
                        (filterv #(seq (domain-entries % domain)) rows))
        evaluated-amount
        (case kind
          :global (reduce + 0 (map #(basis-amount % basis) rows))
          :domain (reduce + 0 (map #(basis-amount % basis) selected-rows))
          :concentration (->> (member-totals selected-rows basis domain)
                              (map :exposure/amount)
                              (apply max 0)))
        status (if (<= evaluated-amount amount) :pass :violation)
        base {:limit/id id
              :limit/kind kind
              :limit/amount amount
              :evaluated/amount evaluated-amount
              :evaluated/conservative-peak (conservative-peak-sum selected-rows)
              :evaluated/after (after-sum selected-rows)
              :status status}]
    (cond-> base
      (contains? #{:domain :concentration} kind) (assoc :risk/domain domain)
      (= kind :concentration)
      (assoc :evaluated/members (member-totals selected-rows basis domain)))))

(defn evaluation-root
  [e]
  (hash-ref/sha256-ref
   (hc/domain-hash :prf-risk-limit-evaluation-v1
                   {:schema-version (:risk-limit-evaluation/schema e)
                    :policy/root (:risk-limit-evaluation/policy-root e)
                    :projection/root (:risk-limit-evaluation/projection-root e)
                    :basis (:risk-limit-evaluation/basis e)
                    :status (:risk-limit-evaluation/status e)
                    :results (:risk-limit-evaluation/results e)})))

(defn evaluate
  "Build a validated risk-limit-evaluation.v1 from a projection and an
   authoritative policy. Both artifacts are fully validated (fail-closed),
   their roots are verified, and units must match. The policy's basis decides
   which per-row amount is limit-compared; conservative-peak and after totals
   are recorded per limit as evidence either way. This entry point does NOT
   enforce exact-state binding — use evaluate-bound for live admission."
  [projection policy]
  (when-not (rp/projection-valid? projection)
    (throw (ex-info "evaluation requires a valid projection"
                    {:errors (:errors (rp/validate-projection projection))})))
  (when-not (= :pass (:status (rp/verify-root projection)))
    (throw (ex-info "projection root verification failed" {})))
  (when-not (lp/policy-valid? policy)
    (throw (ex-info "evaluation requires a valid policy"
                    {:errors (:errors (lp/validate-policy policy))})))
  (when-not (= :pass (:status (lp/verify-root policy)))
    (throw (ex-info "policy root verification failed" {})))
  (when-not (= (:risk-projection/unit-root projection)
               (:risk-limit-policy/unit-root policy))
    (throw (ex-info "policy and projection units differ"
                    {:projection (:risk-projection/unit-root projection)
                     :policy (:risk-limit-policy/unit-root policy)})))
  (let [{basis :risk-limit-policy/basis} policy
        results (mapv #(evaluate-limit (:risk-projection/exposures projection)
                                       basis %)
                      (:risk-limit-policy/limits policy))
        status (if (every? #(= :pass (:status %)) results) :pass :violation)
        base {:risk-limit-evaluation/schema schema-version
              :risk-limit-evaluation/policy-root (:risk-limit-policy/root policy)
              :risk-limit-evaluation/projection-root (:risk-projection/root projection)
              :risk-limit-evaluation/basis basis
              :risk-limit-evaluation/status status
              :risk-limit-evaluation/results results}]
    (assoc base :risk-limit-evaluation/root (evaluation-root base))))

(defn evaluate-bound
  "Live-admission evaluation: like evaluate, but fails closed unless the
   projection is exact-state-bound AND its :risk-projection/source-root equals
   the exact source state root being evaluated:

     exact-source-root == risk-projection/source-root
     AND time-basis {:basis :state-derived}

   The exact source root is the PREDECESSOR state whose exposure the projection
   derives (for pro-rata, canonical-effects/state-root of state-before). It is
   deliberately named exact-source-root to match :risk-projection/source-root;
   it is NOT the post-transition candidate-state root (that binding is provided
   by deriving the projection from the exact transition being admitted).

   A projection that is :as-of (observational/historical) or bound to a
   different source root can never satisfy a live admission check. This
   encodes the invariant a future authoritative consumer must enforce."
  [projection policy exact-source-root]
  (when-not (rp/exact-state-bound? projection)
    (throw (ex-info "live admission requires an exact-state-bound projection"
                    {:time-basis (:risk-projection/time-basis projection)})))
  (when-not (= exact-source-root (:risk-projection/source-root projection))
    (throw (ex-info "exact-source-root does not match projection source-root"
                    {:exact-source-root exact-source-root
                     :projection-source-root (:risk-projection/source-root projection)})))
  (evaluate projection policy))

(defn validate-evaluation
  "Structural validation. Does NOT re-authorize the policy (that is the
   authority machinery's job); it verifies shapes and that the committed
   policy/projection roots are well-formed sha256 refs and that results are
   internally consistent. Returns {:valid? :errors}."
  [e]
  (let [errors (atom [])]
    (if-not (map? e)
      {:valid? false :errors ["not a map"]}
      (do
        (doseq [k (keys e)]
          (when-not (contains? (set evaluation-fields) k)
            (swap! errors conj (str "unknown key " k))))
        (doseq [k evaluation-fields]
          (when-not (contains? e k)
            (swap! errors conj (str "missing key " k))))
        (when (= schema-version (:risk-limit-evaluation/schema e))
          (doseq [k [:risk-limit-evaluation/policy-root
                     :risk-limit-evaluation/projection-root]]
            (when-not (re-matches #"sha256:[0-9a-f]{64}" (str (get e k)))
              (swap! errors conj (str "malformed root " k))))
          (when-not (contains? lp/policy-bases (:risk-limit-evaluation/basis e))
            (swap! errors conj "invalid basis"))
          (when-not (contains? #{:pass :violation} (:risk-limit-evaluation/status e))
            (swap! errors conj "invalid status"))
          (let [results (:risk-limit-evaluation/results e)]
            (if-not (vector? results)
              (swap! errors conj "results must be a vector")
              (do
                (doseq [r results]
                  (cond
                    (not (map? r)) (swap! errors conj "result not a map")
                    (not (contains? #{:pass :violation} (:status r)))
                    (swap! errors conj "result status invalid")
                    (not= (:status r) (if (<= (:evaluated/amount r) (:limit/amount r))
                                        :pass :violation))
                    (swap! errors conj (str "result status inconsistent " (:limit/id r)))))
                (when (seq results)
                  (when-not (= (:risk-limit-evaluation/status e)
                               (if (every? #(= :pass (:status %)) results)
                                 :pass :violation))
                    (swap! errors conj "status inconsistent with results")))))))
        {:valid? (empty? @errors) :errors @errors}))))

(defn evaluation-valid?
  [e]
  (:valid? (validate-evaluation e)))

(defn verify-root
  [e]
  (cond
    (not (evaluation-valid? e)) {:status :fail :reason :invalid-evaluation}
    (not= (:risk-limit-evaluation/root e) (evaluation-root e))
    {:status :fail :reason :root-mismatch}
    :else {:status :pass}))

(defn violations
  "The violating limit ids (empty when :pass)."
  [e]
  (->> (:risk-limit-evaluation/results e)
       (filter #(= :violation (:status %)))
       (mapv :limit/id)))