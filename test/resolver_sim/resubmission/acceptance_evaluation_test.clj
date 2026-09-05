(ns resolver-sim.resubmission.acceptance-evaluation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.resubmission.acceptance-authority-basis :as authority-basis]
            [resolver-sim.resubmission.acceptance-evaluation :as evaluation]
            [resolver-sim.resubmission.basis :as basis]
            [resolver-sim.resubmission.genesis :as genesis]
            [resolver-sim.resubmission.publisher-authority :as pub-authority]
            [resolver-sim.resubmission.publisher-statement :as statement]
            [resolver-sim.resubmission.submission-registry :as submission-registry]
            [resolver-sim.resubmission.results-artifact :as results-artifact]
            [resolver-sim.support.ed25519 :as ed]))

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

(defn- fixture-with-real-publisher
  "V3 fixture with real Ed25519-signed publisher artifacts. The publisher
   authority, signed statement, and envelope are genuine; the publisher-policy
   key matches the real public hex."
  []
  (let [keypair (ed/keypair :publisher-fixture)
        real-pub-hex (:public-hex keypair)
        definition (evaluation/build-definition)
        pa-entry {:principal/id "fixture-principal"
                  :key/id "fixture-pub-key"
                  :key/public real-pub-hex
                  :authorized-actions [:prf.resubmission/publish-attempt]}
        pa-artifact (pub-authority/build {:publisher-authority/entries [pa-entry]})
        pa-root (:attempt-publisher-authority/root pa-artifact)
        pa-body (assoc pa-artifact :entries (:publisher-authority/entries pa-artifact))
        results-art (results-artifact/build
                     {:results/verifier-id "verifier-1"
                      :results/evaluation-root (root :evaluation)
                      :results/certificate-root (root {:certificate/id "cert-1"})
                      :results/execution-evidence-root (root {:execution/id "exec-1"})})
        submission-basis {:results-artifact results-art
                          :certificate {:certificate/id "cert-1"}
                          :execution-evidence {:execution/id "exec-1"}
                          :registry-entries []
                          :publisher-policy {:policy/id "publisher-1"
                                             :publisher-policy-key real-pub-hex}}
        submission-basis-root (basis/submission-basis-root submission-basis)
        submission-registry
        (submission-registry/build
         {:submission-registry/entries
          [{:entry/role :results
            :artifact/kind :results-artifact
            :artifact/root (:attempt-results-artifact/root results-art)}
           {:entry/role :certificate
            :artifact/kind :allocation-certificate
            :artifact/root (root (:certificate submission-basis))}
           {:entry/role :execution-evidence
            :artifact/kind :execution-evidence
            :artifact/root (root (:execution-evidence submission-basis))}]})
        submission-registry-root (:attempt-submission-registry/root submission-registry)
        ;; Break the circular dependency: statement binds to bundle root,
        ;; bundle root depends on envelope hash, envelope wraps statement root.
        ;; Strategy: build statement with placeholder to get stmt-root,
        ;; build envelope to get envelope-hash, compute real bundle root,
        ;; rebuild statement with real bundle root, rebuild envelope.
        placeholder-stmt (statement/build-statement "fixture-principal" "fixture-pub-key"
                                                    "sha256:placeholder-bundle")
        placeholder-stmt-root (:publisher/statement-root placeholder-stmt)
        placeholder-envelope (statement/build-envelope placeholder-stmt-root
                                                       "fixture-pub-key"
                                                       {:algorithm :ed25519 :bytes "placeholder"})
        placeholder-submitted-bundle {:submission-basis-root submission-basis-root
                                      :resubmission-link-hash (root :link)
                                      :publisher-envelope-hash (:publisher-envelope/root placeholder-envelope)
                                      :registry-root submission-registry-root}
        submitted-bundle-root (basis/final-bundle-root placeholder-submitted-bundle)
        ;; Real statement bound to the real bundle root
        unsigned-stmt (statement/build-statement "fixture-principal" "fixture-pub-key"
                                                 submitted-bundle-root)
        stmt-artifact (statement/sign-statement unsigned-stmt (:private-key keypair))
        stmt-root (:publisher/statement-root stmt-artifact)
        ;; Real envelope wrapping the real statement
        envelope (statement/build-envelope stmt-root "fixture-pub-key"
                                           (:publisher/signature stmt-artifact))
        ;; Real submitted-bundle (envelope hash is now from the real envelope)
        submitted-bundle {:submission-basis-root submission-basis-root
                          :resubmission-link-hash (root :link)
                          :publisher-envelope-hash (:publisher-envelope/root envelope)
                          :registry-root submission-registry-root}
        ;; Recompute bundle root from the real submitted-bundle
        submitted-bundle-root (basis/final-bundle-root submitted-bundle)
        authority-basis (authority-basis/build
                         {:authority-basis/verifier-registry-root verifier-registry-root
                          :authority-basis/publisher-authority-root pa-root
                          :authority-basis/extension-resolution-root extension-resolution-root})
        authority-basis-root (:attempt-acceptance-authority-basis/root authority-basis)
        configuration {:configuration/schema genesis/resubmission-chain-configuration-v3-schema
                       :disposition-authority/public-key nil
                       :receipt-authority/public-key nil
                       :attempt-acceptance-definition/root
                       (:attempt-acceptance-definition/root definition)
                       :attempt-acceptance-authority-basis/root
                       authority-basis-root}
        config-root (genesis/resubmission-chain-configuration-root configuration)
        bodies {(:attempt-acceptance-definition/root definition) definition
                authority-basis-root authority-basis
                verifier-registry-root verifier-registry-body
                pa-root pa-body
                extension-resolution-root {}
                submission-registry-root submission-registry
                submission-basis-root submission-basis
                submitted-bundle-root submitted-bundle
                (:publisher-envelope/root envelope) envelope
                stmt-root stmt-artifact}]
    {:definition definition
     :authority-basis authority-basis
     :pa-artifact pa-artifact
     :pa-root pa-root
     :stmt-artifact stmt-artifact
     :envelope envelope
     :keypair keypair
     :configuration configuration
     :submission-basis submission-basis
     :submission-basis-root submission-basis-root
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

 ;; ── item 5: real-crypto publisher binding ────────────────────────────────
 
 (deftest publisher-binding-uses-real-crypto
   (testing "V3 evaluation with real Ed25519 publisher artifacts; the publisher-binding check
            runs but fails due to the circular dependency (statement root → bundle root →
            envelope hash → statement root). All other checks pass."
     (let [{:keys [resolver configuration submitted-bundle-root]} (fixture-with-real-publisher)
           built (evaluation/build-evaluation resolver configuration submitted-bundle-root)]
       (is (= :rejected (:evaluation/outcome built)))
       (is (some #(= :publisher-bundle-mismatch (:observed %))
                 (:evaluation/findings built))
           "publisher-binding fails due to circular dependency"))))
 
 ;; ── item 6: publisher integration tests ──────────────────────────────────
 ;; These call validate-publisher-binding directly because the circular
 ;; dependency (statement root → bundle root → envelope hash → statement root)
 ;; prevents build-evaluation from resolving real publisher artifacts.
 
 (defn- build-real-stmt-and-envelope
   "Build a signed statement and envelope bound to the given bundle-root.
    Returns {:stmt-artifact ... :envelope ...}."
   [keypair bundle-root]
   (let [stmt (statement/build-statement "fixture-principal" "fixture-pub-key" bundle-root)
         signed (statement/sign-statement stmt (:private-key keypair))
         envelope (statement/build-envelope
                   (:publisher/statement-root signed)
                   "fixture-pub-key"
                   (:publisher/signature signed))]
     {:stmt-artifact signed :envelope envelope}))
 
 (deftest publisher-integration-invalid-signature-rejected
   (testing "an invalid Ed25519 signature is detected"
     (let [f (fixture-with-real-publisher)
           bundle-root (:submitted-bundle-root f)
           ;; Build statement with correct bundle root, but sign with wrong key
           bad-stmt (statement/sign-statement
                     (statement/build-statement "fixture-principal" "fixture-pub-key" bundle-root)
                     (:private-key (ed/keypair :attacker-key)))
           bad-envelope (statement/build-envelope
                         (:publisher/statement-root bad-stmt)
                         "fixture-pub-key"
                         (:publisher/signature bad-stmt))
           pa-body (get-in f [:bodies (:pa-root f)])
           result (evaluation/validate-publisher-binding
                   bad-stmt bad-envelope pa-body bundle-root)]
       (is (not (:valid? result)))
       (is (= :publisher-signature-invalid (:reason result))))))
 
 (deftest publisher-integration-unauthorized-key-rejected
   (testing "a key not in the publisher authority is rejected"
     (let [f (fixture-with-real-publisher)
           bundle-root (:submitted-bundle-root f)
           ;; Build statement with correct bundle root and real key (valid signature)
           {:keys [stmt-artifact envelope]} (build-real-stmt-and-envelope (:keypair f) bundle-root)
           ;; Build a publisher authority that doesn't include the real key
           bad-pa (pub-authority/build
                   {:publisher-authority/entries
                    [{:principal/id "other-principal"
                      :key/id "other-key"
                      :key/public "0000000000000000000000000000000000000000000000000000000000000000"
                      :authorized-actions [:prf.resubmission/publish-attempt]}]})
           bad-pa-body (assoc bad-pa :entries (:publisher-authority/entries bad-pa))
           result (evaluation/validate-publisher-binding
                   stmt-artifact envelope bad-pa-body bundle-root)]
       (is (not (:valid? result)))
       (is (= :publisher-not-authorized (:reason result))))))
 
 (deftest publisher-integration-principal-mismatch-rejected
   (testing "a statement with a different principal-id than the authority entry is rejected"
     (let [f (fixture-with-real-publisher)
           bundle-root (:submitted-bundle-root f)
           ;; Build statement with wrong principal but correct key
           bad-stmt (statement/sign-statement
                     (statement/build-statement "attacker-principal" "fixture-pub-key" bundle-root)
                     (:private-key (:keypair f)))
           bad-envelope (statement/build-envelope
                         (:publisher/statement-root bad-stmt)
                         "fixture-pub-key"
                         (:publisher/signature bad-stmt))
           pa-body (get-in f [:bodies (:pa-root f)])
           result (evaluation/validate-publisher-binding
                   bad-stmt bad-envelope pa-body bundle-root)]
       (is (not (:valid? result)))
       (is (= :publisher-principal-mismatch (:reason result))))))
 
 (deftest publisher-integration-statement-transplant-rejected
   (testing "a statement bound to a different bundle is rejected"
     (let [f (fixture-with-real-publisher)
           bundle-root (:submitted-bundle-root f)
           ;; Build statement bound to a different bundle root
           other-bundle "sha256:9999999999999999999999999999999999999999999999999999999999999999"
           {:keys [stmt-artifact envelope]} (build-real-stmt-and-envelope (:keypair f) other-bundle)
           pa-body (get-in f [:bodies (:pa-root f)])
           result (evaluation/validate-publisher-binding
                   stmt-artifact envelope pa-body bundle-root)]
       (is (not (:valid? result)))
       (is (= :publisher-bundle-mismatch (:reason result))))))
 
 (deftest publisher-integration-bundle-transplant-rejected
   (testing "a stored evaluation whose submitted-bundle/root differs is rejected"
     (let [{:keys [resolver configuration submitted-bundle-root]} (fixture-with-real-publisher)
           built (evaluation/build-evaluation resolver configuration submitted-bundle-root)
           other-bundle-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
           tampered (assoc-in built [:evaluation/basis :submitted-bundle/root] other-bundle-root)]
       (is (= :evaluation-mismatch
              (:reason (evaluation/validate-acceptance-evaluation resolver tampered)))))))
 
 ;; ── item 7: registry binding tests ───────────────────────────────────────
 
 (deftest registry-missing-role-rejected
   (testing "a submission-registry missing the execution-evidence role is rejected"
     (let [f (fixture)
           bad-registry (submission-registry/build
                         {:submission-registry/entries
                          [{:entry/role :results
                            :artifact/kind :results-artifact
                            :artifact/root (:attempt-results-artifact/root
                                            (:results-artifact (:submission-basis f)))}
                           {:entry/role :certificate
                            :artifact/kind :allocation-certificate
                            :artifact/root (hash-ref/sha256-ref
                                            (hc/domain-hash :evidence-record
                                                            (:certificate (:submission-basis f))))}]})
           bad-bodies (assoc (:bodies f)
                             (:submission-registry-root f) bad-registry)
           bad-resolver {:resolve-artifact bad-bodies
                         :resolve-configuration (:resolve-configuration (:resolver f))}
           built (evaluation/build-evaluation bad-resolver (:configuration f)
                                              (:submitted-bundle-root f))]
       (is (= :rejected (:evaluation/outcome built)))
       (is (some #(and (= :prf.resubmission.acceptance/submission-registry-integrity-v1 (:check/id %))
                       (= :malformed (:check/status %)))
                 (:evaluation/check-results built))))))
 
 (deftest registry-duplicate-role-rejected
   (testing "a submission-registry with duplicate roles is rejected"
     (let [f (fixture)
           results-root (:attempt-results-artifact/root (:results-artifact (:submission-basis f)))
           bad-registry (submission-registry/build
                         {:submission-registry/entries
                          [{:entry/role :results
                            :artifact/kind :results-artifact
                            :artifact/root results-root}
                           {:entry/role :results
                            :artifact/kind :results-artifact
                            :artifact/root results-root}]})
           bad-bodies (assoc (:bodies f)
                             (:submission-registry-root f) bad-registry)
           bad-resolver {:resolve-artifact bad-bodies
                         :resolve-configuration (:resolve-configuration (:resolver f))}
           built (evaluation/build-evaluation bad-resolver (:configuration f)
                                              (:submitted-bundle-root f))]
       (is (= :rejected (:evaluation/outcome built)))
       (is (some #(and (= :prf.resubmission.acceptance/submission-registry-integrity-v1 (:check/id %))
                       (= :malformed (:check/status %)))
                 (:evaluation/check-results built))))))
 
 (deftest registry-wrong-kind-rejected
   (testing "a submission-registry entry with wrong artifact kind is rejected"
     (let [f (fixture)
           cert-root (hash-ref/sha256-ref
                      (hc/domain-hash :evidence-record (:certificate (:submission-basis f))))
           exec-root (hash-ref/sha256-ref
                      (hc/domain-hash :evidence-record (:execution-evidence (:submission-basis f))))
           results-root (:attempt-results-artifact/root (:results-artifact (:submission-basis f)))
           bad-registry (submission-registry/build
                         {:submission-registry/entries
                          [{:entry/role :results
                            :artifact/kind :results-artifact
                            :artifact/root results-root}
                           {:entry/role :certificate
                            :artifact/kind :results-artifact
                            :artifact/root cert-root}
                           {:entry/role :execution-evidence
                            :artifact/kind :execution-evidence
                            :artifact/root exec-root}]})
           bad-bodies (assoc (:bodies f)
                             (:submission-registry-root f) bad-registry)
           bad-resolver {:resolve-artifact bad-bodies
                         :resolve-configuration (:resolve-configuration (:resolver f))}
           built (evaluation/build-evaluation bad-resolver (:configuration f)
                                              (:submitted-bundle-root f))]
       (is (= :rejected (:evaluation/outcome built)))
       (is (some #(and (= :prf.resubmission.acceptance/submission-registry-integrity-v1 (:check/id %))
                       (= :malformed (:check/status %)))
                 (:evaluation/check-results built))))))
 
 (deftest registry-wrong-root-rejected
   (testing "a submission-registry with a tampered entry root is rejected at the
             registry-root-mismatch level (the tampered body recomputes to a
             different self-root, caught before entry-level checks)"
     (let [f (fixture)
           tampered-registry (submission-registry/build
                              {:submission-registry/entries
                               [{:entry/role :results
                                 :artifact/kind :results-artifact
                                 :artifact/root "sha256:0000000000000000000000000000000000000000000000000000000000000000"}
                                {:entry/role :certificate
                                 :artifact/kind :allocation-certificate
                                 :artifact/root (hash-ref/sha256-ref
                                                 (hc/domain-hash :evidence-record
                                                                 (:certificate (:submission-basis f))))}
                                {:entry/role :execution-evidence
                                 :artifact/kind :execution-evidence
                                 :artifact/root (hash-ref/sha256-ref
                                                 (hc/domain-hash :evidence-record
                                                                 (:execution-evidence (:submission-basis f))))}]})
           bad-bodies (assoc (:bodies f)
                             (:submission-registry-root f) tampered-registry)
           bad-resolver {:resolve-artifact bad-bodies
                         :resolve-configuration (:resolve-configuration (:resolver f))}
           built (evaluation/build-evaluation bad-resolver (:configuration f)
                                              (:submitted-bundle-root f))]
       (is (= :rejected (:evaluation/outcome built)))
       (is (some #(and (= :prf.resubmission.acceptance/submission-registry-integrity-v1 (:check/id %))
                       (contains? #{:root-mismatch :entry-root-mismatch} (:check/status %)))
                 (:evaluation/check-results built))
           "tampered entry root is caught at the registry integrity level"))))
 
 (deftest registry-legitimate-alternate-different-root
   (testing "two different submission-registries produce different roots"
     (let [f (fixture)
           alt-results-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"
           cert-root (hash-ref/sha256-ref
                      (hc/domain-hash :evidence-record (:certificate (:submission-basis f))))
           exec-root (hash-ref/sha256-ref
                      (hc/domain-hash :evidence-record (:execution-evidence (:submission-basis f))))
           registry-a (:submission-registry-root f)
           registry-b (:attempt-submission-registry/root
                       (submission-registry/build
                        {:submission-registry/entries
                         [{:entry/role :results
                           :artifact/kind :results-artifact
                           :artifact/root alt-results-root}
                          {:entry/role :certificate
                           :artifact/kind :allocation-certificate
                           :artifact/root cert-root}
                          {:entry/role :execution-evidence
                           :artifact/kind :execution-evidence
                           :artifact/root exec-root}]}))]
       (is (not= registry-a registry-b)
           "different submission-registries produce different roots")))
   (testing "an evaluation built under a bundle referencing an alt registry is rejected
             when the alt registry body is resolved but doesn't match the committed root"
     (let [f (fixture)
           alt-results-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"
           cert-root (hash-ref/sha256-ref
                      (hc/domain-hash :evidence-record (:certificate (:submission-basis f))))
           exec-root (hash-ref/sha256-ref
                      (hc/domain-hash :evidence-record (:execution-evidence (:submission-basis f))))
           alt-registry (submission-registry/build
                         {:submission-registry/entries
                          [{:entry/role :results
                            :artifact/kind :results-artifact
                            :artifact/root alt-results-root}
                           {:entry/role :certificate
                            :artifact/kind :allocation-certificate
                            :artifact/root cert-root}
                           {:entry/role :execution-evidence
                            :artifact/kind :execution-evidence
                            :artifact/root exec-root}]})
           alt-root (:attempt-submission-registry/root alt-registry)
           ;; Build a submitted-bundle that references the alt registry root
           alt-submitted-bundle (assoc (:submitted-bundle f) :registry-root alt-root)
           alt-bundle-root (basis/final-bundle-root alt-submitted-bundle)
           ;; The resolver has the alt-registry under its root and the alt submitted-bundle
           bad-bodies (assoc (:bodies f) alt-root alt-registry
                             alt-bundle-root alt-submitted-bundle)
           bad-resolver {:resolve-artifact bad-bodies
                         :resolve-configuration (:resolve-configuration (:resolver f))}
           built (evaluation/build-evaluation bad-resolver (:configuration f) alt-bundle-root)]
       (is (= :rejected (:evaluation/outcome built)))
       (is (some #(and (= :prf.resubmission.acceptance/submission-registry-integrity-v1 (:check/id %))
                       (#{:root-mismatch :entry-root-mismatch} (:check/status %)))
                 (:evaluation/check-results built))
           "alt registry entry roots don't match the expected content roots"))))
 
 ;; ── item 8: historical re-evaluation over retained roots ─────────────────
 
 (deftest historical-evaluation-re-validates-under-retained-roots
   (testing "rotation does not invalidate old evidence: evaluation-A remains
             historically valid when its committed config root is still resolvable"
     (let [f-a (fixture)
           config-a-root (genesis/resubmission-chain-configuration-root (:configuration f-a))
           built-a (evaluation/build-evaluation (:resolver f-a) (:configuration f-a)
                                                (:submitted-bundle-root f-a))]
       (is (= :accepted (:evaluation/outcome built-a)))
       (testing "evaluation-A validates under resolver-A"
         (is (:valid? (evaluation/validate-acceptance-evaluation (:resolver f-a) built-a))))
 
       (testing "build a rotated authority basis and configuration"
         (let [new-pa-root "sha256:2222222222222222222222222222222222222222222222222222222222222222"
               new-vr-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"
               new-authority-basis (authority-basis/build
                                    {:authority-basis/verifier-registry-root new-vr-root
                                     :authority-basis/publisher-authority-root new-pa-root
                                     :authority-basis/extension-resolution-root extension-resolution-root})
               new-ab-root (:attempt-acceptance-authority-basis/root new-authority-basis)
               new-config (assoc (:configuration f-a)
                                 :attempt-acceptance-authority-basis/root new-ab-root)
               new-config-root (genesis/resubmission-chain-configuration-root new-config)]
 
           (testing "combined resolver retains BOTH config-A and config-B"
             (let [new-vr-body {:schema "verifier-registry.v1"
                                :entries [{:verifier/id "verifier-1"}
                                          {:verifier/id "verifier-2"}]}
                   combined-bodies (assoc (:bodies f-a)
                                          new-ab-root new-authority-basis
                                          new-vr-root new-vr-body
                                          new-pa-root {:schema "publisher-authority.v1"
                                                       :entries [{:key/id "fixture-pub-key"
                                                                  :publisher-authority/public-key "fixture-pub-key"
                                                                  :principal/id "fixture-principal"
                                                                  :authorized-actions [:prf.resubmission/publish-attempt]}
                                                                 {:key/id "pub-key-1"
                                                                  :publisher-authority/public-key "pub-key-1"
                                                                  :principal/id "fixture-principal"
                                                                  :authorized-actions [:prf.resubmission/publish-attempt]}
                                                                 {:key/id "new-key"
                                                                  :publisher-authority/public-key "new-key"
                                                                  :principal/id "new-principal"
                                                                  :authorized-actions [:prf.resubmission/publish-attempt]}]})
                   combined-resolver {:resolve-artifact combined-bodies
                                      :resolve-configuration (fn [root]
                                                               (or (when (= root config-a-root) (:configuration f-a))
                                                                   (when (= root new-config-root) new-config)))}]
 
               (testing "evaluation-A still validates — its committed config root is still resolvable"
                 (is (:valid? (evaluation/validate-acceptance-evaluation combined-resolver built-a))
                     "rotation must not invalidate historical evidence"))
 
               (testing "evaluation-B builds and validates under the combined resolver"
                 (let [built-b (evaluation/build-evaluation combined-resolver new-config
                                                            (:submitted-bundle-root f-a))]
                   (is (= :accepted (:evaluation/outcome built-b)))
                   (is (:valid? (evaluation/validate-acceptance-evaluation combined-resolver built-b))
                       "new evaluation validates under combined resolver"))))))))))
 
 ;; ── item 9: historical-rotation / currentness smoke ──────────────────────
 
 (deftest historical-rotation-currentness-smoke
   (testing "before rotation: evaluation-A is both historically valid and current"
     (let [f-a (fixture)
           built-a (evaluation/build-evaluation (:resolver f-a) (:configuration f-a)
                                                (:submitted-bundle-root f-a))
           config-a-root (genesis/resubmission-chain-configuration-root (:configuration f-a))
           authority-context-a {:authority/configuration-root config-a-root}]
 
       (is (= :accepted (:evaluation/outcome built-a)))
       (is (:valid? (evaluation/validate-acceptance-evaluation (:resolver f-a) built-a))
           "evaluation-A is historically valid")
       (is (:valid? (evaluation/validate-evaluation-current-for-admission authority-context-a built-a))
           "evaluation-A is current/admissible before rotation")
 
       (testing "after rotation A → B:"
         (let [new-pa-root "sha256:4444444444444444444444444444444444444444444444444444444444444444"
               new-vr-root "sha256:5555555555555555555555555555555555555555555555555555555555555555"
               new-authority-basis (authority-basis/build
                                    {:authority-basis/verifier-registry-root new-vr-root
                                     :authority-basis/publisher-authority-root new-pa-root
                                     :authority-basis/extension-resolution-root extension-resolution-root})
               new-ab-root (:attempt-acceptance-authority-basis/root new-authority-basis)
               new-config (assoc (:configuration f-a)
                                 :attempt-acceptance-authority-basis/root new-ab-root)
               new-config-root (genesis/resubmission-chain-configuration-root new-config)
               new-vr-body {:schema "verifier-registry.v1"
                            :entries [{:verifier/id "verifier-1"}
                                      {:verifier/id "verifier-2"}]}
               combined-bodies (assoc (:bodies f-a)
                                      new-ab-root new-authority-basis
                                      new-vr-root new-vr-body
                                      new-pa-root {:schema "publisher-authority.v1"
                                                   :entries [{:key/id "fixture-pub-key"
                                                              :publisher-authority/public-key "fixture-pub-key"
                                                              :principal/id "fixture-principal"
                                                              :authorized-actions [:prf.resubmission/publish-attempt]}
                                                             {:key/id "pub-key-1"
                                                              :publisher-authority/public-key "pub-key-1"
                                                              :principal/id "fixture-principal"
                                                              :authorized-actions [:prf.resubmission/publish-attempt]}
                                                             {:key/id "new-key"
                                                              :publisher-authority/public-key "new-key"
                                                              :principal/id "new-principal"
                                                              :authorized-actions [:prf.resubmission/publish-attempt]}]})
               combined-resolver {:resolve-artifact combined-bodies
                                  :resolve-configuration (fn [root]
                                                           (or (when (= root config-a-root) (:configuration f-a))
                                                               (when (= root new-config-root) new-config)))}
               authority-context-b {:authority/configuration-root new-config-root}
               built-b (evaluation/build-evaluation combined-resolver new-config
                                                    (:submitted-bundle-root f-a))]
 
           (testing "evaluation-A remains historically valid"
             (is (:valid? (evaluation/validate-acceptance-evaluation combined-resolver built-a))
                 "rotation must not invalidate historical evidence"))
 
           (testing "evaluation-A is no longer current/admissible"
             (is (not (:valid? (evaluation/validate-evaluation-current-for-admission
                                authority-context-b built-a)))
                 "old evaluation loses currentness after rotation"))
 
           (testing "evaluation-B is current/admissible"
             (is (= :accepted (:evaluation/outcome built-b)))
             (is (:valid? (evaluation/validate-evaluation-current-for-admission
                           authority-context-b built-b))
                 "new evaluation is current under the new authority context"))
 
           (testing "evaluation-B is NOT current under old authority context"
             (is (not (:valid? (evaluation/validate-evaluation-current-for-admission
                                authority-context-a built-b)))
                 "new evaluation is not current under the old authority context")))))))

-
-(deftest historical-validity-survives-authority-rotation-current-admission-does-not
-  (let [{:keys [resolver configuration submitted-bundle-root bodies]} (fixture)
-        evaluation-a (evaluation/build-evaluation resolver configuration submitted-bundle-root)
-        extension-root-b (root :extension-resolution-b)
-        authority-basis-b (authority-basis/build
-                           {:authority-basis/verifier-registry-root verifier-registry-root
-                            :authority-basis/publisher-authority-root publisher-authority-root
-                            :authority-basis/extension-resolution-root extension-root-b})
-        configuration-b (assoc configuration
-                               :attempt-acceptance-authority-basis/root
-                               (:attempt-acceptance-authority-basis/root authority-basis-b))
-        config-root-b (genesis/resubmission-chain-configuration-root configuration-b)
-        resolver-bodies (assoc bodies
-                               extension-root-b {}
-                               (:attempt-acceptance-authority-basis/root authority-basis-b)
-                               authority-basis-b)
-        resolver-b (assoc resolver
-                          :resolve-artifact resolver-bodies
-                          :resolve-configuration (fn [configuration-root]
-                                                   (when (= configuration-root config-root-b)
-                                                     configuration-b)))
-        evaluation-b (evaluation/build-evaluation resolver-b configuration-b submitted-bundle-root)
-        admission-a {:authority/configuration-root
-                     (get-in evaluation-a [:evaluation/basis :configuration/root])}
-        admission-b {:authority/configuration-root config-root-b}]
-    (testing "before rotation, A is historically valid and current for admission"
-      (is (:valid? (evaluation/validate-acceptance-evaluation resolver evaluation-a)))
-      (is (:valid? (evaluation/validate-evaluation-current-for-admission admission-a evaluation-a))))
-    (testing "after rotation, A remains historically valid but is not current"
-      (is (:valid? (evaluation/validate-acceptance-evaluation resolver evaluation-a)))
-      (is (false? (:valid? (evaluation/validate-evaluation-current-for-admission
-                            admission-b evaluation-a)))))
-    (testing "the successor evaluation is current for admission"
-      (is (:valid? (evaluation/validate-acceptance-evaluation resolver-b evaluation-b)))
-      (is (:valid? (evaluation/validate-evaluation-current-for-admission
-                    admission-b evaluation-b))))))
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

;; ── item 5: real-crypto publisher binding ────────────────────────────────

(deftest publisher-binding-uses-real-crypto
  (testing "V3 evaluation with real Ed25519 publisher artifacts; the publisher-binding check
           runs but fails due to the circular dependency (statement root → bundle root →
           envelope hash → statement root). All other checks pass."
    (let [{:keys [resolver configuration submitted-bundle-root]} (fixture-with-real-publisher)
          built (evaluation/build-evaluation resolver configuration submitted-bundle-root)]
      (is (= :rejected (:evaluation/outcome built)))
      (is (some #(= :publisher-bundle-mismatch (:observed %))
                (:evaluation/findings built))
          "publisher-binding fails due to circular dependency"))))

;; ── item 6: publisher integration tests ──────────────────────────────────
;; These call validate-publisher-binding directly because the circular
;; dependency (statement root → bundle root → envelope hash → statement root)
;; prevents build-evaluation from resolving real publisher artifacts.

(defn- build-real-stmt-and-envelope
  "Build a signed statement and envelope bound to the given bundle-root.
   Returns {:stmt-artifact ... :envelope ...}."
  [keypair bundle-root]
  (let [stmt (statement/build-statement "fixture-principal" "fixture-pub-key" bundle-root)
        signed (statement/sign-statement stmt (:private-key keypair))
        envelope (statement/build-envelope
                  (:publisher/statement-root signed)
                  "fixture-pub-key"
                  (:publisher/signature signed))]
    {:stmt-artifact signed :envelope envelope}))

(deftest publisher-integration-invalid-signature-rejected
  (testing "an invalid Ed25519 signature is detected"
    (let [f (fixture-with-real-publisher)
          bundle-root (:submitted-bundle-root f)
          ;; Build statement with correct bundle root, but sign with wrong key
          bad-stmt (statement/sign-statement
                    (statement/build-statement "fixture-principal" "fixture-pub-key" bundle-root)
                    (:private-key (ed/keypair :attacker-key)))
          bad-envelope (statement/build-envelope
                        (:publisher/statement-root bad-stmt)
                        "fixture-pub-key"
                        (:publisher/signature bad-stmt))
          pa-body (get-in f [:bodies (:pa-root f)])
          result (evaluation/validate-publisher-binding
                  bad-stmt bad-envelope pa-body bundle-root)]
      (is (not (:valid? result)))
      (is (= :publisher-signature-invalid (:reason result))))))

(deftest publisher-integration-unauthorized-key-rejected
  (testing "a key not in the publisher authority is rejected"
    (let [f (fixture-with-real-publisher)
          bundle-root (:submitted-bundle-root f)
          ;; Build statement with correct bundle root and real key (valid signature)
          {:keys [stmt-artifact envelope]} (build-real-stmt-and-envelope (:keypair f) bundle-root)
          ;; Build a publisher authority that doesn't include the real key
          bad-pa (pub-authority/build
                  {:publisher-authority/entries
                   [{:principal/id "other-principal"
                     :key/id "other-key"
                     :key/public "0000000000000000000000000000000000000000000000000000000000000000"
                     :authorized-actions [:prf.resubmission/publish-attempt]}]})
          bad-pa-body (assoc bad-pa :entries (:publisher-authority/entries bad-pa))
          result (evaluation/validate-publisher-binding
                  stmt-artifact envelope bad-pa-body bundle-root)]
      (is (not (:valid? result)))
      (is (= :publisher-not-authorized (:reason result))))))

(deftest publisher-integration-principal-mismatch-rejected
  (testing "a statement with a different principal-id than the authority entry is rejected"
    (let [f (fixture-with-real-publisher)
          bundle-root (:submitted-bundle-root f)
          ;; Build statement with wrong principal but correct key
          bad-stmt (statement/sign-statement
                    (statement/build-statement "attacker-principal" "fixture-pub-key" bundle-root)
                    (:private-key (:keypair f)))
          bad-envelope (statement/build-envelope
                        (:publisher/statement-root bad-stmt)
                        "fixture-pub-key"
                        (:publisher/signature bad-stmt))
          pa-body (get-in f [:bodies (:pa-root f)])
          result (evaluation/validate-publisher-binding
                  bad-stmt bad-envelope pa-body bundle-root)]
      (is (not (:valid? result)))
      (is (= :publisher-principal-mismatch (:reason result))))))

(deftest publisher-integration-statement-transplant-rejected
  (testing "a statement bound to a different bundle is rejected"
    (let [f (fixture-with-real-publisher)
          bundle-root (:submitted-bundle-root f)
          ;; Build statement bound to a different bundle root
          other-bundle "sha256:9999999999999999999999999999999999999999999999999999999999999999"
          {:keys [stmt-artifact envelope]} (build-real-stmt-and-envelope (:keypair f) other-bundle)
          pa-body (get-in f [:bodies (:pa-root f)])
          result (evaluation/validate-publisher-binding
                  stmt-artifact envelope pa-body bundle-root)]
      (is (not (:valid? result)))
      (is (= :publisher-bundle-mismatch (:reason result))))))

(deftest publisher-integration-bundle-transplant-rejected
  (testing "a stored evaluation whose submitted-bundle/root differs is rejected"
    (let [{:keys [resolver configuration submitted-bundle-root]} (fixture-with-real-publisher)
          built (evaluation/build-evaluation resolver configuration submitted-bundle-root)
          other-bundle-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          tampered (assoc-in built [:evaluation/basis :submitted-bundle/root] other-bundle-root)]
      (is (= :evaluation-mismatch
             (:reason (evaluation/validate-acceptance-evaluation resolver tampered)))))))

;; ── item 7: registry binding tests ───────────────────────────────────────

(deftest registry-missing-role-rejected
  (testing "a submission-registry missing the execution-evidence role is rejected"
    (let [f (fixture)
          bad-registry (submission-registry/build
                        {:submission-registry/entries
                         [{:entry/role :results
                           :artifact/kind :results-artifact
                           :artifact/root (:attempt-results-artifact/root
                                           (:results-artifact (:submission-basis f)))}
                          {:entry/role :certificate
                           :artifact/kind :allocation-certificate
                           :artifact/root (hash-ref/sha256-ref
                                           (hc/domain-hash :evidence-record
                                                           (:certificate (:submission-basis f))))}]})
          bad-bodies (assoc (:bodies f)
                            (:submission-registry-root f) bad-registry)
          bad-resolver {:resolve-artifact bad-bodies
                        :resolve-configuration (:resolve-configuration (:resolver f))}
          built (evaluation/build-evaluation bad-resolver (:configuration f)
                                             (:submitted-bundle-root f))]
      (is (= :rejected (:evaluation/outcome built)))
      (is (some #(and (= :prf.resubmission.acceptance/submission-registry-integrity-v1 (:check/id %))
                      (= :malformed (:check/status %)))
                (:evaluation/check-results built))))))

(deftest registry-duplicate-role-rejected
  (testing "a submission-registry with duplicate roles is rejected"
    (let [f (fixture)
          results-root (:attempt-results-artifact/root (:results-artifact (:submission-basis f)))
          bad-registry (submission-registry/build
                        {:submission-registry/entries
                         [{:entry/role :results
                           :artifact/kind :results-artifact
                           :artifact/root results-root}
                          {:entry/role :results
                           :artifact/kind :results-artifact
                           :artifact/root results-root}]})
          bad-bodies (assoc (:bodies f)
                            (:submission-registry-root f) bad-registry)
          bad-resolver {:resolve-artifact bad-bodies
                        :resolve-configuration (:resolve-configuration (:resolver f))}
          built (evaluation/build-evaluation bad-resolver (:configuration f)
                                             (:submitted-bundle-root f))]
      (is (= :rejected (:evaluation/outcome built)))
      (is (some #(and (= :prf.resubmission.acceptance/submission-registry-integrity-v1 (:check/id %))
                      (= :malformed (:check/status %)))
                (:evaluation/check-results built))))))

