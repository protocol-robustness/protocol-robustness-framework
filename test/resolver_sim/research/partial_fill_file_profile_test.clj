(ns resolver-sim.research.partial-fill-file-profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.partial-fill-file-profile :as profile])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardOpenOption]))

(defn- root [ch]
  (str "sha256:" (apply str (repeat 64 ch))))

(defn- exception-reason [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo error
         (:reason (ex-data error)))))

(defn- rechain-record [sequence previous-root authority-root kind payload]
  (let [body {:artifact/schema "partial-fill-file-journal-record.v1"
              :journal/sequence sequence
              :journal/previous-root previous-root
              :authority/root authority-root
              :record/kind kind
              :record/payload payload}]
    (assoc body :journal/record-root
           (hash-ref/sha256-ref
            (hc/domain-hash "PRF_PARTIAL_FILL_FILE_JOURNAL_RECORD_V1" body)))))

(defn- append-record! [path record]
  (Files/write path (.getBytes (str (pr-str record) "\n") StandardCharsets/UTF_8)
               (into-array StandardOpenOption [StandardOpenOption/APPEND])))

(defn- fixture-operation []
  (profile/build-operation
   {:operation-id :fixture/parallel-partial-fill
    :asset-root (root \a)
    :policy-root (root \b)
    :restricted-refund 10
    :entitlements [{:entitlement/root (root \1) :beneficiary/root (root \c)
                    :entitlement/amount 35}
                   {:entitlement/root (root \2) :beneficiary/root (root \d)
                    :entitlement/amount 21}
                   {:entitlement/root (root \3) :beneficiary/root (root \e)
                    :entitlement/amount 14}]}))

(declare source-for)

(defn- submission-for [operation entitlement-digit id-digit]
  (let [entitlement (some #(when (= (root entitlement-digit) (:entitlement/root %)) %)
                          (:operation/entitlements operation))]
    (profile/build-submission
     {:idempotency-root (root id-digit)
      :operation-root (:operation/root operation)
      :entitlement-root (:entitlement/root entitlement)
      :beneficiary-root (:beneficiary/root entitlement)
      :amount (:entitlement/amount entitlement)
      :asset-root (:operation/asset-root operation)
      :policy-root (:operation/policy-root operation)
      :source-roots [(:source/root (source-for operation))]
      :submitter-root (root \9)})))

(defn- source-for [operation]
  (profile/build-source {:operation-root (:operation/root operation)
                         :evidence-root (root \f)}))

(defn- result-root-for [submission snapshot-root]
  (hash-ref/sha256-ref
   (hc/domain-hash "PRF_PARTIAL_FILL_TEST_PREPARATION_RESULT_V1"
                   {:submission/root (:submission/root submission)
                    :snapshot/root snapshot-root
                    :result :valid})))

(defn- preparation-for [ledger submission worker-digit]
  (let [submission-root (:submission/root submission)
        snapshot-root (profile/frozen-snapshot-root ledger submission-root)]
    (profile/build-preparation
     {:submission-root submission-root
      :snapshot-root snapshot-root
      :worker-root (root worker-digit)
      :result-root (result-root-for submission snapshot-root)})))

(defn- assert-economic-prefix [operation projection]
  (is (= (:operation/root operation) (:operation/root projection)))
  (is (= 70 (+ (:paid-total projection) (:remaining-total projection))))
  (is (= 10 (:restricted/refund projection)))
  (is (= 80 (:funded-total projection)))
  (doseq [{entitlement-root :entitlement/root
           amount :entitlement/amount} (:operation/entitlements operation)]
    (is (= amount (+ (get-in projection [:paid-by-entitlement entitlement-root])
                     (get-in projection [:remaining-by-entitlement entitlement-root]))))))

(defn- assert-forensic-event-coverage [ledger]
  (let [records (:records (profile/ledger-state ledger))
        nodes (profile/forensic-nodes ledger)
        record-roots (mapv :journal/record-root records)
        node-record-roots (mapv #(get-in % [:extensions :forensic/subject :subject/root])
                                nodes)
        validation (profile/validate-forensic-evidence ledger)]
    (is (= record-roots node-record-roots))
    (is (:event-coverage-valid? validation) (pr-str validation))
    (is (:valid? validation) (pr-str validation))))

