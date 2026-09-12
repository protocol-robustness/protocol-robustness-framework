(ns resolver-sim.benchmark.user-defined-configuration-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.cli :as cli]
            [resolver-sim.benchmark.manifest :as manifest]
            [resolver-sim.benchmark.outcome-policy :as outcome-policy]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.benchmark.research-observation-projection :as observation-projection]
            [resolver-sim.benchmark.runner :as runner]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.settings.semantic :as setting]))

(defn- application [owner manifest]
  {:application/default-benchmark-manifest
   (setting/build-setting
    {:setting/id (keyword (name owner) "default-benchmark-manifest")
     :setting/owner-id owner
     :setting/value {:benchmark-manifest/root (:benchmark-manifest/root manifest)}
     :setting/resolution {:benchmark-manifest/source (str "file:" (namespace owner) ".edn")}})})

(deftest applications-can-own-different-rooted-manifests
  (let [a (manifest/build-manifest {:benchmark/id :alpha/benchmark
                                    :benchmark/version 1
                                    :benchmark/requirements [:alpha/deficit-margin]})
        b (manifest/build-manifest {:benchmark/id :beta/benchmark
                                    :benchmark/version 1
                                    :benchmark/requirements [:beta/deficit-margin]})]
    (is (:valid? (manifest/validate-manifest a)))
    (is (:valid? (manifest/validate-manifest b)))
    (is (not= (:benchmark-manifest/root a) (:benchmark-manifest/root b)))
    (is (= "file:alpha.edn" (:benchmark-manifest/source
                             (cli/application-default-manifest (application :alpha/app a)))))
    (is (= "file:beta.edn" (:benchmark-manifest/source
                            (cli/application-default-manifest (application :beta/app b)))))))

(deftest benchmark-selection-precedence
  (let [manifest (manifest/build-manifest {:benchmark/id :alpha/benchmark
                                           :benchmark/version 1})
        app (application :alpha/app manifest)]
    (with-redefs [rp/edn-read (constantly manifest)]
      (is (= "explicit.edn" (cli/resolve-benchmark-manifest "explicit.edn" app)))
      (is (= "file:alpha.edn" (cli/resolve-benchmark-manifest nil app)))
      (is (= "explicit.edn" (cli/resolve-benchmark-manifest "explicit.edn" app)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No benchmark manifest selected"
                            (cli/resolve-benchmark-manifest nil nil))))))

(deftest application-default-manifest-requires-committed-root
  (let [manifest (manifest/build-manifest {:benchmark/id :alpha/benchmark
                                           :benchmark/version 1})
        app (application :alpha/app manifest)]
    (with-redefs [rp/edn-read (constantly (assoc manifest :benchmark-manifest/root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))]
      (is (= :benchmark-manifest/root-mismatch
             (:reason (ex-data (try (cli/resolve-benchmark-manifest nil app)
                                    (catch clojure.lang.ExceptionInfo e e)))))))
    (with-redefs [rp/edn-read (constantly (manifest/body manifest))]
      (is (= :benchmark-manifest/unrooted-default
             (:reason (ex-data (try (cli/resolve-benchmark-manifest nil app)
                                    (catch clojure.lang.ExceptionInfo e e)))))))))

