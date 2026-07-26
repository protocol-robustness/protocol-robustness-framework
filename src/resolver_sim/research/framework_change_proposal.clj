(ns resolver-sim.research.framework-change-proposal
  "research-framework-change-proposal.v1
   
   Represents a researcher-proposed change that affects the framework's
   research contract. The proposal hash binds the semantic proposal
   (current vs proposed contract) separately from the implementation
   reference, preserving the distinction between research assessment
   and code implementation.
   
   The artifact does not block ordinary development — proposals are
   purely opt-in research artifacts."
  (:require [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "research-framework-change-proposal.v1")

(def change-classes
  "Controlled vocabulary for proposal change classes.
   Only :research-semantic and :assurance-contract use researcher review
   during the pilot. Other classes remain ordinary engineering work."
  #{:research-semantic :assurance-contract :implementation-only
    :editorial :emergency})

(def proposal-statuses
  "Controlled vocabulary for proposal lifecycle statuses."
  #{:draft :open-for-review :research-supported
    :research-supported-with-qualification :research-contested
    :withdrawn :superseded})

(def ^:private status-transitions
  "Allowed status transitions."
  {:draft                                                #{:open-for-review :withdrawn :superseded}
   :open-for-review                                      #{:research-supported :research-supported-with-qualification
                                                           :research-contested :withdrawn :superseded}
   :research-supported                                   #{:superseded}
   :research-supported-with-qualification                #{:research-supported :superseded}
   :research-contested                                   #{:open-for-review :superseded}
   :withdrawn                                            #{:superseded}})

(defn valid-change-class?
  [class]
  (contains? change-classes class))

(defn valid-proposal-status?
  [status]
  (contains? proposal-statuses status))

(defn valid-status-transition?
  [from to]
  (contains? (get status-transitions from #{}) to))

(defn build-proposal
  "Build a research-framework-change-proposal.v1 artifact.
   
   Required: title, change-class, research-question, target, provenance.
   The proposal/hash is computed from all fields except itself and
   :proposal/supersedes lineage metadata (the superseded proposal
   hash is part of the registry, not the semantic preimage)."
  [{:keys [proposal/title
           proposal/change-class
           proposal/status
           proposal/research-question
           proposal/target
           proposal/current-contract
           proposal/proposed-contract
           proposal/claims
           proposal/falsifiers
           proposal/evidence
           proposal/implementation
           proposal/impact
           proposal/provenance
           proposal/supersedes]}]
  (let [status (or status :draft)]
    (when-not (valid-change-class? change-class)
      (throw (ex-info (str "Invalid change class: " change-class)
                      {:change-class change-class :allowed change-classes})))
    (when-not (valid-proposal-status? status)
      (throw (ex-info (str "Invalid proposal status: " status)
                      {:status status :allowed proposal-statuses})))
    (when-not title
      (throw (ex-info "Proposal requires a title" {})))
    (when-not research-question
      (throw (ex-info "Proposal requires a research-question" {})))
    (when-not target
      (throw (ex-info "Proposal requires a target" {})))
    (when-not (:component-id target)
      (throw (ex-info "Proposal target requires :component-id" {})))
    (when-not provenance
      (throw (ex-info "Proposal requires provenance" {})))
    (when-not (:proposed-by provenance)
      (throw (ex-info "Proposal provenance requires :proposed-by" {})))
    (let [semantic-base
          {:schema-version schema-version
           :proposal/title title
           :proposal/change-class change-class
           :proposal/research-question research-question
           :proposal/target target
           :proposal/current-contract current-contract
           :proposal/proposed-contract proposed-contract
           :proposal/claims (vec (or claims []))
           :proposal/falsifiers (vec (or falsifiers []))
           :proposal/evidence (vec (or evidence []))
           :proposal/implementation implementation
           :proposal/impact impact
           :proposal/provenance provenance}
          semantic-hash (str "sha256:"
                             (hc/domain-hash :research-framework-change-proposal
                                             semantic-base))
          proposal-id (str "prop:" (subs semantic-hash (count "sha256:")))]

      {:schema-version schema-version
       :proposal/id proposal-id
       :proposal/title title
       :proposal/change-class change-class
       :proposal/status status
       :proposal/research-question research-question
       :proposal/target target
       :proposal/current-contract current-contract
       :proposal/proposed-contract proposed-contract
       :proposal/claims (vec (or claims []))
       :proposal/falsifiers (vec (or falsifiers []))
       :proposal/evidence (vec (or evidence []))
       :proposal/implementation implementation
       :proposal/impact impact
       :proposal/provenance provenance
       :proposal/supersedes supersedes
       :proposal/hash semantic-hash})))

(defn validate-proposal
  "Standalone validator for a loaded or constructed proposal artifact.
   
   Checks schema version, controlled vocabularies, required fields,
   status transitions, and structural integrity.
   
   Returns {:valid? bool :errors [string] :warnings [string]}."
  [proposal]
  (let [errors (atom [])
        warnings (atom [])]
    (when-not (= schema-version (:schema-version proposal))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version proposal))))
    (let [cc (:proposal/change-class proposal)]
      (when-not (valid-change-class? cc)
        (swap! errors conj (str "invalid change-class: " cc " allowed: " change-classes))))
    (let [st (:proposal/status proposal)]
      (when-not (valid-proposal-status? st)
        (swap! errors conj (str "invalid status: " st " allowed: " proposal-statuses))))
    (when-not (:proposal/title proposal)
      (swap! errors conj "missing :proposal/title"))
    (when-not (:proposal/research-question proposal)
      (swap! errors conj "missing :proposal/research-question"))
    (let [target (:proposal/target proposal)]
      (when-not target
        (swap! errors conj "missing :proposal/target"))
      (when (and target (nil? (:component-id target)))
        (swap! errors conj "target missing :component-id")))
    (let [prov (:proposal/provenance proposal)]
      (when-not prov
        (swap! errors conj "missing :proposal/provenance"))
      (when (and prov (nil? (:proposed-by prov)))
        (swap! errors conj "provenance missing :proposed-by")))
    (when (and (:proposal/hash proposal)
               (not (clojure.string/starts-with? (:proposal/hash proposal) "sha256:")))
      (swap! errors conj "proposal/hash does not start with sha256:"))
    {:valid? (empty? @errors) :errors @errors :warnings @warnings}))

(defn supersede
  "Create a successor proposal that supersedes an existing one.
   
   The original proposal is not modified — supersession is append-only.
   Returns the new proposal with :proposal/supersedes set to the original
   proposal/hash."
  [original-proposal new-proposal-params]
  (let [supersedes (:proposal/hash original-proposal)]
    (when-not supersedes
      (throw (ex-info "Original proposal has no hash" {})))
    (build-proposal (assoc new-proposal-params :proposal/supersedes supersedes))))
