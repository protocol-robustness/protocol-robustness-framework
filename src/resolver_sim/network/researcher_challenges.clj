(ns resolver-sim.network.researcher-challenges
  "Researcher Challenges V0. Replay signed actions; derive quotes, acceptance,
   experience and existing reward artifacts from the complete authoritative history."
  (:require [buddy.core.codecs :as codecs]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.research-claim-acceptance :as acceptance]
            [resolver-sim.benchmark.research-claim-lifecycle :as lifecycle]
            [resolver-sim.benchmark.researcher-reward :as reward]
            [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference])
  (:import [java.util Base64]
           [java.time Instant]))

(def tiers [{:minimum 0 :numerator 1 :denominator 1}
            {:minimum 3 :numerator 5 :denominator 4}
            {:minimum 8 :numerator 3 :denominator 2}])

(defn- ensure! [condition reason]
  (when-not condition (throw (ex-info (name reason) {:reason reason}))))

(defn- exact! [value required-keys reason]
  (ensure! (and (map? value) (= (set required-keys) (set (keys value)))) reason))

(defn- root [domain body]
  (reference/sha256-ref (canonical/domain-hash domain (canonical/project-canonical-safe body))))

(defn- rooted [schema fields]
  (let [body (assoc fields :artifact/schema schema)]
    (assoc body :artifact/root (root schema body))))

(defn canonical-public-key [content]
  (let [key (signing/parse-public-key-content content)]
    (str "-----BEGIN PUBLIC KEY-----\n"
         (.encodeToString (Base64/getEncoder) (.getEncoded key))
         "\n-----END PUBLIC KEY-----\n")))

(defn network-root [network-id]
  (ensure! (qualified-keyword? network-id) :invalid-network-id)
  (root :registry {:researcher-network/id network-id :schema/version "researcher-challenges.v0"}))

(defn researcher-reference [network key-content]
  (ensure! (reference/valid-sha256-ref? network) :invalid-network-root)
  (root :evidence-record
        {:reference/kind :researcher/pseudonymous :network/root network
         :public-key/der (codecs/bytes->hex
                          (.getEncoded (signing/parse-public-key-content key-content)))}))

(defn build-reward-policy
  "V2 specifies the exact tier schedule and floors rational rewards to whole
   minor units. The challenge supplies the base amount. V1 remains fixed-rate."
  [currency]
  (ensure! (and (string? currency) (seq currency)) :invalid-currency)
  (rooted "researcher-reward-policy.v2"
          {:reward/currency currency :reward/tiers tiers :reward/rounding :floor-minor-unit
           :experience/basis :unique-accepted-verified-incentive-eligible-contributions
           :experience/count-rule :unique-subject-proposition-metric-witness
           :experience/scope :network :experience/cutoff :task-claim
           :duplicate/rule :challenge-subject-contribution-once}))

(defn- policy! [policy]
  (ensure! (= policy (build-reward-policy (:reward/currency policy))) :unsupported-tier-policy))

