(ns resolver-sim.use-cases.application-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.use-cases.application :as application]))

(defn- root-ref [value]
  (hash-ref/sha256-ref (canonical/domain-hash :registry value)))

(def descriptor
  {:capability/kind :economics/allocation
   :capability/id :prf/pro-rata-allocation
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'external.clean-room/pro-rata
   :input-schema :prf/allocation-context.v1
   :output-schema :prf/allocation-result.v1})

(def definition
  {:concept/id :user/pro-rata-allocation
   :use-case/sequence {:sequence/steps [{:sequence.step/id :allocate}
                                        {:sequence.step/id :verify}]}
   :use-case/step-capabilities
   [{:sequence.step/id :allocate :capability (select-keys descriptor application/capability-identity-keys)}
    {:sequence.step/id :verify :capability (select-keys descriptor application/capability-identity-keys)}]})

(def loaded {:use-case-registry/root (root-ref :registry) :use-cases [definition]})

(defn- bindings [right-input]
  [(application/capability-invocation-binding {:capability descriptor :input-root (root-ref :r0) :output-root (root-ref :r1) :evidence-roots #{(root-ref :e1)}})
   (application/capability-invocation-binding {:capability descriptor :input-root right-input :output-root (root-ref :r2) :evidence-roots #{(root-ref :e2)}})])

(defn- re-root [application]
  (assoc application :application/root (application/application-root application)))

(defn- issue-codes [loaded invocation-bindings descriptors application]
  (mapv :code (:issues (application/valid-application loaded invocation-bindings descriptors application))))

(deftest application-binds-ordered-step-invocation-descriptor-vectors
  (let [valid-bindings (bindings (root-ref :r1))
        application (application/build-application {:loaded loaded :use-case-id :user/pro-rata-allocation :application-id :user/example :invocation-bindings valid-bindings :capability-descriptors [descriptor descriptor]})
        broken-bindings (bindings (root-ref :r9))
        broken (application/build-application {:loaded loaded :use-case-id :user/pro-rata-allocation :application-id :user/broken :invocation-bindings broken-bindings :capability-descriptors [descriptor descriptor]})]
    (is (:valid? (application/valid-application loaded valid-bindings [descriptor descriptor] application)))
    (is (= [:allocate :verify] (mapv :application.step-id (:application/capability-bindings application))))
    (is (= application/sequence-purpose (get-in application [:application/sequence-binding :bound-sequence :purpose])))
    (is (= [:application/consecutive-invocation-root-mismatch]
           (issue-codes loaded broken-bindings [descriptor descriptor] broken)))))

(deftest application-validation-fails-closed-for-positional-and-commitment-tampering
  (let [valid-bindings (bindings (root-ref :r1))
        valid-application (application/build-application
                           {:loaded loaded :use-case-id :user/pro-rata-allocation
                            :application-id :user/example
                            :invocation-bindings valid-bindings
                            :capability-descriptors [descriptor descriptor]})
        wrappers (:application/capability-bindings valid-application)
        mismatched-wrapper (re-root
                            (assoc-in valid-application
                                      [:application/capability-bindings 0 :capability-binding-root]
                                      (root-ref :wrong-binding)))
        reordered-wrappers (re-root
                            (assoc valid-application :application/capability-bindings
                                   (vec (reverse wrappers))))
        wrong-definition (re-root
                          (assoc-in valid-application
                                    [:application/use-case :definition-root]
                                    (root-ref :wrong-definition)))
        wrong-registry (re-root
                        (assoc-in valid-application
                                  [:application/use-case :registry-root]
                                  (root-ref :wrong-registry)))
        wrong-purpose (re-root
                       (assoc-in valid-application
                                 [:application/sequence-binding :bound-sequence :purpose]
                                 :unrelated/purpose))
        unsorted-evidence (assoc-in valid-bindings [0 :invocation/evidence-roots]
                                    [(root-ref :z) (root-ref :a)])
        forged-binding (assoc-in valid-bindings [0 :binding/root] (root-ref :forged))
        wrong-descriptor (assoc descriptor :capability/id :prf/another-allocation)]
    (is (= [:application/companion-vector-cardinality-mismatch]
           (issue-codes loaded [(first valid-bindings)] [descriptor descriptor]
                        valid-application)))
    (is (= [:application/wrapper-binding-root-mismatch
            :application/sequence-binding-mismatch]
           (issue-codes loaded valid-bindings [descriptor descriptor] mismatched-wrapper)))
    (is (= [:application/step-binding-order-mismatch
            :application/wrapper-binding-root-mismatch
            :application/wrapper-binding-root-mismatch
            :application/sequence-binding-mismatch]
           (issue-codes loaded valid-bindings [descriptor descriptor] reordered-wrappers)))
    (is (= [:application/definition-root-mismatch]
           (issue-codes loaded valid-bindings [descriptor descriptor] wrong-definition)))
    (is (= [:application/registry-root-mismatch]
           (issue-codes loaded valid-bindings [descriptor descriptor] wrong-registry)))
    (is (= [:application/sequence-binding-mismatch]
           (issue-codes loaded valid-bindings [descriptor descriptor] wrong-purpose)))
    (is (= [:application/invalid-invocation-binding]
           (issue-codes loaded unsorted-evidence [descriptor descriptor] valid-application)))
    (is (= [:application/wrapper-binding-root-mismatch
            :application/invalid-invocation-binding]
           (issue-codes loaded forged-binding [descriptor descriptor] valid-application)))
    (is (= [:application/capability-descriptor-root-mismatch
            :application/descriptor-binding-identity-mismatch]
           (issue-codes loaded valid-bindings [wrong-descriptor descriptor] valid-application)))))
