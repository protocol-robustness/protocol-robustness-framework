(ns resolver-sim.research.researcher-challenges-test
  (:require [clojure.test :refer [deftest is]]
            [buddy.core.codecs :as codecs]
            [resolver-sim.io.edn :as pretty]
            [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.research.researcher-challenges-cli :as cli]
            [resolver-sim.benchmark.research-analysis-test :as analysis-fixture]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.benchmark.incentive-model :as model]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-claim-acceptance :as acceptance]
            [resolver-sim.benchmark.research-claim-lifecycle :as lifecycle]
            [resolver-sim.benchmark.researcher-reward :as reward]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference])
  (:import [java.security KeyPairGenerator Signature]
           [java.util Base64]
           [java.nio.file Files]))

(defn- r [label]
  (reference/sha256-ref (canonical/domain-hash :evidence-record {:test/label label})))

(defn- keys! []
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))]
    {:private (.getPrivate pair)
     :public (str "-----BEGIN PUBLIC KEY-----\n"
                  (.encodeToString (Base64/getEncoder) (.getEncoded (.getPublic pair)))
                  "\n-----END PUBLIC KEY-----\n")}))

(defn- signed [request key]
  (let [signer (Signature/getInstance "Ed25519")]
    (.initSign signer (:private key))
    (.update signer (.getBytes (:action/root request) "UTF-8"))
    (assoc request :action/signature (codecs/bytes->hex (.sign signer)))))

