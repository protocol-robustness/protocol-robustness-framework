(ns resolver-sim.benchmark.distributed.postgres-coordination-test
  "PostgreSQL integration coverage for immutable distributed benchmark chunks.
   Canonical publication is intentionally outside this namespace."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [next.jdbc :as jdbc]
            [resolver-sim.benchmark.distributed.coordination :as coord]
            [resolver-sim.db.migrate :as migrate]
            [resolver-sim.db.pool :as pool]))

(def ^:dynamic *ds* nil)
(def ^:private url "jdbc:postgresql://localhost:5433/postgres?user=postgres&password=postgres")
(defn- db-url [] (or (System/getenv "DATABASE_URL") url))
(defn- root [ch] (str "sha256:" (apply str (repeat 64 ch))))
(defn- run [] {:run-id "distributed-test-run"
               :run-plan-root (root "a") :execution-plan-root (root "b")
               :chunks [{:chunk-id "chunk-0001" :expected-input-root (root "c")
                         :expected-work-root (root "d")}
                        {:chunk-id "chunk-0002" :expected-input-root (root "e")
                         :expected-work-root (root "f")}]})
(defn- completion
  ([lease result] (completion lease result :sensitivity/public))
  ([lease result sensitivity-level]
   {:run-id (:run-id lease) :execution-plan-root (:execution-plan-root lease)
    :chunk-id (:chunk-id lease) :expected-work-root (:expected-work-root lease)
    :worker-id (:worker-id lease) :fence (:fence lease)
    :result-root (root result) :result-manifest-root (root "m")
    :result-ref (str result "/detached-result.edn")
    :sensitivity-root (root "s") :sensitivity-level sensitivity-level}))

(defn- skip-if-no-db [f]
  (try
    (let [ds (pool/pool (db-url) {})]
      (jdbc/execute-one! ds ["SELECT 1"])
      (.close ds)
      (f))
    (catch Exception e
      (println "PostgreSQL distributed coordination tests skipped (" (.getMessage e) ")"))))

(defn- fixture [f]
  (let [ds (pool/pool (db-url) {})]
    (migrate/migrate! ds)
    (binding [*ds* ds]
      (try (f)
           (finally
             (jdbc/execute! ds ["DELETE FROM benchmark_execution_run WHERE run_id LIKE ?" "distributed-test-run%"])
             (.close ds))))))

(use-fixtures :once skip-if-no-db)
(use-fixtures :each fixture)

