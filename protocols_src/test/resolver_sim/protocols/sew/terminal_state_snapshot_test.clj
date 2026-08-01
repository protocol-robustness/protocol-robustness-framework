(ns resolver-sim.protocols.sew.terminal-state-snapshot-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.held-custody-test-env :as env]
            [resolver-sim.protocols.sew.terminal-state-snapshot :as snapshot]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.evidence.capture :as capture]
            [resolver-sim.evidence.staged-capture :as staged]
            [resolver-sim.io.content-addressed-store :as store])
  (:import [java.nio.file Files]))

(deftest snapshot-is-a-strict-replay-verifiable-held-state-projection
  (let [{:keys [world]} (env/public-disputed-world)
        built (snapshot/build-terminal-state-snapshot world)
        adjustment (last (:held-adjustments built))
        artifact (get-in built [:held-artifacts (:held-adjustment/id adjustment)])
        effect (snapshot/resolve-held-effect
                built
                {:held-adjustment/id (:held-adjustment/id adjustment)
                 :held-custody/artifact-hash (:artifact/hash artifact)})]
    (is (snapshot/valid-terminal-state-snapshot? built))
    (is (:effect-present? effect))
    (is (:artifact-present? effect))
    (is (:artifact-hash-matches? effect))
    (is (not= (:snapshot/hash built)
              (:snapshot/hash (snapshot/build-terminal-state-snapshot
                               (assoc-in world [:total-held :USDC] 1)))))))

(deftest prepared-public-force-authorised-effect-survives-store-reopen
  (let [{:keys [world context workflow-id]} (env/public-disputed-world)
        grant (sew/apply-action context world
                                {:seq 2 :time 1000 :agent "gov"
                                 :action "grant-force-authorisation"
                                 :params {:workflow-id workflow-id
                                          :reason :resolver-overcapacity}})
        auth-id (get-in grant [:extra :authorization/id])
        session (staged/begin-capture-attempt {:attempt-id :attempt/prepared-effect})
        execution (binding [capture/*capture-event-evidence!* (staged/staged-capture-fn session)]
                    (sew/apply-action context (:world grant)
                                      {:seq 3 :time 1000 :agent "executor"
                                       :action "execute-force-authorised-action"
                                       :params {:workflow-id workflow-id
                                                :authorization-id auth-id
                                                :is-release true}}))
        candidate (:world execution)
        capture-artifact (staged/seal-capture! session)
        terminal-snapshot (snapshot/build-terminal-state-snapshot candidate)
        root (str (Files/createTempDirectory "resolver-sim-prepared-effect-"
                                             (make-array java.nio.file.attribute.FileAttribute 0)))
        backend (store/create-store root)
        _ (store/put-if-absent! backend {:hash-reference (:staged-evidence/root capture-artifact)
                                         :artifact capture-artifact
                                         :verify staged/valid-staged-capture?})
        _ (store/put-if-absent! backend {:hash-reference (:snapshot/hash terminal-snapshot)
                                         :artifact terminal-snapshot
                                         :verify snapshot/valid-terminal-state-snapshot?})
        reopened (store/create-store root)
        resolved-capture (store/resolve-artifact reopened (:staged-evidence/root capture-artifact))
        resolved-snapshot (store/resolve-artifact reopened (:snapshot/hash terminal-snapshot))
        adjustment (last (:held-adjustments resolved-snapshot))
        artifact (get-in resolved-snapshot [:held-artifacts (:held-adjustment/id adjustment)])
        effect (snapshot/resolve-held-effect
                resolved-snapshot
                {:held-adjustment/id (:held-adjustment/id adjustment)
                 :held-custody/artifact-hash (:artifact/hash artifact)})]
    (is (:ok grant))
    (is (:ok execution))
    (is (staged/valid-staged-capture? resolved-capture))
    (is (snapshot/valid-terminal-state-snapshot? resolved-snapshot))
    (is (:effect-present? effect))
    (is (:artifact-hash-matches? effect))
    (is (every? #(= :pass (:status %))
                (custody/held-custody-closed-form-checks (vals (:held-artifacts resolved-snapshot)))))
    (is (not (snapshot/valid-terminal-state-snapshot?
              (assoc-in resolved-snapshot [:held-adjustments 1 :amount] 999))))
    (is (not (snapshot/valid-terminal-state-snapshot?
              (assoc-in resolved-snapshot [:held-artifacts (:held-adjustment/id adjustment) :artifact/hash]
                        "sha256:tampered"))))
    (is (not (:effect-present?
              (snapshot/resolve-held-effect resolved-snapshot
                                            {:held-adjustment/id "other-effect"
                                             :held-custody/artifact-hash (:artifact/hash artifact)}))))
    (is (not (:artifact-hash-matches?
              (snapshot/resolve-held-effect resolved-snapshot
                                            {:held-adjustment/id (:held-adjustment/id adjustment)
                                             :held-custody/artifact-hash "sha256:other"}))))))

(deftest snapshot-rejects-runtime-values-and-reconciliation-tampering
  (let [{:keys [world]} (env/public-disputed-world)
        built (snapshot/build-terminal-state-snapshot world)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runtime"
                          (snapshot/build-terminal-state-snapshot
                           (assoc world :held-adjustments [(fn [] :not-persistable)]))))
    (is (contains? (set (:errors (snapshot/validate-terminal-state-snapshot
                                  (assoc built :total-held {}))))
                   :snapshot-hash-mismatch))
    (is (contains? (set (:errors (snapshot/validate-terminal-state-snapshot
                                  (assoc built :snapshot/hash "sha256:bad"))))
                   :snapshot-hash-mismatch))))