(defn- fixture []
  (let [context (#'analysis-fixture/fixture)
        reviewer (keys!) alice (keys!) bob (keys!)
        policy (challenges/build-reward-policy "TEST-POINTS")
        subject (get-in context [:incentive-model :incentive-model/subject-root])
        definitions (mapv #(challenges/build-challenge
                            {:id (keyword "challenge" (str "case-" %)) :subject-root subject
                             :subject-revision subject :contribution-type :adversarial-finding
                             :acceptance-criteria {:proposition-id :claim/incentive-compatibility
                                                   :metric-id :metric/net-payoff-delta
                                                   :result-classifications #{:counterexample-found}}
                             :base-reward 100 :capacity 2 :reward-policy-root (:artifact/root policy)})
                          (range 10))
        config (challenges/configuration :testnet/researcher-challenges (:public reviewer) policy definitions)
        actor (fn [key] (challenges/researcher-reference (:network/root config) (:public key)))]
    {:context context :reviewer reviewer :alice alice :bob bob :config config
     :definitions definitions :alice-ref (actor alice) :bob-ref (actor bob) :authority-ref (actor reviewer)}))

(defn- request [f history actor kind definition payload]
  (challenges/action (:config f) (:history/root (challenges/replay (:config f) history))
                     actor kind (:artifact/root definition) (str (count history)) payload))

(defn- transact [f history actor key kind definition payload]
  (:history (challenges/append-action (:config f) history
                                      (signed (request f history actor kind definition payload) key))))

(defn- enroll [f history who]
  (transact f history (get f (keyword (str (name who) "-ref"))) (get f who)
            :enroll nil {:public-key (:public (get f who)) :disclosure :pseudonymous}))

(defn- evidence [f label]
  (let [context (update (:context f) :research-assignment
                        #(assignment/build-assignment
                          (assoc (dissoc % :research-assignment/hash)
                                 :research-assignment/request-root (r label))))]
    (let [output (-> (#'analysis-fixture/output context :counterexample-found)
                     (assoc-in [:analysis-output/result :result/case-root] (r [label :case]))
                     (assoc-in [:analysis-output/result :result/witness-root] (r [label :witness]))
                     (assoc :analysis-output/evidence-root (r [label :evidence])))]
      (assoc context :analysis-output
             (assoc output :research-analysis-output/root (analysis/output-root output))))))

(defn- claim-task [f history who definition]
  (transact f history (get f (keyword (str (name who) "-ref"))) (get f who)
            :claim definition {}))

(defn- latest [f history]
  (:consequence (last (:events (challenges/replay (:config f) history)))))

(defn- submit [f history who definition q label]
  (transact f history (get f (keyword (str (name who) "-ref"))) (get f who)
            :submit definition {:task-claim/root (:artifact/root q) :evidence/context (evidence f label)}))

(defn- approve [f history definition submission decision incentive-decision]
  (transact f history (:authority-ref f) (:reviewer f) :review definition
            {:submission/root (:submission/root submission) :decision decision
             :incentive/decision incentive-decision :incentive/evidence-root (r :incentive-criteria-review)
             :decision/evidence-root (r :review-report) :accepted-at "2026-10-10T12:00:00Z"}))

(defn- preparation [f]
  {:network-id :testnet/researcher-challenges
   :reviewer-public-key (get-in f [:reviewer :public])
   :incentive-model (get-in f [:context :incentive-model])
   :subject-revision (get-in f [:context :incentive-model :incentive-model/subject-root])
   :challenge-id :challenge/case-0
   :proposition-id :claim/incentive-compatibility :metric-id :metric/net-payoff-delta
   :currency "TEST-POINTS" :base-reward 100 :capacity 2})

(deftest preparation-reuses-existing-identities-without-creating-contributions
  (let [f (fixture) spec (preparation f)
        config (challenges/prepare-configuration spec)
        expected (challenges/configuration :testnet/researcher-challenges
                                           (get-in f [:reviewer :public])
                                           (challenges/build-reward-policy "TEST-POINTS")
                                           [(first (:definitions f))])
        state (challenges/replay config [])]
    (is (= expected config))
    (is (= config (challenges/prepare-configuration (read-string (pr-str spec)))))
    (is (empty? (:events state)))
    (is (empty? (:researchers state)))
    (is (empty? (:reviews state)))
    (is (= config (read-string (with-out-str
                                 (with-redefs [clojure.core/slurp (constantly (pr-str spec))]
                                   (cli/-main "prepare" "operator-input.edn"))))))))

(deftest preparation-rejects-unrooted-tampered-or-result-bearing-input
  (let [spec (preparation (fixture))]
    (doseq [bad [(update spec :incentive-model dissoc :incentive-model/root)
                 (assoc-in spec [:incentive-model :incentive-model/subject-root] (r :wrong-subject))
                 (assoc-in spec [:incentive-model :schema-version] "incentive-model.v999")
                 (assoc spec :subject-root (r :independently-supplied-subject))
                 (assoc spec :analysis-output {})
                 (assoc spec :acceptance :accepted)
                 (assoc spec :experience 8)
                 (assoc spec :private-key "/operator/private-key")]]
      (is (thrown? clojure.lang.ExceptionInfo (challenges/prepare-configuration bad))))))

(deftest preparation-does-not-assert-model-selection-at-submission
  ;; V1 challenge binds subject/revision/criteria, not one exact model root.
  ;; Preparation must not silently strengthen that existing contract.
  (let [spec (preparation (fixture))
        alternative (update (:incentive-model spec) :incentive-model/costs assoc :review 1)
        alternative (assoc alternative :incentive-model/root
                           (model/model-root alternative))]
    (is (= (challenges/prepare-configuration spec)
           (challenges/prepare-configuration (assoc spec :incentive-model alternative))))))

(defn- complete [f history who definition label]
  (let [h (claim-task f history who definition) q (latest f h)
        h (submit f h who definition q label) submission (latest f h)]
    (approve f h definition submission :accepted :eligible)))

(deftest two-key-controlled-researchers-produce-reconstructible-obligations
  (let [f (fixture) definition (first (:definitions f))
        h (enroll f [] :alice) h (enroll f h :bob)
        h (complete f h :alice definition :alice-computation)
        h (complete f h :bob definition :bob-computation)
        state (challenges/replay (:config f) h)
        reconstructed (challenges/replay (read-string (pr-str (:config f))) (read-string (pr-str h)))]
    (is (= 2 (count (:reviews state))))
    (is (= state reconstructed))
    (doseq [review (vals (:reviews state))]
      (is (reward/valid-obligation? (:obligation review)))
      (is (= 100 (get-in review [:obligation :reward/amount])))
      (let [submission (get-in state [:submissions (:submission/root review)])
            accepted (:acceptance review)
            transition (:lifecycle-transition review)
            lifecycle-state (lifecycle/reconstruct-research-claim-lifecycle
                             {:claim-root (get-in submission [:claim :research-claim/root])
                              :policy-root (get-in review [:acceptance-policy :research-claim-acceptance-policy/root])
                              :transitions [transition] :acceptances {0 accepted}})]
        (is (:valid? (acceptance/validate-acceptance
                      accepted {:claim (:claim submission) :verification (:verification submission)
                                :policy (:acceptance-policy review)})))
        (is (= :accepted (:research-claim-lifecycle/state lifecycle-state)))))
    (is (= 1 (:accepted-contribution-count (challenges/researcher-summary (:config f) h (:alice-ref f)))))
    (is (= :unknown (:independence/person (challenges/researcher-summary (:config f) h (:bob-ref f)))))
    (is (:valid? (node/validate-node-dag (challenges/forensic-nodes (:config f) h))))))

(deftest accepted-history-advances-tier-and-quotes-never-reprice
  (let [f (fixture) [d1 d2 d3 d4 d5] (:definitions f)
        h (enroll f [] :alice)
        h (complete f h :alice d1 :one) h (complete f h :alice d2 :two)
        h (claim-task f h :alice d4) frozen (latest f h)
        h (complete f h :alice d3 :three)
        h (submit f h :alice d4 frozen :four) s (latest f h)
        h (approve f h d4 s :accepted :eligible)
        h (claim-task f h :alice d5) promoted (latest f h)
        summary (challenges/researcher-summary (:config f) h (:alice-ref f))]
    (is (= 2 (get-in frozen [:experience/snapshot :accepted-contribution-count])))
    (is (= 100 (:reward/amount frozen)))
    (is (= 100 (get-in (challenges/replay (:config f) h)
                       [:reviews (:submission/root s) :obligation :reward/amount])))
    (is (= 4 (:accepted-contribution-count summary)))
    (is (= {:minimum 3 :numerator 5 :denominator 4} (:reward/tier promoted)))
    (is (= 125 (:reward/amount promoted)))
    (is (= {:minimum 8 :numerator 3 :denominator 2} (challenges/experience-tier 8)))
    (is (= {:minimum 3 :numerator 5 :denominator 4} (challenges/experience-tier 7)))))

(deftest rejection-and-ineligibility-produce-no-experience-or-obligation
  (doseq [[decision incentive] [[:rejected :ineligible] [:accepted :ineligible]]]
    (let [f (fixture) d (first (:definitions f)) h (enroll f [] :alice)
          h (claim-task f h :alice d) q (latest f h)
          h (submit f h :alice d q :rejected) s (latest f h)
          h (approve f h d s decision incentive) result (latest f h)]
      (is (nil? (:obligation result)))
      (is (false? (get-in result [:eligibility :reward/eligible?])))
      (is (= 0 (:accepted-contribution-count (challenges/researcher-summary (:config f) h (:alice-ref f))))))))

(deftest forged-attribution-stale-history-and-replayed-nonce-are-rejected
  (let [f (fixture) d (first (:definitions f)) h (enroll f [] :alice)
        request-a (request f h (:alice-ref f) :claim d {})
        valid (signed request-a (:alice f))
        history (:history (challenges/append-action (:config f) h valid))]
    (is (thrown? clojure.lang.ExceptionInfo (challenges/append-action (:config f) h (signed request-a (:bob f)))))
    (is (thrown? clojure.lang.ExceptionInfo (challenges/append-action (:config f) history valid)))
    (let [fresh (request f history (:alice-ref f) :claim (second (:definitions f)) {})
          reused (assoc fresh :action/nonce (:action/nonce request-a))
          reused (assoc reused :action/root (challenges/action-root reused))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (challenges/append-action (:config f) history (signed reused (:alice f))))))
    (let [forged (assoc-in valid [:action/payload :accepted-contribution-count] 8)
          forged (assoc forged :action/root (challenges/action-root forged))]
      (is (thrown? clojure.lang.ExceptionInfo (challenges/append-action (:config f) h forged))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (transact f h (:alice-ref f) (:alice f) :claim d {:accepted-contribution-count 8})))))

