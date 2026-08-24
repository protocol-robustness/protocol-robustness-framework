(ns resolver-sim.cancellation.statement-boundary-test
  "Statement-boundary tests. Two concerns:

   1. The historical facet-as-sequence misprojection vectors (clean-room
      exploration, :hist-misprojection/*) remain valid GENERIC compositions in
      the V1 encoder but are refused here — wherever an operation STATEMENT is
      required. Reordered/duplicated pseudo-facets change nothing.

   2. Assurance vocabulary: admitted candidates are classified as operation
      STATEMENTS with structural-only assurance and explicit non-claims.
      Acceptance never implies verified execution, authority, admissibility,
      or transition."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.cancellation.operation :as operation]
            [resolver-sim.cancellation.statement-boundary :as boundary]))

;; Copied verbatim (as data) from
;; prf-clean-room resources/exploration/cancellation-provisional-v1.edn —
;; :hist-misprojection/* compact forms.
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

(deftest misprojection-vectors-are-refused-as-operation-statements
  (doseq [[label compact] historical-misprojection-compacts]
    (testing label
      (let [verdict (boundary/statement-verdict compact)]
        (is (false? (:boundary/admitted? verdict)))
        (is (= :boundary/descriptive-composition-not-operation-statement
               (:boundary/reason verdict)))
        (is (= :canonical-cancellation/action
               (get-in verdict [:boundary/details :composition/purpose])))
        (is (nil? (:boundary/statement-root verdict)))))))

(deftest reordered-and-duplicated-pseudo-facets-equally-refused
  (let [base (:cancel-and-terminate-facets historical-misprojection-compacts)
        components (get-in base [:composition/dimensions :components])]
    (doseq [v [(assoc-in base [:composition/dimensions :components] (vec (reverse components)))
               (assoc-in base [:composition/dimensions :components]
                         (into [(first components)] components))]]
      (is (= :boundary/descriptive-composition-not-operation-statement
             (:boundary/reason (boundary/statement-verdict v)))))))

(deftest unrecognized-shapes-fail-closed
  (is (= :boundary/unrecognized-shape (:boundary/reason (boundary/statement-verdict "not a map"))))
  (is (= :boundary/unrecognized-shape
         (:boundary/reason (boundary/statement-verdict {:operation/schema "some-other-schema.v1"})))))

(deftest incomplete-statements-are-refused-with-detail
  (let [partial {:operation/schema operation/schema-version
                 :operation/purpose :cancellation/execution}
        verdict (boundary/statement-verdict partial)]
    (is (= :boundary/statement-incomplete (:boundary/reason verdict)))
    (is (= (operation/missing-operation-fields partial)
           (:missing-fields (:boundary/details verdict))))
    ;; completeness is delegated to the existing authoritative validator:
    (is (= (operation/invalid-operation-references partial)
           (:invalid-references (:boundary/details verdict))))))

(defn- complete-statement-fixture []
  ;; Same recipe as resolver-sim.cancellation.ordinary-admission-test/request:
  ;; placeholder qualified roots satisfy reference-syntax completeness; the
  ;; statement root is computed over the remaining fields.
  (let [sha (fn [n] (format "sha256:%064x" n))
        op0 {:operation/schema operation/schema-version
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
             :execution {:status :applied :effects-root (sha 18) :state-after-root (sha 19)}}]
    (assoc op0 :operation/root (operation/operation-root (dissoc op0 :operation/root)))))

(deftest executed-statement-shape-is-classified-as-operation-statement-only
  (let [verdict (boundary/statement-verdict (complete-statement-fixture))]
    (is (true? (boundary/statement-structurally-accepted? verdict)))
    (is (= :operation-statement (:boundary/classification verdict)))
    (is (= "cancellation-operation.v1" (:boundary/via verdict)))
    (is (= :structural-shape-only (:assurance verdict)))
    ;; explicit non-claims on every acceptance:
    (is (= #{:no-execution-verified :no-authority-verified :no-admissibility-claimed
             :no-transition-verified :no-state-change-verified}
           (:non-claims verdict)))
    ;; the fixture really is applied-shaped, underscoring the point:
    (is (= :applied (get-in (complete-statement-fixture) [:execution :status]))
        "even :status :applied earns only structural classification")))

(deftest unknown-envelope-fields-fail-closed
  ;; The operation validator checks presence and reference syntax but does NOT
  ;; reject unknown fields; the boundary owns that closure.
  (let [decorated (assoc (complete-statement-fixture) :budget 100)
        verdict (boundary/statement-verdict decorated)]
    (is (false? (:boundary/admitted? verdict)))
    (is (= :boundary/unknown-statement-fields (:boundary/reason verdict)))
    (is (= [:budget] (:unknown (:boundary/details verdict)))))
  ;; a second, differently-named intruder is equally refused:
  (let [decorated (assoc (complete-statement-fixture) :execution-note "ok?")
        verdict (boundary/statement-verdict decorated)]
    (is (= :boundary/unknown-statement-fields (:boundary/reason verdict)))
    (is (= [:execution-note] (:unknown (:boundary/details verdict))))))

(deftest closed-envelope-is-exact-not-subset
  ;; every legitimate key passes; nothing extra is tolerated:
  (let [fixture (complete-statement-fixture)
        verdict (boundary/statement-verdict fixture)]
    (is (true? (boundary/statement-structurally-accepted? verdict))
        (str "control accepted; details: " (:boundary/details verdict)))
    (is (= (set (keys fixture))
           #{:operation/schema :operation/purpose :event/id :protocol/id
             :target :request :policy :evaluation :preconditions/root
             :authorization :execution :operation/root}))))
