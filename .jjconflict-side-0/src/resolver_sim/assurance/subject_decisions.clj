(ns resolver-sim.assurance.subject-decisions
  "Optional domain-neutral, authenticated acceptance and adoption log.
   A trusted policy and verifier are inputs, never inferred from signed history.
   This API has no capital, obligation-creation, network or execution capability."
  (:require [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.evidence.finalization-signature :as signatures])
  (:import [java.util Base64]))

(def decision-schema "subject-decision.v1")
(def signature-profile
  {:envelope-schema-version "subject-decision-authorization.v1"
   :payload-type "application/vnd.prf.subject-decision.v1"
   :payload-schema-version decision-schema
   :subject-kind decision-schema
   :subject-schema-version decision-schema})

(defn ensure! [ok reason]
  (when-not ok (throw (ex-info (name reason) {:reason reason}))))

(defn root [schema body]
  (reference/sha256-ref (canonical/domain-hash schema body)))

(defn artifact-root [artifact]
  (let [body (dissoc artifact :artifact/root)]
    (root (:artifact/schema artifact)
          (if (= decision-schema (:artifact/schema artifact)) (dissoc body :authorization) body))))

(defn rooted [schema fields]
  (let [body (assoc fields :artifact/schema schema)]
    (assoc body :artifact/root (artifact-root body))))

(defn canonical-key [pem]
  (let [key (signing/parse-public-key-content pem)]
    (str "-----BEGIN PUBLIC KEY-----\n"
         (.encodeToString (Base64/getEncoder) (.getEncoded key))
         "\n-----END PUBLIC KEY-----\n")))

