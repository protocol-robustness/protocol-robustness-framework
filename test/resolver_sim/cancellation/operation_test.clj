(ns resolver-sim.cancellation.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.cancellation.operation :as cancellation]))

(defn applied-operation []
  {:operation/schema cancellation/schema-version
   :operation/purpose :cancellation/execution
   :event/id "cancel:escrow-42:request-7"
   :protocol/id :sew
   :target {:kind :sew/escrow :id "escrow-42"
            :snapshot-root "sha256:target" :state-before-root "sha256:before"
            :lifecycle-head-root "sha256:head"}
   :request {:caller/id "0xAlice" :action :cancel :reason :mutual-consent
             :requested-at 1735689600}
   :policy {:id :sew/cancellation-policy.v1 :root "sha256:policy"}
   :evaluation {:inputs-root "sha256:inputs"
                :context {:dispute-status :not-disputed
                          :window {:classification :open :opens-at 1735689600
                                   :closes-at 1735776000}}
                :base-decision {:classification :authorized}
                :decision {:classification :authorized :derived-action :cancel
                           :derived-effects-root "sha256:effects"}}
   :authorization {:kind :ordinary :root "sha256:authorization"}
   :execution {:status :applied :effects-root "sha256:effects"
               :state-after-root "sha256:after"}
   :conflict/consumption-key "sha256:consume"
   :previous-event-root "sha256:previous"})