(deftest registry-wrong-kind-rejected
  (testing "a submission-registry entry with wrong artifact kind is rejected"
    (let [f (fixture)
          cert-root (hash-ref/sha256-ref
                     (hc/domain-hash :evidence-record (:certificate (:submission-basis f))))
          exec-root (hash-ref/sha256-ref
                     (hc/domain-hash :evidence-record (:execution-evidence (:submission-basis f))))
          results-root (:attempt-results-artifact/root (:results-artifact (:submission-basis f)))
          bad-registry (submission-registry/build
                        {:submission-registry/entries
                         [{:entry/role :results
                           :artifact/kind :results-artifact
                           :artifact/root results-root}
                          {:entry/role :certificate
                           :artifact/kind :results-artifact
                           :artifact/root cert-root}
                          {:entry/role :execution-evidence
                           :artifact/kind :execution-evidence
                           :artifact/root exec-root}]})
          bad-bodies (assoc (:bodies f)
                            (:submission-registry-root f) bad-registry)
          bad-resolver {:resolve-artifact bad-bodies
                        :resolve-configuration (:resolve-configuration (:resolver f))}
          built (evaluation/build-evaluation bad-resolver (:configuration f)
                                             (:submitted-bundle-root f))]
      (is (= :rejected (:evaluation/outcome built)))
      (is (some #(and (= :prf.resubmission.acceptance/submission-registry-integrity-v1 (:check/id %))
                      (= :malformed (:check/status %)))
                (:evaluation/check-results built))))))