(defn policy
  "The consumer independently pins this complete policy/root and key material.
   V1 uses immutable roles, one purpose, and supersession rather than revocation."
  [{:keys [domain purpose scope subject-schema validation-root reviewer ratifier]}]
  (ensure! (and (qualified-keyword? domain) (qualified-keyword? purpose)
                (string? scope) (seq scope) (string? subject-schema)
                (reference/valid-sha256-ref? validation-root)
                (every? #(= #{:id :public-key} (set (keys %))) [reviewer ratifier])
                (every? #(and (string? (:id %)) (seq (:id %))) [reviewer ratifier]))
           :invalid-policy)
  (let [reviewer (update reviewer :public-key canonical-key)
        ratifier (update ratifier :public-key canonical-key)]
    (ensure! (and (not= (:id reviewer) (:id ratifier))
                  (not= (:public-key reviewer) (:public-key ratifier))) :roles-must-be-distinct)
    (rooted "subject-decision-policy.v1"
            {:domain domain :purpose purpose :scope scope :subject-schema subject-schema :validation-root validation-root
             :reviewer reviewer :ratifier ratifier :version 1
             :revocation :immutable-decisions-successor-only
             :effects :content-adoption-only})))

(defn validate-policy! [p]
  (ensure! (= p (policy (select-keys p [:domain :purpose :scope :subject-schema :validation-root :reviewer :ratifier])))
           :policy-identity-mismatch)
  p)

(defn initial-state [p]
  (validate-policy! p)
  {:history/root (root "subject-decision-history.v1" {:policy/root (:artifact/root p)})
   :sequence 0 :decisions {} :authoritative nil})

(defn verify-subject
  "The adapter must recompute checks from resolved evidence. A caller-supplied
   verification flag/report in an event is never trusted."
  [p verifier subject]
  (ensure! (= (:validation-root p) (:validation/root (meta verifier))) :subject-verifier-mismatch)
  (ensure! (and (= (:subject-schema p) (:artifact/schema subject))
                (= (:domain p) (:domain subject)) (= (:purpose p) (:purpose subject))
                (= (:artifact/root subject) (artifact-root subject))) :subject-scope-or-identity-mismatch)
  (let [report (verifier subject)]
    (ensure! (and (map? report) (boolean? (:verified? report))
                  (boolean? (:eligible? report))
                  (= (:scope p) (:scope report))
                  (reference/valid-sha256-ref? (:evidence/root report))) :invalid-domain-report)
    {:report report :verification/root (root "subject-verification.v1" report)}))

(def decision-fields
  #{:artifact/schema :artifact/root :domain :purpose :policy/root :subject/root
    :verification/root :decision :authority/id :sequence :history/predecessor-root
    :acceptance/root :supersedes/root :authorization})

(defn decision
  "Build an unsigned request against an exact predecessor. Signing alone does
   not admit it. `reconstruct`/`append` revalidate eligibility and authorization."
  [p state verifier subject action]
  (ensure! (contains? #{:accept :reject :ratify} action) :unsupported-decision)
  (let [verified (verify-subject p verifier subject)
        authority (if (= :ratify action) (:ratifier p) (:reviewer p))]
    (rooted decision-schema
            {:domain (:domain p) :purpose (:purpose p) :policy/root (:artifact/root p)
             :subject/root (:artifact/root subject)
             :verification/root (:verification/root verified) :decision action
             :authority/id (:id authority) :sequence (:sequence state)
             :history/predecessor-root (:history/root state)
             :acceptance/root (when (= :ratify action)
                                (get-in state [:decisions (:artifact/root subject) :artifact/root]))
             :supersedes/root (when (= :ratify action) (get-in state [:authoritative :subject/root]))})))

(defn sign-decision [request private-key]
  (assoc request :authorization
         (signatures/build-envelope* signature-profile (:artifact/root request)
                                     (:authority/id request) private-key)))

(defn- authenticate! [p event]
  (let [authority (if (= :ratify (:decision event)) (:ratifier p) (:reviewer p))
        envelope (:authorization event)
        digest (:artifact/root event)
        payload {:schema-version decision-schema
                 :payload-type (:payload-type signature-profile)
                 :payload-hash digest :subject {:kind decision-schema :hash digest}}]
    (ensure! (and (= (:id authority) (:authority/id event))
                  (= #{:schema-version :payload :signature} (set (keys envelope)))
                  (= payload (:payload envelope))
                  (= #{:algorithm :key-id :value} (set (keys (:signature envelope))))
                  (= (:id authority) (get-in envelope [:signature :key-id]))
                  (:valid? (signatures/verify-envelope*
                            signature-profile envelope
                            (signing/parse-public-key-content (:public-key authority)))))
             :invalid-or-unauthorized-signature)))

(defn- advance [p verifier subjects state event]
  (ensure! (and (= decision-fields (set (keys event)))
                (= decision-schema (:artifact/schema event))
                (= (:artifact/root event) (artifact-root event))
                (contains? #{:accept :reject :ratify} (:decision event))
                (= (:domain p) (:domain event)) (= (:purpose p) (:purpose event))
                (= (:artifact/root p) (:policy/root event))) :invalid-decision-binding)
  (ensure! (and (= (:history/root state) (:history/predecessor-root event))
                (= (:sequence state) (:sequence event))) :stale-or-replayed-decision)
  (authenticate! p event)
  (let [subject (get subjects (:subject/root event))
        {report :report verification-root :verification/root} (verify-subject p verifier subject)
        prior (get-in state [:decisions (:subject/root event)])
        ratify? (= :ratify (:decision event))]
    (ensure! (= verification-root (:verification/root event)) :verification-binding-mismatch)
    (if ratify?
      (do
        (ensure! (and (= :accept (:decision prior)) (:verified? report) (:eligible? report)
                      (= (:artifact/root prior) (:acceptance/root event))) :acceptance-required)
        (ensure! (and (not= (:subject/root event) (get-in state [:authoritative :subject/root]))
                      (= (:supersedes/root event) (get-in state [:authoritative :subject/root]))
                      (= (:predecessor/root subject) (:supersedes/root event))) :invalid-successor)
        (ensure! (= (:verification/root prior) (:verification/root event)) :accepted-verification-changed))
      (do
        (ensure! (and (nil? prior) (nil? (:acceptance/root event)) (nil? (:supersedes/root event)))
                 :duplicate-or-malformed-review)
        (when (= :accept (:decision event))
          (ensure! (and (:verified? report) (:eligible? report)) :subject-ineligible))))
    (cond-> (assoc state
                   :sequence (inc (:sequence state))
                   :history/root (root "subject-decision-history.v1"
                                       {:previous (:history/root state) :decision (:artifact/root event)}))
      ratify? (assoc :authoritative {:subject/root (:subject/root event)
                                    :acceptance/root (:acceptance/root event)
                                    :ratification/root (:artifact/root event)})
      (not ratify?) (assoc-in [:decisions (:subject/root event)] (dissoc event :authorization)))))

(defn reconstruct
  "Replay the entire authenticated history from a pinned policy and evidence
   resolver. No supplied cached state, verification report or head is trusted.
   This local log detects known conflicts; it does not prove global latestness."
  [p verifier subjects history]
  (ensure! (and (map? subjects) (vector? history)) :invalid-reconstruction-input)
  (reduce #(advance p verifier subjects %1 %2) (initial-state p) history))

(defn append [p verifier subjects history event]
  (let [candidate (conj history event)]
    (reconstruct p verifier subjects candidate)
    candidate))

(defn reconstruct-at
  "Verify a complete history against an independently retained head/count.
   A trusted checkpoint detects truncation; a self-supplied head does not."
  [p verifier subjects history checkpoint]
  (let [state (reconstruct p verifier subjects history)]
    (ensure! (= checkpoint (select-keys state [:history/root :sequence])) :checkpoint-mismatch)
    state))