(defn- empty-temp-ledger [operation]
  (let [directory (Files/createTempDirectory
                   "partial-fill-file-profile-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        path (.resolve directory "journal.edn")
        authority-root (root \8)]
    {:directory directory
     :path path
     :authority-root authority-root
     :ledger (profile/create-ledger! path operation authority-root)}))

(defn- temp-ledger [operation]
  (let [{:keys [ledger] :as temporary} (empty-temp-ledger operation)
        source (source-for operation)]
    (profile/record-source! ledger source)
    (assoc temporary :source source)))

(defn- delete-tree! [directory]
  (when (Files/exists directory (make-array java.nio.file.LinkOption 0))
    (with-open [paths (Files/walk directory (make-array java.nio.file.FileVisitOption 0))]
      (doseq [path (reverse (vec (.toArray paths)))]
        (Files/deleteIfExists path)))))

(defn- append-torn-tail! [path]
  (Files/write path (.getBytes "{:partial-record true" StandardCharsets/UTF_8)
               (into-array StandardOpenOption [StandardOpenOption/APPEND])))

(defn- execute-order [digits]
  (let [operation (fixture-operation)
        {:keys [directory path authority-root ledger]} (temp-ledger operation)
        submissions (mapv (fn [digit id] (submission-for operation digit id))
                          digits [\a \b \c])]
    (try
      (doseq [submission submissions]
        (is (= :submitted (:status (profile/submit! ledger submission)))))
      (doseq [[index submission] (map-indexed vector submissions)]
        (is (= :prepared
               (:status (profile/prepare! ledger
                                          (preparation-for ledger submission
                                                           (nth [\6 \7 \8] index)))))))
      (is (= :duplicate
             (:status (profile/submit! ledger (first submissions)))))
      (let [admission-roots (mapv (fn [submission]
                                    (:admission/root
                                     (profile/admit! ledger (:submission/root submission))))
                                  submissions)
            before (profile/economic-projection (profile/ledger-state ledger))
            bytes-before (Files/readAllBytes path)]
        (is (= :duplicate
               (:status (profile/prepare! ledger
                                          (preparation-for ledger (first submissions) \6))))
            "lost preparation acknowledgement remains retryable after admission")
        (is (= :partial-fill-file/stale-preparation
               (exception-reason #(profile/prepare!
                                   ledger (preparation-for ledger (first submissions) \5)))))
        (is (= (seq bytes-before) (seq (Files/readAllBytes path))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Commit must follow admission order"
                              (profile/commit! ledger (second admission-roots))))
        (is (= before (profile/economic-projection (profile/ledger-state ledger))))
        (is (= (seq bytes-before) (seq (Files/readAllBytes path))))
        (let [commits (mapv (fn [admission-root]
                              (let [commit (profile/commit! ledger admission-root)]
                                (assert-economic-prefix
                                 operation
                                 (profile/economic-projection (profile/ledger-state ledger)))
                                commit))
                            admission-roots)
              completion (profile/complete! ledger)
              state (profile/ledger-state ledger)
              projection (profile/economic-projection state)
              graph (profile/validate-forensic-evidence ledger)
              first-submission-root (:submission/root (first submissions))
              submission-record (some #(when (and (= :submission (:record/kind %))
                                                  (= first-submission-root
                                                     (get-in % [:record/payload
                                                                :submission/root]))) %)
                                      (:records state))
              preparation-record (some #(when (and (= :prepared (:record/kind %))
                                                   (= first-submission-root
                                                      (get-in % [:record/payload
                                                                 :preparation/submission-root]))) %)
                                       (:records state))
              submission-node (some #(when (= (:journal/record-root submission-record)
                                              (get-in % [:extensions :forensic/subject
                                                         :subject/root])) %)
                                    (profile/forensic-nodes ledger))
              preparation-node (some #(when (= (:journal/record-root preparation-record)
                                               (get-in % [:extensions :forensic/subject
                                                          :subject/root])) %)
                                     (profile/forensic-nodes ledger))
              submission-causal-roots (get-in submission-node
                                              [:extensions :forensic/causal-parent-roots])
              preparation-actor (get-in preparation-node [:extensions :forensic/actor])
              reopened (profile/create-ledger! path operation authority-root)
              recovered (profile/ledger-state reopened)]
          (is (every? #(= :committed (:status %)) commits))
          (is (= :completed (:status completion)))
          (is (= 70 (:paid-total projection)))
          (is (zero? (:remaining-total projection)))
          (is (= 10 (:restricted/refund projection)))
          (is (= 80 (:funded-total projection)))
          (is (:valid? graph) (pr-str graph))
          (is (every? (set submission-causal-roots)
                      (get-in submission-record [:record/payload :submission/source-roots])))
          (is (= :worker (:actor/role preparation-actor)))
          (is (= (get-in preparation-record [:record/payload :preparation/worker-root])
                 (:actor/root preparation-actor)))
          (is (= projection (profile/economic-projection recovered)))
          (is (= (:journal/root state) (:journal/root recovered)))
          (is (= :duplicate (:status (profile/complete! reopened))))
          ;; Simulate a crash after durable commit but before acknowledgement.
          (is (= {:status :duplicate :commit/root (:commit/root (first commits))}
                 (profile/commit! reopened (first admission-roots))))
          {:projection-root (profile/economic-projection-root state)
           :journal-root (:journal/root state)
           :commit-roots (mapv :commit/root commits)}))
      (finally (delete-tree! directory)))))