(defn experience-tier [n]
  (ensure! (and (integer? n) (not (neg? n))) :invalid-experience-count)
  (last (filter #(<= (:minimum %) n) tiers)))

(defn build-challenge
  "One finite, verified-analysis counterexample challenge. Capacity reserves
   whole task claims. Criteria are a closed vocabulary, not policy scripting."
  [{:keys [id subject-root subject-revision contribution-type acceptance-criteria
           base-reward capacity reward-policy-root]}]
  (ensure! (and (qualified-keyword? id)
                (reference/valid-sha256-ref? subject-root)
                (reference/valid-sha256-ref? subject-revision)
                (= :adversarial-finding contribution-type)
                (integer? base-reward) (not (neg? base-reward))
                (pos-int? capacity) (reference/valid-sha256-ref? reward-policy-root))
           :invalid-challenge)
  (exact! acceptance-criteria [:proposition-id :metric-id :result-classifications]
          :unsupported-acceptance-criteria)
  (ensure! (and (qualified-keyword? (:proposition-id acceptance-criteria))
                (qualified-keyword? (:metric-id acceptance-criteria))
                (= #{:counterexample-found} (:result-classifications acceptance-criteria)))
           :unsupported-acceptance-criteria)
  (rooted "researcher-challenge.v1"
          {:challenge/id id :subject/root subject-root :subject/revision-root subject-revision
           :contribution/type contribution-type :acceptance/criteria acceptance-criteria
           :reward/base-amount base-reward :challenge/capacity capacity
           :researcher-reward-policy/root reward-policy-root}))

(defn configuration
  "Freeze one testnet scope, pinned reviewer key, policy and challenge catalog."
  [network-id reviewer-key policy challenges]
  (policy! policy)
  (let [challenges (vec (sort-by :artifact/root challenges))]
    (ensure! (and (seq challenges)
                  (= (count challenges)
                     (count (set (map (juxt :challenge/id :subject/revision-root) challenges)))))
             :duplicate-challenge-revision)
    (doseq [challenge challenges]
      (ensure! (= challenge
                  (build-challenge {:id (:challenge/id challenge)
                                    :subject-root (:subject/root challenge)
                                    :subject-revision (:subject/revision-root challenge)
                                    :contribution-type (:contribution/type challenge)
                                    :acceptance-criteria (:acceptance/criteria challenge)
                                    :base-reward (:reward/base-amount challenge)
                                    :capacity (:challenge/capacity challenge)
                                    :reward-policy-root (:researcher-reward-policy/root challenge)}))
               :invalid-challenge)
      (ensure! (= (:artifact/root policy) (:researcher-reward-policy/root challenge))
               :challenge-policy-mismatch))
    (rooted "researcher-challenges-configuration.v1"
            {:network/id network-id :network/root (network-root network-id)
             :reviewer/public-key (canonical-public-key reviewer-key)
             :reward/policy policy :challenges challenges})))

(defn prepare-configuration
  "Operator-only preparation from a resolved canonical model, not a finding.
   Derives the subject and reward policy; creates no enrollment or acceptance.
   Retain the preparation input alongside the published configuration."
  [spec]
  (exact! spec [:network-id :reviewer-public-key :incentive-model :subject-revision
                :challenge-id :proposition-id :metric-id :currency :base-reward :capacity]
          :invalid-preparation-input)
  (let [incentive-model (:incentive-model spec)
        _ (ensure! (and (reference/valid-sha256-ref? (:incentive-model/root incentive-model))
                        (:valid? (model/validate-model incentive-model)))
                   :invalid-preparation-model)
        policy (build-reward-policy (:currency spec))
        challenge (build-challenge
                   {:id (:challenge-id spec)
                    :subject-root (:incentive-model/subject-root incentive-model)
                    :subject-revision (:subject-revision spec)
                    :contribution-type :adversarial-finding
                    :acceptance-criteria {:proposition-id (:proposition-id spec)
                                          :metric-id (:metric-id spec)
                                          :result-classifications #{:counterexample-found}}
                    :base-reward (:base-reward spec) :capacity (:capacity spec)
                    :reward-policy-root (:artifact/root policy)})]
    (configuration (:network-id spec) (:reviewer-public-key spec) policy [challenge])))

(defn- configuration! [config]
  (ensure! (= config (configuration (:network/id config) (:reviewer/public-key config)
                                    (:reward/policy config) (:challenges config)))
           :configuration-mismatch))

(defn action-root [action]
  (root "researcher-challenge-action.v1" (dissoc action :action/root :action/signature)))

(defn action
  "Build the exact unsigned request to sign. No experience/count/rate input.
   history-root is the current published prefix, preventing stale quotations."
  [config history-root actor kind challenge-root nonce payload]
  (ensure! (and (reference/valid-sha256-ref? history-root)
                (reference/valid-sha256-ref? actor)
                (contains? #{:enroll :claim :submit :review :payout} kind)
                (or (nil? challenge-root) (reference/valid-sha256-ref? challenge-root))
                (string? nonce) (seq nonce) (map? payload)) :invalid-action)
  (let [body {:artifact/schema "researcher-challenge-action.v1"
              :network/root (:network/root config) :history/root history-root
              :actor/root actor :action/kind kind :challenge/root challenge-root
              :action/nonce nonce :action/payload payload}]
    (assoc body :action/root (action-root body))))

(defn sign-action
  "Local signing only; the key path is never included in an artifact."
  [request private-key-path]
  (assoc request :action/signature (signing/sign-hash (:action/root request) private-key-path nil)))

(defn- signature! [a public-key]
  (ensure! (try (and (string? (:action/signature a))
                     (re-matches #"[0-9a-f]{128}" (:action/signature a))
                     (signing/verify-signature-with-public-key-content
                      (:action/root a) (:action/signature a) public-key))
                (catch Exception _ false))
           :invalid-signature))

(defn- seed [config]
  {:configuration config
   :history/root (root :evidence-chain {:configuration/root (:artifact/root config) :actions []})
   :events [] :researchers {} :quotes {} :submissions {} :reviews {}
   :nonces #{} :contributions #{} :payouts {}})

(defn- accepted-work [state researcher]
  (->> (vals (:reviews state))
       (filter #(and (= researcher (:researcher/root %))
                     (= :accepted (:decision %)) (:incentive/eligible? %)
                     (not (contains? (:withdrawals state) (:submission/root %)))))
       (sort-by :review/action-root) vec))

(defn- accepted-count [work]
  (count (set (map :contribution/key work))))

(defn- derive-quote [state challenge researcher request]
  (let [work (accepted-work state researcher)
        tier (experience-tier (accepted-count work))
        snapshot {:network/root (get-in state [:configuration :network/root])
                  :history/root (:history/root state) :researcher/root researcher
                  :accepted-contribution-count (accepted-count work)
                  :accepted-contribution-roots (vec (sort (set (map :contribution/root work))))
                  :accepted-contribution-keys (vec (sort-by canonical/canonical-bytes-hex (set (map :contribution/key work))))
                  :accepted-review-roots (mapv :review/action-root work)}]
    (rooted "researcher-challenge-task-claim.v1"
            {:challenge/root (:artifact/root challenge) :researcher/root researcher
             :claim/action-root (:action/root request)
             :experience/snapshot snapshot :experience/snapshot-root (root :evidence-record snapshot)
             :reward/tier tier :reward/policy-root (:researcher-reward-policy/root challenge)
             :reward/base-amount (:reward/base-amount challenge)
             :reward/amount (quot (*' (:reward/base-amount challenge) (:numerator tier)) (:denominator tier))
             :reward/currency (get-in state [:configuration :reward/policy :reward/currency])})))

(def context-keys
  #{:research-command :incentive-model :deviation-domain :research-assignment
    :outcome-manifest :analysis-output})

(defn- checked-evidence [challenge context]
  (exact! context context-keys :invalid-evidence-context)
  (let [verification (analysis/verify-output context)]
    (ensure! (analysis/verification-valid? verification (:analysis-output context)) :unverified-evidence)
    (let [claim (analysis/derive-claim (:analysis-output context) verification (:incentive-model context))
          criteria (:acceptance/criteria challenge)]
      (ensure! (and (= (:subject/root challenge) (:research-claim/subject-root claim))
                    (= (:proposition-id criteria) (:research-claim/proposition-id claim))
                    (= (:metric-id criteria) (:research-claim/metric-id claim))
                    (contains? (:result-classifications criteria)
                               (get-in claim [:research-claim/result :result/classification])))
               :challenge-criteria-mismatch)
      {:verification verification :claim claim})))

(defn- finding-key [claim]
  [(:research-claim/subject-root claim) (:research-claim/proposition-id claim)
   (:research-claim/metric-id claim) (get-in claim [:research-claim/result :result/witness-root])])

(defn- contribution-key [challenge claim]
  ;; Excludes researcher: copying another participant's contribution cannot pay twice.
  [(:challenge/id challenge) (:subject/revision-root challenge) (finding-key claim)])

(defn- review [state challenge a]
  (let [p (:action/payload a)]
    (exact! p [:submission/root :decision :incentive/decision :incentive/evidence-root
               :decision/evidence-root :accepted-at] :invalid-review)
    (ensure! (and (contains? #{:accepted :rejected} (:decision p))
                  (contains? #{:eligible :ineligible} (:incentive/decision p))
                  (reference/valid-sha256-ref? (:incentive/evidence-root p))
                  (reference/valid-sha256-ref? (:decision/evidence-root p))
                  (try (Instant/parse (:accepted-at p)) (catch Exception _ false))
                  (or (= :accepted (:decision p)) (= :ineligible (:incentive/decision p))))
             :invalid-review)
    (let [submission (get-in state [:submissions (:submission/root p)])
          q (get-in state [:quotes (:task-claim/root submission)])
          _ (ensure! (and submission (= (:artifact/root challenge) (:challenge/root submission))
                          (not (contains? (:reviews state) (:submission/root p)))) :unknown-or-reviewed-submission)
          key (contribution-key challenge (:claim submission))
          _ (ensure! (not (contains? (:contributions state) key)) :duplicate-contribution)
          authority (researcher-reference (get-in state [:configuration :network/root])
                                          (get-in state [:configuration :reviewer/public-key]))
          acceptance-policy (acceptance/build-policy
                             {:acceptance-policy/id (keyword "researcher-challenge" (str "definition-" (subs (:artifact/root challenge) 7)))
                              :acceptance-policy/authority-root authority})
          accepted (acceptance/build-acceptance
                    {:claim (:claim submission) :verification (:verification submission)
                     :policy acceptance-policy :decision (:decision p)
                     :decision-evidence-root (:action/root a)
                     :accepted-at (:accepted-at p) :acceptance-sequence 0})
          transition (lifecycle/build-transition {:acceptance accepted :from :unseen
                                                  :to (:decision p) :sequence 0})
          qualified? (and (= :accepted (:decision p)) (= :eligible (:incentive/decision p)))
          v1-policy (reward/build-policy
                     {:accepted-contribution-types #{(:contribution/type challenge)}
                      :required-verification #{:verified :claimable}
                      :required-claim-statuses #{(:research-claim/status (:claim submission))}
                      :reward-amount (:reward/amount q) :reward-currency (:reward/currency q)
                      :split-rules {:default :full} :duplicate-rule {:mode :reject}
                      :conflict-of-interest-rule {:mode :authority-reviewed}
                      :challenge/root (:artifact/root challenge)
                      :reward/policy-v2-root (:reward/policy-root q)
                      :task-claim/root (:artifact/root q)})
          eligibility (reward/evaluate
                       {:policy v1-policy :researcher (:researcher/root submission)
                        :assignment-root (get-in submission [:context :research-assignment :research-assignment/hash])
                        :contribution-root (get-in submission [:claim :research-claim/root])
                        :contribution-type (:contribution/type challenge)
                        :verification {:verified? true
                                       :research-analysis-verification/root (get-in submission [:verification :research-analysis-verification/root])
                                       :research-claim-acceptance/root (:research-claim-acceptance/root accepted)
                                       :incentive/decision (:incentive/decision p)
                                       :incentive/evidence-root (:incentive/evidence-root p)
                                       :review/action-root (:action/root a)
                                       :experience/snapshot-root (:experience/snapshot-root q)}
                        :claim (assoc (:claim submission) :claimable? qualified?)
                        :duplicate? false :conflict-of-interest? false})
          proposal (when qualified? (reward/build-proposal {:eligibility eligibility :policy v1-policy}))
          obligation (when qualified?
                       (reward/build-obligation {:proposal proposal :eligibility eligibility
                                                 :approval-authority {:authority/id "researcher-challenges-v0-reviewer"
                                                                      :authority/root authority :authority/authorized? true}}))]
      {:review/action-root (:action/root a) :researcher/root (:researcher/root submission)
       :contribution/root (get-in submission [:claim :research-claim/root])
       :contribution/key (finding-key (:claim submission))
       :challenge/root (:artifact/root challenge) :submission/root (:submission/root p)
       :task-claim/root (:artifact/root q) :decision (:decision p) :incentive/eligible? qualified?
       :acceptance-policy acceptance-policy :acceptance accepted :lifecycle-transition transition
       :reward-policy-v1 v1-policy :eligibility eligibility :proposal proposal :obligation obligation})))

(defn- step [state a]
  (let [config (:configuration state)
        kind (:action/kind a) actor (:actor/root a) p (:action/payload a)
        challenge (some #(when (= (:artifact/root %) (:challenge/root a)) %) (:challenges config))
        authority (researcher-reference (:network/root config) (:reviewer/public-key config))]
    (exact! a [:artifact/schema :network/root :history/root :actor/root :action/kind :challenge/root
               :action/nonce :action/payload :action/root :action/signature] :invalid-action-envelope)
    (ensure! (= (dissoc a :action/signature)
                (action config (:history/root a) actor kind (:challenge/root a) (:action/nonce a) p))
             :action-identity-mismatch)
    (ensure! (= (:history/root state) (:history/root a)) :stale-history-or-replay)
    (ensure! (not (contains? (:nonces state) [actor (:action/nonce a)])) :nonce-replay)
    (if (contains? #{:review :payout} kind)
      (do (ensure! (= authority actor) :unauthorized-reviewer)
          (signature! a (:reviewer/public-key config)))
      (if (= :enroll kind)
        (do (exact! p [:public-key :disclosure] :invalid-enrollment)
            (ensure! (and (nil? (:challenge/root a)) (= :pseudonymous (:disclosure p))
                          (not= actor authority)
                          (= (:public-key p) (canonical-public-key (:public-key p)))
                          (= actor (researcher-reference (:network/root config) (:public-key p)))
                          (not (contains? (:researchers state) actor))) :invalid-enrollment)
            (signature! a (:public-key p)))
        (let [enrollment (get-in state [:researchers actor])]
          (ensure! enrollment :not-enrolled)
          (signature! a (:public-key enrollment)))))
    (when (contains? #{:claim :submit :review} kind) (ensure! challenge :unknown-challenge))
    (let [[next-state consequence]
          (case kind
            :enroll [(assoc-in state [:researchers actor] (assoc p :enrollment/root (:action/root a))) p]
            :claim
            (do (exact! p [] :claim-does-not-accept-experience)
                (let [quotes (filter #(= (:artifact/root challenge) (:challenge/root %)) (vals (:quotes state)))]
                  (ensure! (< (count quotes) (:challenge/capacity challenge)) :challenge-capacity-full)
                  (ensure! (not-any? #(= actor (:researcher/root %)) quotes) :duplicate-task-claim)
                  (let [q (derive-quote state challenge actor a)]
                    [(assoc-in state [:quotes (:artifact/root q)] q) q])))
            :submit
            (do (exact! p [:task-claim/root :evidence/context] :invalid-submission)
                (let [q (get-in state [:quotes (:task-claim/root p)])]
                  (ensure! (and q (= actor (:researcher/root q))
                                (= (:artifact/root challenge) (:challenge/root q))
                                (not-any? #(= (:artifact/root q) (:task-claim/root %)) (vals (:submissions state))))
                           :wrong-or-submitted-task-claim)
                  (let [checked (checked-evidence challenge (:evidence/context p))
                        _ (ensure! (not-any? #(= (contribution-key challenge (:claim checked))
                                                 (contribution-key challenge (:claim %)))
                                             (filter #(= (:artifact/root challenge) (:challenge/root %)) (vals (:submissions state))))
                                   :duplicate-contribution)
                        submission (merge checked {:submission/root (:action/root a) :researcher/root actor
                                                   :task-claim/root (:artifact/root q)
                                                   :challenge/root (:artifact/root challenge) :context (:evidence/context p)})]
                    [(assoc-in state [:submissions (:action/root a)] submission) submission])))
            :review
            (let [r (review state challenge a)]
              [(cond-> (assoc-in state [:reviews (:submission/root r)] r)
                 (:incentive/eligible? r)
                 (update :contributions conj (contribution-key challenge (get-in state [:submissions (:submission/root r) :claim])))) r])
            :payout
            (do (exact! p [:obligation/root :payout/status :external/reference] :invalid-payout-record)
                (ensure! (and (nil? (:challenge/root a))
                              (contains? #{:recorded-pending :recorded-paid :recorded-failed} (:payout/status p))
                              (string? (:external/reference p)) (seq (:external/reference p))
                              (some #(= (:obligation/root p) (get-in % [:obligation :researcher-reward-obligation/root]))
                                    (vals (:reviews state)))) :unknown-obligation-or-payout-status)
                [(update-in state [:payouts (:obligation/root p)] (fnil conj [])
                            (assoc p :record/action-root (:action/root a))) p]))
          next-root (root :evidence-chain {:previous/root (:history/root state)
                                           :sequence (count (:events state)) :action/root (:action/root a)})]
      (-> next-state
          (assoc :history/root next-root)
          (update :nonces conj [actor (:action/nonce a)])
          (update :events conj {:action a :consequence consequence :history/root next-root})))))

(defn replay
  "V1 history admission path. Never accepts a score, cached acceptance,
   eligibility flag or derived state from a caller. Revalidates every signature
   and deterministically reconstructs all consequences."
  ([config signed-actions]
   (configuration! config)
   (ensure! (vector? signed-actions) :history-must-be-vector)
   (reduce step (seed config) signed-actions))
  ([config signed-actions published-history-root]
   (let [state (replay config signed-actions)]
     (ensure! (= published-history-root (:history/root state)) :published-history-mismatch)
     state)))

(defn append-action [config history signed-action]
  (let [next-history (conj history signed-action)]
    {:history next-history :state (replay config next-history)}))

(defn build-control-policy
  "Explicit composition over a frozen V1 configuration and trusted V1 prefix.
   Reviewer-key recovery/rotation and replacement verdicts are not supported."
  [config activation-history]
  (let [state (replay config activation-history)]
    (rooted "researcher-challenge-control-policy.v1"
            {:configuration/root (:artifact/root config) :network/root (:network/root config)
             :activation/history-root (:history/root state)
             :activation/action-count (count activation-history)
             :control/authority :pinned-reviewer
             :control/kinds [:revoke-participant :withdraw-review]
             :revocation/effect :prospective-participant-actions
             :withdrawal/effect :supersede-without-replacement
             :experience/effect :exclude-withdrawn-from-future-quotes
             :historical/effect :preserve-quotes-obligations-payouts-and-duplicate-reservations})))

(defn control-action-root [a]
  (root "researcher-challenge-control.v1" (dissoc a :action/root :action/signature)))

(defn control-action
  "Derive the reviewer identity and control bindings. The usual sign-action
   signs this envelope; signature verification is mandatory during admission."
  [config policy history-root kind nonce payload]
  (configuration! config)
  (ensure! (and (= "researcher-challenge-control-policy.v1" (:artifact/schema policy))
                (= (:artifact/root config) (:configuration/root policy))
                (= (:network/root config) (:network/root policy))
                (= (:artifact/root policy) (root (:artifact/schema policy) (dissoc policy :artifact/root)))
                (reference/valid-sha256-ref? history-root)
                (contains? #{:revoke-participant :withdraw-review} kind)
                (string? nonce) (seq nonce) (map? payload)) :invalid-control-action)
  (let [body {:artifact/schema "researcher-challenge-control.v1"
              :configuration/root (:artifact/root config) :network/root (:network/root config)
              :control-policy/root (:artifact/root policy) :history/root history-root
              :actor/root (researcher-reference (:network/root config) (:reviewer/public-key config))
              :action/kind kind :action/nonce nonce :action/payload payload}]
    (assoc body :action/root (control-action-root body))))

(defn- control-step [state policy a]
  (let [config (:configuration state) p (:action/payload a) actor (:actor/root a)
        sequence (count (:events state))]
    (exact! a [:artifact/schema :configuration/root :network/root :control-policy/root
               :history/root :actor/root :action/kind :action/nonce :action/payload
               :action/root :action/signature] :invalid-control-envelope)
    (ensure! (= (dissoc a :action/signature)
                (control-action config policy (:history/root a) (:action/kind a) (:action/nonce a) p))
             :control-binding-mismatch)
    (ensure! (= (:history/root state) (:history/root a)) :stale-control-action)
    (ensure! (not (contains? (:nonces state) [actor (:action/nonce a)])) :replayed-nonce)
    (signature! a (:reviewer/public-key config))
    (let [[next-state consequence]
          (case (:action/kind a)
            :revoke-participant
            (do
              (exact! p [:researcher/root :enrollment/root :reason :evidence/root] :invalid-revocation)
              (let [enrollment (get-in state [:researchers (:researcher/root p)])
                    _ (ensure! (and enrollment
                                    (= (:enrollment/root p) (:enrollment/root enrollment))
                                    (contains? #{:key-compromised :participant-request :operator-suspension} (:reason p))
                                    (reference/valid-sha256-ref? (:evidence/root p))
                                    (not (contains? (:revocations state) (:researcher/root p))))
                               :invalid-revocation-target)
                    result (assoc p :control/action-root (:action/root a) :control/sequence sequence)]
                [(assoc-in state [:revocations (:researcher/root p)] result) result]))
            :withdraw-review
            (do
              (exact! p [:submission/root :review/action-root :reason :evidence/root] :invalid-withdrawal)
              (let [review (get-in state [:reviews (:submission/root p)])
                    _ (ensure! (and review (= (:review/action-root p) (:review/action-root review))
                                    (contains? #{:review-error :evidence-invalidated :incentive-review-error} (:reason p))
                                    (reference/valid-sha256-ref? (:evidence/root p))
                                    (not (contains? (:withdrawals state) (:submission/root p))))
                               :invalid-withdrawal-target)
                    acceptance (:acceptance review)
                    transition (lifecycle/build-transition
                                {:acceptance acceptance :from (:decision review) :to :superseded
                                 :sequence 1 :supersession? true})
                    current (lifecycle/reconstruct-research-claim-lifecycle
                             {:claim-root (:research-claim-acceptance/claim-root acceptance)
                              :policy-root (:research-claim-acceptance/policy-root acceptance)
                              :transitions [(:lifecycle-transition review) transition]
                              :acceptances {0 acceptance 1 acceptance}})
                    result (assoc p :control/action-root (:action/root a) :control/sequence sequence
                                  :researcher/root (:researcher/root review)
                                  :acceptance/root (:research-claim-acceptance/root acceptance)
                                  :prior-lifecycle/root (get-in review [:lifecycle-transition :research-claim-lifecycle/root])
                                  :lifecycle-transition transition :lifecycle-state current)]
                [(assoc-in state [:withdrawals (:submission/root p)] result) result])))
          next-root (root :evidence-chain {:previous/root (:history/root state)
                                           :sequence sequence :action/root (:action/root a)})]
      (-> next-state
          (assoc :history/root next-root)
          (update :nonces conj [actor (:action/nonce a)])
          (update :events conj {:action a :consequence consequence :history/root next-root})))))

(defn replay-controlled
  "Opt-in replay only. Authenticate controls at the rooted activation prefix;
   historical V1 artifacts stay unchanged and ordinary V1 replay stays closed."
  [config policy history]
  (ensure! (vector? history) :history-must-be-vector)
  (let [n (:activation/action-count policy)]
    (ensure! (and (integer? n) (<= 0 n (count history))) :invalid-control-activation)
    (ensure! (= policy (build-control-policy config (subvec history 0 n))) :invalid-control-policy)
    (reduce (fn [state a]
              (if (= "researcher-challenge-control.v1" (:artifact/schema a))
                (control-step state policy a)
                (do
                  (ensure! (not (contains? (:revocations state) (:actor/root a))) :participant-revoked)
                  (step state a))))
            (assoc (replay config (subvec history 0 n))
                   :control-policy policy :revocations {} :withdrawals {})
            (subvec history n))))

(defn append-controlled-action [config policy history a]
  (let [next-history (conj history a)]
    {:history next-history :state (replay-controlled config policy next-history)}))

(defn- checkpoint-from-state [config state]
  {:configuration/root (:artifact/root config)
   :network/root (:network/root config)
   :history/root (:history/root state)
   :history/action-count (count (:events state))})

(defn history-checkpoint
  "Public replay-derived publication metadata, not a new canonical artifact or
   authentication mechanism. Distribute via a separately trusted channel."
  [config history]
  (checkpoint-from-state config (replay config history)))

(defn verify-history-checkpoint
  "Return fully replayed state only when configuration and complete history
   match an externally trusted checkpoint. Does not establish latestness."
  [config history checkpoint]
  (exact! checkpoint [:configuration/root :network/root :history/root :history/action-count]
          :invalid-history-checkpoint)
  (let [state (replay config history)]
    (ensure! (= checkpoint (checkpoint-from-state config state)) :history-checkpoint-mismatch)
    state))

(defn controlled-history-checkpoint [config policy history]
  (assoc (checkpoint-from-state config (replay-controlled config policy history))
         :control-policy/root (:artifact/root policy)))

(defn verify-controlled-history-checkpoint [config policy history checkpoint]
  (exact! checkpoint [:configuration/root :network/root :history/root :history/action-count
                      :control-policy/root] :invalid-history-checkpoint)
  (let [state (replay-controlled config policy history)]
    (ensure! (= checkpoint (assoc (checkpoint-from-state config state)
                                  :control-policy/root (:artifact/root policy))) :history-checkpoint-mismatch)
    state))

(defn- summary-from-state [config state researcher]
  (let [enrollment (get-in state [:researchers researcher])
        _ (ensure! enrollment :not-enrolled)
        accepted (accepted-work state researcher)]
    {:researcher/root researcher :disclosure :pseudonymous
     :network/root (:network/root config) :history/root (:history/root state)
     :accepted-contribution-count (accepted-count accepted) :experience/tier (experience-tier (accepted-count accepted))
     :reviewed-work (mapv #(select-keys % [:submission/root :contribution/root :decision :incentive/eligible? :review/action-root])
                          (sort-by :review/action-root
                                   (filter #(= researcher (:researcher/root %)) (vals (:reviews state)))))
     :accepted-work
     (mapv (fn [r]
             (let [submission (get-in state [:submissions (:submission/root r)])
                   obligation (:obligation r)]
               {:challenge/root (:challenge/root r) :submission/root (:submission/root r)
                :evidence/root (get-in submission [:claim :research-claim/evidence-root])
                :claim/root (get-in submission [:claim :research-claim/root])
                :verification/root (get-in submission [:verification :research-analysis-verification/root])
                :acceptance/root (get-in r [:acceptance :research-claim-acceptance/root])
                :eligibility/root (get-in r [:eligibility :researcher-reward-eligibility/root])
                :quoted-tier (get-in state [:quotes (:task-claim/root r) :reward/tier])
                :reward-obligation obligation
                :payout-records (get-in state [:payouts (:researcher-reward-obligation/root obligation)] [])})) accepted)
     :independence/person :unknown :independence/organization :unknown}))

(defn researcher-summary [config history researcher]
  (summary-from-state config (replay config history) researcher))

(defn controlled-researcher-summary [config policy history researcher]
  (let [state (replay-controlled config policy history)]
    (assoc (summary-from-state config state researcher)
           :control-policy/root (:artifact/root policy)
           :revocation (get-in state [:revocations researcher])
           :withdrawals (vec (sort-by :control/sequence
                                      (filter #(= researcher (:researcher/root %)) (vals (:withdrawals state)))))
           :historical-accepted-work (:accepted-work (summary-from-state config (dissoc state :withdrawals) researcher)))))

(defn- dossier-from-state
  "Read-only reviewer/retention view derived by full replay. No new canonical
   artifact, decision, eligibility assertion or public disclosure permission.
   External witness/justification bodies are unresolved references, not evidence
   manufactured by this report. Retain those bodies separately."
  [config history state submission-root]
  (let [submission (get-in state [:submissions submission-root])
        _ (ensure! submission :unknown-submission)
        challenge (some #(when (= (:artifact/root %) (:challenge/root submission)) %)
                        (:challenges config))
        review (get-in state [:reviews submission-root])
        review-action (some #(when (= (:action/root %) (:review/action-root review)) %)
                            history)
        unresolved (keep (fn [[kind artifact-root]]
                           (when artifact-root
                             {:reference/kind kind :reference/root artifact-root
                              :reference/status :not-resolved}))
                         [[:analysis-evidence (get-in submission [:context :analysis-output :analysis-output/evidence-root])]
                          [:counterexample-witness (get-in submission [:claim :research-claim/result :result/witness-root])]
                          [:review-justification (get-in review-action [:action/payload :decision/evidence-root])]
                          [:incentive-evaluation (get-in review-action [:action/payload :incentive/evidence-root])]])]
    {:checkpoint (checkpoint-from-state config state)
     :retained/configuration config :retained/signed-actions history
     :challenge challenge
     :researcher/enrollment (get-in state [:researchers (:researcher/root submission)])
     :submission submission
     :task-claim (get-in state [:quotes (:task-claim/root submission)])
     :review/status (or (:decision review) :unreviewed)
     :review review :review/signed-action review-action
     :external-evidence/references (vec unresolved)
     :retention/limitations [:external-artifact-bodies-not-embedded
                             :not-a-public-disclosure-authorization]}))

(defn review-dossier [config history submission-root]
  (dossier-from-state config history (replay config history) submission-root))

(defn controlled-review-dossier [config policy history submission-root]
  (let [state (replay-controlled config policy history)
        dossier (dossier-from-state config history state submission-root)
        withdrawal (get-in state [:withdrawals submission-root])]
    (cond-> (assoc dossier :retained/control-policy policy
                   :checkpoint (controlled-history-checkpoint config policy history)
                   :revocation (get-in state [:revocations (get-in dossier [:submission :researcher/root])])
                   :withdrawal withdrawal)
      withdrawal (assoc :review/status :superseded)
      withdrawal (update :external-evidence/references conj
                         {:reference/kind :withdrawal-justification
                          :reference/root (:evidence/root withdrawal) :reference/status :not-resolved}))))

(defn- nodes-from-state
  "Ordinary typed evidence nodes for signed action causality and consequences.
   All supporting bodies remain reconstructible from configuration + history."
  [config state]
  (loop [events (:events state) parent nil nodes []]
    (if-let [{:keys [action consequence]} (first events)]
      (let [a-node (forensic/build-forensic-evidence-node
                    {:subject {:subject/kind :research/challenge-action :subject/root (:action/root action)}
                     :role :validated :visibility :public :parent-nodes (if parent [parent] [])
                     :actor {:actor/root (:actor/root action) :actor/role :attestor}
                     :causal-parent-roots (vec (sort (distinct (remove nil?
                                                                       [(:artifact/root config) (:history/root action)
                                                                        (:actor/root action) (:challenge/root action)
                                                                        (:control-policy/root action)]))))})
            causes (vec (sort (distinct
                               (concat [(:action/root action)]
                                       (keep consequence [:task-claim/root :submission/root :review/action-root
                                                          :acceptance/root :prior-lifecycle/root
                                                          :enrollment/root :evidence/root])
                                       (keep #(get-in consequence %)
                                             [[:verification :research-analysis-verification/root]
                                              [:claim :research-claim/root]
                                              [:acceptance :research-claim-acceptance/root]
                                              [:eligibility :researcher-reward-eligibility/root]])))))
            outcome-root (or (:artifact/root consequence) (root :evidence-record consequence))
            c-node (forensic/build-forensic-evidence-node
                    {:subject {:subject/kind :research/challenge-consequence :subject/root outcome-root}
                     :role :derived :visibility :public :consequence? true
                     :parent-nodes [(:node-hash a-node)] :causal-parent-roots causes})
            stages (keep (fn [[kind artifact root-key role]]
                           (when artifact {:kind kind :root (get artifact root-key) :role role}))
                         [[:research/analysis-verification (:verification consequence) :research-analysis-verification/root :verified]
                          [:research/analytical-claim (:claim consequence) :research-claim/root :derived]
                          [:research/claim-acceptance (:acceptance consequence) :research-claim-acceptance/root :declared]
                          [:research/claim-lifecycle (:lifecycle-transition consequence) :research-claim-lifecycle/root :derived]
                          [:research/reward-eligibility (:eligibility consequence) :researcher-reward-eligibility/root :derived]
                          [:research/reward-proposal (:proposal consequence) :researcher-reward-proposal/root :derived]
                          [:research/reward-obligation (:obligation consequence) :researcher-reward-obligation/root :derived]
                          [:research/claim-lifecycle-state (:lifecycle-state consequence) :research-claim-lifecycle/root :derived]])
            branch (reduce (fn [branch {:keys [kind root role]}]
                             (conj branch
                                   (forensic/build-forensic-evidence-node
                                    {:subject {:subject/kind kind :subject/root root}
                                     :role role :visibility :public :consequence? true
                                     :parent-nodes [(:node-hash (last branch))]
                                     :causal-parent-roots (vec (sort (distinct (conj causes outcome-root))))})))
                           [a-node c-node] stages)]
        (recur (next events) (:node-hash (last branch)) (into nodes branch)))
      nodes)))

(defn forensic-nodes [config history]
  (nodes-from-state config (replay config history)))

(defn controlled-forensic-nodes [config policy history]
  (nodes-from-state config (replay-controlled config policy history)))
