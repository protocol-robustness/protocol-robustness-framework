(ns resolver-sim.resubmission.acceptance-evaluation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.acceptance-authority-basis :as authority-basis]
            [resolver-sim.resubmission.acceptance-evaluation :as evaluation]
            [resolver-sim.resubmission.basis :as basis]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.resubmission.submission-registry :as submission-registry]
            [resolver-sim.resubmission.results-artifact :as results-artifact]))

(defn- root [value]
  (hash-ref/sha256-ref (hc/domain-hash :evidence-record value)))

(def ^:private verifier-registry-root
  "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
(def ^:private publisher-authority-root
  "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
(def ^:private extension-resolution-root
  "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")
(def ^:private submission-registry-root
  "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")

(def ^:private verifier-registry-body
  {:schema "verifier-registry.v1"
   :entries [{:verifier/id "verifier-1"}
             {:verifier/id "verifier-2"}]})

(def ^:private publisher-authority-body
  {:schema "publisher-authority.v1"
   :entries [{:publisher-authority/public-key "pub-key-1"}]})

(def ^:private submission-registry-body
  {:schema "submission-registry.v1"
   :entries []})

(defn- build-authority-basis []
  (authority-basis/build
   {:authority-basis/verifier-registry-root verifier-registry-root
    :authority-basis/publisher-authority-root publisher-authority-root
    :authority-basis/extension-resolution-root extension-resolution-root}))

(defn- fixture
  "V3 fixture: configuration carries both attempt-acceptance-definition/root
   and attempt-acceptance-authority-basis/root, with an authority basis
   containing three rooted registries."
  []
  (let [definition (evaluation/build-definition)
        authority-basis (build-authority-basis)
        authority-basis-root (:attempt-acceptance-authority-basis/root authority-basis)
        results-artifact (results-artifact/build
                          {:results/verifier-id "verifier-1"
                           :results/evaluation-root (root :evaluation)
                           :results/certificate-root (root {:certificate/id "cert-1"})
                           :results/execution-evidence-root (root {:execution/id "execution-1"})})
        submission-basis {:results-artifact results-artifact
                          :certificate {:certificate/id "cert-1"}
                          :execution-evidence {:execution/id "execution-1"}
                          :registry-entries []
                          :publisher-policy {:policy/id "publisher-1"
                                             :publisher-policy-key "pub-key-1"}}
        submission-basis-root (basis/submission-basis-root submission-basis)
        submission-registry
        (submission-registry/build
         {:submission-registry/entries
          [{:entry/role :results
            :artifact/kind :results-artifact
            :artifact/root (:attempt-results-artifact/root results-artifact)}
           {:entry/role :certificate
            :artifact/kind :allocation-certificate
            :artifact/root (root (:certificate submission-basis))}
           {:entry/role :execution-evidence
            :artifact/kind :execution-evidence
            :artifact/root (root (:execution-evidence submission-basis))}]})
        submission-registry-root (:attempt-submission-registry/root submission-registry)
        submitted-bundle {:submission-basis-root submission-basis-root
                          :resubmission-link-hash (root :link)
                          :publisher-envelope-hash (root :envelope)
                          :registry-root submission-registry-root}
        submitted-bundle-root (basis/final-bundle-root submitted-bundle)
        configuration {:configuration/schema genesis/resubmission-chain-configuration-v3-schema
                       :disposition-authority/public-key nil
                       :receipt-authority/public-key nil
                       :attempt-acceptance-definition/root
                       (:attempt-acceptance-definition/root definition)
                       :attempt-acceptance-authority-basis/root
                       authority-basis-root}
        bodies {(:attempt-acceptance-definition/root definition) definition
                authority-basis-root authority-basis
                verifier-registry-root verifier-registry-body
                publisher-authority-root publisher-authority-body
                extension-resolution-root {}
                submission-registry-root submission-registry
                submission-basis-root submission-basis
                submitted-bundle-root submitted-bundle}
        config-root (genesis/resubmission-chain-configuration-root configuration)]
    {:definition definition
     :authority-basis authority-basis
     :configuration configuration
     :submission-basis submission-basis
     :submission-basis-root submission-basis-root
     :submission-registry-root submission-registry-root
     :submitted-bundle submitted-bundle
     :submitted-bundle-root submitted-bundle-root
     :resolver {:resolve-artifact bodies
                :resolve-configuration (fn [root]
                                         (when (= root config-root)
                                           configuration))}
     :bodies bodies}))

(def ^:private v1-configuration
  {:configuration/schema genesis/resubmission-chain-configuration-schema
   :disposition-authority/public-key nil
   :receipt-authority/public-key nil})

(deftest configuration-v3-binds-definition-and-authority-basis
  (let [v1 {:configuration/schema genesis/resubmission-chain-configuration-schema
            :disposition-authority/public-key nil
            :receipt-authority/public-key nil}
        v1-root "sha256:199e84173c0013469e63e9195e60c3d04658933c2546f8ffa059039d0b36a1f7"
        {:keys [configuration definition authority-basis]} (fixture)
        changed (assoc configuration :attempt-acceptance-definition/root (root :other))
        v2-like (dissoc configuration :attempt-acceptance-authority-basis/root)]
    (is (= v1-root (genesis/resubmission-chain-configuration-root v1)))
    (is (nil? (genesis/authorized-attempt-acceptance-definition-root v1)))
    (is (nil? (genesis/authorized-attempt-acceptance-authority-basis-root v1)))
    (is (:valid? (genesis/validate-resubmission-chain-configuration configuration)))
    (is (not= (genesis/resubmission-chain-configuration-root configuration)
              (genesis/resubmission-chain-configuration-root changed)))
    (is (= (:attempt-acceptance-definition/root definition)
           (genesis/authorized-attempt-acceptance-definition-root configuration)))
    (is (some? (genesis/authorized-attempt-acceptance-authority-basis-root configuration)))
    (is (= (:attempt-acceptance-authority-basis/root authority-basis)
           (genesis/authorized-attempt-acceptance-authority-basis-root configuration)))
    (is (not (:valid? (genesis/validate-resubmission-chain-configuration
                       (assoc configuration :attempt-acceptance-definition/root "bad")))))
    (is (not (:valid? (genesis/validate-resubmission-chain-configuration
                       (assoc configuration :attempt-acceptance-authority-basis/root "bad")))))))

(deftest evaluation-is-derived-from-authorized-definition-and-basis
  (let [{:keys [resolver configuration submitted-bundle-root bodies]} (fixture)
        built (evaluation/build-evaluation resolver configuration submitted-bundle-root)]
    (testing "the production constructor accepts no caller conclusions"
      (is (= :accepted (:evaluation/outcome built)))
      (is (empty? (:evaluation/findings built)))
      (is (:valid? (evaluation/validate-acceptance-evaluation resolver built))))
    (testing "stored caller-nominated conclusions reject on re-evaluation"
      (is (= :evaluation-mismatch
             (:reason (evaluation/validate-acceptance-evaluation
                       resolver (assoc built :evaluation/outcome :rejected))))))
    (testing "a V1 configuration cannot acquire implicit acceptance authority"
      (let [v1 {:configuration/schema genesis/resubmission-chain-configuration-schema
                :disposition-authority/public-key nil
                :receipt-authority/public-key nil}
            v1-bodies {(:attempt-acceptance-definition/root (:definition (fixture)))
                       (:definition (fixture))}
            v1-resolver {:resolve-artifact bodies}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"invalid acceptance definition"
                              (evaluation/build-evaluation v1-resolver v1 submitted-bundle-root)))))
    (testing "a root-valid definition not named by configuration is rejected"
      (let [attacker-definition (evaluation/build-definition "attacker.v1")
            resolved (evaluation/resolve-evaluation-basis
                      resolver configuration submitted-bundle-root)]
        (is (evaluation/valid-definition? attacker-definition))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"not configuration-authorized"
                              (evaluation/evaluate
                               (assoc-in resolved [:attempt-acceptance-definition :root]
                                         (:attempt-acceptance-definition/root attacker-definition))
                               attacker-definition)))))
    (testing "bundle substitution cannot transplant an evaluation"
      (let [other-bundle-root (root :other-bundle)]
        (is (= :evaluation-mismatch
               (:reason (evaluation/validate-acceptance-evaluation
                         resolver (assoc-in built [:evaluation/basis :submitted-bundle/root]
                                            other-bundle-root)))))))))

