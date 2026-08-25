(ns resolver-sim.benchmark.governed-authority-resolution-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.governed-authority-resolution :as sut]
            [resolver-sim.hash.reference :as ref]))

(defn- hash-ref [ch]
  (str "sha256:" (apply str (take 64 (cycle ch)))))

(def basis-input
  {:resolution/purpose :transition-replay
   :chain-instance-genesis/root (hash-ref "1")
   :resolution/state-before-root (hash-ref "2")
   :resolution/anchor-root (hash-ref "3")
   :review-round/hash (hash-ref "4")})

(def basis (sut/build-resolution-basis basis-input))

(def context-input
  {:resolution-basis/root (:resolution-basis/root basis)
   :chain-instance-genesis/root (:chain-instance-genesis/root basis)
   :resolution/state-before-root (:resolution/state-before-root basis)
   :authority-state/root (hash-ref "5")
   :chain-configuration/root (hash-ref "6")
   :review-governance/root (hash-ref "7")
   :review-governance-activation/root (hash-ref "8")
   :review-round/hash (:review-round/hash basis)
   :review-round/root (hash-ref "9")
   :position-time-basis/root (hash-ref "a")
   :review-governance-admissibility/root (hash-ref "b")
   :control-plane-evidence/root (hash-ref "c")})

(def context (sut/build-resolved-context context-input))

(def binding-input
  {:resolved-review-authority-context/root
   (:resolved-review-authority-context/root context)
   :transition/root (hash-ref "d")
   :transaction/state-before-root (:resolution/state-before-root context)
   :transaction/state-after-root (hash-ref "e")
   :authorization/result-root (hash-ref "f")})

(deftest resolution-basis-contract
  (testing "the basis is closed, rooted, and purpose-explicit"
    (is (= sut/resolution-basis-schema (:artifact/schema basis)))
    (is (ref/valid-sha256-ref? (:resolution-basis/root basis)))
    (is (:valid? (sut/validate-resolution-basis basis)))
    (is (not= (:resolution-basis/root basis)
              (:resolution-basis/root
               (sut/build-resolution-basis
                (assoc basis-input :resolution/purpose :historical-audit))))))
  (testing "unknown fields and unsupported purposes fail closed"
    (is (not (:valid? (sut/validate-resolution-basis
                       (assoc basis :unknown true)))))
    (is (some #(re-find #"resolution/purpose" %)
              (:errors (sut/validate-resolution-basis
                        (assoc basis :resolution/purpose :implicit-head)))))))

(deftest resolved-context-contract
  (testing "the context binds the authenticated answer to the exact basis and pre-state"
    (is (= sut/resolved-context-schema (:artifact/schema context)))
    (is (ref/valid-sha256-ref? (:resolved-review-authority-context/root context)))
    (is (:valid? (sut/validate-resolved-context context))))
  (testing "the context excludes runtime resolver objects by closed shape"
    (is (not (:valid? (sut/validate-resolved-context
                       (assoc context :governance-current? (constantly true))))))))

(deftest transition-binding-contract
  (let [binding (sut/build-transition-binding binding-input)]
    (testing "after-state is bound only in the post hoc transition artifact"
      (is (= sut/transition-binding-schema (:artifact/schema binding)))
      (is (:valid? (sut/validate-transition-binding binding)))
      (is (sut/transition-binding-compatible? context binding)))
    (testing "a context cannot be substituted across pre-states"
      (is (not (sut/transition-binding-compatible?
                context
                (assoc binding :transaction/state-before-root (hash-ref "0"))))))
    (testing "the binding root changes with the resulting state"
      (is (not= (:governed-authority-transition-binding/root binding)
                (:governed-authority-transition-binding/root
                 (sut/build-transition-binding
                  (assoc binding-input :transaction/state-after-root
                         (hash-ref "0")))))))))

(deftest resolver-identity-stabilisation
  (let [r1 sut/default-resolver
        r2 (sut/build-resolver-descriptor
            {:resolver/id :governed-review-authority
             :resolver/contract :governed-authority-resolution.v1
             :resolver/profile :state-addressed
             :resolver/version 2})
        v2-input (assoc basis-input :authority-resolver/root
                        (:governed-authority-resolver/root r1))
        b1 (sut/build-resolution-basis-v2 v2-input)]
    (is (= (:governed-authority-resolver/root r1)
           (sut/resolver-root r1))
        "descriptor roots reproduce from semantic fields")
    (is (not= (:governed-authority-resolver/root r1)
              (:governed-authority-resolver/root r2)))
    (is (:valid? (sut/validate-resolution-basis-v2 b1)))
    (is (not (:valid? (sut/validate-resolution-basis-v2
                       (assoc b1 :authority-resolver/root
                              (:governed-authority-resolver/root r2))))))
    (is (:valid? (sut/validate-resolution-basis basis))
        "V1 remains verifiable for historical artifacts")))