(deftest fixed-registration-is-an-exact-immutable-chunk-set
  (let [registered (coord/register-run! *ds* (run))
        reordered (update (run) :chunks #(vec (reverse %)))]
    (is (= 2 (:chunk-count registered)))
    (is (= registered (coord/register-run! *ds* reordered)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (coord/register-run! *ds*
                                      (update-in (run) [:chunks] conj
                                                 {:chunk-id "chunk-0003"
                                                  :expected-input-root (root "1")
                                                  :expected-work-root (root "2")}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (coord/register-run! *ds* (assoc (run) :chunks [(first (:chunks (run)))]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (coord/register-run! *ds*
                                      (assoc (run) :chunks [(first (:chunks (run)))
                                                            (first (:chunks (run)))]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (coord/register-run! *ds*
                                      (assoc-in (run) [:chunks 1 :expected-work-root] (root "9")))))))

(deftest fixed-claims-are-fenced-and-completion-is-strictly-bound
  (coord/register-run! *ds* (run))
  (let [first (coord/claim-chunk! *ds* {:run-id "distributed-test-run" :worker-id "worker-a" :lease-ms 60000})
        complete (coord/complete-chunk! *ds* (completion first "r"))
        retry (coord/complete-chunk! *ds* (completion first "r"))
        conflict (coord/complete-chunk! *ds* (completion first "x"))]
    (is (= :leased (:outcome first)))
    (is (= 1 (:fence first)))
    (is (= :completed (:outcome complete)))
    (is (= :idempotent-completion (:outcome retry)))
    (is (= :completed-result-conflict (:reason conflict)))
    (is (= (:result-root complete)
           (:result-root (coord/resolve-chunk-completion! *ds* "distributed-test-run" (:chunk-id first)))))))

(deftest sensitivity-level-is-required-valid-and-part-of-completion-identity
  (coord/register-run! *ds* (run))
  (let [lease (coord/claim-chunk! *ds* {:run-id "distributed-test-run" :worker-id "worker-a" :lease-ms 60000})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (coord/complete-chunk! *ds* (assoc (completion lease "r") :sensitivity-level nil))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (coord/complete-chunk! *ds* (completion lease "r" :sensitivity/unknown))))
    (is (= :completed (:outcome (coord/complete-chunk! *ds* (completion lease "r")))))
    (is (= :completed-result-conflict
           (:reason (coord/complete-chunk! *ds* (completion lease "r" :sensitivity/internal)))))))

(deftest db-expiry-reclaims-with-a-new-fence-and-rejects-the-stale-worker
  (coord/register-run! *ds* (run))
  (let [first (coord/claim-chunk! *ds* {:run-id "distributed-test-run" :worker-id "worker-a" :lease-ms 1})]
    (jdbc/execute! *ds* ["UPDATE benchmark_execution_chunk
                          SET lease_expires_at = clock_timestamp()
                          WHERE run_id = ? AND chunk_id = ?"
                         "distributed-test-run" (:chunk-id first)])
    (let [replacement (coord/claim-chunk! *ds* {:run-id "distributed-test-run" :worker-id "worker-b" :lease-ms 60000})
          accepted (coord/complete-chunk! *ds* (completion replacement "r"))
          stale (coord/complete-chunk! *ds* (completion first "r"))]
      (is (= (:chunk-id first) (:chunk-id replacement)))
      (is (= 2 (:fence replacement)))
      (is (= :completed (:outcome accepted)))
      (is (= :stale-fence (:reason stale))))))

(deftest terminal-lifecycle-blocks-new-work-and-requires-all-completions
  (coord/register-run! *ds* (run))
  (let [first (coord/claim-chunk! *ds* {:run-id "distributed-test-run" :worker-id "worker-a" :lease-ms 60000})]
    (is (= :incomplete-run (:reason (coord/mark-run-execution-complete! *ds* "distributed-test-run"))))
    (is (= :failed (:outcome (coord/fail-run! *ds* "distributed-test-run"))))
    (is (nil? (coord/claim-chunk! *ds* {:run-id "distributed-test-run" :worker-id "worker-b" :lease-ms 60000})))
    (is (= :run-failed (:reason (coord/complete-chunk! *ds* (completion first "r"))))))
  (coord/register-run! *ds* (assoc (run) :run-id "distributed-test-run-2"))
  (let [a (coord/claim-chunk! *ds* {:run-id "distributed-test-run-2" :worker-id "worker-a" :lease-ms 60000})
        b (coord/claim-chunk! *ds* {:run-id "distributed-test-run-2" :worker-id "worker-b" :lease-ms 60000})]
    (coord/complete-chunk! *ds* (completion a "r"))
    (coord/complete-chunk! *ds* (completion b "t"))
    (is (= :execution-complete (:outcome (coord/mark-run-execution-complete! *ds* "distributed-test-run-2"))))
    (is (nil? (coord/claim-chunk! *ds* {:run-id "distributed-test-run-2" :worker-id "worker-c" :lease-ms 60000})))
    (is (= :idempotent-completion (:outcome (coord/complete-chunk! *ds* (completion a "r")))))))

(deftest result-existence-does-not-constitute-database-completion
  (coord/register-run! *ds* (run))
  (let [lease (coord/claim-chunk! *ds* {:run-id "distributed-test-run" :worker-id "worker-a" :lease-ms 60000})]
    (is (nil? (coord/resolve-chunk-completion! *ds* "distributed-test-run" (:chunk-id lease))))
    (is (= :completed
           (:outcome (coord/complete-chunk! *ds* (completion lease "r")))))))
