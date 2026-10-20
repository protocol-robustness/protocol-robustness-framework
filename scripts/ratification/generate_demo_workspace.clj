(ns scripts.ratification.generate-demo-workspace
  "Generate the public, test-key-backed wire example for the draft ratification API."
  (:require [clojure.data.json :as json]
            [resolver-sim.benchmark.researcher-force-authorisation :as decisions]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.research.ratification :as ratification]))

(def ^:private private-key "test-keys/test-researcher-signing-key")
(def ^:private public-key "test-keys/test-researcher-signing-key.pub")
(def ^:private signed-at "2026-01-01T00:00:00Z")

(defn- root [value]
  (ref/sha256-ref (canonical/domain-hash :evidence-record {:demo/value value})))

(defn- principal-name [principal]
  (if-let [space (namespace principal)]
    (str space "/" (name principal))
    (name principal)))

(defn- wire-target [target]
  (cond-> {"schemaVersion" (:schema/version target)
           "domain" (principal-name (:target/domain target))
           "subjectRoot" (:target/subject-root target)
           "evidenceRoot" (:target/evidence-root target)
           "roundRoot" (:target/round-root target)
           "policyRoot" (:target/policy-root target)
           "targetSchema" (:target/schema target)
           "root" (:target/root target)}
    (:target/predecessor-root target)
    (assoc "predecessorRoot" (:target/predecessor-root target))))

(defn- wire-policy [policy]
  {"schemaVersion" (:schema/version policy)
   "id" (principal-name (:policy/id policy))
   "authorityRoot" (:policy/authority-root policy)
   "eligiblePrincipals" (mapv principal-name (:policy/eligible-principals policy))
   "threshold" (:policy/threshold policy)
   "allowDissent" (:policy/allow-dissent? policy)
   "challengesBlock" (:policy/challenges-block? policy)
   "root" (:policy/root policy)})

(defn- wire-decision [decision]
  (cond-> {"schemaVersion" (:schema-version decision)
           "researcherId" (:researcher/id decision)
           "authorisationId" (:authorisation/id decision)
           "requestRoot" (:authorisation/request-root decision)
           "reviewRoundRoot" (:review-round/hash decision)
           "outcomeRoot" (:outcome/root decision)
           "decision" (name (:decision decision))
           "decisionHash" (:decision/hash decision)
           "signature" {"algorithm" (name (get-in decision [:signature :algorithm]))
                        "value" (get-in decision [:signature :value])
                        "signedAt" signed-at}}
    (:signing-key/id decision) (assoc "signingKeyId" (:signing-key/id decision))
    (:dissent/reason decision) (assoc "dissentReason" (:dissent/reason decision))))

(defn- contribution [target principal decision-kind contribution-kind]
  (let [principal-id (keyword "researcher" (name principal))
        outcome-root (ratification/contribution-subject-root
                      {:target-root (:target/root target)
                       :principal-id principal-id
                       :kind contribution-kind
                       :evidence-root nil})
        decision (decisions/build-signed-decision-v2
                  (name principal) (:target/root target) (:target/root target)
                  (:target/round-root target) outcome-root decision-kind private-key
                  :signing-key-id (str "demo-test-key/" (name principal))
                  :dissent-reason (when (= :dissent decision-kind)
                                    "The dissent is retained on this exact basis."))
        decision (assoc-in decision [:signature :signed-at] signed-at)
        item (ratification/build-contribution
              {:target target :principal-id principal-id :decision decision
               :kind contribution-kind})]
    {:model item
     :wire {"contributionRoot" (:contribution/root item)
            "principalId" (principal-name principal-id)
            "kind" (name contribution-kind)
            "signatureStatus" "verified"
            "recordedAt" signed-at
            "decision" (wire-decision decision)}}))

(defn workspace []
  (let [round-root (root :demo-round)
        evidence-root (root :demo-evidence)
        authority-root (root :demo-authority)
        policy (ratification/build-policy
                {:id :policy/demo-research-review
                 :authority-root authority-root
                 :eligible-principals #{:researcher/alex :researcher/blair :researcher/casey}
                 :threshold 2
                 :allow-dissent? true
                 :challenges-block? true})
        target (ratification/build-target
                {:domain :prf/analytical-claim
                 :subject-root (root :demo-claim)
                 :evidence-root evidence-root
                 :round-root round-root
                 :policy-root (:policy/root policy)
                 :schema "analytical-claim-basis.v1"})
        contributions [(contribution target :researcher/alex :approve :approve)
                       (contribution target :researcher/blair :approve :approve)
                       (contribution target :researcher/casey :dissent :dissent)]
        model-contributions (mapv :model contributions)
        key-bindings (into {}
                           (for [researcher ["alex" "blair" "casey"]]
                             [researcher {:researcher/id researcher
                                          :principal/id (keyword "researcher" researcher)
                                          :public-key-path public-key}]))
        ratification-result (ratification/reconstruct-ratification
                             {:target target :policy policy
                              :contributions model-contributions
                              :key-bindings key-bindings})]
    {"demoOnly" true
     "target" (wire-target target)
     "policy" (wire-policy policy)
     "contributions" (mapv :wire contributions)
     "ratification" {"schemaVersion" (:schema/version ratification-result)
                     "targetRoot" (:ratification/target-root ratification-result)
                     "policyRoot" (:ratification/policy-root ratification-result)
                     "contributionRoots" (:ratification/contribution-roots ratification-result)
                     "approvalCount" (:ratification/approval-count ratification-result)
                     "dissentCount" (:ratification/dissent-count ratification-result)
                     "challengeRoots" (:ratification/challenge-roots ratification-result)
                     "status" (name (:ratification/status ratification-result))
                     "operationalAuthority" (:ratification/operational-authority? ratification-result)
                     "root" (:ratification/root ratification-result)
                     "verification" "verified"}}))

(defn -main [& _]
  (println (json/write-str (workspace) :escape-slash false)))
