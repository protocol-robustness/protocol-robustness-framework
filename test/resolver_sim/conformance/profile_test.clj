(ns resolver-sim.conformance.profile-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.conformance.profile :as profile]))

(def first-profile-path
  "etc/conformance/profiles/sew-trace-equivalence.v1.edn")

(defn- first-profile []
  (profile/load-profile first-profile-path))

(deftest committed-first-profile-loads-and-validates
  (let [p (first-profile)]
    (is (= :sew-trace-equivalence.v1 (:profile/id p)))
    (is (= "conformance-profile.v1" (:profile/schema-version p)))
    (is (:valid? (profile/validate-profile p)))
    (is (= 7 (count (:profile/required-components p))))
    (is (= 7 (count (profile/required-component-ids p))))
    (is (= 6 (count (get-in p [:profile/verdict-policy :derivation-boundaries]))))))

(deftest profile-root-is-content-bound-and-deterministic
  (let [p (first-profile)]
    (is (string? (profile/profile-root p)))
    (is (= (profile/profile-root p) (profile/profile-root p)))
    ;; adding an unrelated key changes the root
    (is (not= (profile/profile-root p)
              (profile/profile-root (assoc p :unrelated-key 1))))))

(deftest validate-rejects-wrong-schema-version
  (let [p (assoc (first-profile) :profile/schema-version "bogus")]
    (is (not (:valid? (profile/validate-profile p))))
    (is (some #(= :violation/invalid-profile-schema-version (:violation/id %))
              (:violations (profile/validate-profile p))))))

(deftest validate-rejects-missing-keys
  (let [p (dissoc (first-profile) :profile/capabilities)]
    (is (not (:valid? (profile/validate-profile p))))
    (is (some #(= :violation/missing-profile-key (:violation/id %))
              (:violations (profile/validate-profile p))))))

(deftest benchmark-profile-validates
  (let [p (profile/load-profile
           "etc/conformance/profiles/research-benchmark-reproduction.v1.edn")]
    (is (:valid? (profile/validate-profile p)))
    (is (= :research-benchmark-reproduction.v1 (:profile/id p)))))

(deftest validate-rejects-duplicate-components
  (let [p (update (first-profile) :profile/required-components
                  (fn [cs] (conj (vec cs) (first cs))))]
    (is (not (:valid? (profile/validate-profile p))))
    (is (some #(= :violation/duplicate-component (:violation/id %))
              (:violations (profile/validate-profile p))))))

(deftest validate-rejects-malformed-capability
  (let [p (assoc-in (first-profile) [:profile/capabilities :required]
                    [{:capability :x}])] ; missing :version
    (is (not (:valid? (profile/validate-profile p))))
    (is (some #(= :violation/invalid-capability-requirement (:violation/id %))
              (:violations (profile/validate-profile p))))))

(deftest validate-rejects-non-keyword-derivation-boundary
  (let [p (assoc-in (first-profile) [:profile/verdict-policy :derivation-boundaries]
                    ["not-a-keyword"])]
    (is (not (:valid? (profile/validate-profile p))))
    (is (some #(= :violation/invalid-derivation-boundary (:violation/id %))
              (:violations (profile/validate-profile p))))))
