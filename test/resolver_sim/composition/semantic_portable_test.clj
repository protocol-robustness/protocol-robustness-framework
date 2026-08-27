(ns resolver-sim.composition.semantic-portable-test
  "Regression coverage for the untrusted persisted semantic-composition boundary."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.composition.semantic :as semantic]
            [resolver-sim.extensions.resolution :as resolution]))

(defn- composition []
  (:composition
   (semantic/compose-authoritative :development [] {:schemas {} :effect-schemas {}} {})))

(defn- body []
  (semantic/portable-body (composition)))

(deftest semantic-composition-v1-historical-root-is-stable
  (is (= "f6ea51337ae057f232d183215db400026079e280c2ef16071af6ad5e4cf53b59"
         (:semantic-composition/root (body)))))

(deftest constructor-and-portable-verifier-share-one-identity-projection
  (let [constructed (composition)
        portable (body)
        deserialized (edn/read-string (pr-str portable))
        verified (semantic/verify-portable! deserialized)]
    (is (= (semantic/composition-root constructed)
           (:semantic-composition/root verified)))
    (is (= portable verified))))

(deftest portable-verifier-rejects-root-preserving-identity-mutations
  (let [baseline (body)
        mutations [[:packages #(assoc % :semantic-composition/packages {:package/a {:package-root "sha256:changed"}})]
                   [:capabilities #(assoc % :semantic-composition/capabilities {[:kind/a :cap/a] {:version 2}})]
                   [:dependencies #(assoc % :semantic-composition/dependencies [{:from [:kind/a :cap/a] :to [:kind/b :cap/b]}])]
                   [:policy-bindings #(assoc % :semantic-composition/policy-bindings {:policy/a :changed})]
                   [:provider-identity #(assoc % :semantic-composition/provider-package-roots #{"sha256:provider-b"})]
                   [:modules #(assoc % :semantic-composition/action-modules [:action/changed])]]]
    (doseq [[label mutation] mutations]
      (testing (name label)
        (is (thrown? clojure.lang.ExceptionInfo
                     (semantic/verify-portable! (mutation baseline))))))))

(deftest portable-verifier-rejects-closed-shape-and-version-attacks
  (let [baseline (body)]
    (doseq [candidate [(assoc baseline :attacker/unknown true)
                       (dissoc baseline :semantic-composition/packages)
                       (assoc baseline :semantic-composition/version 999)]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (semantic/verify-portable! candidate))))))

(deftest portable-semantic-composition-is-closed-form
  (let [portable (body)
        identity-fields [:semantic-composition/packages
                         :semantic-composition/capabilities
                         :semantic-composition/dependencies
                         :semantic-composition/policy-bindings
                         :semantic-composition/action-modules
                         :semantic-composition/resolution-root]
        verified (semantic/verify-portable! portable)]
    (testing "verification is self-contained and canonical"
      (is (= portable verified))
      (is (= portable
             (edn/read-string (pr-str (semantic/verify-portable! portable)))))
      (with-redefs [resolution/resolve-requested
                    (fn [& _]
                      (throw (ex-info "ambient resolution must not run" {})))]
        (is (= portable (semantic/verify-portable! portable)))))
    (testing "no identity-bearing field may be omitted or ambiently recovered"
      (doseq [field identity-fields]
        (is (thrown? clojure.lang.ExceptionInfo
                     (semantic/verify-portable! (dissoc portable field)))
            (str "missing " field " must fail without lookup"))))))

(deftest body-a-with-declared-root-b-cannot-authenticate
  (let [a (body)
        b (-> (body)
              (assoc :semantic-composition/policy-bindings {:policy/b :different})
              (assoc :semantic-composition/root "sha256:attacker-root"))]
    ;; Both an outer caller and the nested declaration could agree on B; the
    ;; semantic owner still rejects body A or B unless its own projection derives B.
    (is (thrown? clojure.lang.ExceptionInfo (semantic/verify-portable! b)))
    (is (not= (:semantic-composition/root a) (:semantic-composition/root b)))))
