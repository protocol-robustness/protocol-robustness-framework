(ns resolver-sim.server.session-test
  "Unit tests for the Phase 2 gRPC session store.

   Tests run against the pure session logic only — no gRPC server is started.
   All invariant enforcement flows through replay/process-step which is already
   tested in replay_test.clj; these tests focus on session lifecycle and
   thread-safety contracts."
  (:require [clojure.test                    :refer [deftest is testing run-tests]]
            [resolver-sim.server.session     :as session])
  (:import [java.util.concurrent Executors CountDownLatch]))

;; ---------------------------------------------------------------------------
;; Shared fixtures
;; ---------------------------------------------------------------------------

(def ^:private agents
  [{:id "buyer"    :address "0xbuyer"    :type "honest"}
   {:id "seller"   :address "0xseller"   :type "honest"}
   {:id "resolver" :address "0xresolver" :type "resolver"}])

(def ^:private params {:resolver-fee-bps 50})

(defn- fresh-sid [] (str (java.util.UUID/randomUUID)))

(defn- create! [sid]
  (session/create-session! sid agents params 1000))

(defn- create-step! [sid event]
  (session/step-session! sid event))

(def ^:private create-event
  {:seq 0 :time 1000 :agent "buyer" :action "create_escrow"
   :params {:token "USDC" :to "0xseller" :amount 500}})

(def ^:private release-event
  {:seq 1 :time 1060 :agent "buyer" :action "release"
   :params {:workflow-id 0}})

;; ---------------------------------------------------------------------------
;; create-session!
;; ---------------------------------------------------------------------------

(deftest test-create-session-ok
  (session/with-fresh-sessions
    (let [sid (fresh-sid)
          r   (create! sid)]
      (is (:ok r))
      (is (= sid (:session-id r)))
      (session/destroy-session! sid))))

(deftest test-create-session-duplicate-rejected
  (session/with-fresh-sessions
    (let [sid (fresh-sid)]
      (create! sid)
      (let [r2 (create! sid)]
        (is (not (:ok r2)))
        (is (= :session-already-exists (:error r2))))
      (session/destroy-session! sid))))

(deftest test-create-session-duplicate-agent-ids-rejected
  (session/with-fresh-sessions
    (let [sid  (fresh-sid)
          bad  [{:id "buyer" :address "0xbuyer" :type "honest"}
                {:id "buyer" :address "0xother" :type "honest"}]
          r    (session/create-session! sid bad {} 1000)]
      (is (not (:ok r)))
      (is (= :duplicate-agent-ids (:error r))))))

(deftest test-create-session-duplicate-agent-addresses-rejected
  (session/with-fresh-sessions
    (let [sid (fresh-sid)
          bad [{:id "buyer"  :address "0xsame" :type "honest"}
               {:id "seller" :address "0xsame" :type "honest"}]
          r   (session/create-session! sid bad {} 1000)]
      (is (not (:ok r)))
      (is (= :duplicate-agent-addresses (:error r))))))

(deftest test-create-session-no-agents-rejected
  (session/with-fresh-sessions
    (let [sid (fresh-sid)
          r   (session/create-session! sid [] {} 1000)]
      (is (not (:ok r)))
      (is (= :no-agents (:error r))))))

(deftest test-create-session-string-keys-accepted
  "Agents with string map keys (from JSON parse) must be normalised."
  (session/with-fresh-sessions
    (let [sid    (fresh-sid)
          agents [{"id" "buyer" "address" "0xbuyer" "type" "honest"}]
          r      (session/create-session! sid agents {} 1000)]
      (is (:ok r))
      (session/destroy-session! sid))))

;; ---------------------------------------------------------------------------
;; step-session!
;; ---------------------------------------------------------------------------

(deftest test-step-unknown-session
  (session/with-fresh-sessions
    (let [r (session/step-session! "does-not-exist" create-event)]
      (is (not (:ok r)))
      (is (= :session-not-found (:error r))))))

(deftest test-step-create-escrow-ok
  (session/with-fresh-sessions
    (let [sid (fresh-sid)]
      (create! sid)
      (let [r (session/step-session! sid create-event)]
        (is (:ok r))
        (is (= :ok (get-in r [:step :trace-entry :result])))
        (is (= 0   (get-in r [:step :trace-entry :extra :workflow-id]))))
      (session/destroy-session! sid))))

(deftest test-step-advances-world-state
  (session/with-fresh-sessions
    (let [sid (fresh-sid)]
      (create! sid)
      (session/step-session! sid create-event)
      (let [info (session/session-info sid)]
        (is (= 1 (:escrow-count info))))
      (session/destroy-session! sid))))