(deftest user-defined-deficit-margin-measures-remain-framework-generic
  (let [source {:artifact/schema "research-source.v1"
                :research/id :alpha/study
                :research/title "Alpha"
                :research/question "Does the application meet its requirement?"
                :research/vary {:alpha/input [1N]}
                :research/measures
                [{:measure/id :alpha/deficit-margin
                  :measure/kind :requirement-margin.v1
                  :measure/domain {:kind :integer :unit :points}
                  :measure/observation {:observation/id :alpha/observed-score}
                  :measure/requirement {:requirement/kind :constant :value 90N :unit :points}
                  :measure/satisfying-direction :at-least}
                 {:measure/id :beta/deficit-margin
                  :measure/kind :requirement-margin.v1
                  :measure/domain {:kind :integer :unit :points}
                  :measure/observation {:observation/id :beta/observed-loss}
                  :measure/requirement {:requirement/kind :constant :value 10N :unit :points}
                  :measure/satisfying-direction :at-most}]
                :research/hypotheses []}
        frozen (research/freeze-research source)]
    (is (= 2 (count (get-in frozen [:research-definition :research/measures]))))
    (is (= 5N (research/requirement-margin {:observed 95N :requirement 90N
                                            :domain {:kind :integer :unit :points}
                                            :satisfying-direction :at-least})))
    (is (= 4N (research/requirement-margin {:observed 6N :requirement 10N
                                            :domain {:kind :integer :unit :points}
                                            :satisfying-direction :at-most})))
    (is (= {:measure/id :alpha/deficit-margin
            :observed/value 85N
            :requirement/value 90N
            :requirement/unit :points
            :requirement/satisfying-direction :at-least
            :requirement/margin -5N
            :requirement/status :unsatisfied
            :requirement/satisfied? false}
           (research/evaluate-requirement
            (first (:research/measures source)) 85N)))))

(deftest manifest-selects-but-never-redefines-research-measures
  (let [frozen (research/freeze-research
                {:artifact/schema "research-source.v1"
                 :research/id :alpha/study
                 :research/title "Alpha"
                 :research/question "Question"
                 :research/vary {:alpha/input [1N]}
                 :research/measures [{:measure/id :alpha/requirement
                                      :measure/kind :requirement-margin.v1
                                      :measure/domain {:kind :integer :unit :points}
                                      :measure/observation {:observation/id :alpha/score}
                                      :measure/requirement {:requirement/kind :constant :value 80N :unit :points}
                                      :measure/satisfying-direction :at-least}]
                 :research/hypotheses []})
        binding {:research-definition/root (:research-definition/root frozen)
                 :measure-ids [:alpha/requirement]}
        manifest (manifest/build-manifest {:benchmark/id :alpha/benchmark
                                           :benchmark/research-binding binding})]
    (is (:valid? (manifest/validate-manifest manifest)))
    (is (= [:alpha/requirement]
           (mapv :measure/id (manifest/selected-research-measures binding frozen))))))

(deftest observation-mapping-is-declared-data
  (is (= {:observation/id :alpha/coverage-bps
          :value 8300N
          :unit :basis-points}
         (research/map-observation
          {:observation/id :alpha/coverage-bps
           :observation/source {:observation/id :waterfall/coverage-adequacy-pct}
           :observation/transform {:transform/kind :scale :multiply 100N}
           :observation/domain {:kind :integer :unit :basis-points}}
          {:waterfall/coverage-adequacy-pct 83N}))))

(deftest semantic-observation-projection-is-rooted-and-typed
  (let [projection (observation-projection/build-projection
                    {:producer/coverage-pct {:observation/value 90N
                                             :observation/domain {:kind :integer :unit :percentage-points}}})]
    (is (:valid? (observation-projection/validate-projection projection)))
    (is (= {:producer/coverage-pct 90N}
           (observation-projection/observation-values projection)))
    (is (not= (:research-observation-projection/root projection)
              (:research-observation-projection/root
               (observation-projection/build-projection
                {:producer/coverage-pct {:observation/value 91N
                                         :observation/domain {:kind :integer :unit :percentage-points}}}))))))

