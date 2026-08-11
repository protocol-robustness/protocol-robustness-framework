(ns resolver-sim.resubmission.admission-store-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.resubmission.admission :as admission]
            [resolver-sim.resubmission.admission-store :as store]
            [resolver-sim.resubmission.admission-workflow :as workflow]))

(def family "sha256:FAMILY")
(defn r [c] (str "sha256:" (apply str (repeat 64 c))))

(defn request
  [s id candidate]
  {:concurrency/partition-key (:concurrency/partition-key s)
   :concurrency/snapshot-root (:concurrency/snapshot-root s)
   :concurrency/expected-state-version (:concurrency/expected-state-version s)
   :concurrency/idempotency-key id
   :reservation/candidate-root candidate
   :reservation/validation-root (r "v")
   :reservation/proposed-ordering-root (r "o")})

(defn validation-for [s candidate]
  {:profile-id :strict :profile-version 1
   :checks (mapv (fn [id] {:check/id id :valid? true
                            :validated-against/root (:concurrency/snapshot-root s)
                            :validated-against/version (:concurrency/expected-state-version s)
                            :validated-against/candidate-root candidate})
                 admission/required-check-order)})

(defn finalize-request [s reservation receipt]
  {:concurrency/partition-key (:concurrency/partition-key s)
   :concurrency/expected-state-version (:concurrency/expected-state-version s)
   :reservation/id (:reservation/id reservation)
   :concurrency/fence (:reservation/fence reservation)
   :reservation/candidate-root (:reservation/candidate-root reservation)
   :reservation/validation-root (:reservation/validation-root reservation)
   :reservation/proposed-ordering-root (:reservation/proposed-ordering-root reservation)
   :signing/payload-root (:signing/payload-root (admission/signing-payload reservation))
   :receipt/root receipt
   :authorization/evidence-root receipt})

(deftest validation-is-complete-snapshot-bound-and-deterministic
  (let [checks (mapv (fn [id] {:check/id id :valid? true
                                     :validated-against/root (r "s")
                                     :validated-against/version 7
                                     :validated-against/candidate-root (r "c")})
                     admission/required-check-order)
        v1 (admission/build-validation {:partition-key (admission/partition-key family)
                                        :snapshot-root (r "s") :snapshot-version 7
                                        :candidate-root (r "c")
                                        :profile-id :strict :profile-version 1
                                        :checks (reverse checks)})
        v2 (admission/build-validation {:partition-key (admission/partition-key family)
                                        :snapshot-root (r "s") :snapshot-version 7
                                        :candidate-root (r "c")
                                        :profile-id :strict :profile-version 1
                                        :checks checks})]
    (is (:validation/pass? v1))
    (is (= (:validation/root v1) (:validation/root v2)))
    (is (= admission/required-check-order (mapv :check/id (:validation/checks v1))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incomplete or not snapshot-consistent"
                          (admission/build-validation {:partition-key (admission/partition-key family)
                                                       :snapshot-root (r "s") :snapshot-version 7
                                                       :candidate-root (r "c")
                                                       :profile-id :strict :profile-version 1
                                                       :checks []})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incomplete or not snapshot-consistent"
                          (admission/build-validation {:partition-key (admission/partition-key family)
                                                       :snapshot-root (r "s") :snapshot-version 7
                                                       :candidate-root (r "c")
                                                       :profile-id :strict :profile-version 1
                                                       :checks (assoc-in checks [0 :validated-against/root] (r "x"))})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"incomplete or not snapshot-consistent"
                          (admission/build-validation {:partition-key (admission/partition-key family)
                                                       :snapshot-root (r "s") :snapshot-version 7
                                                       :candidate-root (r "c")
                                                       :profile-id :strict :profile-version 1
                                                       :validation/check-order [] :checks []})))))