(deftest definition-and-check-results-fail-closed
  (let [{:keys [definition resolver configuration submitted-bundle-root]} (fixture)]
    (testing "unknown semantic check ids are not dispatchable"
      (let [bad (assoc-in definition [:definition/checks 0 :check/id] :attacker/check)]
        (is (not (evaluation/valid-definition? bad)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid acceptance definition"
                              (evaluation/evaluate
                               (evaluation/resolve-evaluation-basis resolver configuration submitted-bundle-root)
                               bad)))))
    (testing "reordered or duplicate check definitions are invalid"
      (is (not (evaluation/valid-definition?
                (update definition :definition/checks #(vec (reverse %))))))
      (is (not (evaluation/valid-definition?
                (update definition :definition/checks conj (first (:definition/checks definition)))))))))

(deftest v3-authority-buffers-resolve-registries
  (let [{:keys [resolver configuration submission-registry-root]} (fixture)
        resolved (evaluation/resolve-evaluation-basis resolver configuration (:submitted-bundle-root (fixture)))]
    (testing "authority basis resolves authority leaves while the bundle resolves submitted content"
      (let [registries (:attempt-acceptance-authority-registries resolved)]
        (is (some? registries))
        (is (authority-basis/valid? (get-in resolved [:attempt-acceptance-authority-basis :body])))
        (is (= verifier-registry-root
               (get-in registries [:verifier-registry :root])))
        (is (= publisher-authority-root
               (get-in registries [:publisher-authority :root])))
        (is (map? (get-in registries [:verifier-registry :body])))
        (is (map? (get-in registries [:publisher-authority :body])))
        (is (= submission-registry-root
               (get-in resolved [:submission-registry :root])))
        (is (map? (get-in resolved [:submission-registry :body])))))))

(deftest v3-rejects-tampered-results-binding
  (let [{:keys [resolver configuration submitted-bundle-root]} (fixture)
        bodies (:resolve-artifact resolver)
        basis-root (get-in (fixture) [:submission-basis-root])
        original (get bodies basis-root)
        tampered (assoc-in original [:results-artifact :results/certificate-root]
                           (root :attacker-certificate))
        tampered-resolver (assoc resolver :resolve-artifact
                                 (assoc bodies basis-root tampered))
        built (evaluation/build-evaluation tampered-resolver configuration submitted-bundle-root)]
    (is (= :rejected (:evaluation/outcome built)))
    (is (some #(and (= :prf.resubmission.acceptance/submission-components-present-v1
                       (:check/id %))
                    (not= :verified (:check/status %)))
              (:evaluation/check-results built)))))

(deftest v3-rejects-tampered-execution-binding
  (let [{:keys [resolver configuration submitted-bundle-root]} (fixture)
        bodies (:resolve-artifact resolver)
        basis-root (:submission-basis-root (fixture))
        original (get bodies basis-root)
        tampered (assoc-in original [:results-artifact :results/execution-evidence-root]
                           (root :attacker-execution))
        tampered-resolver (assoc resolver :resolve-artifact
                                 (assoc bodies basis-root tampered))
        built (evaluation/build-evaluation tampered-resolver configuration submitted-bundle-root)]
    (is (= :rejected (:evaluation/outcome built)))
    (is (some #(and (= :prf.resubmission.acceptance/submission-components-present-v1
                       (:check/id %))
                    (not= :verified (:check/status %)))
              (:evaluation/check-results built)))))