(deftest runner-evaluates-selected-requirements-without-application-branches
  (let [source (fn [requirement]
                 {:artifact/schema "research-source.v1"
                  :research/id :alpha/study
                  :research/title "Alpha"
                  :research/question "Question"
                  :research/vary {:alpha/input [1N]}
                  :research/measures [{:measure/id :alpha/coverage
                                       :measure/kind :requirement-margin.v1
                                       :measure/domain {:kind :integer :unit :basis-points}
                                       :measure/observation {:observation/id :alpha/coverage-bps
                                                             :observation/source {:observation/id :waterfall/coverage-pct}
                                                             :observation/transform {:transform/kind :scale :multiply 100N}
                                                             :observation/domain {:kind :integer :unit :basis-points}}
                                       :measure/requirement {:requirement/kind :constant :value requirement :unit :basis-points}
                                       :measure/satisfying-direction :at-least}]
                  :research/hypotheses []})
        frozen-a (research/freeze-research (source 8000N))
        frozen-b (research/freeze-research (source 9500N))
        manifest-for (fn [frozen]
                       (manifest/build-manifest
                        {:benchmark/id :alpha/benchmark
                         :benchmark/research-binding {:research-definition/root (:research-definition/root frozen)
                                                      :measure-ids [:alpha/coverage]}}))
        raw {:waterfall/coverage-pct 90N}
        a (runner/evaluate-selected-research-requirements (manifest-for frozen-a) frozen-a raw)
        b (runner/evaluate-selected-research-requirements (manifest-for frozen-b) frozen-b raw)]
    (is (not= (:research-definition/root frozen-a) (:research-definition/root frozen-b)))
    (is (= 9000N (get-in a [0 :observation :value])))
    (is (= 1000N (get-in a [0 :requirement/margin])))
    (is (true? (get-in a [0 :requirement/satisfied?])))
    (is (= -500N (get-in b [0 :requirement/margin])))
    (is (false? (get-in b [0 :requirement/satisfied?])))))

(deftest manifest-policy-controls-which-evaluated-measures-gate
  (let [policy {:policy/kind :all-of
                :policy/inputs [{:input/kind :research-requirements
                                 :measure-ids [:alpha/gating]}]}
        decisions (outcome-policy/research-decisions
                   [{:measure/id :alpha/gating :requirement/satisfied? true}
                    {:measure/id :alpha/informational :requirement/satisfied? false}])]
    (is (= :passed (:benchmark-outcome/status (outcome-policy/evaluate policy decisions))))
    (is (= :indeterminate
           (:benchmark-outcome/status (outcome-policy/evaluate policy []))))))

(deftest runner-policy-makes-research-gating-application-owned
  (let [scenarios [{:execution/id "scenario-a" :pass? true}]
        research [{:measure/id :alpha/requirement :requirement/satisfied? false}]
        scenario-only {:benchmark/outcome-policy
                       {:policy/kind :all-of
                        :policy/inputs [{:input/kind :scenario-results :input/selection :all}]}}
        with-research {:benchmark/outcome-policy
                       {:policy/kind :all-of
                        :policy/inputs [{:input/kind :scenario-results :input/selection :all}
                                        {:input/kind :research-requirements
                                         :measure-ids [:alpha/requirement]}]}}]
    (is (= :passed
           (:benchmark-outcome/status
            (runner/evaluate-manifest-outcome scenario-only scenarios [] research))))
    (is (= :failed
           (:benchmark-outcome/status
            (runner/evaluate-manifest-outcome with-research scenarios [] research))))))

(deftest runner-research-path-preserves-at-most-semantics
  (let [frozen (research/freeze-research
                {:artifact/schema "research-source.v1"
                 :research/id :alpha/loss-study
                 :research/title "Loss"
                 :research/question "Question"
                 :research/vary {:alpha/input [1N]}
                 :research/measures [{:measure/id :alpha/max-loss
                                      :measure/kind :requirement-margin.v1
                                      :measure/domain {:kind :integer :unit :basis-points}
                                      :measure/observation {:observation/id :alpha/loss
                                                            :observation/domain {:kind :integer :unit :basis-points}}
                                      :measure/requirement {:requirement/kind :constant :value 500N :unit :basis-points}
                                      :measure/satisfying-direction :at-most}]
                 :research/hypotheses []})
        manifest (manifest/build-manifest
                  {:benchmark/id :alpha/loss
                   :benchmark/research-binding {:research-definition/root (:research-definition/root frozen)
                                                :measure-ids [:alpha/max-loss]}})]
    (is (= {:requirement/margin 200N
            :requirement/status :satisfied
            :requirement/satisfied? true}
           (select-keys (first (runner/evaluate-selected-research-requirements
                                manifest frozen {:alpha/loss 300N}))
                        [:requirement/margin :requirement/status :requirement/satisfied?])))))
