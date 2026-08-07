(ns resolver-sim.io.content-addressed-store-test
  "Regression acceptance for the content-addressed store idempotency checklist.

   Covers identical-content idempotency, conflicting-content rejection, atomic
   visibility under contention, retry-after-interruption, durability
   idempotency, read-after-write convergence, the content↔key integrity
   boundary, and fail-closed readback validation.

   Contention coverage scope: cross-thread contention is mechanically verified
   here; cross-process contention is guaranteed by the Files/createLink
   exclusivity primitive (a design argument, not an exercised unit regression)
   and is an optional integration test, not unit coverage. See the store ns
   docstring for the six-property contract invariant this suite pins down."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [resolver-sim.io.content-addressed-store :as store]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref])
  (:import [java.nio.file Files]))

(defn- temp-store []
  (store/create-store (str (Files/createTempDirectory "resolver-sim-cas-" (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn- artifact [value]
  (let [base {:artifact/type :test-artifact :value value}
        hash (hash-ref/sha256-ref (hc/hash-with-intent {:hash/intent :evidence-record} base))]
    (assoc base :artifact/hash hash)))

(defn- valid? [artifact]
  (= (:artifact/hash artifact)
     (hash-ref/sha256-ref
      (hc/hash-with-intent {:hash/intent :evidence-record}
                           (dissoc artifact :artifact/hash)))))

(defn- put [backend hash artifact]
  (store/put-if-absent! backend {:hash-reference hash :artifact artifact :verify valid?}))

(defn- stored-bytes [backend hash]
  (slurp (store/artifact-path backend hash)))

(defn- run-identical
  "Run n concurrent identical writers against backend and return {:results [...]}."
  [backend hash value n]
  (let [pool (java.util.concurrent.Executors/newFixedThreadPool 8)]
    (try
      (let [futures (mapv (fn [_]
                            (.submit pool
                                     ^java.util.concurrent.Callable
                                     (fn []
                                       (try
                                         (put backend hash value)
                                         (catch Exception e {:error (ex-message e)})))))
                          (range n))
            results (mapv #(deref % 20000 ::timeout) futures)]
        {:results results})
      (finally (.shutdownNow pool)))))

(defn- assert-converged
  "Assert a contended identical-writer run converged to one immutable artifact."
  [backend hash value results]
  (is (not-any? #(or (= ::timeout %) (and (map? %) (:error %))) results) "all writers succeed")
  (is (= 1 (count (filter #(= :created (:status %)) results))) "exactly one creator")
  (is (= (dec (count results)) (count (filter #(= :exists (:status %)) results)))
      "remaining writers observe the existing artifact")
  (is (= 1 (count (set (map :crash-durable? results)))) "consistent durability claim")
  (is (= value (store/resolve-artifact backend hash)) "single canonical artifact")
  (is (= (store/canonical-edn value) (stored-bytes backend hash)) "byte-identical stored content"))

;; ── Foundational behavior ──────────────────────────────────────────────

(deftest unlinked-store-is-idempotent-and-self-verifying
  (let [backend (temp-store)
        value (artifact :one)
        hash (:artifact/hash value)]
    (is (= :created (:status (put backend hash value))))
    (is (= :exists (:status (put backend hash value))))
    (is (= value (store/resolve-artifact backend hash)))
    (is (= {:present? true :valid? true :hash hash :artifact value}
           (store/verify-stored-artifact backend hash valid?)))))

(deftest canonical-bytes-preserve-semantic-data-across-map-order
  (let [first-value {:artifact/type :test-artifact
                     :nested {:z 2 :a 1}
                     :keyword :sample/namespaced
                     :ratio 3/7}
        second-value (array-map :ratio 3/7
                                :keyword :sample/namespaced
                                :nested (array-map :a 1 :z 2)
                                :artifact/type :test-artifact)]
    (is (= (store/canonical-edn first-value) (store/canonical-edn second-value)))
    (is (= first-value (clojure.edn/read-string (store/canonical-edn first-value))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/canonical-edn {:when (java.time.Instant/now)})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (store/canonical-edn {:runtime (fn [] :nope)})))))

(deftest unlinked-store-rejects-invalid-and-colliding-writes
  (let [backend (temp-store)
        value (artifact :one)
        hash (:artifact/hash value)]
    (put backend hash value)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collision"
                          (store/put-if-absent! backend
                                                {:hash-reference hash
                                                 :artifact (assoc value :value :tampered)
                                                 :verify (constantly true)})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"self-verification"
                          (store/put-if-absent! backend
                                                {:hash-reference hash
                                                 :artifact (assoc value :value :tampered)
                                                 :verify valid?})))))

;; ── 1. Idempotent content — identical ─────────────────────────────────

(deftest idempotent-content-identical-sequential
  (testing "same key + identical content: sequential writes converge to one immutable artifact"
    (let [backend (temp-store)
          value (artifact :one)
          hash (:artifact/hash value)
          r1 (put backend hash value)
          r2 (put backend hash value)
          r3 (put backend hash value)]
      (is (= :created (:status r1)) "first successful write returns :created")
      (is (= :exists (:status r2)) "subsequent identical writes return :exists")
      (is (= :exists (:status r3)) "repeating the write stays :exists")
      (is (= value (:artifact r1)) "created result exposes the stored artifact")
      (is (= value (:artifact r2)) "exists result exposes the identical stored artifact")
      (is (= value (:artifact r3)))
      (is (= hash (:hash r1)) "result key matches the supplied reference")
      (is (= (store/canonical-edn value) (stored-bytes backend hash))
          "stored bytes are exactly the canonical encoding — unchanged by repeats"))))

(deftest idempotent-content-identical-concurrent
  (testing "concurrent identical writers converge to exactly one visible artifact"
    (let [backend (temp-store)
          value (artifact :one)
          hash (:artifact/hash value)
          n 16                                     ;; writer count > pool size
          pool (java.util.concurrent.Executors/newFixedThreadPool 8)
          futures (mapv (fn [_]
                          (.submit pool
                                   ^java.util.concurrent.Callable
                                   (fn []
                                     (try
                                       (put backend hash value)
                                       (catch Exception e {:error (ex-message e)})))))
                        (range n))]
      (try
        (let [results (mapv #(deref % 20000 ::timeout) futures)]
          (is (not-any? #(= ::timeout %) results) "no writer hangs")
          (is (not-any? #(and (map? %) (:error %)) results) "no writer receives :hash-content-collision")
          (is (= n (count results)))
          (is (= 1 (count (filter #(= :created (:status %)) results)))
              "exactly one concurrent writer observes :created")
          (is (= (dec n) (count (filter #(= :exists (:status %)) results)))
              "all other writers observe :exists")
          (is (= 1 (count (set (map :crash-durable? results))))
              "all writers report the same effective durability")
          (is (= value (store/resolve-artifact backend hash))
              "read-after-write converges on the single canonical artifact")
          (is (= (store/canonical-edn value) (stored-bytes backend hash))
              "stored bytes are byte-for-byte the canonical encoding"))
        (finally (.shutdownNow pool))))))

(deftest idempotent-content-identical-concurrent-interleavings
  (testing "result is independent of writer scheduling/interleaving"
    (testing "high-repeat stress (20 rounds, no artificial delay)"
      (dotimes [_ 20]
        (let [backend (temp-store)
              value (artifact :one)
              hash (:artifact/hash value)]
          (assert-converged backend hash value
                            (:results (run-identical backend hash value 16))))))
    (testing "artificial delay before publication widens the contention window"
      (let [orig (deref (find-var (quote resolver-sim.io.content-addressed-store/atomic-create!)))]
        (with-redefs [store/atomic-create! (fn [t c] (Thread/sleep 25) (orig t c))]
          (dotimes [_ 8]
            (let [backend (temp-store)
                  value (artifact :one)
                  hash (:artifact/hash value)]
              (assert-converged backend hash value
                                (:results (run-identical backend hash value 16))))))))
    (testing "artificial delay during read-back verification (winner and losers)"
      (let [orig store/resolve-artifact]
        (with-redefs [store/resolve-artifact (fn [s h] (Thread/sleep 15) (orig s h))]
          (dotimes [_ 8]
            (let [backend (temp-store)
                  value (artifact :one)
                  hash (:artifact/hash value)]
              (assert-converged backend hash value
                                (:results (run-identical backend hash value 16))))))))))

(deftest idempotent-content-identical-durability
  (testing ":created and :exists use the same durability semantics; repeats do not weaken durability"
    (let [backend (temp-store)
          value (artifact :one)
          hash (:artifact/hash value)
          r1 (put backend hash value)
          r2 (put backend hash value)]
      (is (= (:crash-durable? r1) (:crash-durable? r2))
          "created and exists paths report identical durability")
      (dotimes [_ 20]
        (let [r (put backend hash value)]
          (is (= :exists (:status r)))
          (is (= (:crash-durable? r1) (:crash-durable? r))
              "repeated duplicates never regress the durability claim"))))))

(deftest idempotent-content-identical-no-directory-fsync
  (testing "host without directory fsync: first and duplicate report consistent non-durable outcomes"
    (let [backend (temp-store)
          value (artifact :one)
          hash (:artifact/hash value)]
      (with-redefs [store/force-directory! (constantly false)]
        (let [r1 (put backend hash value)
              r2 (put backend hash value)]
          (is (= :created (:status r1)))
          (is (= :exists (:status r2)))
          (is (false? (:crash-durable? r1))
              "first write truthfully reports no directory fsync")
          (is (false? (:crash-durable? r2))
              "duplicate does not fabricate durability the host cannot provide"))))))

(deftest idempotent-content-retry-after-publish
  (testing "retry after 'publish succeeded but caller never received the response'"
    (let [backend (temp-store)
          value (artifact :one)
          hash (:artifact/hash value)
          r1 (put backend hash value)]
      (is (= :created (:status r1)))
      (let [r2 (put backend hash value)]
        (is (= :exists (:status r2))
            "retry resolves through the identical-content :exists path, not a collision")
        (is (= value (:artifact r2)))
        (is (= value (store/resolve-artifact backend hash)))
        (is (= (store/canonical-edn value) (stored-bytes backend hash))
            "stored bytes unchanged by the retry"))))
  (testing "failure before publication leaves the key absent; retry behaves like a fresh write"
    (let [backend (temp-store)
          value (artifact :one)
          hash (:artifact/hash value)
          calls (atom 0)
          orig (deref (find-var (quote resolver-sim.io.content-addressed-store/atomic-create!)))]
      (with-redefs [store/atomic-create! (fn [t c]
                                           (if (= 1 (swap! calls inc))
                                             (throw (ex-info "simulated pre-publication failure" {}))
                                             (orig t c)))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (put backend hash value))
            "first attempt fails before publication")
        (is (nil? (store/resolve-artifact backend hash))
            "canonical key is absent after a pre-publication failure")
        (is (= :created (:status (put backend hash value)))
            "retry after pre-publication failure behaves like a fresh write")
        (is (= value (store/resolve-artifact backend hash)))))))

;; ── 2. Idempotent key — conflicting content ───────────────────────────

(deftest idempotent-key-conflicting-sequential
  (testing "same key + different content: first content stays authoritative; later writes collide"
    (let [backend (temp-store)
          value (artifact :one)
          hash (:artifact/hash value)
          r1 (put backend hash value)]
      (is (= :created (:status r1)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collision"
                            (store/put-if-absent! backend
                                                  {:hash-reference hash
                                                   :artifact (assoc value :value :tampered)
                                                   :verify (constantly true)}))
          "different content under the same key never silently succeeds")
      (is (= value (store/resolve-artifact backend hash))
          "first content remains authoritative")
      (is (= (store/canonical-edn value) (stored-bytes backend hash))
          "stored bytes are the first writer's complete content"))))

(deftest idempotent-key-conflicting-concurrent
  (testing "concurrent conflicting writers: exactly one byte sequence becomes visible; losers collide"
    (let [backend (temp-store)
          probe-hash (hash-ref/sha256-ref
                      (hc/hash-with-intent {:hash/intent :evidence-record}
                                           {:artifact/type :test-artifact :value :probe}))
          n 12
          pool (java.util.concurrent.Executors/newFixedThreadPool 8)
          futures (mapv (fn [i]
                          (.submit pool
                                   ^java.util.concurrent.Callable
                                   (fn []
                                     (try
                                       (store/put-if-absent! backend
                                                             {:hash-reference probe-hash
                                                              :artifact (artifact (keyword (str "writer-" i)))
                                                              :verify valid?})
                                       (catch Exception e (ex-message e))))))
                        (range n))]
      (try
        (let [results (mapv #(deref % 20000 ::timeout) futures)
              success (count (filter #(and (map? %) (= :created (:status %))) results))
              collisions (count (filter #(and (string? %) (re-find #"collision" %)) results))
              unexpected (count (filter #(and (string? %) (not (re-find #"collision" %))) results))]
          (is (not-any? #(= ::timeout %) results) "no writer hangs")
          (is (= 1 success) "exactly one writer wins the key")
          (is (= (dec n) collisions) "every losing writer fails with :hash-content-collision")
          (is (zero? unexpected) "no losing writer overwrites or replaces the winner")
          (is (= (count results) (+ success collisions unexpected))
              "every writer outcome is accounted for")
          (let [stored (store/resolve-artifact backend probe-hash)]
            (is (some? stored) "a complete artifact is visible")
            (is (= stored (:artifact (first (filter #(= :created (:status %)) results))))
                "final content is one complete contender, never a mixture")))
        (finally (.shutdownNow pool))))))

;; ── 3. Atomic visibility ──────────────────────────────────────────────

(deftest atomic-visibility-no-torn-read
  (testing "readers observe only 'absent' or the complete artifact while contended writers publish"
    (let [backend (temp-store)
          value (artifact :one)
          hash (:artifact/hash value)
          stop (atom false)
          observations (atom [])
          orig (deref (find-var (quote resolver-sim.io.content-addressed-store/atomic-create!)))]
      (with-redefs [store/atomic-create! (fn [t c]
                                           (Thread/sleep 20)
                                           (orig t c))]
        ;; Daemon reader thread: clojure.core/future's send-off pool thread is
        ;; non-daemon here and would keep the JVM alive long after the test body
        ;; completes (observed ~60s JVM-exit hang).
        (let [reader (Thread.
                      (fn []
                        (while (not @stop)
                          (try
                            (swap! observations conj (store/resolve-artifact backend hash))
                            (catch Exception e (swap! observations conj :error)))))
                      "cas-no-torn-read-reader")]
          (.setDaemon reader true)
          (.start reader)
          (let [pool (java.util.concurrent.Executors/newFixedThreadPool 8)]
            (try
              (let [writers (mapv (fn [_]
                                    (.submit pool
                                             ^java.util.concurrent.Callable
                                             (fn [] (put backend hash value))))
                                  (range 12))]
                (doseq [f writers] (deref f 20000 ::timeout))
                (Thread/sleep 50)
                (reset! stop true)
                (.join reader 5000)
                (let [obs @observations]
                  (is (some some? obs) "reader observed the artifact during the contended run")
                  (is (not-any? #(= :error %) obs) "reader never hit a read error")
                  (is (every? #(or (nil? %) (= value %)) obs)
                      "every observation is absent or the complete artifact — never a partial write")
                  (is (nil? (some #(and (some? %) (not= value %)) obs))
                      "no intermediate/truncated/mixed bytes are ever observable")))
              (finally
                (reset! stop true)
                (.shutdownNow pool)))))))))

(deftest winner-never-replaced
  (testing "a losing writer can never overwrite or replace the winner"
    (let [backend (temp-store)
          probe-hash (hash-ref/sha256-ref
                      (hc/hash-with-intent {:hash/intent :evidence-record}
                                           {:artifact/type :test-artifact :value :probe}))
          winners (atom [])
          n 12
          pool (java.util.concurrent.Executors/newFixedThreadPool 8)
          futures (mapv (fn [i]
                          (.submit pool
                                   ^java.util.concurrent.Callable
                                   (fn []
                                     (let [v (artifact (keyword (str "writer-" i)))]
                                       (try
                                         (let [r (store/put-if-absent! backend
                                                                       {:hash-reference probe-hash
                                                                        :artifact v
                                                                        :verify valid?})]
                                           (when (= :created (:status r)) (swap! winners conj v))
                                           :ok)
                                         (catch Exception e (ex-message e)))))))
                        (range n))]
      (try
        (let [results (mapv #(deref % 20000 ::timeout) futures)]
          (is (not-any? #(= ::timeout %) results))
          (is (= 1 (count @winners)) "exactly one winner among contended writers")
          (let [winner (first @winners)
                stored (store/resolve-artifact backend probe-hash)]
            (is (= winner stored) "stored artifact is exactly the winner's complete content")
            (is (= (store/canonical-edn winner) (stored-bytes backend probe-hash))
                "stored bytes match the winner byte-for-byte — no mixture, no replacement")
            (is (= :exists (:status (store/put-if-absent! backend
                                                          {:hash-reference probe-hash
                                                           :artifact winner
                                                           :verify valid?})))
                "the winner is re-acknowledged :exists by a later identical writer")))
        (finally (.shutdownNow pool))))))

;; ── 4. Retry after interruption — orphaned temp files ─────────────────

(deftest orphan-temp-does-not-affect-key
  (testing "orphaned temp files never affect canonical reads or future writes"
    (let [backend (temp-store)
          value (artifact :one)
          hash (:artifact/hash value)
          target (store/artifact-path backend hash)
          sha256-dir (.getParentFile target)]
      (put backend hash value)
      ;; Scatter crash-leftover temp files next to the canonical key
      (doseq [i (range 5)]
        (spit (java.io.File. sha256-dir (str "." (.getName target) ".tmp-orphan-" i))
              "partial-garbage-not-canonical"))
      (is (= value (store/resolve-artifact backend hash)) "orphans do not affect canonical reads")
      (is (= :exists (:status (put backend hash value))) "orphans do not affect identical retries")
      (let [other (artifact :two)
            other-hash (:artifact/hash other)]
        (is (= :created (:status (put backend other-hash other)))
            "orphans do not affect writes to other keys"))
      (is (= (store/canonical-edn value) (stored-bytes backend hash))
          "original artifact is byte-identical after orphan noise"))))

;; ── 7. Content ↔ key integrity boundary ───────────────────────────────

(deftest content-key-mismatch-boundary
  (testing "key/content consistency is caller-trusted: the store guarantees key occupancy, not key correctness"
    (let [backend (temp-store)
          value (artifact :one)
          correct-hash (:artifact/hash value)
          wrong-hash (hash-ref/sha256-ref
                      (hc/hash-with-intent {:hash/intent :evidence-record}
                                           {:artifact/type :test-artifact :value :something-else}))]
      (is (not= correct-hash wrong-hash) "precondition: the two references differ")
      (is (= :created (:status (put backend wrong-hash value)))
          "store accepts content under a caller-supplied key when :verify self-verifies the artifact")
      (is (= value (store/resolve-artifact backend wrong-hash))
          "artifact is retrievable under the caller-supplied key")
      (is (nil? (store/resolve-artifact backend correct-hash))
          "artifact is NOT retrievable under the reference implied by its own content")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collision"
                            (store/put-if-absent! backend
                                                  {:hash-reference wrong-hash
                                                   :artifact (artifact :tampered)
                                                   :verify (constantly true)}))
          "the caller-trusted key is immutable once occupied"))))

;; ── 6. Read-back validation fails closed ──────────────────────────────

(deftest readback-validation-fails-closed
  (testing "corrupt/non-canonical stored bytes fail closed; never reported as successful idempotency"
    (let [backend (temp-store)
          value (artifact :one)
          hash (:artifact/hash value)]
      (put backend hash value)
      (spit (store/artifact-path backend hash) "{:not :canonical !!}")
      (is (thrown? clojure.lang.ExceptionInfo (store/resolve-artifact backend hash))
          "resolve-artifact fails closed on non-canonical stored bytes")
      (is (thrown? clojure.lang.ExceptionInfo
                   (store/verify-stored-artifact backend hash (constantly true)))
          "verify-stored-artifact fails closed rather than reporting an artifact")
      (is (thrown? clojure.lang.ExceptionInfo
                   (put backend hash value))
          "put-if-absent! fails closed instead of interpreting corrupt bytes as successful idempotency"))))