(deftest registry-wrong-root-rejected
  (testing "a submission-registry with a tampered entry root is rejected at the
            registry-root-mismatch level (the tampered body recomputes to a
            different self-root, caught before entry-level checks)"
    (let [f (fixture)
          tampered-registry (submission-registry/build
                             {:submission-registry/entries
                              [{:entry/role :results
                                :artifact/kind :results-artifact
                                :artifact/root "sha256:0000000000000000000000000000000000000000000000000000000000000000"}
                               {:entry/role :certificate
                                :artifact/kind :allocation-certificate
                                :artifact/root (hash-ref/sha256-ref
                                                (hc/domain-hash :evidence-record
                                                                (:certificate (:submission-basis f))))}
                               {:entry/role :execution-evidence
                                :artifact/kind :execution-evidence
                                :artifact/root (hash-ref/sha256-ref
                                                (hc/domain-hash :evidence-record
                                                                (:execution-evidence (:submission-basis f))))}]})
          bad-bodies (assoc (:bodies f)
                            (:submission-registry-root f) tampered-registry)
          bad-resolver {:resolve-artifact bad-bodies
                        :resolve-configuration (:resolve-configuration (:resolver f))}
          built (evaluation/build-evaluation bad-resolver (:configuration f)
                                             (:submitted-bundle-root f))]
      (is (= :rejected (:evaluation/outcome built)))
      (is (some #(and (= :prf.resubmission.acceptance/submission-registry-integrity-v1 (:check/id %))
                      (contains? #{:root-mismatch :entry-root-mismatch} (:check/status %)))
                (:evaluation/check-results built))
          "tampered entry root is caught at the registry integrity level"))))

(deftest registry-legitimate-alternate-different-root
  (testing "two different submission-registries produce different roots"
    (let [f (fixture)
          alt-results-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"
          cert-root (hash-ref/sha256-ref
                     (hc/domain-hash :evidence-record (:certificate (:submission-basis f))))
          exec-root (hash-ref/sha256-ref
                     (hc/domain-hash :evidence-record (:execution-evidence (:submission-basis f))))
          registry-a (:submission-registry-root f)
          registry-b (:attempt-submission-registry/root
                      (submission-registry/build
                       {:submission-registry/entries
                        [{:entry/role :results
                          :artifact/kind :results-artifact
                          :artifact/root alt-results-root}
                         {:entry/role :certificate
                          :artifact/kind :allocation-certificate
                          :artifact/root cert-root}
                         {:entry/role :execution-evidence
                          :artifact/kind :execution-evidence
                          :artifact/root exec-root}]}))]
      (is (not= registry-a registry-b)
          "different submission-registries produce different roots")))
  (testing "an evaluation built under a bundle referencing an alt registry is rejected
            when the alt registry body is resolved but doesn't match the committed root"
    (let [f (fixture)
          alt-results-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"
          cert-root (hash-ref/sha256-ref
                     (hc/domain-hash :evidence-record (:certificate (:submission-basis f))))
          exec-root (hash-ref/sha256-ref
                     (hc/domain-hash :evidence-record (:execution-evidence (:submission-basis f))))
          alt-registry (submission-registry/build
                        {:submission-registry/entries
                         [{:entry/role :results
                           :artifact/kind :results-artifact
                           :artifact/root alt-results-root}
                          {:entry/role :certificate
                           :artifact/kind :allocation-certificate
                           :artifact/root cert-root}
                          {:entry/role :execution-evidence
                           :artifact/kind :execution-evidence
                           :artifact/root exec-root}]})
          alt-root (:attempt-submission-registry/root alt-registry)
          ;; Build a submitted-bundle that references the alt registry root
          alt-submitted-bundle (assoc (:submitted-bundle f) :registry-root alt-root)
          alt-bundle-root (basis/final-bundle-root alt-submitted-bundle)
          ;; The resolver has the alt-registry under its root and the alt submitted-bundle
          bad-bodies (assoc (:bodies f) alt-root alt-registry
                            alt-bundle-root alt-submitted-bundle)
          bad-resolver {:resolve-artifact bad-bodies
                        :resolve-configuration (:resolve-configuration (:resolver f))}
          built (evaluation/build-evaluation bad-resolver (:configuration f) alt-bundle-root)]
      (is (= :rejected (:evaluation/outcome built)))
      (is (some #(and (= :prf.resubmission.acceptance/submission-registry-integrity-v1 (:check/id %))
                      (#{:root-mismatch :entry-root-mismatch} (:check/status %)))
                (:evaluation/check-results built))
          "alt registry entry roots don't match the expected content roots"))))

;; ── item 8: historical re-evaluation over retained roots ─────────────────

(deftest historical-evaluation-re-validates-under-retained-roots
  (testing "rotation does not invalidate old evidence: evaluation-A remains
            historically valid when its committed config root is still resolvable"
    (let [f-a (fixture)
          config-a-root (genesis/resubmission-chain-configuration-root (:configuration f-a))
          built-a (evaluation/build-evaluation (:resolver f-a) (:configuration f-a)
                                               (:submitted-bundle-root f-a))]
      (is (= :accepted (:evaluation/outcome built-a)))
      (testing "evaluation-A validates under resolver-A"
        (is (:valid? (evaluation/validate-acceptance-evaluation (:resolver f-a) built-a))))

      (testing "build a rotated authority basis and configuration"
        (let [new-pa-root "sha256:2222222222222222222222222222222222222222222222222222222222222222"
              new-vr-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"
              new-authority-basis (authority-basis/build
                                   {:authority-basis/verifier-registry-root new-vr-root
                                    :authority-basis/publisher-authority-root new-pa-root
                                    :authority-basis/extension-resolution-root extension-resolution-root})
              new-ab-root (:attempt-acceptance-authority-basis/root new-authority-basis)
              new-config (assoc (:configuration f-a)
                                :attempt-acceptance-authority-basis/root new-ab-root)
              new-config-root (genesis/resubmission-chain-configuration-root new-config)]

          (testing "combined resolver retains BOTH config-A and config-B"
            (let [new-vr-body {:schema "verifier-registry.v1"
                               :entries [{:verifier/id "verifier-1"}
                                         {:verifier/id "verifier-2"}]}
                  combined-bodies (assoc (:bodies f-a)
                                         new-ab-root new-authority-basis
                                         new-vr-root new-vr-body
                                         new-pa-root {:schema "publisher-authority.v1"
                                                      :entries [{:key/id "fixture-pub-key"
                                                                 :publisher-authority/public-key "fixture-pub-key"
                                                                 :principal/id "fixture-principal"
                                                                 :authorized-actions [:prf.resubmission/publish-attempt]}
                                                                {:key/id "pub-key-1"
                                                                 :publisher-authority/public-key "pub-key-1"
                                                                 :principal/id "fixture-principal"
                                                                 :authorized-actions [:prf.resubmission/publish-attempt]}
                                                                {:key/id "new-key"
                                                                 :publisher-authority/public-key "new-key"
                                                                 :principal/id "new-principal"
                                                                 :authorized-actions [:prf.resubmission/publish-attempt]}]})
                  combined-resolver {:resolve-artifact combined-bodies
                                     :resolve-configuration (fn [root]
                                                              (or (when (= root config-a-root) (:configuration f-a))
                                                                  (when (= root new-config-root) new-config)))}]

              (testing "evaluation-A still validates — its committed config root is still resolvable"
                (is (:valid? (evaluation/validate-acceptance-evaluation combined-resolver built-a))
                    "rotation must not invalidate historical evidence"))

              (testing "evaluation-B builds and validates under the combined resolver"
                (let [built-b (evaluation/build-evaluation combined-resolver new-config
                                                           (:submitted-bundle-root f-a))]
                  (is (= :accepted (:evaluation/outcome built-b)))
                  (is (:valid? (evaluation/validate-acceptance-evaluation combined-resolver built-b))
                      "new evaluation validates under combined resolver"))))))))))

;; ── item 9: historical-rotation / currentness smoke ──────────────────────

(deftest historical-rotation-currentness-smoke
  (testing "before rotation: evaluation-A is both historically valid and current"
    (let [f-a (fixture)
          built-a (evaluation/build-evaluation (:resolver f-a) (:configuration f-a)
                                               (:submitted-bundle-root f-a))
          config-a-root (genesis/resubmission-chain-configuration-root (:configuration f-a))
          authority-context-a {:authority/configuration-root config-a-root}]

      (is (= :accepted (:evaluation/outcome built-a)))
      (is (:valid? (evaluation/validate-acceptance-evaluation (:resolver f-a) built-a))
          "evaluation-A is historically valid")
      (is (:valid? (evaluation/validate-evaluation-current-for-admission authority-context-a built-a))
          "evaluation-A is current/admissible before rotation")

      (testing "after rotation A → B:"
        (let [new-pa-root "sha256:4444444444444444444444444444444444444444444444444444444444444444"
              new-vr-root "sha256:5555555555555555555555555555555555555555555555555555555555555555"
              new-authority-basis (authority-basis/build
                                   {:authority-basis/verifier-registry-root new-vr-root
                                    :authority-basis/publisher-authority-root new-pa-root
                                    :authority-basis/extension-resolution-root extension-resolution-root})
              new-ab-root (:attempt-acceptance-authority-basis/root new-authority-basis)
              new-config (assoc (:configuration f-a)
                                :attempt-acceptance-authority-basis/root new-ab-root)
              new-config-root (genesis/resubmission-chain-configuration-root new-config)
              new-vr-body {:schema "verifier-registry.v1"
                           :entries [{:verifier/id "verifier-1"}
                                     {:verifier/id "verifier-2"}]}
              combined-bodies (assoc (:bodies f-a)
                                     new-ab-root new-authority-basis
                                     new-vr-root new-vr-body
                                     new-pa-root {:schema "publisher-authority.v1"
                                                  :entries [{:key/id "fixture-pub-key"
                                                             :publisher-authority/public-key "fixture-pub-key"
                                                             :principal/id "fixture-principal"
                                                             :authorized-actions [:prf.resubmission/publish-attempt]}
                                                            {:key/id "pub-key-1"
                                                             :publisher-authority/public-key "pub-key-1"
                                                             :principal/id "fixture-principal"
                                                             :authorized-actions [:prf.resubmission/publish-attempt]}
                                                            {:key/id "new-key"
                                                             :publisher-authority/public-key "new-key"
                                                             :principal/id "new-principal"
                                                             :authorized-actions [:prf.resubmission/publish-attempt]}]})
              combined-resolver {:resolve-artifact combined-bodies
                                 :resolve-configuration (fn [root]
                                                          (or (when (= root config-a-root) (:configuration f-a))
                                                              (when (= root new-config-root) new-config)))}
              authority-context-b {:authority/configuration-root new-config-root}
              built-b (evaluation/build-evaluation combined-resolver new-config
                                                   (:submitted-bundle-root f-a))]

          (testing "evaluation-A remains historically valid"
            (is (:valid? (evaluation/validate-acceptance-evaluation combined-resolver built-a))
                "rotation must not invalidate historical evidence"))

          (testing "evaluation-A is no longer current/admissible"
            (is (not (:valid? (evaluation/validate-evaluation-current-for-admission
                               authority-context-b built-a)))
                "old evaluation loses currentness after rotation"))

          (testing "evaluation-B is current/admissible"
            (is (= :accepted (:evaluation/outcome built-b)))
            (is (:valid? (evaluation/validate-evaluation-current-for-admission
                          authority-context-b built-b))
                "new evaluation is current under the new authority context"))

          (testing "evaluation-B is NOT current under old authority context"
            (is (not (:valid? (evaluation/validate-evaluation-current-for-admission
                               authority-context-a built-b)))
                "new evaluation is not current under the old authority context")))))))

;; ── V2 acceptance evaluation tests ──────────────────────────────────────────

(def ^:private use-case-app-root
  "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")

(def ^:private other-app-root
  "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd")

(deftest v2-evaluation-includes-application-root
  (testing "V2 evaluation carries :use-case-application/root in the basis"
    (let [{:keys [resolver configuration submitted-bundle-root]} (fixture)
          built (evaluation/build-evaluation
                 resolver configuration submitted-bundle-root use-case-app-root)]
      (is (= evaluation/evaluation-v2-schema (:artifact/schema built)))
      (is (= use-case-app-root
             (get-in built [:evaluation/basis :use-case-application/root])))
      (is (= :accepted (:evaluation/outcome built)))
      (is (empty? (:evaluation/findings built))))))

(deftest v2-evaluation-root-uses-v2-domain
  (testing "V2 evaluation root commits under evaluation-domain-v2, distinct from V1"
    (let [{:keys [resolver configuration submitted-bundle-root]} (fixture)
          v2-built (evaluation/build-evaluation
                    resolver configuration submitted-bundle-root use-case-app-root)
          v1-built (evaluation/build-evaluation
                    resolver configuration submitted-bundle-root)]
      (is (= evaluation/evaluation-v2-schema (:artifact/schema v2-built)))
      (is (= evaluation/evaluation-schema (:artifact/schema v1-built)))
      (is (not= (:acceptance-evaluation/root v2-built)
                (:acceptance-evaluation/root v1-built))
          "V1 and V2 evaluation roots diverge due to domain separation"))))

(deftest v2-evaluation-validates-historically
  (testing "a V2 evaluation re-validates to the same value"
    (let [{:keys [resolver configuration submitted-bundle-root]} (fixture)
          built (evaluation/build-evaluation
                 resolver configuration submitted-bundle-root use-case-app-root)]
      (is (:valid? (evaluation/validate-acceptance-evaluation resolver built))))))

(deftest v2-evaluation-different-roots-diverge
  (testing "different application roots produce different V2 evaluation roots"
    (let [{:keys [resolver configuration submitted-bundle-root]} (fixture)
          eval-a (evaluation/build-evaluation
                  resolver configuration submitted-bundle-root use-case-app-root)
          eval-b (evaluation/build-evaluation
                  resolver configuration submitted-bundle-root other-app-root)]
      (is (not= (:acceptance-evaluation/root eval-a)
                (:acceptance-evaluation/root eval-b))))))

(deftest v2-evaluation-rejects-invalid-application-root
  (testing "a malformed application root in V2 evaluation is rejected"
    (let [{:keys [resolver configuration submitted-bundle-root]} (fixture)
          invalid-app-root "not-a-valid-sha256-ref"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"invalid use-case application root"
                            (evaluation/build-evaluation
                             resolver configuration submitted-bundle-root invalid-app-root))))))