(deftest reservations-fence-idempotency-and-finalization
  (let [db (store/in-memory-store)
        s0 (store/snapshot! db family)
        reserved (store/reserve! db (request s0 "idem-a" (r "a")))
        reservation (:reservation reserved)
        duplicate (store/reserve! db (request s0 "idem-a" (r "a")))
        conflict (store/reserve! db (request s0 "idem-a" (r "b")))
        finalized (store/finalize! db (finalize-request s0 reservation (r "r")))
        retry (store/finalize! db (finalize-request s0 reservation (r "r")))]
    (is (= :reserved (:concurrency/outcome reserved)))
    (is (= 1 (:reservation/fence reservation)))
    (is (= :idempotent-replay (:concurrency/outcome duplicate)))
    (is (= :idempotency-conflict (:reason conflict)))
    (is (= :finalized (:concurrency/outcome finalized)))
    (is (= :idempotent-replay (:concurrency/outcome retry)))
    (is (= 1 (:family/version (store/snapshot! db family))))))

(deftest expiry-boundaries-and-finalization-race
  (let [s0 (admission/empty-partition family)
        snap (admission/snapshot s0)
        issued "2026-01-01T00:00:00Z"
        expires "2026-01-01T00:02:00Z"
        reserved (admission/reserve-transition s0
                                                (assoc (request snap "idem-time" (r "a"))
                                                       :reservation/issued-at issued
                                                       :reservation/expires-at expires))
        state (:state reserved)
        reservation (:reservation reserved)
        before (admission/expire-transition state (:reservation/id reservation)
                                            (:reservation/fence reservation)
                                            (java.time.Instant/parse "2026-01-01T00:01:59Z"))
        at (admission/expire-transition state (:reservation/id reservation)
                                        (:reservation/fence reservation)
                                        (java.time.Instant/parse expires))
        after (admission/expire-transition state (:reservation/id reservation)
                                           (:reservation/fence reservation)
                                           (java.time.Instant/parse "2026-01-01T00:02:01Z"))
        final-request (assoc (finalize-request snap reservation (r "r"))
                             :authorization/evidence-root (r "r"))
        expiry-wins (:state at)
        stale-finalize (admission/finalize-transition expiry-wins final-request)
        finalize-wins (admission/finalize-transition state final-request)
        late-expire (admission/expire-transition (:state finalize-wins)
                                                 (:reservation/id reservation)
                                                 (:reservation/fence reservation)
                                                 (java.time.Instant/parse "2026-01-01T00:02:01Z"))]
    (is (= :reservation-not-expired (:reason before)))
    (is (= :expired (:concurrency/outcome at)))
    (is (= :expired (:concurrency/outcome after)))
    (is (= :reservation-not-active (:reason stale-finalize)))
    (is (= :finalized (:concurrency/outcome finalize-wins)))
    (is (= :reservation-not-active (:reason late-expire)))))

(deftest compaction-preserves-replay-and-idempotency-surface
  (let [db (store/in-memory-store)
        s (store/snapshot! db family)
        reservation (:reservation (store/reserve! db (request s "idem-compact" (r "a"))))
        final-request (finalize-request s reservation (r "r"))
        _ (store/finalize! db final-request)
        _ (store/compact! db family)
        replay (store/finalize! db final-request)
        conflict (store/reserve! db (request (store/snapshot! db family) "idem-compact" (r "b")))]
    (is (= :idempotent-replay (:concurrency/outcome replay)))
    (is (= :idempotency-conflict (:reason conflict)))
    (is (= :finalized (:concurrency/outcome
                        (workflow/resolve-finalization! db family final-request))))
    (is (= (r "r") (get-in (workflow/resolve-finalization! db family final-request)
                            [:finalization :receipt/root])))))