(deftest resolver-registry-is-derived
  (testing "known-resolver-roots is derived from known-descriptors, not a disconnected literal"
    (let [r1 sut/default-resolver
          r1-root (:governed-authority-resolver/root r1)
          r2 (sut/build-resolver-descriptor
              {:resolver/id :governed-review-authority
               :resolver/contract :governed-authority-resolution.v1
               :resolver/profile :state-addressed
               :resolver/version 2})
          r2-root (:governed-authority-resolver/root r2)]
      (is (contains? sut/known-resolver-roots r1-root)
          "R1 root is recognized")
      (is (not (contains? sut/known-resolver-roots r2-root))
          "R2 root is not recognized — registry derived from known-descriptors only")
      (is (= #{r1-root} sut/known-resolver-roots)
          "registry contains exactly the re-rooted known descriptors"))))

(deftest recognized-resolver-self-validates
  (testing "recognized descriptor self-validates to the committed root"
    (let [r1 sut/default-resolver
          r1-root (:governed-authority-resolver/root r1)]
      (is (some? (sut/recognized-resolver-descriptor r1-root))
          "recognized root yields a descriptor")
      (is (= r1 (sut/recognized-resolver-descriptor r1-root))
          "recognized descriptor is the original descriptor")
      (is (= r1-root (sut/resolver-root (sut/recognized-resolver-descriptor r1-root)))
          "recognized descriptor re-roots to exactly the committed root"))
    (is (nil? (sut/recognized-resolver-descriptor (hash-ref "z")))
        "unknown root yields no descriptor")))

(deftest anti-substitution-resolver-dispatch-boundary
  (testing "a basis committing resolver R1 cannot dispatch an implementation for R2"
    (let [r1 sut/default-resolver
          r1-root (:governed-authority-resolver/root r1)
          r2 (sut/build-resolver-descriptor
              {:resolver/id :governed-review-authority
               :resolver/contract :governed-authority-resolution.v1
               :resolver/profile :state-addressed
               :resolver/version 2})
          r2-root (:governed-authority-resolver/root r2)
          r1-basis (sut/build-resolution-basis-v2
                    (assoc basis-input :authority-resolver/root r1-root))]
      (testing "committing R1's root produces a valid V2 basis"
        (is (:valid? (sut/validate-resolution-basis-v2 r1-basis))))
      (testing "the dispatch boundary resolves R1's root to R1, never R2"
        (is (= r1 (sut/recognized-resolver-descriptor r1-root))
            "recognized-descriptor returns R1 for R1's root (not R2)")
        (is (= r1-root (sut/resolver-root (sut/recognized-resolver-descriptor r1-root)))
            "recognized descriptor self-validates to committed root"))
      (testing "R2 is structurally distinct and not dispatchable under R1"
        (is (not= r1-root r2-root)
            "R1 and R2 produce distinct descriptor roots")
        (is (not= r1-root (sut/resolver-root r2))
            "R2 descriptor does not self-validate to R1 root")
        (is (nil? (sut/recognized-resolver-descriptor r2-root))
            "R2 root yields no recognized descriptor — R2 cannot be dispatched"))
      (testing "a basis committing R2's root is rejected (unknown resolver)"
        (let [rejected (sut/validate-resolution-basis-v2
                        (-> r1-basis
                            (assoc :authority-resolver/root r2-root)
                            (dissoc :resolution-basis/root)
                            (assoc :artifact/schema sut/resolution-basis-v2-schema)))]
          (is (not (:valid? rejected))
              "basis with R2's root is rejected")
          (is (some #(re-find #"unknown" %) (:errors rejected))
              "rejection cites unrecognized resolver root"))))))

(deftest current-admission-requires-v2
  (testing "V1 is accepted for historical purposes but V2 is required for current-admission"
    (let [v1-historical (sut/build-resolution-basis
                         (assoc basis-input :resolution/purpose :historical-audit))
          v1-replay (sut/build-resolution-basis
                     (assoc basis-input :resolution/purpose :transition-replay))
          v1-admission (sut/build-resolution-basis
                        (assoc basis-input :resolution/purpose :current-admission))
          v2-admission (sut/build-resolution-basis-v2
                        (assoc basis-input
                               :resolution/purpose :current-admission
                               :authority-resolver/root
                               (:governed-authority-resolver/root sut/default-resolver)))]
      (is (:valid? (sut/validate-resolution-basis-any v1-historical))
          "V1 accepted for historical-audit")
      (is (:valid? (sut/validate-resolution-basis-any v1-replay))
          "V1 accepted for transition-replay")
      (is (not (:valid? (sut/validate-resolution-basis-any v1-admission)))
          "V1 rejected for current-admission — live downgrade blocked")
      (is (some #(re-find #"current-admission requires" %)
                (:errors (sut/validate-resolution-basis-any v1-admission)))
          "rejection cites the v2 requirement")
      (is (:valid? (sut/validate-resolution-basis-any v2-admission))
          "V2 accepted for current-admission"))))

(deftest stable-failure-taxonomy
  (is (contains? sut/failure-classes :state-unavailable))
  (is (contains? sut/failure-classes :round-not-found-at-state))
  (is (contains? sut/failure-classes :resolver-unavailable))
  (is (not= :state-unavailable :governance-not-active-at-state)))