(deftest v3-rejects-missing-historical-extension-resolution
  (let [{:keys [resolver configuration submitted-bundle-root]} (fixture)
        bodies (:resolve-artifact resolver)
        missing-extension (assoc resolver :resolve-artifact
                                 (dissoc bodies extension-resolution-root))
        built (evaluation/build-evaluation missing-extension configuration submitted-bundle-root)
        authority-result (some #(when (= :prf.resubmission.acceptance/authority-registries-verifiable-v1
                                         (:check/id %)) %)
                               (:evaluation/check-results built))]
    (is (= :rejected (:evaluation/outcome built)))
    (is (= :malformed (:check/status authority-result)))
    (is (= :unavailable (:extension-resolution-status authority-result))))

  (deftest v3-evaluation-accepts-when-all-authority-checks-pass
    (let [{:keys [resolver configuration submitted-bundle-root]} (fixture)
          built (evaluation/build-evaluation resolver configuration submitted-bundle-root)]
      (testing "V3 evaluation outcome is :accepted with no findings"
        (is (= :accepted (:evaluation/outcome built)))
        (is (empty? (:evaluation/findings built))))
      (testing "V3 evaluation basis does not duplicate the content-side registry root"
        (is (nil? (get-in built [:evaluation/basis :submission-registry/root]))))
      (testing "V3 evaluation runs the three additional V3 checks"
        (let [check-ids (set (map :check/id (:evaluation/check-results built)))]
          (is (contains? check-ids :prf.resubmission.acceptance/authority-registries-verifiable-v1))
          (is (contains? check-ids :prf.resubmission.acceptance/verifier-registry-selection-v1))
          (is (contains? check-ids :prf.resubmission.acceptance/results-binding-v1))))))

  (deftest v3-rejects-unauthorized-verifier
    (let [{:keys [resolver configuration submitted-bundle-root]} (fixture)
          f (fixture)
          bad-submission-basis (assoc-in f [:submission-basis :results-artifact]
                                         {:artifact/id "results-1"
                                          :results/verifier-id "verifier-999"})
          bad-bodies (assoc f :submission-basis bad-submission-basis
                            (:submission-basis-root f) bad-submission-basis)
          bad-resolver (assoc-in f [:resolver :resolve-artifact]
                                 (assoc (:bodies f)
                                        (:submission-basis-root f) bad-submission-basis))
          resolved (evaluation/resolve-evaluation-basis
                    (:resolver bad-resolver) configuration submitted-bundle-root)]
      (testing "results-artifact with unauthorized verifier-id is rejected"
        (let [check-result (evaluation/check-dispatch
                            :prf.resubmission.acceptance/verifier-registry-selection-v1)]
          (is (fn? check-result))))
      (testing "full evaluation with unauthorized verifier produces a finding"
        (let [definition (:definition f)
              bad-eval (evaluation/evaluate resolved definition)
              findings (:evaluation/findings bad-eval)]
          (is (= :rejected (:evaluation/outcome bad-eval)))
          (is (some #(= :prf.resubmission.acceptance/verifier-registry-selection-v1
                        (:check/id %))
                    findings))))))

  (deftest v3-rejects-unauthorized-publisher
    (let [{:keys [resolver configuration definition authority-basis]} (fixture)
          f (fixture)
          bad-submission-basis (assoc-in f [:submission-basis :publisher-policy]
                                         {:policy/id "publisher-1"
                                          :publisher-policy-key "unauthorized-key"})
          bad-bodies (assoc (:bodies f)
                            (:submission-basis-root f) bad-submission-basis)
          bad-resolver {:resolve-artifact bad-bodies
                        :resolve-configuration (:resolve-configuration resolver)}
          resolved (evaluation/resolve-evaluation-basis
                    bad-resolver configuration (:submitted-bundle-root f))]
      (testing "publisher-policy with unauthorized key is rejected"
        (let [bad-eval (evaluation/evaluate resolved definition)
              findings (:evaluation/findings bad-eval)]
          (is (= :rejected (:evaluation/outcome bad-eval)))
          (is (some #(= :prf.resubmission.acceptance/results-binding-v1
                        (:check/id %))
                    findings))))))

  (deftest v3-results-binding-verifies-authority-root
    (testing "the authority basis root in configuration matches the built authority basis root"
      (let [{:keys [authority-basis configuration]} (fixture)]
        (is (= (:attempt-acceptance-authority-basis/root authority-basis)
               (:attempt-acceptance-authority-basis/root configuration)))))))