(deftest aborted-reservation-closes-its-attempt-and-allows-a-new-fence
  (let [db (store/in-memory-store)
        s0 (store/snapshot! db family)
        first (:reservation (store/reserve! db (request s0 "idem-a" (r "a"))))
        _ (store/abort! db family (:reservation/id first) (:reservation/fence first))
        closed (store/reserve! db (request s0 "idem-a" (r "a")))
        retry (:reservation (store/reserve! db (request s0 "idem-a-retry" (r "a"))))
        substitution (store/reserve! db (request s0 "idem-a" (r "b")))]
    (is (= 1 (:reservation/fence first)))
    (is (= :idempotency-attempt-closed (:reason closed)))
    (is (= 2 (:reservation/fence retry)))
    (is (= :idempotency-conflict (:reason substitution)))))

(deftest stale-worker-cannot-finalize-after-fence-replacement
  (let [db (store/in-memory-store)
        s0 (store/snapshot! db family)
        a (:reservation (store/reserve! db (request s0 "idem-a" (r "a"))))
        _ (store/abort! db family (:reservation/id a) (:reservation/fence a))
        b (:reservation (store/reserve! db (request s0 "idem-b" (r "b"))))
        b-final (store/finalize! db (finalize-request s0 b (r "b")))
        stale (store/finalize! db (finalize-request s0 a (r "a")))]
    (is (= 1 (:reservation/fence a)))
    (is (= 2 (:reservation/fence b)))
    (is (= :finalized (:concurrency/outcome b-final)))
    (is (= :stale-fence (:reason stale)))
    (is (= (r "b") (:family/head (store/snapshot! db family))))))

(deftest workflow-reserves-signs-verifies-and-finalizes-without-signer-arbitration
  (let [db (store/in-memory-store)
        s (store/snapshot! db family)
        candidate (r "c")
        sign! (fn [payload] {:receipt/root (r "r")
                              :signing/payload-root (:signing/payload-root payload)})
        result (workflow/attempt! {:admission-store db :family-id family
                                   :candidate-root candidate :idempotency-key "idem-workflow"
                                   :proposed-ordering-root (r "o")
                                   :validation (validation-for s candidate)
                                   :sign! sign! :verify-signature! (constantly true)})]
    (is (= :finalized (:concurrency/outcome result)))
    (is (= 1 (:family/version (store/snapshot! db family))))
    (is (= (r "r") (:family/head (store/snapshot! db family))))))

(deftest workflow-releases-fenced-reservations-for-adversarial-callbacks
  (doseq [[label sign! verify! expected reason]
          [[:signer-throws (fn [_] (throw (ex-info "signer unavailable" {})))
            (constantly true) :signing-failed :signer-threw]
           [:signer-nil (constantly nil) (constantly true)
            :signing-failed :malformed-signer-result]
           [:wrong-payload (fn [_] {:receipt/root (r "r") :signing/payload-root (r "x")})
            (constantly true) :signature-invalid :signing-payload-mismatch]
           [:verifier-throws (fn [payload] {:receipt/root (r "r")
                                            :signing/payload-root (:signing/payload-root payload)})
            (fn [_ _] (throw (ex-info "verifier unavailable" {})))
            :workflow-failed :verifier-threw]
           [:verifier-false (fn [payload] {:receipt/root (r "r")
                                           :signing/payload-root (:signing/payload-root payload)})
            (constantly false) :signature-invalid :signature-verification-failed]
           [:verifier-malformed (fn [payload] {:receipt/root (r "r")
                                               :signing/payload-root (:signing/payload-root payload)})
            (constantly :truthy-but-invalid) :workflow-failed :malformed-verifier-result]]]
    (testing (name label)
      (let [db (store/in-memory-store)
            f (str family "-" (name label))
            s (store/snapshot! db f)
            candidate (r "c")
            result (workflow/attempt! {:admission-store db :family-id f
                                       :candidate-root candidate :idempotency-key (str "idem-" (name label))
                                       :proposed-ordering-root (r "o")
                                       :validation (validation-for s candidate)
                                       :sign! sign! :verify-signature! verify!})]
        (is (= expected (:concurrency/outcome result)))
        (is (= reason (:reason result)))
        (is (= :aborted (:reservation/release-outcome result)))
        (is (= 0 (:family/version (store/snapshot! db f))))))))