(deftest test-step-increments-step-count
  (session/with-fresh-sessions
    (let [sid (fresh-sid)]
      (create! sid)
      (session/step-session! sid create-event)
      (session/step-session! sid release-event)
      (is (= 2 (:step-count (session/session-info sid))))
      (session/destroy-session! sid))))

(deftest test-step-rejected-action-does-not-halt
  "Unknown-agent revert is non-fatal — session remains alive."
  (session/with-fresh-sessions
    (let [sid   (fresh-sid)]
      (create! sid)
      (let [bad-event {:seq 0 :time 1000 :agent "ghost" :action "create_escrow"
                       :params {:token "USDC" :to "0xseller" :amount 100}}
            r         (session/step-session! sid bad-event)]
        (is (:ok r))                            ; step call succeeded
        (is (= :rejected (get-in r [:step :trace-entry :result])))
        (is (not (get-in r [:step :halted?])))   ; not halted
        (is (session/session-exists? sid)))      ; session still live
      (session/destroy-session! sid))))

(deftest test-step-full-happy-path-release
  (session/with-fresh-sessions
    (let [sid (fresh-sid)]
      (create! sid)
      (session/step-session! sid create-event)
      (let [r (session/step-session! sid release-event)]
        (is (:ok r))
        (is (= :ok (get-in r [:step :trace-entry :result]))))
      (session/destroy-session! sid))))

;; ---------------------------------------------------------------------------
;; destroy-session!
;; ---------------------------------------------------------------------------

(deftest test-destroy-session-ok
  (session/with-fresh-sessions
    (let [sid (fresh-sid)]
      (create! sid)
      (let [r (session/destroy-session! sid)]
        (is (:ok r)))
      (is (not (session/session-exists? sid))))))

(deftest test-destroy-unknown-session
  (session/with-fresh-sessions
    (let [r (session/destroy-session! "no-such-session")]
      (is (not (:ok r)))
      (is (= :session-not-found (:error r))))))

;; ---------------------------------------------------------------------------
;; session-info
;; ---------------------------------------------------------------------------

(deftest test-session-info-unknown-returns-nil
  (session/with-fresh-sessions
    (is (nil? (session/session-info "nonexistent")))))

(deftest test-session-info-reflects-world
  (session/with-fresh-sessions
    (let [sid (fresh-sid)]
      (create! sid)
      (let [info (session/session-info sid)]
        (is (= 1000 (:block-time info)))
        (is (= 0    (:escrow-count info)))
        (is (= 0    (:step-count info))))
      (session/step-session! sid create-event)
      (let [info2 (session/session-info sid)]
        (is (= 1    (:escrow-count info2)))
        (is (= 1    (:step-count info2))))
      (session/destroy-session! sid))))

;; ---------------------------------------------------------------------------
;; active-sessions
;; ---------------------------------------------------------------------------

(deftest test-active-sessions-lists-live-sessions
  (session/with-fresh-sessions
    (let [sid1 (fresh-sid)
          sid2 (fresh-sid)]
      (create! sid1)
      (create! sid2)
      (let [active (set (session/active-sessions))]
        (is (contains? active sid1))
        (is (contains? active sid2)))
      (session/destroy-session! sid1)
      (session/destroy-session! sid2))))

;; ---------------------------------------------------------------------------
;; Thread-safety: concurrent steps on the same session
;; ---------------------------------------------------------------------------

(deftest test-concurrent-steps-on-same-session-are-serialised
  "Ten threads each attempt create_escrow on the same session.
   Due to CAS-based optimistic concurrency control, concurrent steps are
   serialised through compare-and-set retry. Each thread uses the same
   :time (1000) to avoid time-regression rejections on retry, and a unique
   token so each event changes world state (no false no-ops). All 10 steps
   must complete successfully and step-count must reach 10.
   The session must remain consistent after all threads complete."
  (let [sid     (fresh-sid)
        n       10
        latch   (CountDownLatch. n)
        pool    (Executors/newFixedThreadPool n)
        results (atom [])]
    (create! sid)
    (dotimes [i n]
      (.submit pool
               (reify Runnable
                 (run [_]
                   (.countDown latch)
                   (.await latch)            ; start all threads simultaneously
                   (let [evt {:seq i :time 1000 :agent "buyer"
                              :action "create_escrow"
                              :params {:token (str "USDC" i) :to "0xseller" :amount 100}}
                         r   (session/step-session! sid evt)]
                     (swap! results conj r))))))
    (.shutdown pool)
    (.awaitTermination pool 10 java.util.concurrent.TimeUnit/SECONDS)
    ;; All 10 steps must complete without error
    (is (= n (count @results)))
    (is (every? :ok @results))
    ;; Session must still be alive and consistent
    (is (session/session-exists? sid))
    (let [info (session/session-info sid)]
      (is (= n (:step-count info))))
    (session/destroy-session! sid)))