(deftest historical-validity-survives-authority-rotation-current-admission-does-not
  (let [{:keys [resolver configuration submitted-bundle-root bodies]} (fixture)
        evaluation-a (evaluation/build-evaluation resolver configuration submitted-bundle-root)
        extension-root-b (root :extension-resolution-b)
        authority-basis-b (authority-basis/build
                           {:authority-basis/verifier-registry-root verifier-registry-root
                            :authority-basis/publisher-authority-root publisher-authority-root
                            :authority-basis/extension-resolution-root extension-root-b})
        configuration-b (assoc configuration
                               :attempt-acceptance-authority-basis/root
                               (:attempt-acceptance-authority-basis/root authority-basis-b))
        config-root-b (genesis/resubmission-chain-configuration-root configuration-b)
        resolver-bodies (assoc bodies
                               extension-root-b {}
                               (:attempt-acceptance-authority-basis/root authority-basis-b)
                               authority-basis-b)
        resolver-b (assoc resolver
                          :resolve-artifact resolver-bodies
                          :resolve-configuration (fn [configuration-root]
                                                   (when (= configuration-root config-root-b)
                                                     configuration-b)))
        evaluation-b (evaluation/build-evaluation resolver-b configuration-b submitted-bundle-root)
        admission-a {:authority/configuration-root
                     (get-in evaluation-a [:evaluation/basis :configuration/root])}
        admission-b {:authority/configuration-root config-root-b}]
    (testing "before rotation, A is historically valid and current for admission"
      (is (:valid? (evaluation/validate-acceptance-evaluation resolver evaluation-a)))
      (is (:valid? (evaluation/validate-evaluation-current-for-admission admission-a evaluation-a))))
    (testing "after rotation, A remains historically valid but is not current"
      (is (:valid? (evaluation/validate-acceptance-evaluation resolver evaluation-a)))
      (is (false? (:valid? (evaluation/validate-evaluation-current-for-admission
                            admission-b evaluation-a)))))
    (testing "the successor evaluation is current for admission"
      (is (:valid? (evaluation/validate-acceptance-evaluation resolver-b evaluation-b)))
      (is (:valid? (evaluation/validate-evaluation-current-for-admission
                    admission-b evaluation-b))))))
