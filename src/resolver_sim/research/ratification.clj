(ns resolver-sim.research.ratification
  "Exact-target researcher contributions and policy-governed ratification.

   This module consumes existing signed researcher-decision.v2 artifacts. It
   does not issue governance authority, Sew rulings, settlement, or rewards."
  (:require [resolver-sim.benchmark.researcher-force-authorisation :as decisions]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as ref]))

(def target-schema "prf/research-target.v1")
(def policy-schema "prf/research-ratification-policy.v1")
(def contribution-schema "prf/research-contribution.v1")
(def ratification-schema "prf/research-ratification.v1")
(def contribution-kinds #{:approve :dissent :challenge :reproduction})
(def outcomes #{:ratified :ratified-with-dissent :contested-unresolved :rejected})

(def target-fields
  #{:target/domain :target/subject-root :target/root :target/evidence-root
    :target/round-root :target/policy-root :target/schema :target/predecessor-root})

(defn- sha256? [value]
  (ref/valid-sha256-ref? value))

(defn target-root [target]
  (ref/sha256-ref
   (canonical/domain-hash :research-ratification-target-v1
                          (dissoc target :target/root))))

(defn validate-target [target]
  (let [errors (cond-> []
                 (not (map? target)) (conj :target-not-a-map)
                 (not (keyword? (:target/domain target))) (conj :invalid-domain)
                 (not (sha256? (:target/subject-root target))) (conj :invalid-subject-root)
                 (not (sha256? (:target/evidence-root target))) (conj :invalid-evidence-root)
                 (not (sha256? (:target/round-root target))) (conj :invalid-round-root)
                 (not (sha256? (:target/policy-root target))) (conj :invalid-policy-root)
                 (not= target-schema (:schema/version target)) (conj :unsupported-target-schema)
                 (not (string? (:target/schema target))) (conj :invalid-target-schema)
                 (and (:target/predecessor-root target)
                      (not (sha256? (:target/predecessor-root target))))
                 (conj :invalid-predecessor-root)
                 (and (:target/root target) (not= (:target/root target) (target-root target)))
                 (conj :target-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build-target
  [{:keys [domain subject-root evidence-root round-root policy-root schema predecessor-root]}]
  (let [target {:schema/version target-schema
                :target/domain domain
                :target/subject-root subject-root
                :target/evidence-root evidence-root
                :target/round-root round-root
                :target/policy-root policy-root
                :target/schema schema
                :target/predecessor-root predecessor-root}]
    (when-not (:valid? (validate-target target))
      (throw (ex-info "Research target is invalid" (validate-target target))))
    (assoc target :target/root (target-root target))))

(defn policy-root [policy]
  (ref/sha256-ref
   (canonical/domain-hash :research-ratification-policy-v1
                          (-> policy
                              (dissoc :policy/root)
                              (update :policy/eligible-principals #(vec (sort %)))))))

(defn validate-policy [policy]
  (let [errors (cond-> []
                 (not= policy-schema (:schema/version policy)) (conj :unsupported-policy-schema)
                 (not (keyword? (:policy/id policy))) (conj :invalid-policy-id)
                 (not (set? (:policy/eligible-principals policy))) (conj :invalid-eligible-principals)
                 (empty? (:policy/eligible-principals policy)) (conj :empty-eligible-principals)
                 (not (and (integer? (:policy/threshold policy))
                           (pos? (:policy/threshold policy)))) (conj :invalid-threshold)
                 (and (integer? (:policy/threshold policy))
                      (> (:policy/threshold policy)
                         (count (:policy/eligible-principals policy))))
                 (conj :threshold-exceeds-eligible-principals)
                 (not (boolean? (:policy/allow-dissent? policy))) (conj :invalid-dissent-policy)
                 (not (boolean? (:policy/challenges-block? policy))) (conj :invalid-challenge-policy)
                 (and (:policy/root policy) (not= (:policy/root policy) (policy-root policy)))
                 (conj :policy-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build-policy
  [{:keys [id eligible-principals threshold allow-dissent? challenges-block?]}]
  (let [policy {:schema/version policy-schema
                :policy/id id
                :policy/eligible-principals (set eligible-principals)
                :policy/threshold threshold
                :policy/allow-dissent? (boolean allow-dissent?)
                :policy/challenges-block? (boolean challenges-block?)}
        validation (validate-policy policy)]
    (when-not (:valid? validation)
      (throw (ex-info "Research ratification policy is invalid" validation)))
    (assoc policy :policy/root (policy-root policy))))

(defn contribution-root [contribution]
  (ref/sha256-ref
   (canonical/domain-hash :research-contribution-v1
                          (dissoc contribution :contribution/root))))

(defn contribution-subject-root
  "Commit to the exact target, principal, contribution type, and evidence
   reference before creating the existing signed decision.v2 artifact."
  [{:keys [target-root principal-id kind evidence-root]}]
  (ref/sha256-ref
   (canonical/domain-hash :research-contribution-subject-v1
                          {:contribution/target-root target-root
                           :contribution/principal-id principal-id
                           :contribution/kind kind
                           :contribution/evidence-root evidence-root})))

(defn build-contribution
  "Bind one signed researcher-decision.v2 and optional challenge/reproduction
   evidence to the exact target. Evidence references are preserved verbatim;
   their scientific correctness is outside this admission check."
  [{:keys [target principal-id decision kind evidence-root]}]
  (let [contribution {:schema/version contribution-schema
                      :contribution/target-root (:target/root target)
                      :contribution/principal-id principal-id
                      :contribution/kind kind
                      :contribution/decision decision
                      :contribution/evidence-root evidence-root}
        errors (cond-> []
                 (not (:valid? (validate-target target))) (conj :invalid-target)
                 (not (contains? contribution-kinds kind)) (conj :invalid-kind)
                 (not= (contribution-subject-root
                        {:target-root (:target/root target)
                         :principal-id principal-id
                         :kind kind
                         :evidence-root evidence-root})
                       (:outcome/root decision))
                 (conj :decision-contribution-mismatch)
                 (not= (:target/root target) (:authorisation/request-root decision))
                 (conj :decision-request-mismatch)
                 (not= (:target/round-root target) (:review-round/hash decision))
                 (conj :decision-round-mismatch)
                 (not= decisions/decision-v2-schema-version (:schema-version decision))
                 (conj :legacy-or-unsupported-decision-schema)
                 (not= (:target/root target) (:contribution/target-root contribution))
                 (conj :contribution-target-mismatch)
                 (not (contains? #{:approve :dissent} (:decision decision)))
                 (conj :unsupported-signed-decision)
                 (not= (if (= :approve kind) :approve :dissent) (:decision decision))
                 (conj :contribution-kind-decision-mismatch)
                 (and (contains? #{:challenge :reproduction} kind)
                      (not (sha256? evidence-root))) (conj :missing-evidence-root))]
    (when (seq errors)
      (throw (ex-info "Research contribution is not bound to its target"
                      {:errors errors :target-root (:target/root target)})))
    (assoc contribution :contribution/root (contribution-root contribution))))

(defn- decision-signature-valid? [decision {:keys [public-key-path]}]
  (:valid? (decisions/verify-signed-decision-v2 decision public-key-path)))

(defn- contribution-errors [target policy contribution key-bindings]
  (let [decision (:contribution/decision contribution)
        principal (:contribution/principal-id contribution)
        binding (get key-bindings (:researcher/id decision))]
    (cond-> []
      (not= (:target/root target) (:contribution/target-root contribution))
      (conj :foreign-target)
      (not (contains? (:policy/eligible-principals policy) principal))
      (conj :ineligible-principal)
      (not= principal (:principal/id binding)) (conj :principal-binding-mismatch)
      (not= (:researcher/id decision) (:researcher/id binding))
      (conj :researcher-binding-mismatch)
      (not (decision-signature-valid? decision binding)) (conj :invalid-signature)
      (not= contribution-schema (:schema/version contribution)) (conj :invalid-contribution-schema)
      (not= (:contribution/root contribution) (contribution-root contribution))
      (conj :contribution-root-mismatch)
      (not= (contribution-subject-root
             {:target-root (:target/root target)
              :principal-id principal
              :kind (:contribution/kind contribution)
              :evidence-root (:contribution/evidence-root contribution)})
            (:outcome/root decision)) (conj :decision-contribution-mismatch)
      (not= (:target/root target) (:authorisation/request-root decision))
      (conj :request-target-mismatch)
      (not= (:target/round-root target) (:review-round/hash decision))
      (conj :round-mismatch))))

(defn ratification-root [ratification]
  (ref/sha256-ref
   (canonical/domain-hash :research-ratification-v1
                          (dissoc ratification :ratification/root))))

(defn reconstruct-ratification
  "Independently evaluate contributions against a target and policy. Key
   bindings map researcher IDs to {:principal/id ... :public-key-path ...}.
   Returns a deterministic artifact or throws with typed validation errors."
  [{:keys [target policy contributions key-bindings]}]
  (let [target-errors (:errors (validate-target target))
        policy-errors (:errors (validate-policy policy))
        contributions (vec contributions)
        per-contribution (mapv #(contribution-errors target policy % key-bindings)
                               contributions)
        errors (cond-> []
                 (seq target-errors) (into (map #(keyword (str "target-" (name %))) target-errors))
                 (seq policy-errors) (into (map #(keyword (str "policy-" (name %))) policy-errors))
                 (not= (:target/policy-root target) (:policy/root policy))
                 (conj :target-policy-mismatch)
                 (some seq per-contribution) (conj :invalid-contribution)
                 (not= (count contributions)
                       (count (set (map :researcher/id (map :contribution/decision contributions)))))
                 (conj :duplicate-researcher)
                 (not= (count contributions)
                       (count (set (map :contribution/principal-id contributions))))
                 (conj :duplicate-principal))]
    (when (seq errors)
      (throw (ex-info "Research ratification inputs failed verification"
                      {:errors errors :contribution-errors per-contribution})))
    (let [approvals (filter #(= :approve (get-in % [:contribution/decision :decision])) contributions)
          dissents (filter #(= :dissent (get-in % [:contribution/decision :decision])) contributions)
          challenges (filter #(= :challenge (:contribution/kind %)) contributions)
          complete? (= (set (:policy/eligible-principals policy))
                       (set (map :contribution/principal-id contributions)))
          status (cond
                   (< (count approvals) (:policy/threshold policy))
                   (if complete? :rejected :contested-unresolved)
                   (and (:policy/challenges-block? policy) (seq challenges)) :contested-unresolved
                   (and (seq dissents) (not (:policy/allow-dissent? policy))) :contested-unresolved
                   (seq dissents) :ratified-with-dissent
                   :else :ratified)
          base {:schema/version ratification-schema
                :ratification/target-root (:target/root target)
                :ratification/policy-root (:policy/root policy)
                :ratification/contribution-roots (vec (sort (map :contribution/root contributions)))
                :ratification/approval-count (count approvals)
                :ratification/dissent-count (count dissents)
                :ratification/challenge-roots (vec (sort (keep :contribution/evidence-root challenges)))
                :ratification/status status
                :ratification/operational-authority? false}]
      (assoc base :ratification/root (ratification-root base)))))

(defn validate-ratification
  "Reconstruct and compare the complete persisted result; a stored status is
   never accepted as evidence on its own."
  [persisted inputs]
  (try
    (let [expected (reconstruct-ratification inputs)]
      {:valid? (= persisted expected)
       :errors (cond-> []
                 (not= persisted expected) (conj :ratification-mismatch)
                 (not= (:ratification/root persisted) (ratification-root persisted))
                 (conj :ratification-root-mismatch))})
    (catch clojure.lang.ExceptionInfo error
      {:valid? false :errors (or (:errors (ex-data error)) [:invalid-inputs])})))