(deftest exact-submission-and-contribution-replays-cannot-double-credit
  (let [f (fixture) d (first (:definitions f)) h (enroll f [] :alice) h (enroll f h :bob)
        h (claim-task f h :alice d) qa (latest f h)
        h (claim-task f h :bob d) qb (latest f h)
        h (submit f h :alice d qa :shared-output) s (latest f h)
        submitted (last h)]
    (is (thrown? clojure.lang.ExceptionInfo (challenges/append-action (:config f) h submitted)))
    (is (thrown? clojure.lang.ExceptionInfo (submit f h :alice d qa :another-output)))
    (is (thrown? clojure.lang.ExceptionInfo (submit f h :bob d qb :shared-output)))
    (is (thrown? clojure.lang.ExceptionInfo (submit f h :bob d qa :different-output)))
    (let [h (approve f h d s :accepted :eligible)]
      (is (thrown? clojure.lang.ExceptionInfo (approve f h d s :accepted :eligible)))
      (is (= 1 (:accepted-contribution-count (challenges/researcher-summary (:config f) h (:alice-ref f))))))))

(deftest another-assignment-cannot-disguise-the-same-counterexample-witness
  (let [f (fixture) d (first (:definitions f)) h (enroll f [] :alice) h (enroll f h :bob)
        h (claim-task f h :alice d) qa (latest f h)
        h (claim-task f h :bob d) qb (latest f h)
        h (submit f h :alice d qa :original)
        original (get-in (evidence f :original) [:analysis-output :analysis-output/result :result/witness-root])
        context (evidence f :another-assignment)
        output (assoc-in (:analysis-output context) [:analysis-output/result :result/witness-root] original)
        context (assoc context :analysis-output (assoc output :research-analysis-output/root (analysis/output-root output)))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (transact f h (:bob-ref f) (:bob f) :submit d
                           {:task-claim/root (:artifact/root qb) :evidence/context context})))))

(deftest eight-accepted-unique-findings-establish-the-third-quoted-tier
  (let [f (fixture)
        h (reduce (fn [h [n d]] (complete f h :alice d [:accepted n]))
                  (enroll f [] :alice) (map-indexed vector (take 8 (:definitions f))))
        h (claim-task f h :alice (nth (:definitions f) 8)) q (latest f h)]
    (is (= 8 (get-in q [:experience/snapshot :accepted-contribution-count])))
    (is (= {:minimum 8 :numerator 3 :denominator 2} (:reward/tier q)))
    (is (= 150 (:reward/amount q)))))

(deftest unverified-wrong-subject-and-self-asserted-review-cannot-pay
  (let [f (fixture) d (first (:definitions f)) h (enroll f [] :alice)
        h (claim-task f h :alice d) q (latest f h) context (evidence f :valid)
        invalid (assoc-in context [:analysis-output :analysis-output/closure-root] (r :wrong))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (transact f h (:alice-ref f) (:alice f) :submit d
                           {:task-claim/root (:artifact/root q) :evidence/context invalid})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (transact f h (:alice-ref f) (:alice f) :submit (second (:definitions f))
                           {:task-claim/root (:artifact/root q) :evidence/context context})))
    (let [h (submit f h :alice d q :valid) s (latest f h)
          payload {:submission/root (:submission/root s) :decision :accepted :incentive/decision :eligible
                   :incentive/evidence-root (r :i) :decision/evidence-root (r :d) :accepted-at "2026-10-10T00:00:00Z"}
          a (request f h (:authority-ref f) :review d payload)]
      (is (thrown? clojure.lang.ExceptionInfo (challenges/append-action (:config f) h (signed a (:alice f)))))
      (is (thrown? clojure.lang.ExceptionInfo (transact f h (:alice-ref f) (:alice f) :review d payload))))))

