(ns resolver-sim.cancellation.action-boundary-test
  "M1 acceptance: the three historical facet-as-sequence misprojection vectors
   from the clean-room exploration remain valid GENERIC compositions there, but
   are rejected wherever a genuine cancellation action is required. Reordering
   or duplicating their pseudo-facets changes nothing about the refusal."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.cancellation.action-boundary :as boundary]
            [resolver-sim.cancellation.operation :as operation]))

;; Copied verbatim (as data) from
;; prf-clean-room resources/exploration/cancellation-provisional-v1.edn —
;; :hist-misprojection/* compact forms. No clean-room dependency; these are
;; ordinary maps that the generic V1 sequence encoder roots validly THERE.
(def ^:private historical-misprojection-compacts
  {:sew-party-cancel-pending
   {:composition/version 1 :composition/family :composition-sequence
    :composition/dimensions {:purpose :canonical-cancellation/action
                             :components [:operation/party-cancel
                                          :domain/sew
                                          :required-state/pending]}}
   :dispute-case
   {:composition/version 1 :composition/family :composition-sequence
    :composition/dimensions {:purpose :canonical-cancellation/action
                             :components [:operation/party-cancel
                                          :domain/sew
                                          :state/disputed
                                          :result/transfer-not-pending]}}
   :cancel-and-terminate-facets
   {:composition/version 1 :composition/family :composition-sequence
    :composition/dimensions {:purpose :canonical-cancellation/action
                             :components [:action/cancel-and-terminate
                                          :domain/command-lineage
                                          :effect/terminal-receipt]}}})

(deftest historical-misprojection-vectors-are-refused-as-actions
  (doseq [[label compact] historical-misprojection-compacts]
    (testing label
      (let [verdict (boundary/action-admit compact)]
        (is (false? (:boundary/admitted? verdict)))
        (is (= :boundary/descriptive-composition-not-action (:boundary/reason verdict)))
        (is (= :canonical-cancellation/action
               (get-in verdict [:boundary/details :composition/purpose])))
        (is (nil? (:boundary/operation-root verdict)) "no operation root is produced")))))

(deftest reordered-and-duplicated-pseudo-facets-equally-refused
  (let [base (:cancel-and-terminate-facets historical-misprojection-compacts)
        components (get-in base [:composition/dimensions :components])
        variants [(assoc-in base [:composition/dimensions :components] (vec (reverse components)))
                  (assoc-in base [:composition/dimensions :components]
                            (into [(first components)] components))]]
    (doseq [v variants]
      (let [verdict (boundary/action-admit v)]
        (is (false? (:boundary/admitted? verdict)))
        (is (= :boundary/descriptive-composition-not-action (:boundary/reason verdict)))))))

(deftest unrecognized-shapes-fail-closed
  (is (= :boundary/unrecognized-shape (:boundary/reason (boundary/action-admit "not a map"))))
  (is (= :boundary/unrecognized-shape
         (:boundary/reason (boundary/action-admit {:operation/schema "some-other-schema.v1"})))
      "an unknown schema string is refused, not guessed"))

(deftest incomplete-operation-statements-are-refused-with-detail
  (let [partial {:operation/schema operation/schema-version
                 :operation/purpose :cancellation/execution}
        verdict (boundary/action-admit partial)]
    (is (false? (:boundary/admitted? verdict)))
    (is (= :boundary/operation-incomplete (:boundary/reason verdict)))
    (is (seq (:missing-fields (:boundary/details verdict))))
    ;; completeness is delegated to the existing authoritative validator:
    (is (= (operation/missing-operation-fields partial)
           (:missing-fields (:boundary/details verdict))))))

(deftest complete-ordinary-operation-passes-the-boundary
  ;; Build a genuinely complete cancellation-operation.v1 statement using the
  ;; same recipe as resolver-sim.cancellation.ordinary-admission-test.
  (let [sha (fn [n] (format "sha256:%064x" n))
        s0 {:snapshot/schema "sew-escrow-state-snapshot.v1"
            :workflow/id "escrow-b" :escrow/sender "alice" :escrow/recipient "bob"
            :escrow/state :pending :sender/cancellation-status :none
            :recipient/cancellation-status :agree-to-cancel}
        p0 {:policy/schema "sew-party-cancellation-policy.v1"
            :policy/can-cancel? true :policy/unilateral-cancel? false}
        bare {:operation/schema operation/schema-version
              :operation/purpose :cancellation/execution
              :event/id "cancel-b" :protocol/id :sew
              :target {:kind :sew/escrow :id "escrow-b"
                       :snapshot-root (sha 11) :state-before-root (sha 12)
                       :lifecycle-head-root (sha 13)}
              :request {:caller/id "alice" :party :sender :action :cancel :requested-at 1}
              :policy {:id :sew/party-cancellation :root (sha 14)}
              :evaluation {:inputs-root (sha 15) :base-decision :ordinary
                           :decision {:derived-effects-root (sha 20)}}
              :preconditions/root (sha 16)
              :authorization {:kind :ordinary :root (sha 17)}
              :execution {:status :applied :effects-root (sha 18) :state-after-root (sha 19)}}
        ;; roots must be QUALIFIED sha256 refs; the sha helper satisfies that.
        op0 bare
        op0 (assoc-in op0 [:target :snapshot-root]
                      (format "sha256:%064x" 111))
        op (assoc op0 :operation/root (operation/operation-root (dissoc op0 :operation/root)))
        verdict (boundary/action-admit op)]
    (is (true? (:boundary/admitted? verdict))
        (str "a complete cancellation-operation.v1 statement is the one admitted shape; "
             "details: " (:boundary/details verdict)))
    (is (= "cancellation-operation.v1" (:boundary/via verdict)))
    (is (= (:operation/root op) (:boundary/operation-root verdict)))))
