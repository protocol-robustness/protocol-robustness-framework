(ns resolver-sim.protocols.sew.force-authorisation
  "Optional bridge from researcher force-authorisations to Sew grant inputs.

   This adapter owns the benchmark and governed-authority checks. Sew supplies
   the already-computed scope binding; this namespace does not construct or
   apply protocol state transitions."
  (:require [resolver-sim.benchmark.researcher-force-authorisation :as researcher-fa]
            [resolver-sim.benchmark.research-assignment :as research-assignment]
            [resolver-sim.assurance.governed-authority-consumer :as governed-authority]))

(defn- resolved-artifact [resolver reference]
  (when (and (fn? resolver) (some? reference))
    (try (resolver reference)
         (catch Exception _ nil))))

(defn- parse-instant [value]
  (try
    (cond
      (instance? java.time.Instant value) value
      (string? value) (java.time.Instant/parse value)
      :else nil)
    (catch Exception _ nil)))

(defn validate-consensus-authorisation
  "Resolve and fail-closed validate the researcher artifacts for a Sew grant.
   `scope-hash` is computed by Sew and is checked against the signed target."
  [{:keys [context event workflow-id scope-hash]}]
  (let [pp (:params event)
        auth-hash (:researcher-force-authorisation/hash pp)
        assignment-hash (:research-assignment/hash pp)
        assignment (resolved-artifact (:research-assignment-resolver context) assignment-hash)
        auth (resolved-artifact (:researcher-force-authorisation-resolver context) auth-hash)
        policy-ref (get-in auth [:authorisation/policy :policy/hash])
        round-ref (get-in auth [:authorisation/review-round :review-round/hash])
        policy (resolved-artifact (:researcher-force-authorisation-policy-resolver context) policy-ref)
        round (resolved-artifact (:researcher-force-authorisation-round-resolver context) round-ref)
        structural (when auth (researcher-fa/validate-authorisation auth))
        signatures (when auth
                     (researcher-fa/verify-decision-signatures
                      (:researcher-public-key-resolver context) auth))
        policy-check (when (and policy auth) (researcher-fa/verify-against-policy policy auth))
        round-check (when (and round auth) (researcher-fa/verify-against-round round auth))
        governed (when auth
                   (governed-authority/verify-governed-authority context auth round-ref))
        consumed? (when-let [checker (:researcher-force-authorisation-consumed? context)]
                    (try (checker (:authorisation/consumption-key auth))
                         (catch Exception _ true)))
        now (parse-instant (:researcher-force-authorisation/now context))
        valid-from (parse-instant (:authorisation/valid-from auth))
        expires-at (parse-instant (:authorisation/expires-at auth))
        actual-policy-hash (or (:policy/hash policy) (get policy "policy_sha256"))
        target (:authorisation/target auth)
        checks {:authorisation-resolved? (and auth (= auth-hash (:authorisation/hash auth)))
                :structurally-valid? (true? (:valid? structural))
                :approved? (boolean (and auth (researcher-fa/authorisation-approved? auth)))
                :decision-signatures-valid? (true? (:valid? signatures))
                :policy-binding-valid? (and policy-check (:valid? policy-check)
                                            (= policy-ref actual-policy-hash))
                :review-round-binding-valid? (and round-check (:valid? round-check)
                                                  (= round-ref (:review-round/hash round)))
                :governed-authority-valid? (true? (:valid? governed))
                :target-kind-valid? (= :governance-mandated (:target/kind target))
                :assignment-resolved? (and assignment
                                           (= assignment-hash (:research-assignment/hash assignment)))
                :assignment-valid? (boolean (and assignment
                                                 (research-assignment/assignment-valid? assignment)))
                :assignment-binding-valid?
                (and assignment auth
                     (research-assignment/assignment-matches-authorisation? assignment auth)
                     (= policy-ref (:research-assignment/policy-hash assignment))
                     (= round-ref (:research-assignment/review-round-hash assignment))
                     (= workflow-id (get-in assignment [:research-assignment/target :target/workflow-id]))
                     (= (:reason pp) (get-in assignment [:research-assignment/target :target/reason])))
                ;; Validity endpoints are inclusive.
                :time-valid? (and now
                                  (or (nil? valid-from) (not (.isAfter valid-from now)))
                                  (or (nil? expires-at) (not (.isBefore expires-at now))))
                :public-sew-binding-valid? (and (string? scope-hash)
                                                (re-matches #"[0-9a-f]{64}" scope-hash)
                                                (= (str "sha256:" scope-hash)
                                                   (:target/public-force-authorisation-scope-hash target)))
                :consumption-key-unconsumed? (and (fn? (:researcher-force-authorisation-consumed? context))
                                                  (false? consumed?))}]
    {:valid? (every? true? (vals checks))
     :checks checks
     :authorisation auth
     :research-assignment assignment
     :governed-authority governed}))

(defn build-reservation
  "Build the immutable pre-grant reservation from a validated authorisation."
  [authorisation params]
  (try
    (researcher-fa/build-reservation
     {:reservation/authorisation-hash (:authorisation/hash authorisation)
      :reservation/consumption-key (:authorisation/consumption-key authorisation)
      :reservation/execution-attempt-id
      (:researcher-force-authorisation/execution-attempt-id params)
      :reservation/command-root (:researcher-force-authorisation/command-root params)
      :reservation/plan-root (:researcher-force-authorisation/plan-root params)})
    (catch Exception _ nil)))
