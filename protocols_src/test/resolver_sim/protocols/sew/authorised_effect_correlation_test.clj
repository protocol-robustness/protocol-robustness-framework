(ns resolver-sim.protocols.sew.authorised-effect-correlation-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.authorised-effect-correlation :as adapter]
            [resolver-sim.protocols.sew.force-authorisation-test]
            [resolver-sim.benchmark.researcher-force-authorisation :as researcher-fa]
            [resolver-sim.io.content-addressed-store :as store])
  (:import [java.nio.file Files]))

(defn- test-helper [symbol]
  (var-get (ns-resolve 'resolver-sim.protocols.sew.force-authorisation-test symbol)))

(deftest adapter-derives-and-persists-the-consensus-bound-effect-correlation
  (let [world0 ((test-helper 'disputed-world))
        fixture ((test-helper 'consensus-grant-fixture) world0)
        grant (with-redefs [researcher-fa/verify-decision-signatures
                            (fn [_ _] {:valid? true :results []})]
                (sew/apply-action (:context fixture) world0 (:event fixture)))
        auth-id (get-in grant [:extra :authorization/id])
        execution (sew/apply-action {:agent-index {"exec" {:address "0xResolver"}}}
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
    (is (= :stored (get-in result [:persistence :status])))
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