(deftest finalization-rejects-poisoned-request-root-and-mismatched-partition
  (let [db (store/in-memory-store)
        s (store/snapshot! db family)
        reservation (:reservation (store/reserve! db (request s "idem-root" (r "a"))))
        request (finalize-request s reservation (r "r"))
        poisoned (store/finalize! db (assoc request :finalization/request-root (r "x")))
        wrong-partition (store/finalize! db (assoc request :concurrency/partition-key [:wrong family]))
        finalized (store/finalize! db request)]
    (is (= :finalization-request-root-mismatch (:reason poisoned)))
    (is (= :partition-mismatch (:reason wrong-partition)))
    (is (= :finalized (:concurrency/outcome finalized)))
    (is (= (r "r") (:family/head (store/snapshot! db family))))))

(deftest authoritative-finalization-resolution-replays-a-lost-response
  (let [db (store/in-memory-store)
        s (store/snapshot! db family)
        reservation (:reservation (store/reserve! db (request s "idem-final" (r "a"))))
        final-request (finalize-request s reservation (r "r"))
        _ (store/finalize! db final-request)
        resolved (workflow/resolve-finalization! db family final-request)]
    (is (= :finalized (:concurrency/outcome resolved)))
    (is (= (r "r") (get-in resolved [:finalization :receipt/root])))
    (is (= 1 (:family/version (store/snapshot! db family))))))

(deftest store-clock-enforces-expiry-and-lazy-terminalizes
  (let [t0 (java.time.Instant/parse "2026-01-01T00:00:00Z")
        now (atom t0)
        db (store/in-memory-store {:clock #(deref now)})
        s0 (store/snapshot! db family)
        first (:reservation (store/reserve! db (request s0 "idem-clock" (r "a"))))
        _ (reset! now (.plusMillis t0 119999))
        premature (store/expire! db family (:reservation/id first) (:reservation/fence first))
        _ (reset! now (.plusMillis t0 120000))
        replacement (store/reserve! db (request s0 "idem-clock-retry" (r "b")))
        old-final (store/finalize! db (finalize-request s0 first (r "a")))]
    (is (= :reservation-not-expired (:reason premature)))
    (is (= :reserved (:concurrency/outcome replacement)))
    (is (= 2 (get-in replacement [:reservation :reservation/fence])))
    (is (= :stale-fence (:reason old-final)))
    (is (= :expired (get-in (store/resolve-finalization! db family (:reservation/id first))
                            [:reservation :reservation/status])))))

(deftest abort-is-fenced-and-idempotent
  (let [db (store/in-memory-store)
        s (store/snapshot! db family)
        reservation (:reservation (store/reserve! db (request s "idem-abort" (r "a"))))
        first (store/abort! db family (:reservation/id reservation) (:reservation/fence reservation))
        retry (store/abort! db family (:reservation/id reservation) (:reservation/fence reservation))
        stale (store/abort! db family (:reservation/id reservation) 0)]
    (is (= :aborted (:concurrency/outcome first)))
    (is (= :idempotent-replay (:concurrency/outcome retry)))
    (is (= :stale-fence (:reason stale)))))

(deftest distinct-families-do-not-share-a-cas-partition
  (let [db (store/in-memory-store)
        families ["sha256:FA" "sha256:FB" "sha256:FC"]
        results (mapv (fn [f]
                        (future
                          (let [s (store/snapshot! db f)]
                            (store/reserve! db (request s (str "idem-" f) (r "c"))))))
                      families)]
    (doseq [result (mapv deref results)]
      (is (= :reserved (:concurrency/outcome result)))
      (is (= 1 (get-in result [:reservation :reservation/fence]))))))