(deftest capacity-and-configuration-substitution-fail-closed
  (let [f (fixture) d (first (:definitions f)) h (enroll f [] :alice) h (enroll f h :bob)
        h (claim-task f h :alice d) h (claim-task f h :bob d) other (keys!)
        other-ref (challenges/researcher-reference (get-in f [:config :network/root]) (:public other))
        h (transact f h other-ref other :enroll nil {:public-key (:public other) :disclosure :pseudonymous})]
    (is (thrown? clojure.lang.ExceptionInfo (transact f h other-ref other :claim d {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (challenges/replay (assoc-in (:config f) [:reward/policy :reward/tiers 1 :numerator] 9) h)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (transact f h (:authority-ref f) (:reviewer f) :enroll nil
                           {:public-key (get-in f [:reviewer :public]) :disclosure :pseudonymous})))))

(deftest payout-records-are-external-assertions-and-preserve-obligations
  (let [f (fixture) d (first (:definitions f)) h (enroll f [] :alice)
        h (complete f h :alice d :paid) original (latest f h)
        obligation (:obligation original)
        h (transact f h (:authority-ref f) (:reviewer f) :payout nil
                    {:obligation/root (:researcher-reward-obligation/root obligation)
                     :payout/status :recorded-paid :external/reference "testnet-ledger:123"})
        summary (challenges/researcher-summary (:config f) h (:alice-ref f))]
    (is (= obligation (get-in summary [:accepted-work 0 :reward-obligation])))
    (is (= :recorded-paid (get-in summary [:accepted-work 0 :payout-records 0 :payout/status])))
    (is (not (contains? obligation :payment/status)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (transact f h (:authority-ref f) (:reviewer f) :payout nil
                           {:obligation/root (r :nonexistent) :payout/status :recorded-paid :external/reference "x"})))))

(deftest file-admission-replays-state-and-failed-actions-do-not-alter-history
  (let [f (fixture) directory (.toFile (Files/createTempDirectory "researcher-challenges-"
                                                                  (make-array java.nio.file.attribute.FileAttribute 0)))
        config-file (str directory "/config.edn") history-file (str directory "/history.edn")
        action-file (str directory "/action.edn")
        enrollment (first (enroll f [] :alice))]
    (try
      (spit config-file (pr-str (:config f)))
      (spit action-file (pr-str enrollment))
      (cli/apply-file! config-file history-file action-file)
      (let [before (slurp history-file)]
        (is (= 1 (count (read-string before))))
        (is (thrown? clojure.lang.ExceptionInfo (cli/apply-file! config-file history-file action-file)))
        (is (= before (slurp history-file))))
      (finally
        (doseq [file (reverse (file-seq directory))] (.delete file))))))

(deftest checkpoints-bind-the-frozen-configuration-and-exact-history-prefix
  (let [f (fixture) config (:config f) h (enroll f [] :alice)
        checkpoint (challenges/history-checkpoint config h)
        empty-checkpoint (challenges/history-checkpoint config [])
        other-config (challenges/configuration :testnet/another-network
                                               (get-in f [:reviewer :public])
                                               (:reward/policy config) (:challenges config))]
    (is (= (challenges/replay config h)
           (challenges/verify-history-checkpoint config h checkpoint)))
    (is (= checkpoint (challenges/history-checkpoint (read-string (pr-str config))
                                                     (read-string (pr-str h)))))
    (doseq [[cfg history cp] [[config [] checkpoint]
                              [config h empty-checkpoint]
                              [other-config [] empty-checkpoint]
                              [config h (assoc checkpoint :configuration/root (r :wrong-config))]
                              [config h (assoc checkpoint :network/root (r :wrong-network))]
                              [config h (assoc checkpoint :history/root (r :wrong-history))]
                              [config h (update checkpoint :history/action-count inc)]
                              [config h (dissoc checkpoint :configuration/root)]
                              [config h (assoc checkpoint :trusted? true)]]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (challenges/verify-history-checkpoint cfg history cp))))
    ;; An internally valid older checkpoint is not proof of latestness.
    (is (= (challenges/replay config [])
           (challenges/verify-history-checkpoint config [] empty-checkpoint)))))

(deftest checkpoint-cli-gates-request-and-file-admission-without-changing-action-identity
  (let [f (fixture) config (:config f)
        directory (.toFile (Files/createTempDirectory "challenge-checkpoint-"
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        config-file (str directory "/config.edn") history-file (str directory "/history.edn")
        checkpoint-file (str directory "/checkpoint.edn") request-file (str directory "/request.edn")
        action-file (str directory "/action.edn")
        enrollment (first (enroll f [] :alice))
        request {:actor (:alice-ref f) :kind :enroll :challenge-root nil :nonce "0"
                 :payload {:public-key (get-in f [:alice :public]) :disclosure :pseudonymous}}]
    (try
      (spit config-file (pr-str config))
      (spit request-file (pr-str request))
      (spit action-file (pr-str enrollment))
      (let [checkpoint (read-string (with-out-str (cli/-main "checkpoint" config-file history-file)))]
        (spit checkpoint-file (pr-str checkpoint))
        (is (= (challenges/history-checkpoint config []) checkpoint))
        (is (= :verified (:checkpoint/status
                          (read-string (with-out-str
                                         (cli/-main "verify-checkpoint" config-file history-file checkpoint-file))))))
        (is (= (with-out-str (cli/-main "request" config-file history-file request-file))
               (with-out-str (cli/-main "request" config-file history-file request-file checkpoint-file))))
        (cli/apply-file! config-file history-file action-file checkpoint-file)
        (let [before (slurp history-file)]
          (is (= [enrollment] (read-string before)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (cli/-main "request" config-file history-file request-file checkpoint-file)))
          (is (thrown? clojure.lang.ExceptionInfo
                       (cli/apply-file! config-file history-file action-file checkpoint-file)))
          (is (= before (slurp history-file)))
          (is (= :history-checkpoint-mismatch
                 (try (cli/apply-file! config-file history-file action-file checkpoint-file)
                      (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))
      (finally (doseq [file (reverse (file-seq directory))] (.delete file))))))

(deftest review-dossier-is-a-read-only-reconstructible-retention-view
  (let [f (fixture) definition (first (:definitions f))
        h (enroll f [] :alice) h (claim-task f h :alice definition) quote (latest f h)
        h (submit f h :alice definition quote :reviewed-finding) submission (latest f h)
        state (challenges/replay (:config f) h)
        dossier (challenges/review-dossier (:config f) h (:submission/root submission))]
    (is (= :unreviewed (:review/status dossier)))
    (is (nil? (:review dossier)))
    (is (nil? (:review/signed-action dossier)))
    (is (= quote (:task-claim dossier)))
    (is (= submission (:submission dossier)))
    (is (= definition (:challenge dossier)))
    (is (= 2 (count (:external-evidence/references dossier))))
    (is (every? #(= :not-resolved (:reference/status %)) (:external-evidence/references dossier)))
    (is (= state (challenges/verify-history-checkpoint (:retained/configuration dossier)
                                                       (:retained/signed-actions dossier)
                                                       (:checkpoint dossier))))
    (is (= state (challenges/replay (:config f) h)))
    (is (empty? (:reviews state)))
    (is (empty? (:contributions state)))
    (doseq [[decision incentive] [[:accepted :eligible] [:accepted :ineligible] [:rejected :ineligible]]]
      (let [reviewed (approve f h definition submission decision incentive)
            report (challenges/review-dossier (:config f) reviewed (:submission/root submission))
            roundtrip (read-string (pr-str report))
            reconstructed (challenges/replay (:retained/configuration roundtrip)
                                             (:retained/signed-actions roundtrip))
            authoritative (get-in reconstructed [:reviews (:submission/root submission)])]
        (is (= decision (:review/status report)))
        (is (= authoritative (:review report)))
        (is (= (last reviewed) (:review/signed-action report)))
        (is (= incentive (get-in report [:review/signed-action :action/payload :incentive/decision])))
        (is (= quote (:task-claim report)))
        (is (= 4 (count (:external-evidence/references report))))
        (is (= (and (= :accepted decision) (= :eligible incentive))
               (some? (get-in report [:review :obligation]))))
        (is (= (mapv :node-hash (challenges/forensic-nodes (:config f) reviewed))
               (mapv :node-hash (challenges/forensic-nodes (:retained/configuration roundtrip)
                                                           (:retained/signed-actions roundtrip)))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (challenges/review-dossier (:config f) h (r :wrong-submission))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (challenges/review-dossier (:config f)
                                            (assoc-in h [0 :action/signature] (apply str (repeat 128 "0")))
                                            (:submission/root submission))))))

(deftest reviewer-cli-requires-the-exact-published-prefix-when-specified
  (let [f (fixture) definition (first (:definitions f))
        h (enroll f [] :alice) h (claim-task f h :alice definition) q (latest f h)
        h (submit f h :alice definition q :pending-review) submission (latest f h)
        config (:config f) checkpoint (challenges/history-checkpoint config h)
        files {"config.edn" (pr-str config) "history.edn" (pr-str h)
               "checkpoint.edn" (pr-str checkpoint)
               "old.edn" (pr-str (challenges/history-checkpoint config (pop h)))}]
    (with-redefs [clojure.core/slurp #(get files %)]
      (is (= (challenges/review-dossier config h (:submission/root submission))
             (read-string (with-out-str
                            (cli/-main "review-dossier" "config.edn" "history.edn"
                                       (:submission/root submission) "checkpoint.edn")))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (cli/-main "review-dossier" "config.edn" "history.edn"
                              (:submission/root submission) "old.edn"))))))

(deftest v0-rejects-key-substitution-and-unversioned-control-actions
  (let [f (fixture) config (:config f) h (enroll f [] :alice)
        replacement (keys!)
        rotated (challenges/configuration (:network/id config) (:public replacement)
                                          (:reward/policy config) (:challenges config))
        prefix (:history/root (challenges/replay config h))]
    (is (not= (:artifact/root config) (:artifact/root rotated)))
    (is (thrown? clojure.lang.ExceptionInfo (challenges/replay rotated h)))
    (is (not= (:alice-ref f) (challenges/researcher-reference (:network/root config) (:public replacement))))
    (doseq [kind [:revoke-participant :rotate-key :withdraw-review :correct-review]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (challenges/action config prefix (:authority-ref f) kind nil "control-1" {})))
      (let [request (challenges/action config prefix (:authority-ref f) :payout nil "control-1" {})
            altered (assoc request :action/kind kind)
            altered (assoc altered :action/root (challenges/action-root altered))
            signed-control (signed altered (:reviewer f))]
        ;; A valid signature cannot authorize a kind absent from the V1 contract.
        (is (thrown? clojure.lang.ExceptionInfo (challenges/append-action config h signed-control)))))
    (is (= 1 (count (:researchers (challenges/replay config h)))))))

(deftest generic-supersession-preserves-artifacts-but-is-not-a-challenge-correction
  (let [f (fixture) definition (first (:definitions f))
        h (enroll f [] :alice) h (claim-task f h :alice definition) quote (latest f h)
        h (submit f h :alice definition quote :withdrawal-audit) submission (latest f h)
        h (approve f h definition submission :accepted :eligible)
        state (challenges/replay (:config f) h)
        reviewed (get-in state [:reviews (:submission/root submission)])
        original (:acceptance reviewed) first-transition (:lifecycle-transition reviewed)
        obligation (:obligation reviewed)
        supersession (lifecycle/build-transition {:acceptance original :from :accepted :to :superseded
                                                  :sequence 1 :supersession? true})
        reconstructed (lifecycle/reconstruct-research-claim-lifecycle
                       {:claim-root (:research-claim-acceptance/claim-root original)
                        :policy-root (:research-claim-acceptance/policy-root original)
                        :transitions [first-transition supersession]
                        :acceptances {0 original 1 original}})]
    (is (= :superseded (:research-claim-lifecycle/state reconstructed)))
    (is (:valid? (lifecycle/validate-transition supersession original)))
    (is (= :accepted (:research-claim-acceptance/decision original)))
    (is (= obligation (:obligation reviewed)))
    (is (reward/valid-obligation? obligation))
    (is (= quote (get-in state [:quotes (:artifact/root quote)])))
    (is (= state (challenges/replay (:config f) h)))
    (is (= 1 (:accepted-contribution-count (challenges/researcher-summary (:config f) h (:alice-ref f)))))
    ;; The generic builder is not admission authority and does not alter V0 history.
    (is (thrown? clojure.lang.ExceptionInfo (approve f h definition submission :rejected :ineligible)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (lifecycle/build-transition {:acceptance original :from :superseded :to :accepted
                                              :sequence 2 :supersession? false})))
    (is (not (:valid? (lifecycle/validate-transition
                       (assoc supersession :research-claim-lifecycle/acceptance-root (r :another-acceptance))
                       original))))))

(defn- control-transact [f policy h kind payload]
  (let [state (challenges/replay-controlled (:config f) policy h)
        a (challenges/control-action (:config f) policy (:history/root state) kind (str (count h)) payload)]
    (:history (challenges/append-controlled-action (:config f) policy h (signed a (:reviewer f))))))

(defn- controlled-transact [f policy h who kind definition payload]
  (let [state (challenges/replay-controlled (:config f) policy h)
        a (challenges/action (:config f) (:history/root state) (get f (keyword (str (name who) "-ref")))
                             kind (:artifact/root definition) (str (count h)) payload)]
    (:history (challenges/append-controlled-action (:config f) policy h (signed a (get f who))))))

(defn- revoke-payload [f state who]
  {:researcher/root (get f (keyword (str (name who) "-ref")))
   :enrollment/root (get-in state [:researchers (get f (keyword (str (name who) "-ref"))) :enrollment/root])
   :reason :key-compromised :evidence/root (r :revocation-justification)})

(defn- withdraw-payload [submission review]
  {:submission/root (:submission/root submission) :review/action-root (:review/action-root review)
   :reason :review-error :evidence/root (r :withdrawal-justification)})

(deftest control-opt-in-preserves-existing-v1-identity-and-forensics
  (let [f (fixture) h (enroll f [] :alice)
        h (complete f h :alice (first (:definitions f)) :existing-finding)
        policy (challenges/build-control-policy (:config f) h)
        original (challenges/replay (:config f) h)
        composed (challenges/replay-controlled (:config f) policy h)]
    (is (= original (dissoc composed :control-policy :revocations :withdrawals)))
    (is (= (challenges/researcher-summary (:config f) h (:alice-ref f))
           (dissoc (challenges/controlled-researcher-summary (:config f) policy h (:alice-ref f))
                   :control-policy/root :revocation :withdrawals :historical-accepted-work)))
    (is (= (mapv :node-hash (challenges/forensic-nodes (:config f) h))
           (mapv :node-hash (challenges/controlled-forensic-nodes (:config f) policy h))))
    (is (= policy (challenges/build-control-policy (:config f) (read-string (pr-str h)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (challenges/replay-controlled (:config f) (assoc policy :experience/effect :erase-history) h)))
    (let [weakened (assoc policy :experience/effect :retain-withdrawn-credit)
          weakened (assoc weakened :artifact/root
                          (reference/sha256-ref
                           (canonical/domain-hash "researcher-challenge-control-policy.v1"
                                                  (canonical/project-canonical-safe (dissoc weakened :artifact/root)))))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (challenges/replay-controlled (:config f) weakened h))))
    (is (thrown? clojure.lang.ExceptionInfo (challenges/replay-controlled (:config f) policy (pop h))))))

(deftest prospective-revocation-preserves-prior-work-and-stops-future-participant-actions
  (let [f (fixture) d (first (:definitions f)) next-definition (second (:definitions f))
        h (enroll f [] :alice) h (enroll f h :bob)
        h (complete f h :alice d :prior-finding)
        h (claim-task f h :alice next-definition) reserved (latest f h)
        prior (challenges/replay (:config f) h)
        policy (challenges/build-control-policy (:config f) h)
        revoked (control-transact f policy h :revoke-participant (revoke-payload f prior :alice))
        state (challenges/replay-controlled (:config f) policy revoked)]
    (is (= (:reviews prior) (:reviews state)))
    (is (= (:quotes prior) (:quotes state)))
    (is (= (:researchers prior) (:researchers state)))
    (is (= (:contributions prior) (:contributions state)))
    (is (= (count h) (get-in state [:revocations (:alice-ref f) :control/sequence])))
    (is (= 1 (:accepted-contribution-count
              (challenges/controlled-researcher-summary (:config f) policy revoked (:alice-ref f)))))
    (is (thrown? clojure.lang.ExceptionInfo (challenges/replay (:config f) revoked)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (controlled-transact f policy revoked :alice :claim (nth (:definitions f) 2) {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (controlled-transact f policy revoked :alice :submit next-definition
                                      {:task-claim/root (:artifact/root reserved)
                                       :evidence/context (evidence f :post-cutoff)})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (control-transact f policy revoked :revoke-participant (revoke-payload f prior :alice))))
    (let [bob (controlled-transact f policy revoked :bob :claim next-definition {})]
      (is (= (inc (count revoked)) (count bob)))
      (is (= 0 (get-in (last (:events (challenges/replay-controlled (:config f) policy bob)))
                       [:consequence :experience/snapshot :accepted-contribution-count]))))
    (is (:valid? (node/validate-node-dag (challenges/controlled-forensic-nodes (:config f) policy revoked))))))

(deftest withdrawal-adjusts-only-future-experience-and-preserves-issued-obligations
  (let [f (fixture) [a b c reserved next-definition] (:definitions f)
        h (enroll f [] :alice)
        h (complete f h :alice a :first) h (complete f h :alice b :second)
        h (complete f h :alice c :third)
        h (claim-task f h :alice reserved) frozen (latest f h)
        prior (challenges/replay (:config f) h)
        review (first (sort-by :review/action-root (vals (:reviews prior))))
        submission (get-in prior [:submissions (:submission/root review)])
        obligation (:obligation review)
        h (transact f h (:authority-ref f) (:reviewer f) :payout nil
                    {:obligation/root (:researcher-reward-obligation/root obligation)
                     :payout/status :recorded-paid :external/reference "external:historical-payment"})
        baseline (challenges/replay (:config f) h)
        policy (challenges/build-control-policy (:config f) h)
        withdrawn (control-transact f policy h :withdraw-review (withdraw-payload submission review))
        state (challenges/replay-controlled (:config f) policy withdrawn)
        report (challenges/controlled-researcher-summary (:config f) policy withdrawn (:alice-ref f))
        continued (controlled-transact f policy withdrawn :alice :claim next-definition {})
        next-quote (:consequence (last (:events (challenges/replay-controlled (:config f) policy continued))))]
    (is (= 125 (:reward/amount frozen)))
    (is (= frozen (get-in state [:quotes (:artifact/root frozen)])))
    (is (= 100 (:reward/amount next-quote)))
    (is (= 2 (get-in next-quote [:experience/snapshot :accepted-contribution-count])))
    (is (= (:reviews baseline) (:reviews state)))
    (is (= (:payouts baseline) (:payouts state)))
    (is (= (:contributions baseline) (:contributions state)))
    (is (= 2 (:accepted-contribution-count report)))
    (is (= 2 (count (:accepted-work report))))
    (is (= 3 (count (:historical-accepted-work report))))
    (is (= :superseded (get-in state [:withdrawals (:submission/root submission)
                                      :lifecycle-state :research-claim-lifecycle/state])))
    (let [dossier (challenges/controlled-review-dossier (:config f) policy withdrawn (:submission/root submission))]
      (is (= :superseded (:review/status dossier)))
      (is (= :accepted (get-in dossier [:review :decision])))
      (is (= obligation (get-in dossier [:review :obligation])))
      (is (= :withdrawal-justification (:reference/kind (last (:external-evidence/references dossier))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (control-transact f policy withdrawn :withdraw-review (withdraw-payload submission review))))
    (let [re-review (challenges/action (:config f) (:history/root state) (:authority-ref f) :review
                                       (:challenge/root review) "replacement-verdict"
                                       {:submission/root (:submission/root submission) :decision :rejected
                                        :incentive/decision :ineligible :incentive/evidence-root (r :other)
                                        :decision/evidence-root (r :other) :accepted-at "2026-10-10T12:00:00Z"})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (challenges/append-controlled-action (:config f) policy withdrawn (signed re-review (:reviewer f))))))
    (is (= state (challenges/replay-controlled (:config f) policy (read-string (pr-str withdrawn)))))
    (let [target (some #(when (= (:artifact/root %) (:challenge/root review)) %) (:definitions f))
          bob (controlled-transact f policy withdrawn :bob :enroll nil
                                   {:public-key (get-in f [:bob :public]) :disclosure :pseudonymous})
          bob (controlled-transact f policy bob :bob :claim target {})
          bob-quote (:consequence (last (:events (challenges/replay-controlled (:config f) policy bob))))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (controlled-transact f policy bob :bob :submit target
                                        {:task-claim/root (:artifact/root bob-quote)
                                         :evidence/context (:context submission)}))))
    (is (:valid? (node/validate-node-dag (challenges/controlled-forensic-nodes (:config f) policy withdrawn))))))

(deftest control-substitution-forgery-and-replay-are-rejected
  (let [f (fixture) h (enroll f [] :alice)
        h (complete f h :alice (first (:definitions f)) :control-target)
        state (challenges/replay (:config f) h)
        review (first (vals (:reviews state))) submission (get-in state [:submissions (:submission/root review)])
        policy (challenges/build-control-policy (:config f) h)
        a (challenges/control-action (:config f) policy (:history/root state) :withdraw-review "withdraw-1"
                                     (withdraw-payload submission review))
        signed-a (signed a (:reviewer f))
        changed (fn [body] (signed (assoc body :action/root (challenges/control-action-root body)) (:reviewer f)))]
    (doseq [bad [(signed a (:alice f))
                 (changed (assoc a :configuration/root (r :foreign-configuration)))
                 (changed (assoc a :network/root (r :foreign-network)))
                 (changed (assoc a :control-policy/root (r :foreign-policy)))
                 (changed (assoc a :actor/root (:alice-ref f)))
                 (changed (assoc a :history/root (r :wrong-prefix)))
                 (changed (assoc-in a [:action/payload :review/action-root] (r :other-review)))
                 (changed (assoc-in a [:action/payload :submission/root] (r :other-submission)))
                 (changed (assoc-in a [:action/payload :accepted-at] "1900-01-01T00:00:00Z"))]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (challenges/append-controlled-action (:config f) policy h bad))))
    (let [applied (:history (challenges/append-controlled-action (:config f) policy h signed-a))
          cp (challenges/controlled-history-checkpoint (:config f) policy applied)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (challenges/append-controlled-action (:config f) policy applied signed-a)))
      (is (= (challenges/replay-controlled (:config f) policy applied)
             (challenges/verify-controlled-history-checkpoint (:config f) policy applied cp)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (challenges/verify-controlled-history-checkpoint (:config f) policy h cp)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (challenges/verify-controlled-history-checkpoint (:config f) policy applied
                                                                    (assoc cp :control-policy/root (r :other-policy))))))))

(deftest controlled-file-admission-and-cli-preserve-history-on-control-rejection
  (let [f (fixture) h (enroll f [] :alice) h (enroll f h :bob)
        config (:config f) policy (challenges/build-control-policy config h)
        state (challenges/replay config h)
        directory (.toFile (Files/createTempDirectory "challenge-control-"
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        path (fn [filename] (str directory "/" filename))
        cp (challenges/controlled-history-checkpoint config policy h)
        payload (revoke-payload f state :alice)
        request {:actor (:authority-ref f) :kind :revoke-participant :challenge-root nil
                 :nonce "revoke-1" :payload payload}]
    (try
      (doseq [[file value] [["config.edn" config] ["policy.edn" policy] ["history.edn" h]
                            ["checkpoint.edn" cp] ["request.edn" request]]]
        (spit (path file) (pr-str value)))
      (let [ordinary (challenges/action config (:history/root state) (:alice-ref f) :claim
                                        (:artifact/root (first (:definitions f))) "ordinary-claim" {})
            before (slurp (path "history.edn"))]
        (spit (path "ordinary.edn") (pr-str (signed ordinary (:alice f))))
        (spit (path "missing-policy.edn") "nil")
        (is (thrown? clojure.lang.ExceptionInfo
                     (cli/apply-controlled-file! (path "config.edn") (path "missing-policy.edn")
                                                 (path "history.edn") (path "ordinary.edn") nil)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (cli/apply-controlled-file! (path "config.edn") nil
                                                 (path "history.edn") (path "ordinary.edn") nil)))
        (is (= before (slurp (path "history.edn")))))
      (let [unsigned (read-string (with-out-str
                                    (cli/-main "control-request" (path "config.edn") (path "policy.edn")
                                               (path "history.edn") (path "request.edn") (path "checkpoint.edn"))))
            a (signed unsigned (:reviewer f))]
        (is (= unsigned (challenges/control-action config policy (:history/root state) :revoke-participant "revoke-1" payload)))
        (spit (path "action.edn") (pr-str a))
        (cli/apply-controlled-file! (path "config.edn") (path "policy.edn") (path "history.edn")
                                    (path "action.edn") (path "checkpoint.edn"))
        (let [before (slurp (path "history.edn"))
              admitted (read-string before)]
          (is (= (conj h a) admitted))
          (is (thrown? clojure.lang.ExceptionInfo
                       (cli/apply-controlled-file! (path "config.edn") (path "policy.edn") (path "history.edn")
                                                   (path "action.edn") (path "checkpoint.edn"))))
          (is (= before (slurp (path "history.edn"))))
          (is (= (challenges/replay-controlled config policy admitted)
                 (read-string (with-out-str
                                (cli/-main "control-state" (path "config.edn") (path "policy.edn") (path "history.edn"))))))
          (is (= (challenges/controlled-history-checkpoint config policy admitted)
                 (read-string (with-out-str
                                (cli/-main "control-checkpoint" (path "config.edn") (path "policy.edn") (path "history.edn"))))))
          (is (= (:historical-accepted-work (challenges/controlled-researcher-summary config policy admitted (:alice-ref f)))
                 (:historical-accepted-work (read-string (with-out-str
                                                           (cli/-main "control-summary" (path "config.edn") (path "policy.edn")
                                                                      (path "history.edn") (:alice-ref f)))))))
          ;; Same participant count is not an enrollment binding: A's reference + B's enrollment fails.
          (let [bad (challenges/control-action config policy
                                               (:history/root (challenges/replay-controlled config policy admitted))
                                               :revoke-participant "wrong-enrollment"
                                               (assoc (revoke-payload f state :bob) :enrollment/root (:enrollment/root payload)))]
            (is (thrown? clojure.lang.ExceptionInfo
                         (challenges/append-controlled-action config policy admitted (signed bad (:reviewer f))))))))
      (finally (doseq [file (reverse (file-seq directory))] (.delete file))))))

(deftest rejected-review-can-be-withdrawn-without-gaining-credit
  (let [f (fixture) d (first (:definitions f)) h (enroll f [] :alice)
        h (claim-task f h :alice d) q (latest f h)
        h (submit f h :alice d q :rejected-finding) s (latest f h)
        h (approve f h d s :rejected :ineligible) reviewed (latest f h)
        policy (challenges/build-control-policy (:config f) h)
        withdrawn (control-transact f policy h :withdraw-review (withdraw-payload s reviewed))
        report (challenges/controlled-researcher-summary (:config f) policy withdrawn (:alice-ref f))]
    (is (= 0 (:accepted-contribution-count report)))
    (is (empty? (:historical-accepted-work report)))
    (is (= :superseded (:review/status (challenges/controlled-review-dossier (:config f) policy withdrawn (:submission/root s)))))
    (is (nil? (:obligation reviewed)))))

(deftest unreviewed-work-has-no-credit-and-published-checkpoint-detects-omission
  (let [f (fixture) d (first (:definitions f)) h (enroll f [] :alice)
        h (claim-task f h :alice d) q (latest f h)
        h (submit f h :alice d q :pending) s (latest f h)
        state (challenges/replay (:config f) h)]
    (is (empty? (:reviews state)))
    (is (= 0 (:accepted-contribution-count (challenges/researcher-summary (:config f) h (:alice-ref f)))))
    (is (thrown? clojure.lang.ExceptionInfo (approve f h d s :rejected :eligible)))
    (is (= state (challenges/replay (:config f) h (:history/root state))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (challenges/replay (:config f) (pop h) (:history/root state))))))

(deftest the-same-canonical-contribution-in-another-challenge-does-not-farm-experience
  (let [f (fixture) [a b c] (:definitions f) h (enroll f [] :alice)
        h (complete f h :alice a :same-contribution)
        h (complete f h :alice b :same-contribution)
        summary (challenges/researcher-summary (:config f) h (:alice-ref f))
        h (claim-task f h :alice c) q (latest f h)]
    (is (= 2 (count (:accepted-work summary))))
    (is (= 1 (:accepted-contribution-count summary)))
    (is (= 1 (get-in q [:experience/snapshot :accepted-contribution-count])))
    (is (= 100 (:reward/amount q)))))

(deftest local-key-file-signing-produces-authenticated-enrollment
  (let [f (fixture) directory (.toFile (Files/createTempDirectory "challenge-signing-"
                                                                  (make-array java.nio.file.attribute.FileAttribute 0)))
        private-file (str directory "/private.pem")
        unsigned (request f [] (:alice-ref f) :enroll nil
                          {:public-key (get-in f [:alice :public]) :disclosure :pseudonymous})]
    (try
      (spit private-file (str "-----BEGIN PRIVATE KEY-----\n"
                              (.encodeToString (Base64/getEncoder) (.getEncoded (get-in f [:alice :private])))
                              "\n-----END PRIVATE KEY-----\n"))
      (let [enrollment (challenges/sign-action unsigned private-file)
            state (challenges/replay (:config f) [enrollment])]
        (is (contains? (:researchers state) (:alice-ref f)))
        (is (not-any? #{private-file} (vals enrollment))))
      (finally (doseq [file (reverse (file-seq directory))] (.delete file))))))

(defn qualification-demo
  "Portable signed fixture with no private keys. Findings are qualification
   inputs from the existing analysis tests, not substantive research findings."
  []
  (let [f (fixture) [a b c d] (:definitions f)
        history (enroll f [] :alice) history (enroll f history :bob)
        history (complete f history :alice a :alice-first)
        history (complete f history :bob a :bob-first)
        history (complete f history :alice b :alice-second)
        history (complete f history :alice c :alice-third)
        history (claim-task f history :alice d)
        state (challenges/replay (:config f) history)]
    {:fixture/classification :qualification-only
     :configuration (:config f) :signed-actions history :history/root (:history/root state)
     :frozen-promoted-quote (latest f history)
     :researcher-summaries (mapv #(challenges/researcher-summary (:config f) history %)
                                 [(:alice-ref f) (:bob-ref f)])
     :forensic/nodes (challenges/forensic-nodes (:config f) history)}))

(defn -main [output-file]
  (let [demo (qualification-demo)]
    (spit output-file (pretty/ppr-str demo))
    (prn {:fixture/classification :qualification-only
          :history/root (:history/root demo)
          :signed-action-count (count (:signed-actions demo))
          :accepted-counts (mapv :accepted-contribution-count (:researcher-summaries demo))
          :promoted-reward (get-in demo [:frozen-promoted-quote :reward/amount])
          :forensic-node-count (count (:forensic/nodes demo))})))
