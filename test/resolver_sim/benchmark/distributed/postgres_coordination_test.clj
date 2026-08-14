(ns resolver-sim.benchmark.distributed.postgres-coordination-test
  "PostgreSQL integration coverage for the benchmark-specific operational
   dispatcher. Requires DATABASE_URL or the repository's local PostgreSQL.
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
               :chunks [{:chunk-id "chunk-0001" :expected-work-root (root "c")}
                        {:chunk-id "chunk-0002" :expected-work-root (root "d")}]})
(defn- completion [lease result]
  {:run-id (:run-id lease) :execution-plan-root (:execution-plan-root lease)
   :chunk-id (:chunk-id lease) :expected-work-root (:expected-work-root lease)
   :worker-id (:worker-id lease) :fence (:fence lease)
   :result-root (root result) :result-manifest-root (root "m")
   :result-ref (str result "/detached-result.edn")
   :sensitivity-root (root "s") :sensitivity-level :sensitivity/public})

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
             (jdbc/execute! ds ["DELETE FROM benchmark_execution_run WHERE run_id = ?" "distributed-test-run"])
             (.close ds))))))

(use-fixtures :once skip-if-no-db)
(use-fixtures :each fixture)

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

(deftest db-expiry-reclaims-with-a-new-fence-and-rejects-the-stale-worker
  (coord/register-run! *ds* (run))
  (let [first (coord/claim-chunk! *ds* {:run-id "distributed-test-run" :worker-id "worker-a" :lease-ms 1})]
    ;; Force expiry in the database; no JVM clock participates in the claim.
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

(deftest result-existence-does-not-constitute-database-completion
  (coord/register-run! *ds* (run))
  (let [lease (coord/claim-chunk! *ds* {:run-id "distributed-test-run" :worker-id "worker-a" :lease-ms 60000})]
    ;; A worker may have fully persisted an object in ResultStore and then die.
    ;; Without complete-chunk!, the coordination row remains only leased.
    (is (nil? (coord/resolve-chunk-completion! *ds* "distributed-test-run" (:chunk-id lease))))
    (is (= :completed
           (:outcome (coord/complete-chunk! *ds* (completion lease "r")))))))