(deftest applied-cancellation-is-a-typed-commitment
  (let [operation (applied-operation)
        root (cancellation/operation-root operation)]
    (is (cancellation/operation-complete? operation))
    (is (re-matches #"sha256:[0-9a-f]{64}" root))
    (is (cancellation/operation-root-valid? (assoc operation :operation/root root)))
    (is (not= root (cancellation/operation-root
                    (assoc-in operation [:target :snapshot-root] "sha256:other"))))))

(deftest cancellation-binding-uses-event-identity-and-derived-dispute-context
  (let [operation (applied-operation)
        root (cancellation/cancellation-binding-root operation)
        disputed (assoc-in operation [:evaluation :context :dispute-status] :active)
        disputed-statuses #{:active :disputed}]
    (is (re-matches #"sha256:[0-9a-f]{64}" root))
    (is (false? (:cancellation/during-dispute? (cancellation/cancellation-binding operation disputed-statuses))))
    (is (true? (:cancellation/during-dispute? (cancellation/cancellation-binding disputed disputed-statuses))))
    (is (not= root (cancellation/cancellation-binding-root
                    (assoc operation :event/id "cancel:escrow-42:request-8"))))
    (is (cancellation/cancellation-binding-valid?
         (assoc operation :cancellation/binding-root root)))))

(deftest applied-cancellation-requires-exact-derived-effects
  (let [operation (applied-operation)]
    (is (not (cancellation/operation-complete?
              (assoc-in operation [:execution :effects-root] "sha256:other"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cancellation/operation-root
                  (assoc-in operation [:execution :effects-root] "sha256:other"))))))

(deftest exceptional-authorization-is-not-an-opaque-classification
  (let [operation (-> (applied-operation)
                      (assoc-in [:evaluation :base-decision :classification] :forbidden)
                      (assoc-in [:evaluation :decision :classification] :authorized-by-override)
                      (assoc-in [:authorization :kind] :override))]
    (is (cancellation/operation-complete? operation))
    (is (not (cancellation/operation-complete?
              (assoc-in operation [:authorization :kind] :ordinary))))))

(deftest forbidden-decision-cannot-produce-an-applied-operation
  (let [operation (-> (applied-operation)
                      (assoc-in [:evaluation :base-decision :classification] :forbidden)
                      (assoc-in [:evaluation :decision :classification] :forbidden))]
    (is (not (cancellation/operation-complete? operation)))))

(deftest ordinary-authorization-cannot-be-labelled-as-an-override
  (let [operation (assoc-in (applied-operation) [:authorization :kind] :override)]
    (is (not (cancellation/operation-complete? operation)))))

(deftest failed-attempts-do-not-fabricate-execution-evidence
  (let [attempt (-> (applied-operation)
                    (dissoc :authorization :conflict/consumption-key :previous-event-root)
                    (assoc :execution {:status :rejected})
                    (assoc-in [:evaluation :base-decision :classification] :forbidden)
                    (update :evaluation dissoc :decision)
                    (assoc-in [:evaluation :decision]
                              {:classification :forbidden :derived-action :cancel}))]
    (is (cancellation/operation-complete? attempt))
    (is (re-matches #"sha256:[0-9a-f]{64}" (cancellation/operation-root attempt)))))

(deftest effective-decision-valid-rejects-unknown-classification
  (let [operation (-> (applied-operation)
                      (assoc-in [:evaluation :base-decision :classification] :forbidden)
                      (assoc-in [:evaluation :decision :classification] :unknown-classification))]
    (is (not (cancellation/operation-complete? operation)))))

(deftest authorized-base-cannot-be-overridden-to-forbidden-effective
  (let [operation (-> (applied-operation)
                      (assoc-in [:evaluation :base-decision :classification] :authorized)
                      (assoc-in [:evaluation :decision :classification] :forbidden)
                      (assoc-in [:authorization :kind] :override))]
    (is (not (cancellation/operation-complete? operation)))))

(deftest forbidden-base-with-ordinary-authorization-is-not-complete
  (let [operation (-> (applied-operation)
                      (assoc-in [:evaluation :base-decision :classification] :forbidden)
                      (assoc-in [:evaluation :decision :classification] :authorized-by-override)
                      (assoc-in [:authorization :kind] :ordinary))]
    (is (not (cancellation/operation-complete? operation)))))

(deftest authorized-base-cannot-use-authorized-by-override-classification
  (let [operation (-> (applied-operation)
                      (assoc-in [:evaluation :base-decision :classification] :authorized)
                      (assoc-in [:evaluation :decision :classification] :authorized-by-override)
                      (assoc-in [:authorization :kind] :override))]
    (is (not (cancellation/operation-complete? operation)))))

(deftest rejected-forbidden-attempt-with-derived-action-is-auditable
  (let [attempt (-> (applied-operation)
                    (dissoc :authorization :conflict/consumption-key :previous-event-root)
                    (assoc :execution {:status :rejected})
                    (assoc-in [:evaluation :base-decision :classification] :forbidden)
                    (assoc-in [:evaluation :decision]
                              {:classification :forbidden :derived-action :cancel}))]
    (is (cancellation/operation-complete? attempt))
    (is (re-matches #"sha256:[0-9a-f]{64}" (cancellation/operation-root attempt)))))

(deftest evaluation-inputs-are-a-named-map-not-concatenated-roots
  (let [inputs {:schema cancellation/evaluation-inputs-schema-version
                :target-snapshot-root "sha256:target"
                :policy-root "sha256:policy"
                :request-root "sha256:request"}]
    (is (re-matches #"sha256:[0-9a-f]{64}" (cancellation/evaluation-inputs-root inputs)))
    (let [swapped (assoc inputs :policy-root "sha256:request"
                         :request-root "sha256:policy")]
      (is (not= (cancellation/evaluation-inputs-root inputs)
                (cancellation/evaluation-inputs-root swapped))))))

(deftest ordered-derivation-requires-typed-role-bound-components
  (let [components [{:role :target-snapshot :ref/kind :state-snapshot :ref/root "sha256:target"}
                    {:role :policy :ref/kind :policy :ref/root "sha256:policy"}
                    {:role :decision :ref/kind :cancellation-decision :ref/root "sha256:decision"}
                    {:role :authorization :ref/kind :authorization :ref/root "sha256:authorization"}
                    {:role :effects :ref/kind :effect-plan :ref/root "sha256:effects"}
                    {:role :state-after :ref/kind :state-snapshot :ref/root "sha256:after"}]]
    (is (re-matches #"sha256:[0-9a-f]{64}" (cancellation/derivation-root components)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (cancellation/derivation-root
                  (assoc components 1 (assoc (nth components 1) :role :authorization)))))))
