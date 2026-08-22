(ns resolver-sim.protocols.sew.authorised-effect-correlation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.authorised-effect-correlation :as adapter]
            [resolver-sim.protocols.sew.force-authorisation-test]
            [resolver-sim.benchmark.researcher-force-authorisation :as researcher-fa]
            [resolver-sim.assurance.three-member-authority :as governed-authority]
            [resolver-sim.extensions.force-authorisation :as force-extension]
            [resolver-sim.io.content-addressed-store :as store])
  (:import [java.nio.file Files]))

(defn- test-helper [symbol]
  (var-get (ns-resolve 'resolver-sim.protocols.sew.force-authorisation-test symbol)))

(defn- stub-governed-authorisation
  "Sew wiring tests stub the governed three-member evaluation (see
   force-authorisation-test's consensus tests) and exercise signature
   authenticity separately in the researcher integration suite."
  [body]
  (with-redefs [researcher-fa/verify-decision-signatures
                (fn [_ _] {:valid? true :results []})
                governed-authority/evaluate-governed-authority
                (fn [& _]
                  {:authority-status :authorised
                   :governance-root (str "sha256:" (apply str (take 64 (cycle "7"))))})]
    (body)))

(def exec-ctx
  "Executor context on the legacy compatibility path (see
   resolver-sim.protocols.sew's force-authorisation activation docs)."
  {:agent-index {"exec" {:address "0xResolver"}}
   :force-authorisation/allow-local-compatibility? true
   :extension-map (force-extension/install (force-extension/install-governed-authority {}))})

(deftest adapter-derives-and-persists-the-consensus-bound-effect-correlation
  (let [world0 ((test-helper 'disputed-world))
        fixture ((test-helper 'consensus-grant-fixture) world0)
        grant (stub-governed-authorisation
               (fn []
                 (sew/apply-action (:context fixture) world0 (:event fixture))))
        auth-id (get-in grant [:extra :authorization/id])
        execution (sew/apply-action exec-ctx
                                    (:world grant)
                                    {:seq 1 :time 1000 :agent "exec"
                                     :action "execute-force-authorised-action"
                                     :params {:workflow-id 0 :authorization-id auth-id :is-release true}})
        adjustment (last (get-in execution [:world :held-adjustments]))
        backend (store/create-store
                 (str (Files/createTempDirectory "resolver-sim-effect-correlation-"
                                                 (make-array java.nio.file.attribute.FileAttribute 0))))
        result (adapter/persist-authorised-held-effect
                (:world execution)
                {:public-authorisation/id auth-id
                 :held-adjustment/id (:held-adjustment/id adjustment)
                 :reservation-registry (:registry fixture)
                 :artifact-store backend})]
    (is (:ok grant))
    (is (:ok execution))
    (is (= :created (get-in result [:persistence :status])))
    (is (= (:research-assignment/hash (:assignment fixture))
           (get-in result [:correlation :research-assignment/hash])))
    (is (= (:artifact/hash (get-in execution [:world :held-artifacts (:held-adjustment/id adjustment)]))
           (get-in result [:correlation :effect/artifact-hash])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (adapter/persist-authorised-held-effect
                  (:world execution)
                  {:public-authorisation/id auth-id
                   :held-adjustment/id (:held-adjustment/id adjustment)
                   :reservation/hash "sha256:wrong"
                   :reservation-registry (:registry fixture)
                   :artifact-store backend})))))

(deftest effect-correlation-rejects-missing-grant
  (let [world0 ((test-helper 'disputed-world))
        fixture ((test-helper 'consensus-grant-fixture) world0)
        grant (stub-governed-authorisation
               (fn []
                 (sew/apply-action (:context fixture) world0 (:event fixture))))
        execution (sew/apply-action exec-ctx
                                    (:world grant)
                                    {:seq 1 :time 1000 :agent "exec"
                                     :action "execute-force-authorised-action"
                                     :params {:workflow-id 0 :authorization-id (get-in grant [:extra :authorization/id])
                                              :is-release true}})
        adjustment (last (get-in execution [:world :held-adjustments]))
        backend (store/create-store
                 (str (Files/createTempDirectory "resolver-sim-effect-correlation-"
                                                 (make-array java.nio.file.attribute.FileAttribute 0))))
        run (fn [opts]
              (try
                (adapter/persist-authorised-held-effect (:world execution) opts)
                :no-throw
                (catch clojure.lang.ExceptionInfo e
                  (:reason (ex-data e)))))]
    (testing "unknown public authorisation is rejected"
      (is (= :public-authorisation-not-found
             (run {:public-authorisation/id "no-such-grant"
                   :held-adjustment/id (:held-adjustment/id adjustment)
                   :reservation-registry (:registry fixture)
                   :artifact-store backend}))))
    (testing "an unknown held adjustment is rejected"
      (is (= :held-adjustment-not-found
             (run {:public-authorisation/id (get-in grant [:extra :authorization/id])
                   :held-adjustment/id "no-such-adjustment"
                   :reservation-registry (:registry fixture)
                   :artifact-store backend}))))))