(deftest submissions-require-at-least-one-recorded-source
  (let [operation (fixture-operation)
        {:keys [directory path ledger]} (temp-ledger operation)
        valid (submission-for operation \1 \a)
        body (assoc valid :submission/source-roots [])
        without-sources (assoc body :submission/root (profile/submission-root body))
        before (Files/readAllBytes path)]
    (try
      (is (= :partial-fill-file/invalid-submission
             (exception-reason #(profile/submit! ledger without-sources))))
      (is (= (seq before) (seq (Files/readAllBytes path))))
      (is (empty? (profile/submissions ledger)))
      (finally (delete-tree! directory)))))

(deftest journal-replay-rejects-a-re-rooted-source-free-submission
  (let [operation (fixture-operation)
        {:keys [directory path authority-root]} (empty-temp-ledger operation)
        genesis (profile/create-ledger! path operation authority-root)
        valid (submission-for operation \1 \a)
        body (assoc valid :submission/source-roots [])
        source-free (assoc body :submission/root (profile/submission-root body))
        record (rechain-record 1 (:journal/root (profile/ledger-state genesis))
                               authority-root :submission source-free)]
    (try
      ;; Bypass submit!, as if an externally edited journal had been re-rooted.
      (append-record! path record)
      (is (= :partial-fill-file/invalid-submission
             (exception-reason #(profile/create-ledger! path operation authority-root))))
      (finally (delete-tree! directory)))))

(deftest six-admission-and-commit-orders-converge-but-retain-distinct-history
  (let [orders [[\1 \2 \3] [\1 \3 \2] [\2 \1 \3]
                [\2 \3 \1] [\3 \1 \2] [\3 \2 \1]]
        results (mapv execute-order orders)]
    (is (= 1 (count (set (map :projection-root results)))))
    (is (= 6 (count (set (map :journal-root results)))))
    (is (= 6 (count (set (map :commit-roots results)))))))

(deftest live-forensic-nodes-track-every-durable-transition
  (let [operation (fixture-operation)
        {:keys [directory ledger]} (empty-temp-ledger operation)
        source (source-for operation)
        submission (submission-for operation \1 \a)]
    (try
      (assert-forensic-event-coverage ledger)
      (is (= :recorded (:status (profile/record-source! ledger source))))
      (assert-forensic-event-coverage ledger)
      (is (= :submitted (:status (profile/submit! ledger submission))))
      (assert-forensic-event-coverage ledger)
      (is (= :prepared
             (:status (profile/prepare! ledger (preparation-for ledger submission \6)))))
      (assert-forensic-event-coverage ledger)
      (let [admission (profile/admit! ledger (:submission/root submission))]
        (assert-forensic-event-coverage ledger)
        (is (= :committed (:status (profile/commit! ledger (:admission/root admission)))))
        (assert-forensic-event-coverage ledger)
        (is (= :completed (:status (profile/complete! ledger))))
        (assert-forensic-event-coverage ledger))
      ;; The validator must catch a missing event node instead of accepting a
      ;; graph whose remaining nodes are individually well-formed.
      (swap! (:state ledger) update :forensic-nodes pop)
      (let [validation (profile/validate-forensic-evidence ledger)]
        (is (false? (:valid? validation)))
        (is (false? (:integrity-valid? validation)))
        (is (false? (:event-coverage-valid? validation)))
        (is (= :partial-fill-file/forensic-event-coverage
               (get-in validation [:errors (dec (count (:errors validation))) :error]))))
      (finally (delete-tree! directory)))))

(defn- run-fixed-order [parallel-preparation?]
  (let [operation (fixture-operation)
        {:keys [directory path authority-root ledger]} (temp-ledger operation)
        submissions (mapv (fn [entitlement id]
                            (submission-for operation entitlement id))
                          [\1 \2 \3] [\a \b \c])]
    (try
      (doseq [submission submissions]
        (profile/submit! ledger submission))
      (if parallel-preparation?
        (->> submissions
             (map-indexed (fn [index submission]
                            (future
                              (Thread/sleep (long (* (- 3 index) 5)))
                              (profile/prepare!
                               ledger
                               (preparation-for ledger submission
                                                (nth [\6 \7 \8] index))))))
             (mapv deref))
        (doseq [[index submission] (map-indexed vector submissions)]
          (profile/prepare! ledger
                            (preparation-for ledger submission
                                             (nth [\6 \7 \8] index)))))
      (let [admissions (mapv #(profile/admit! ledger (:submission/root %)) submissions)]
        (doseq [admission admissions]
          (profile/commit! ledger (:admission/root admission)))
        (profile/complete! ledger)
        {:projection-root (profile/economic-projection-root (profile/ledger-state ledger))
         :journal-root (:journal/root (profile/ledger-state ledger))
         :admission-sequences (mapv :admission/sequence admissions)})
      (finally (delete-tree! directory)))))

(deftest parallel-worker-preparation-does-not-change-fixed-serial-admission-result
  (let [serial (run-fixed-order false)
        parallel (run-fixed-order true)]
    (is (= (:projection-root serial) (:projection-root parallel)))
    (is (= [1 2 3] (:admission-sequences serial)))
    (is (= [1 2 3] (:admission-sequences parallel)))))

(deftest stale-and-conflicting-worker-preparations-fail-without-admission
  (let [operation (fixture-operation)
        {:keys [directory path ledger]} (temp-ledger operation)
        submission (submission-for operation \1 \a)]
    (try
      (profile/submit! ledger submission)
      (let [snapshot (profile/frozen-snapshot-root ledger (:submission/root submission))
            stale (profile/build-preparation
                   {:submission-root (:submission/root submission)
                    :snapshot-root (root \0)
                    :worker-root (root \6)
                    :result-root (root \f)})
            valid (preparation-for ledger submission \6)
            conflicting (profile/build-preparation
                         {:submission-root (:submission/root submission)
                          :snapshot-root snapshot
                          :worker-root (root \7)
                          :result-root (root \e)})]
        (let [before (Files/readAllBytes path)]
          (is (= :partial-fill-file/stale-preparation
                 (exception-reason #(profile/prepare! ledger stale))))
          (is (= (seq before) (seq (Files/readAllBytes path)))))
        (profile/prepare! ledger valid)
        (profile/prepare! ledger conflicting)
        (let [before (Files/readAllBytes path)]
          (is (= :partial-fill-file/preparation-conflict
                 (exception-reason #(profile/admit! ledger (:submission/root submission)))))
          (is (= (seq before) (seq (Files/readAllBytes path))))
          (is (empty? (:admissions (profile/ledger-state ledger))))))
      (finally (delete-tree! directory)))))

(deftest restart-recovers-each-transition-boundary-and-completion-is-terminal
  (let [operation (fixture-operation)
        {:keys [directory path authority-root ledger]} (temp-ledger operation)
        submissions (mapv (fn [entitlement id]
                            (submission-for operation entitlement id))
                          [\1 \2 \3] [\a \b \c])]
    (try
      ;; Source and submission are durable across reopen; retries are no-ops.
      (let [reopened (profile/create-ledger! path operation authority-root)]
        (is (= :duplicate (:status (profile/record-source! reopened (source-for operation))))))
      (profile/submit! ledger (first submissions))
      (let [reopened (profile/create-ledger! path operation authority-root)]
        (is (= :duplicate (:status (profile/submit! reopened (first submissions))))))
      (profile/prepare! ledger (preparation-for ledger (first submissions) \6))
      (append-torn-tail! path)
      (let [reopened (profile/create-ledger! path operation authority-root)]
        (is (= 1 (count (profile/preparations reopened))))
        (is (= :duplicate
               (:status (profile/prepare! reopened
                                          (preparation-for reopened (first submissions) \6))))))
      (doseq [submission (rest submissions)]
        (profile/submit! ledger submission)
        (profile/prepare! ledger (preparation-for ledger submission \7)))
      (let [before-admission (profile/create-ledger! path operation authority-root)]
        (is (empty? (profile/admissions before-admission))))
      ;; Simulate a torn append while admission is being written, then retry.
      (append-torn-tail! path)
      (let [recovered (profile/create-ledger! path operation authority-root)
            admission-results (mapv #(profile/admit! recovered (:submission/root %))
                                    submissions)
            admission-roots (mapv :admission/root admission-results)
            replayed (profile/create-ledger! path operation authority-root)]
        (is (= [1 2 3] (mapv :admission/sequence admission-results)))
        (is (= :duplicate
               (:status (profile/admit! replayed (:submission/root (first submissions))))))
        ;; A torn commit record recovers to the pre-commit projection.
        (append-torn-tail! path)
        (let [before-commit (profile/create-ledger! path operation authority-root)]
          (is (zero? (:paid-total (profile/economic-projection
                                   (profile/ledger-state before-commit)))))
          (let [first-commit (profile/commit! before-commit (first admission-roots))
                after-first (profile/create-ledger! path operation authority-root)]
            (is (= :committed (:status first-commit)))
            (is (= :duplicate
                   (:status (profile/commit! after-first (first admission-roots))))
                "durable commit is not duplicated after lost acknowledgement")
            (let [bytes-before-completion (Files/readAllBytes path)]
              (is (= :partial-fill-file/incomplete-operation
                     (exception-reason #(profile/complete! after-first))))
              (is (= (seq bytes-before-completion) (seq (Files/readAllBytes path)))))
            (doseq [admission-root (rest admission-roots)]
              (profile/commit! after-first admission-root))
            ;; A partial completion append leaves the fully committed operation
            ;; open and can be safely retried after recovery.
            (append-torn-tail! path)
            (let [ready (profile/create-ledger! path operation authority-root)
                  completion (profile/complete! ready)
                  completed (profile/create-ledger! path operation authority-root)]
              (is (= :completed (:status completion)))
              (is (= :duplicate (:status (profile/complete! completed))))
              (is (= 70 (:paid-total (profile/economic-projection
                                      (profile/ledger-state completed)))))
              (is (= 10 (:restricted/refund
                         (profile/economic-projection (profile/ledger-state completed)))))
              (let [final-bytes (Files/readAllBytes path)
                    new-submission (submission-for operation \1 \9)]
                (is (= :partial-fill-file/operation-complete
                       (exception-reason #(profile/submit! completed new-submission))))
                (is (= (seq final-bytes) (seq (Files/readAllBytes path)))))))))
      (finally (delete-tree! directory)))))

(deftest torn-source-and-submission-appends-recover-to-prior-durable-state
  (let [operation (fixture-operation)
        {:keys [directory path authority-root ledger]} (empty-temp-ledger operation)
        source (source-for operation)
        submission (submission-for operation \1 \a)]
    (try
      ;; A source append that never reaches its newline is absent after restart.
      (append-torn-tail! path)
      (let [after-torn-source (profile/create-ledger! path operation authority-root)]
        (is (empty? (:sources (profile/ledger-state after-torn-source))))
        (is (= :recorded (:status (profile/record-source! after-torn-source source)))))
      ;; A torn submission is likewise absent; the same immutable proposal can
      ;; be submitted after recovery without changing its identity.
      (append-torn-tail! path)
      (let [after-torn-submission (profile/create-ledger! path operation authority-root)]
        (is (empty? (profile/submissions after-torn-submission)))
        (is (= :submitted
               (:status (profile/submit! after-torn-submission submission))))
        (is (= #{(:submission/root submission)}
               (set (keys (profile/submissions after-torn-submission))))))
      (finally (delete-tree! directory)))))

(deftest torn-genesis-recovers-only-when-fragment-matches-expected-prefix
  (let [operation (fixture-operation)
        {:keys [directory path authority-root]} (empty-temp-ledger operation)
        genesis-bytes (Files/readAllBytes path)
        torn-path (.resolve directory "torn-genesis.edn")
        malformed-path (.resolve directory "malformed-genesis.edn")]
    (try
      (Files/write torn-path (java.util.Arrays/copyOf genesis-bytes 12)
                   (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW
                                                   StandardOpenOption/WRITE]))
      (let [recovered (profile/create-ledger! torn-path operation authority-root)
            state (profile/ledger-state recovered)
            original (profile/create-ledger! path operation authority-root)]
        (is (= 1 (count (:records state))))
        (is (= (:journal/root (profile/ledger-state original)) (:journal/root state)))
        (is (.endsWith (slurp (.toFile torn-path)) "\n")))
      (Files/write malformed-path (.getBytes "{not-the-genesis" StandardCharsets/UTF_8)
                   (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW
                                                   StandardOpenOption/WRITE]))
      (is (= :partial-fill-file/malformed-record
             (exception-reason #(profile/create-ledger! malformed-path operation authority-root))))
      (finally (delete-tree! directory)))))

(deftest blank-complete-journal-lines-fail-closed
  (let [operation (fixture-operation)
        {:keys [directory path authority-root ledger]} (temp-ledger operation)]
    (try
      (profile/submit! ledger (submission-for operation \1 \a))
      (Files/write path (.getBytes "\n" StandardCharsets/UTF_8)
                   (into-array StandardOpenOption [StandardOpenOption/APPEND]))
      (is (= :partial-fill-file/malformed-record
             (exception-reason #(profile/create-ledger! path operation authority-root))))
      (finally (delete-tree! directory)))))

(deftest malformed-record-values-return-profile-reason-codes
  (let [operation (fixture-operation)
        scalar-record-values [42 "str" 'sym]
        scalar-payload-values [42 "str"]
        scalar-payload-kinds [:source :admitted :committed :completed]]
    (doseq [scalar scalar-record-values]
      (let [{:keys [directory path authority-root]} (empty-temp-ledger operation)]
        (try
          (Files/write path (.getBytes (str (pr-str scalar) "\n") StandardCharsets/UTF_8)
                       (into-array StandardOpenOption [StandardOpenOption/APPEND]))
          (is (= :partial-fill-file/malformed-record
                 (exception-reason #(profile/create-ledger! path operation authority-root))))
          (finally (delete-tree! directory)))))
    (doseq [kind scalar-payload-kinds
            scalar scalar-payload-values]
      (let [{:keys [directory path authority-root ledger]} (empty-temp-ledger operation)
            state (profile/ledger-state ledger)
            record (rechain-record (inc (:journal/sequence state))
                                   (:journal/root state) authority-root kind scalar)]
        (try
          (append-record! path record)
          (is (= :partial-fill-file/malformed-record
                 (exception-reason #(profile/create-ledger! path operation authority-root)))
              (str "kind=" kind " payload=" (pr-str scalar)))
          (finally (delete-tree! directory)))))))

(deftest complete-journal-lines-reject-trailing-edn-and-text
  (doseq [suffix [" 42" " garbage"]]
    (let [operation (fixture-operation)
          {:keys [directory path authority-root]} (empty-temp-ledger operation)
          genesis-line (first (str/split-lines (slurp (.toFile path))))]
      (try
        (Files/write path
                     (.getBytes (str genesis-line suffix "\n") StandardCharsets/UTF_8)
                     (into-array StandardOpenOption [StandardOpenOption/TRUNCATE_EXISTING
                                                     StandardOpenOption/WRITE]))
        (is (= :partial-fill-file/malformed-record
               (exception-reason #(profile/create-ledger! path operation authority-root)))
            (str "suffix=" (pr-str suffix)))
        (finally (delete-tree! directory))))))

(deftest admission-overdraw-and-cross-operation-substitution-are-no-write
  (let [operation (fixture-operation)
        {:keys [directory path ledger]} (temp-ledger operation)
        original (submission-for operation \1 \a)
        ent1 (first (:operation/entitlements operation))
        oversized (profile/build-submission
                   {:idempotency-root (root \b)
                    :operation-root (:operation/root operation)
                    :entitlement-root (:entitlement/root ent1)
                    :beneficiary-root (:beneficiary/root ent1)
                    :amount 20
                    :asset-root (:operation/asset-root operation)
                    :policy-root (:operation/policy-root operation)
                    :source-roots [(:source/root (source-for operation))]
                    :submitter-root (root \9)})
        other-operation (profile/build-operation
                         {:operation-id :fixture/other-operation
                          :asset-root (:operation/asset-root operation)
                          :policy-root (:operation/policy-root operation)
                          :restricted-refund 0
                          :entitlements [{:entitlement/root (root \4)
                                          :beneficiary/root (root \5)
                                          :entitlement/amount 10}]})
        cross-context (profile/build-submission
                       {:idempotency-root (root \6)
                        :operation-root (:operation/root other-operation)
                        :entitlement-root (root \1)
                        :beneficiary-root (root \c)
                        :amount 35
                        :asset-root (:operation/asset-root operation)
                        :policy-root (:operation/policy-root operation)
                        :source-roots [(:source/root (source-for operation))]
                        :submitter-root (root \9)})
        wrong-beneficiary (profile/build-submission
                           {:idempotency-root (root \7)
                            :operation-root (:operation/root operation)
                            :entitlement-root (:entitlement/root ent1)
                            :beneficiary-root (root \d)
                            :amount 35
                            :asset-root (:operation/asset-root operation)
                            :policy-root (:operation/policy-root operation)
                            :source-roots [(:source/root (source-for operation))]
                            :submitter-root (root \9)})
        unknown-source (profile/build-submission
                        {:idempotency-root (root \0)
                         :operation-root (:operation/root operation)
                         :entitlement-root (:entitlement/root ent1)
                         :beneficiary-root (:beneficiary/root ent1)
                         :amount 5
                         :asset-root (:operation/asset-root operation)
                         :policy-root (:operation/policy-root operation)
                         :source-roots [(root \f)]
                         :submitter-root (root \9)})]
    (try
      (profile/submit! ledger original)
      (profile/submit! ledger oversized)
      (profile/prepare! ledger (preparation-for ledger original \6))
      (profile/prepare! ledger (preparation-for ledger oversized \7))
      (profile/admit! ledger (:submission/root original))
      (let [before-projection (profile/economic-projection (profile/ledger-state ledger))
            before-bytes (Files/readAllBytes path)]
        (is (= :partial-fill-file/entitlement-overdraw
               (exception-reason #(profile/admit! ledger (:submission/root oversized)))))
        (is (= before-projection (profile/economic-projection (profile/ledger-state ledger))))
        (is (= (seq before-bytes) (seq (Files/readAllBytes path))))
        (is (= :partial-fill-file/invalid-submission
               (exception-reason #(profile/submit! ledger cross-context))))
        (is (= :partial-fill-file/invalid-submission
               (exception-reason #(profile/submit! ledger wrong-beneficiary))))
        (is (= :partial-fill-file/unknown-source
               (exception-reason #(profile/submit! ledger unknown-source))))
        (is (= before-projection (profile/economic-projection (profile/ledger-state ledger))))
        (is (= (seq before-bytes) (seq (Files/readAllBytes path)))))
      (finally (delete-tree! directory)))))

(deftest independent-handles-serialize-file-submissions
  (let [operation (fixture-operation)
        {:keys [directory path authority-root ledger]} (temp-ledger operation)
        second-handle (profile/create-ledger! path operation authority-root)
        submissions [(submission-for operation \1 \a)
                     (submission-for operation \2 \b)]]
    (try
      (let [results (->> submissions
                         (map-indexed (fn [index submission]
                                        (future (profile/submit!
                                                 (if (zero? index) ledger second-handle)
                                                 submission))))
                         (mapv deref))
            recovered (profile/create-ledger! path operation authority-root)]
        (is (= #{:submitted} (set (map :status results))))
        (is (= 2 (count (profile/submissions recovered))))
        (is (= 4 (count (:records (profile/ledger-state recovered))))))
      (finally (delete-tree! directory)))))

(deftest restart-truncates-only-incomplete-final-record-and-rejects-complete-corruption
  (let [operation (fixture-operation)
        {:keys [directory path authority-root ledger]} (temp-ledger operation)
        submission (submission-for operation \1 \a)]
    (try
      (profile/submit! ledger submission)
      (let [expected-root (:journal/root (profile/ledger-state ledger))]
        (Files/write path (.getBytes "{:truncated true" StandardCharsets/UTF_8)
                     (into-array StandardOpenOption [StandardOpenOption/APPEND]))
        (let [recovered (profile/create-ledger! path operation authority-root)]
          (is (= expected-root (:journal/root (profile/ledger-state recovered))))
          (is (.endsWith (slurp (.toFile path)) "\n")))
        (is (= :partial-fill-file/journal-owner-mismatch
               (exception-reason #(profile/create-ledger! path operation (root \7)))))
        (Files/write path (.getBytes "{:corrupt true}\n" StandardCharsets/UTF_8)
                     (into-array StandardOpenOption [StandardOpenOption/APPEND]))
        (is (= :partial-fill-file/malformed-record
               (exception-reason #(profile/create-ledger! path operation authority-root)))))
      (finally (delete-tree! directory)))))

(deftest stale-and-cross-operation-journal-records-fail-closed
  (testing "a correctly chained replay of an already committed claim is stale"
    (let [operation (fixture-operation)
          {:keys [directory path authority-root ledger]} (temp-ledger operation)
          submission (submission-for operation \1 \a)]
      (try
        (profile/submit! ledger submission)
        (profile/prepare! ledger (preparation-for ledger submission \6))
        (let [admission (profile/admit! ledger (:submission/root submission))
              _ (profile/commit! ledger (:admission/root admission))
              state (profile/ledger-state ledger)
              committed (last (filter #(= :committed (:record/kind %)) (:records state)))
              stale (rechain-record (inc (:journal/sequence state))
                                    (:journal/root state) authority-root :committed
                                    (:record/payload committed))]
          (append-record! path stale)
          (is (= :partial-fill-file/commit-mismatch
                 (exception-reason #(profile/create-ledger! path operation authority-root)))))
        (finally (delete-tree! directory)))))
  (testing "a valid foreign-operation submission cannot be inserted into this journal"
    (let [operation (fixture-operation)
          {:keys [directory path authority-root ledger]} (temp-ledger operation)
          foreign-operation (profile/build-operation
                             {:operation-id :fixture/foreign-operation
                              :asset-root (root \a)
                              :policy-root (root \b)
                              :restricted-refund 0
                              :entitlements [{:entitlement/root (root \4)
                                              :beneficiary/root (root \5)
                                              :entitlement/amount 10}]})
          foreign-submission (submission-for foreign-operation \4 \6)]
      (try
        (let [state (profile/ledger-state ledger)
              substituted (rechain-record (inc (:journal/sequence state))
                                          (:journal/root state) authority-root :submission
                                          foreign-submission)]
          (append-record! path substituted)
          (is (= :partial-fill-file/invalid-submission
                 (exception-reason #(profile/create-ledger! path operation authority-root)))))
        (finally (delete-tree! directory))))))
