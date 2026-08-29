(ns resolver-sim.benchmark.governed-authority-authorisation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.governed-authority-authorisation :as sut]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.signing :as signing]))

(defn- root [ch]
  (str "sha256:" (apply str (repeat 64 ch))))

(def request-root (root "a"))
(def round-root (root "b"))
(def proposed-root (root "c"))
(def signature "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

(defn- signed-v1 [researcher]
  (with-redefs [signing/sign-hash (fn [_ _ _] signature)]
    (rfa/build-signed-decision researcher :authority/test request-root round-root
                               :approve "/dev/null")))

(defn- signed-v2 [researcher]
  (with-redefs [signing/sign-hash (fn [_ _ _] signature)]
    (rfa/build-signed-decision-v2 researcher :authority/test request-root round-root
                                  proposed-root :approve "/dev/null")))

(defn- candidate [decisions]
  {:authorisation/id :authority/test
   :authorisation/request-root request-root
   :authorisation/review-round {:review-round/id round-root
                                :review-round/hash round-root}
   :authorisation/target {:target/kind :benchmark-branch
                          :target/baseline-content-root (root "d")
                          :target/branch-descriptor-hash (root "e")
                          :target/proposed-content-root proposed-root}
   :authorisation/decision-references decisions})

(defn- reseal [authorisation]
  (assoc authorisation :governed-authority-authorisation/root
         (sut/authorisation-root authorisation)))

(deftest accepts-closed-v1-and-v2-decisions-with-intrinsic-integrity
  (doseq [decision [(signed-v1 "researcher-v1") (signed-v2 "researcher-v2")]]
    (let [authorisation (sut/build-authorisation (candidate [decision]))]
      (is (:valid? (sut/validate-authorisation authorisation)))
      (is (= (:governed-authority-authorisation/root authorisation)
             (sut/authorisation-root authorisation))))))

(deftest rejects-unknown-nested-fields-and-invalid-semantic-roots
  (let [authorisation (sut/build-authorisation (candidate [(signed-v2 "researcher")]))
        invalid? #(not (:valid? (sut/validate-authorisation (reseal %))))]
    (testing "closed nested reference, target, decision, and signature shapes"
      (is (invalid? (assoc-in authorisation [:authorisation/review-round :extra] true)))
      (is (invalid? (assoc-in authorisation [:authorisation/target :extra] true)))
      (is (invalid? (assoc-in authorisation
                              [:authorisation/decision-references 0 :signature :extra]
                              true)))
      (is (invalid? (assoc-in authorisation
                              [:authorisation/decision-references 0 :extra]
                              true))))
    (testing "all semantic roots are SHA-256 references"
      (is (invalid? (assoc-in authorisation
                              [:authorisation/target :target/proposed-content-root]
                              "sha256:not-a-root"))))))

(deftest rejects-tampered-or-substituted-decision-content
  (let [authorisation (sut/build-authorisation (candidate [(signed-v2 "researcher")]))]
    (testing "the intrinsic hash binds every decision field"
      (is (false? (:valid? (sut/validate-authorisation
                            (reseal (assoc-in authorisation
                                              [:authorisation/decision-references 0 :decision] :dissent)))))))
    (testing "V2 binds the containing authorisation and complete outcome"
      (is (false? (:valid? (sut/validate-authorisation
                            (reseal (assoc-in authorisation
                                              [:authorisation/decision-references 0 :authorisation/id]
                                              :authority/other))))))
      (is (false? (:valid? (sut/validate-authorisation
                            (reseal (assoc-in authorisation
                                              [:authorisation/decision-references 0 :outcome/root]
                                              (root "f"))))))))
    (testing "the outer root is an integrity commitment, not a trust override"
      (is (false? (:valid? (sut/validate-authorisation
                            (assoc authorisation :governed-authority-authorisation/root
                                   (root "f")))))))))
