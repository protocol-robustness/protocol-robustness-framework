(ns resolver-sim.protocols.sew.adjudication-case
  "Pure, machine-semantic Case V1 artifacts for SEW adjudication.

   These artifacts describe what is adjudicated. They do not establish who may
   adjudicate it, select evidence membership, or render human/Kleros wording."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.protocols.sew.evidence-admission :as evidence-admission]))

(def question-schema "sew/dispute-question.v1")
(def ruling-schema "sew/ruling-schema.v1")
(def policy-schema "sew/adjudication-policy.v1")
(def definition-schema "sew/adjudication-case-definition.v1")
(def case-schema "sew/adjudication-case.v1")
(def outcome-mapping {:release :release-to-recipient
                      :refund :refund-to-sender
                      :refuse :no-settlement})

(defn- root [domain payload] (ref/sha256-ref (hc/domain-hash domain (hc/project-canonical-safe payload))))
(defn- valid-ref? [x] (or (nil? x) (ref/valid-sha256-ref? x)))
(defn- artifact [schema domain root-key fields]
  (let [base (assoc fields :artifact/schema schema)]
    (assoc base root-key (root domain base))))
(defn- valid-artifact? [schema domain root-key required reference-fields x]
  (and (map? x)
       (= schema (:artifact/schema x))
       (= (set (keys x)) (conj required root-key))
       (every? #(contains? x %) required)
       (every? #(valid-ref? (get x %)) reference-fields)
       (= (get x root-key) (root domain (dissoc x root-key)))))

(defn build-dispute-question
  "Build the canonical machine question. `:predecessor-decision/root` is nil for
   the initial adjudication and a rooted ruling/decision reference for a successor."
  [{:keys [workflow-id round-id subject-root predecessor-decision-root]}]
  (when-not (and (integer? workflow-id) (not (neg? workflow-id))
                 (integer? round-id) (not (neg? round-id))
                 (ref/valid-sha256-ref? subject-root)
                 (valid-ref? predecessor-decision-root))
    (throw (ex-info "Invalid SEW dispute question inputs" {:type :invalid-dispute-question})))
  (artifact question-schema :sew-dispute-question-v1 :sew/dispute-question-root
            {:workflow/id workflow-id :adjudication/round-id round-id
             :subject/root subject-root :issue :escrow-resolution
             :predecessor-decision/root predecessor-decision-root
             :ruling/outcome-mapping outcome-mapping}))

(defn valid-dispute-question? [x]
  (and (valid-artifact? question-schema :sew-dispute-question-v1 :sew/dispute-question-root
                        #{:artifact/schema :workflow/id :adjudication/round-id :subject/root :issue
                          :predecessor-decision/root :ruling/outcome-mapping}
                        #{:subject/root :predecessor-decision/root} x)
       (= :escrow-resolution (:issue x)) (= outcome-mapping (:ruling/outcome-mapping x))))

(defn build-ruling-schema []
  (artifact ruling-schema :sew-ruling-schema-v1 :sew/ruling-schema-root
            {:ruling/outcomes [:release :refund :refuse]
             :ruling/outcome-mapping outcome-mapping}))
(defn valid-ruling-schema? [x]
  (and (valid-artifact? ruling-schema :sew-ruling-schema-v1 :sew/ruling-schema-root
                        #{:artifact/schema :ruling/outcomes :ruling/outcome-mapping} #{} x)
       (= [:release :refund :refuse] (:ruling/outcomes x))
       (= outcome-mapping (:ruling/outcome-mapping x))))

(defn build-adjudication-policy []
  (artifact policy-schema :sew-adjudication-policy-v1 :sew/adjudication-policy-root
            {:policy/allowed-rulings [:release :refund :refuse]
             :policy/refuse-semantics :no-settlement
             :policy/evidence-basis :closed-canonical-round-evidence-set}))
(defn valid-adjudication-policy? [x]
  (and (valid-artifact? policy-schema :sew-adjudication-policy-v1 :sew/adjudication-policy-root
                        #{:artifact/schema :policy/allowed-rulings :policy/refuse-semantics :policy/evidence-basis} #{} x)
       (= [:release :refund :refuse] (:policy/allowed-rulings x))
       (= :no-settlement (:policy/refuse-semantics x))
       (= :closed-canonical-round-evidence-set (:policy/evidence-basis x))))

(defn build-case-definition [question ruling-schema policy]
  (when-not (and (valid-dispute-question? question) (valid-ruling-schema? ruling-schema) (valid-adjudication-policy? policy))
    (throw (ex-info "Invalid Case V1 definition inputs" {:type :invalid-adjudication-case-definition})))
  (artifact definition-schema :sew-adjudication-case-definition-v1 :sew/adjudication-case-definition-root
            {:workflow/id (:workflow/id question) :adjudication/round-id (:adjudication/round-id question)
             :question/root (:sew/dispute-question-root question)
             :ruling-schema/root (:sew/ruling-schema-root ruling-schema)
             :adjudication-policy/root (:sew/adjudication-policy-root policy)}))

(defn valid-case-definition?
  "Reconstruct a definition from its validated components and require equality."
  [definition question ruling-schema policy]
  (and (valid-dispute-question? question)
       (valid-ruling-schema? ruling-schema)
       (valid-adjudication-policy? policy)
       (= definition (build-case-definition question ruling-schema policy))))

(defn build-adjudication-case [definition evidence-set]
  (when-not (and (ref/valid-sha256-ref? (:sew/adjudication-case-definition-root definition))
                 (:valid? (evidence-admission/validate-evidence-set evidence-set)))
    (throw (ex-info "Invalid Case V1 inputs" {:type :invalid-adjudication-case})))
  (artifact case-schema :sew-adjudication-case-v1 :sew/adjudication-case-root
            {:workflow/id (:workflow/id definition) :adjudication/round-id (:adjudication/round-id definition)
             :case-definition/root (:sew/adjudication-case-definition-root definition)
             :evidence-set/root (:sew/dispute-evidence-set/root evidence-set)}))

(defn valid-adjudication-case?
  "Reconstruct a case from the exact definition, question, and closed evidence
   set. Cross-workflow/round component substitution fails before root equality."
  [adjudication-case definition question ruling-schema policy evidence-set]
  (and (valid-case-definition? definition question ruling-schema policy)
       (:valid? (evidence-admission/validate-evidence-set evidence-set))
       (= (:workflow/id question) (:workflow/id evidence-set))
       (= (:adjudication/round-id question) (:adjudication/round-id evidence-set))
       (= adjudication-case (build-adjudication-case definition evidence-set))))

(defn- subject-root [world workflow-id]
  (root :sew-adjudication-case-subject-v1
        {:workflow/id workflow-id
         :escrow (get-in world [:escrow-transfers workflow-id])}))

(defn reconstruct-authoritative-case
  "Reconstruct Case V1 from authoritative SEW world state. The evidence set is
   available only after its round admission has closed; no case/root input is
   accepted from a caller."
  [world workflow-id round-id]
  (let [evidence-set (evidence-admission/reconstruct-evidence-set world workflow-id round-id)
        predecessor (get-in world [:adjudication-predecessor-decisions workflow-id round-id])]
    (when (and (integer? workflow-id) (not (neg? workflow-id))
               (= round-id (get-in world [:dispute-levels workflow-id] 0))
               evidence-set
               (:valid? (evidence-admission/validate-evidence-set evidence-set)))
      (let [question (build-dispute-question {:workflow-id workflow-id :round-id round-id
                                              :subject-root (subject-root world workflow-id)
                                              :predecessor-decision-root predecessor})
            ruling-schema (build-ruling-schema)
            policy (build-adjudication-policy)
            definition (build-case-definition question ruling-schema policy)
            adjudication-case (build-adjudication-case definition evidence-set)]
        {:question question :ruling-schema ruling-schema :policy policy
         :case-definition definition :evidence-set evidence-set
         :adjudication-case adjudication-case}))))

(defn adjudication-round-fence-root [world workflow-id round-id]
  "The exact state projection Case V1 publication compares before selection."
  (root :sew-adjudication-case-fence-v1
        {:workflow/id workflow-id :adjudication/round-id round-id
         :escrow (get-in world [:escrow-transfers workflow-id])
         :level (get-in world [:dispute-levels workflow-id] 0)
         :admission (get-in world [:dispute-evidence-admissions workflow-id round-id])
         :predecessor-decision (get-in world [:adjudication-predecessor-decisions workflow-id round-id])}))

(defn publish-adjudication-case!
  "Atomically select the one reconstructed Case V1 artifact for a frozen round.
   The expected fence detects stale round state; repeated identical publication
   is idempotent and a distinct existing case is never replaceable."
  [world workflow-id round-id expected-round-fence]
  (let [observed-fence (adjudication-round-fence-root world workflow-id round-id)
        reconstructed (reconstruct-authoritative-case world workflow-id round-id)
        existing (get-in world [:adjudication-published-cases workflow-id round-id])
        case-root (get-in reconstructed [:adjudication-case :sew/adjudication-case-root])]
    (cond
      (not= expected-round-fence observed-fence) {:ok false :error :stale-adjudication-case-fence :world world}
      (nil? reconstructed) {:ok false :error :adjudication-case-unavailable :world world}
      (nil? existing) {:ok true :world (assoc-in world [:adjudication-published-cases workflow-id round-id]
                                                {:case/root case-root :case (:adjudication-case reconstructed)})
                        :case (:adjudication-case reconstructed)}
      (= (:case/root existing) case-root) {:ok true :world world :case (:case existing) :idempotent? true}
      :else {:ok false :error :conflicting-adjudication-case-publication :world world})))
