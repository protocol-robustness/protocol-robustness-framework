(ns resolver-sim.research.shortfall-pilot-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [resolver-sim.research.shortfall-pilot :as pilot]
            [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.research.researcher-challenges-cli :as cli]
            [resolver-sim.research.researcher-challenges-test :as helpers]
            [resolver-sim.benchmark.research-analysis :as analysis]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.hash.canonical :as canonical])
  (:import [java.nio.file Files]))

(defn- inputs []
  (mapv #(edn/read-string (slurp (str "examples/researcher-challenges/shortfall-stress/" % ".edn")))
        ["unallocated" "redistribute"]))

(defn- candidate-inputs []
  ;; Test-only non-calibration case. The published examples remain excluded.
  (mapv #(assoc % :allocation/id :researcher-pilot/non-calibration-test-case) (inputs)))

(defn- scope-candidate []
  (edn/read-string
   (slurp "examples/researcher-challenges/shortfall-stress/challenge-001-scope-candidate.v1.edn")))

(defn- fixture []
  (let [f (#'helpers/fixture)
        old (first (:definitions f))
        definition (challenges/build-challenge
                    {:id :challenge/shortfall :subject-root (:subject/root old)
                     :subject-revision (:subject/revision-root old) :contribution-type :adversarial-finding
                     :acceptance-criteria {:proposition-id pilot/proposition :metric-id pilot/metric
                                           :result-classifications #{:counterexample-found}}
                     :base-reward 100 :capacity 2 :reward-policy-root (get-in f [:config :reward/policy :artifact/root])})
        config (challenges/configuration (get-in f [:config :network/id])
                                         (get-in f [:reviewer :public]) (get-in f [:config :reward/policy]) [definition])
        comparison (pilot/execute-comparison (candidate-inputs))
        metadata {:subject-root (:subject/root old)
                  :environment-root (get-in f [:context :research-command :command/environment-root])
                  :runner-root (get-in f [:context :research-command :command/runner-root])
                  :assignment-fields (select-keys (get-in f [:context :research-assignment])
                                                  [:research-assignment/id :research-assignment/policy-hash
                                                   :research-assignment/review-round-hash :research-assignment/target
                                                   :research-assignment/plan-root])}]
    (assoc f :config config :definition definition :comparison comparison :metadata metadata
           :context (pilot/build-context metadata comparison)
           :profile (pilot/build-profile config comparison)
           :evidence {(:artifact/root comparison) comparison})))

(defn- submission [f history context]
  (#'helpers/transact f history (:alice-ref f) (:alice f) :submit (:definition f)
                      {:task-claim/root (:artifact/root (#'helpers/latest f history)) :evidence/context context}))

(defn- before-submission [f]
  (#'helpers/claim-task f (#'helpers/enroll f [] :alice) :alice (:definition f)))

(defn- reroot [x]
  (assoc x :artifact/root (reference/sha256-ref
                           (canonical/domain-hash (:artifact/schema x) (dissoc x :artifact/root)))))

(defn- reroot-candidate [x]
  (assoc x :candidate/root (reference/sha256-ref
                            (canonical/domain-hash (:artifact/schema x) (dissoc x :candidate/root)))))

(deftest real-arithmetic-and-closed-inputs
  (let [comparison (pilot/execute-comparison (inputs))]
    (is (= 12 (:value comparison)))
    (is (= [76 100] (mapv :allocated-total (:results comparison))))
    (is (= ["fca0d5cb0b56e8df5a7fc94d9f292130b03658fe2e3ade27f5e48b79c7fb4ec4"
            "46f3a6ecafbd1c1b46f66e8a79092fb6bdae1d6bb5bb98c84976eccbfc33468a"]
           (mapv :allocation/hash (:results comparison))))
    (is (= comparison (pilot/validate-comparison! (edn/read-string (pr-str comparison)))))
    (is (= 0 (:value (pilot/execute-comparison [(first (inputs)) (first (inputs))]))))
    (doseq [bad [(assoc-in (inputs) [0 :expected-result] 12)
                 (assoc-in (inputs) [0 :runner] :runtime/alice)
                 (assoc-in (inputs) [0 :rows 0 :callback] :runtime/callback)
                 (assoc-in (inputs) [1 :available] 101)
                 (assoc-in (inputs) [1 :rows 0 :obligation/id] :obligation/wrong)]]
      (is (thrown? clojure.lang.ExceptionInfo (pilot/execute-comparison bad))))
    (doseq [bad [(assoc comparison :value 99)
                 (reroot (assoc comparison :value 99))
                 (reroot (assoc-in comparison [:results 0 :rows 0 :allocated] 99))]]
      (is (thrown? clojure.lang.ExceptionInfo (pilot/validate-comparison! bad))))))

(deftest challenge-001-scope-candidate-is-bounded-and-non-admitting
  (let [candidate (scope-candidate)
        comparison (pilot/execute-comparison (candidate-inputs))
        checked (pilot/validate-challenge-001-scope-candidate! candidate comparison)]
    (is (= :valid-candidate-input (:scope/status checked)))
    (is (= :counterexample-found (:result/classification checked)))
    (is (true? (:falsification-condition-met? checked)))
    (is (true? (:novelty/review-required? checked)))
    (is (= (:artifact/root comparison) (get-in checked [:witness :comparison/root])))
    (is (= 12N (get-in checked [:witness :maximum-difference])))
    ;; The boundary classification is supported, not a falsification and not
    ;; an authority/reward transition.
    (let [zero-inputs (mapv #(assoc % :available 0) (candidate-inputs))
          zero (pilot/execute-comparison zero-inputs)
          zero-checked (pilot/validate-challenge-001-scope-candidate! candidate zero)]
      (is (= :supported (:result/classification zero-checked)))
      (is (false? (:falsification-condition-met? zero-checked)))
      (is (empty? (get-in zero-checked [:witness :rows]))))
    ;; Fresh arithmetic outside the proposed bounded domain is rejected.
    (let [out-of-domain (mapv #(-> %
                                   (assoc-in [:rows 0 :requested] 1000001)
                                   (assoc-in [:rows 0 :weight] 1000001))
                              (candidate-inputs))
          comparison-outside (pilot/execute-comparison out-of-domain)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (pilot/validate-challenge-001-scope-candidate! candidate comparison-outside))))
    ;; A same-policy pair cannot stand in for the specifically declared contrast.
    (let [same-policy (mapv #(assoc % :redistribution-policy :unallocated)
                            (candidate-inputs))
          comparison-same-policy (pilot/execute-comparison same-policy)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (pilot/validate-challenge-001-scope-candidate! candidate comparison-same-policy))))
    ;; The exact published calibration root remains ineligible.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"calibration-case-not-qualifying"
                          (pilot/validate-challenge-001-scope-candidate!
                           candidate (pilot/execute-comparison (inputs)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (pilot/validate-challenge-001-scope-candidate!
                  (assoc candidate :challenge/title "substituted without rerooting") comparison)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (pilot/validate-challenge-001-scope-candidate!
                  (reroot-candidate (assoc-in candidate [:input-domain :allocation-row-count :maximum] 99))
                  comparison)))))

(deftest signed-economic-evidence-remains-separate-from-review-and-reward
  (let [f (fixture) history (submission f (before-submission f) (:context f))
        checked (pilot/replay (:config f) (:profile f) history (:evidence f))
        submitted (#'helpers/latest f history)
        reviewed (#'helpers/approve f history (:definition f) submitted :accepted :eligible)
        final (pilot/replay (:config f) (:profile f) reviewed (:evidence f))]
    (is (= (:state checked) (challenges/replay (:config f) history)))
    (is (empty? (get-in checked [:state :reviews])))
    (is (= 1 (count (:replay/nodes checked))))
    (is (= :validated (get-in checked [:replay/nodes 0 :extensions :forensic/role])))
    (is (= :counterexample-found (get-in submitted [:claim :research-claim/result :result/classification])))
    (is (some? (get-in (#'helpers/latest f reviewed) [:obligation :researcher-reward-obligation/root])))
    (is (= (:state final) (challenges/replay (:config f) reviewed)))
    (let [recovered (pilot/replay (:config f) (:profile f) (edn/read-string (pr-str reviewed))
                                  (edn/read-string (pr-str (:evidence f))))]
      (is (= (:state final) (:state recovered)))
      (is (= (mapv :node-hash (:replay/nodes final))
             (mapv :node-hash (:replay/nodes recovered)))))))

(deftest method-gate-rejects-rooted-but-false-results-and-policy-substitution
  (let [f (fixture) context (:context f)
        validate #(pilot/validate-context! (:config f) (:profile f) (:artifact/root (:definition f)) % (:evidence f))]
    (is (map? (validate context)))
    (doseq [[path value] [[[:analysis-output/result :result/value] 99]
                          [[:analysis-output/result :result/witness-root] (:method/root (:comparison f))]
                          [[:analysis-output/result :result/case-root] (:method/root (:comparison f))]
                          [[:analysis-output/evidence-root] (:method/root (:comparison f))]
                          [[:analysis-engine/version] "wrong"]]]
      (let [output (assoc-in (:analysis-output context) path value)
            changed (assoc context :analysis-output
                           (assoc output :research-analysis-output/root (analysis/output-root output)))]
        (when (= path [:analysis-output/result :result/value])
          ;; Generic provenance verification alone cannot establish arithmetic.
          (is (analysis/verification-valid? (analysis/verify-output changed) (:analysis-output changed))))
        (is (thrown? clojure.lang.ExceptionInfo (validate changed)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (pilot/validate-context! (:config f) (:profile f) (:artifact/root (:definition f)) context {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (pilot/validate-context! (:config f)
                                          (reroot (assoc (:profile f) :allocation-policy/roots [pilot/method-root]))
                                          (:artifact/root (:definition f)) context (:evidence f))))
    (let [changed (pilot/build-context (assoc (:metadata f) :runner-root pilot/method-root) (:comparison f))]
      (is (map? (validate changed)))
      (is (= (get-in context [:analysis-output :analysis-output/evidence-root])
             (get-in changed [:analysis-output :analysis-output/evidence-root]))))))

(deftest published-calibration-case-cannot-qualify-as-a-contribution
  (let [f (fixture)
        calibration (pilot/execute-comparison (inputs))
        metadata (:metadata f)
        context (pilot/build-context metadata calibration)
        evidence {(:artifact/root calibration) calibration}]
    (is (some #{(:case/root calibration)}
              (:excluded-calibration-case/roots (:profile f))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"calibration-case-not-qualifying"
                          (pilot/validate-context! (:config f) (:profile f)
                                                   (:artifact/root (:definition f)) context evidence)))))

(deftest failed-method-admission-leaves-durable-history-byte-exact
  (let [f (fixture) history (before-submission f)
        good (submission f history (:context f))
        directory (.toFile (Files/createTempDirectory "shortfall-admission-" (make-array java.nio.file.attribute.FileAttribute 0)))
        path #(str directory "/" %)
        check #(pilot/replay %1 (:profile f) %2 (:evidence f))]
    (spit (path "config.edn") (pr-str (:config f)))
    (spit (path "history.edn") (pr-str history))
    (spit (path "action.edn") (pr-str (last good)))
    (let [bytes (slurp (path "history.edn"))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (cli/apply-checked-file! (path "config.edn") (path "history.edn") (path "action.edn") nil
                                            #(pilot/replay %1 (:profile f) %2 {}))))
      (is (= bytes (slurp (path "history.edn")))))
    (is (map? (cli/apply-checked-file! (path "config.edn") (path "history.edn") (path "action.edn") nil check)))
    (is (= good (edn/read-string (slurp (path "history.edn")))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cli/apply-checked-file! (path "config.edn") (path "history.edn") (path "action.edn") nil check)))))

(deftest controlled-history-composes-with-the-shortfall-arithmetic-gate
  (let [f (fixture)
        submitted-history (submission f (before-submission f) (:context f))
        submission-body (#'helpers/latest f submitted-history)
        reviewed-history (#'helpers/approve f submitted-history (:definition f)
                                            submission-body :accepted :eligible)
        activation-history reviewed-history
        control-policy (challenges/build-control-policy (:config f) activation-history)
        profile (pilot/build-controlled-profile (:config f) (:profile f) control-policy activation-history)
        state (challenges/replay (:config f) reviewed-history)
        review (get-in state [:reviews (:submission/root submission-body)])
        withdrawal-payload (#'helpers/withdraw-payload submission-body review)
        controlled-state (challenges/replay-controlled (:config f) control-policy activation-history)
        unsigned (challenges/control-action (:config f) control-policy (:history/root controlled-state)
                                            :withdraw-review "withdraw-shortfall-review" withdrawal-payload)
        signed (#'helpers/signed unsigned (:reviewer f))
        history (:history (challenges/append-controlled-action (:config f) control-policy activation-history signed))
        result (pilot/replay-controlled (:config f) profile control-policy history (:evidence f))]
    (is (= 1 (count (filter #(= :submit (:action/kind %)) history))))
    (is (= 2 (count (filter #(= :validated (get-in % [:extensions :forensic/role]))
                            (:replay/nodes result)))))
    (is (= 1 (count (get-in result [:state :withdrawals]))))
    (is (= (challenges/replay-controlled (:config f) control-policy history) (:state result)))
    (is (:valid? (node/validate-node-dag (:replay/nodes result))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (pilot/replay-controlled (:config f) profile
                                          (assoc control-policy :artifact/root (reference/sha256-ref "wrong"))
                                          history (:evidence f))))
    (let [directory (.toFile (Files/createTempDirectory "controlled-shortfall-" (make-array java.nio.file.attribute.FileAttribute 0)))
          path #(str directory "/" %)
          action (last history)]
      (spit (path "config.edn") (pr-str (:config f)))
      (spit (path "profile.edn") (pr-str profile))
      (spit (path "policy.edn") (pr-str control-policy))
      (spit (path "history.edn") (pr-str activation-history))
      (spit (path "action.edn") (pr-str action))
      (spit (path "evidence.edn") (pr-str (:evidence f)))
      (let [before (slurp (path "history.edn"))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (cli/apply-controlled-checked-file!
                      (path "config.edn") (path "policy.edn") (path "history.edn") (path "action.edn") nil
                      #(pilot/replay-controlled %1 profile control-policy %2 {}))))
        (is (= before (slurp (path "history.edn")))))
      (is (map? (cli/apply-controlled-checked-file!
                 (path "config.edn") (path "policy.edn") (path "history.edn") (path "action.edn") nil
                 #(pilot/replay-controlled %1 profile control-policy %2 (:evidence f)))))
      (is (= history (edn/read-string (slurp (path "history.edn"))))))))
