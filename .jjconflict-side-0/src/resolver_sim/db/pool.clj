(ns resolver-sim.db.pool
  "Configurable bounded connection pool for the PostgreSQL admission store.

   Built on `next.jdbc.connection/->pool` over HikariCP, which is the pooling
   provider already bundled transitively by next.jdbc — no new dependency and no
   second pool framework.

   Capacity model (documented in docs/operations/postgres-admission.md):
       per-instance pool size × application-instance count   ≤  DB slot budget
   Pool sizing is therefore configurable per instance and must be chosen knowing
   how many independent application/JVM instances share the database. There is
   no assumption that only one JVM exists.

   Application-instance identity is diagnostic only — it must never be part of
   canonical protocol semantics. It is surfaced in log lines and Hikari pool
   name so owners can attribute a connection to a specific instance/process."
  (:require [clojure.string :as str]
            [resolver-sim.config.hardening :as hardening])
  (:import [com.zaxxer.hikari HikariConfig HikariDataSource]))

(defn instance-id
  "Stable-ish identity for THIS application process, for diagnostics only:
   host name + pid. Never used for protocol authority."
  []
  (let [host (or (System/getenv "HOSTNAME") "unknown-host")
        pid (or (System/getenv "PID") "?")]
    (str host ":" pid)))

(defn- hikari-config
  [{:keys [jdbcUrl user password pool-size max-pool-size
           min-idle idle-timeout-ms connection-timeout-ms max-lifetime-ms
           pool-name postgres-options]
    :or {pool-size (hardening/value :db-pool-size {:fallback 5})
         pool-name (str "prf-" (instance-id))}}]
  (let [cfg (doto (HikariConfig.)
              (.setPoolName (str pool-name "-" (System/currentTimeMillis)))
              (.setJdbcUrl jdbcUrl)
              (.setDriverClassName "org.postgresql.Driver"))]
    (when user (.setUsername cfg user))
    (when password (.setPassword cfg password))
    (.setMaximumPoolSize cfg (long (or max-pool-size pool-size)))
    (.setMinimumIdle cfg (long (or min-idle
                                   (hardening/value :db-pool-min-idle {:fallback 1}))))
    (.setIdleTimeout cfg (long (or idle-timeout-ms
                                   (hardening/value :db-pool-idle-timeout-ms {:fallback 600000}))))
    (.setConnectionTimeout cfg (long (or connection-timeout-ms
                                         (hardening/value :db-pool-connection-timeout-ms {:fallback 30000}))))
    (.setMaxLifetime cfg (long (or max-lifetime-ms
                                   (hardening/value :db-pool-max-lifetime-ms {:fallback 1800000}))))
     ;; Fail fast rather than queue requests against a black-holed DB.
    (.setConnectionInitSql cfg (or (:init-sql postgres-options) "SELECT 1"))
    cfg))

(defn pool
  "Build a bounded Hikari connection pool from a JDBC URL (and optional map).
   The returned value is an auto-closeable javax.sql.DataSource. Callers own
   `.close` on it when nothing else shares the instance.

   Options (map; each independently optional):
     :user / :password            — DB credentials (or embedded in :jdbcUrl)
     :pool-size, :max-pool-size   — Hikari maximum pool size (per instance)
     :min-idle                    — Hikari minimum idle connections
     :idle-timeout-ms             — recycle idle connections (default 10 min)
     :connection-timeout-ms       — acquire timeout (default 30 s)
     :max-lifetime-ms             — max per-connection lifetime (default 30 min)
     :pool-name                   — optional; defaults to instance-id based name"
  (^javax.sql.DataSource [jdbcUrl {:keys [user password] :as opts}]
   (let [url (if (and jdbcUrl user)
               (str jdbcUrl "?user=" user "&password=" password)
               jdbcUrl)]
     (pool (assoc opts :jdbcUrl url))))
  (^javax.sql.DataSource [config]
   (HikariDataSource. (hikari-config config))))

(defn close!
  "Close a pooled datasource if it is closeable. Safe to call on nil."
  [ds]
  (when (instance? java.io.Closeable ds)
    (.close ^java.io.Closeable ds)))

(defn describe
  "Human description of a pool configuration, WITHOUT credentials."
  [{:keys [jdbcUrl max-pool-size pool-size pool-name]}]
  {:pool/name (or pool-name
                  (str "prf-" (instance-id)))
   :pool/size (or max-pool-size pool-size)
   :pool/endpoint (some-> jdbcUrl
                          (str/replace #"password=[^&\s]*" "password=***")
                          (str/replace #"user=\w+" "user=<redacted>"))})

(comment
  ;; Single-instance default: bounded pool for one JVM.
  (def ds (pool "jdbc:postgresql://localhost:5433/postgres" {:user "postgres" :password "postgres"})))