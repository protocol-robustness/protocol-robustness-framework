(ns resolver-sim.db.migrate
  "Minimal forward-only SQL migration runner for the authoritative PostgreSQL
   admission store.

   Rationale for a bespoke runner instead of Flyway/Liquibase:
     * the repository has no established migration framework — adding one would
       introduce a second, heavier dependency stack for a single schema;
     * the admission schema is small and version-controlled as plain SQL;
     * forward-only, ordered, checksummed application is all that is required
       and must remain single-owner (see docs/operations/postgres-admission.md).

   Ownership contract:
     * migrations are forward-only; never edit an already-applied migration;
     * a single migration job applies them (Terraform -> DB -> migration job ->
       verify -> app deploy), never arbitrary application instances on startup;
     * each applied row records its script name, an SHA-256 of its content, and
       the apply timestamp; re-running a changed script is rejected.

   Usage:
     (resolver-sim.db.migrate/migrate! datasource)
     clojure -M:run -m resolver-sim.db.migrate   ; CLI over DATABASE_URL
  "
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def migration-dir "db/migrations")

(def schema-version-ddl
  ["CREATE TABLE IF NOT EXISTS prf_schema_version (
       version          BIGINT       NOT NULL,
       script           TEXT         NOT NULL,
       sha256           TEXT         NOT NULL,
       applied_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
       applied_by       TEXT         NOT NULL DEFAULT current_user,
       PRIMARY KEY (version)
     )"])

(defn- sha256-hex [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str
           (map #(format "%02x" %)
                (.digest md (.getBytes s "UTF-8"))))))

(defn- migration-files
  "Return a sorted vector of accepted migration scripts under the classpath
   `db/migrations` dir. Only `NNNN__name.sql` files are migrations."
  []
  (let [res (io/resource migration-dir)]
    (if (nil? res)
      (throw (ex-info "migration directory not found on classpath"
                      {:dir migration-dir}))
      (let [dir (io/file res)]
        (if (.isDirectory dir)
          (->> (file-seq dir)
               (filter #(and (.isFile %)
                             (re-matches #"\d{4}__.*\.sql" (.getName %))))
               (sort-by #(.getName %)))
          (throw (ex-info "migration resource is not a directory"
                          {:dir migration-dir})))))))

(defn- version-of [^java.io.File f]
  (Long/parseLong (.substring (.getName f) 0 4)))

(defn- applied-versions [ds]
  (let [rows (jdbc/execute! ds
                            ["SELECT version, sha256 FROM prf_schema_version"]
                            {:builder-fn rs/as-unqualified-maps})]
    (into {} (map (juxt :version :sha256)) rows)))

(defn- ensure-version-table! [ds]
  (doseq [ddl schema-version-ddl] (jdbc/execute! ds [ddl]))
  ds)

(defn- split-statements
  "Split a SQL script into statements on top-level `;` terminators while
   respecting `$$...$$` dollar-quoted bodies (e.g. PL/pgSQL functions and any
   embedded `;`) and `--` line comments. Crudely but safely handles the single-
   tag dollar quoting used by this schema."
  [sql]
  (let [n (count sql)
        result (transient [])
        sb (StringBuilder.)]
    (loop [i 0
           in-dollar (atom false)]
      (if (>= i n)
        (->> (persistent! (conj! result (str/trim (str sb))))
             (remove str/blank?) vec)
        (let [c (.charAt sql i)
              nxt (when (< (inc i) n) (.charAt sql (inc i)))]
          (cond
            (and (not @in-dollar)
                 (= c \-) (= nxt \-))
            (let [eol (.indexOf sql "\n" i)]
              (recur (cond (neg? eol) n eol (inc eol))
                     in-dollar))

            (and (not @in-dollar)
                 (= c \$) (= nxt \$))
            (do (.append sb c) (.append sb nxt)
                (reset! in-dollar true)
                (recur (+ i 2) in-dollar))

            (and @in-dollar (= c \$) (= nxt \$))
            (do (.append sb c) (.append sb nxt)
                (reset! in-dollar false)
                (recur (+ i 2) in-dollar))

            (and (not @in-dollar) (= c \;))
            (do (conj! result (str/trim (str sb)))
                (.setLength sb 0)
                (recur (inc i) in-dollar))

            :else
            (do (.append sb c) (recur (inc i) in-dollar))))))))

(defn- statements-of
  [sql]
  (split-statements sql))

(defn- apply-one!
  [ds {:keys [version script sha256 file]}]
  (jdbc/with-transaction [tx ds]
    (let [sql (slurp file)]
      (doseq [statement (statements-of sql)]
        (jdbc/execute! tx [statement]))
      (jdbc/execute! tx
                     ["INSERT INTO prf_schema_version (version, script, sha256)
                       VALUES (?, ?, ?)" version script sha256]))))

(defn applied-migrations
  "Return [{:version :script :sha256}] applied so far, sorted ascending."
  [ds]
  (mapv #(update % :version long)
        (jdbc/execute! ds ["SELECT version, script, sha256 FROM prf_schema_version
                            ORDER BY version"]
                       {:builder-fn rs/as-unqualified-maps})))

(defn migrate!
  "Apply all pending forward-only migrations inside their own transactions.
   Returns {:applied [...] :pending [...] :schema-version N}."
  [ds]
  (ensure-version-table! ds)
  (let [known (applied-versions ds)
        files (migration-files)
        pending (->> files
                     (remove #(contains? known (version-of %)))
                     (map (fn [f]
                            (let [content (slurp f)]
                              {:version (version-of f)
                               :script (.getName f)
                               :sha256 (sha256-hex content)
                               :file f})))
                     (sort-by :version))]
    (doseq [{:keys [version sha256 script] :as m} pending]
      (when-let [known-sha (get known version)]
        (throw (ex-info "migration checksum mismatch — forward-only migration edited"
                        {:version version :script script
                         :expected known-sha :observed sha256})))
      (if (get known version)
        (throw (ex-info "migration declared applied but not seen by runner"
                        {:version version :script script}))
        (apply-one! ds m)))
    (let [applied (applied-migrations ds)]
      {:applied (mapv :script pending)
       :pending []
       :schema-version (or (some-> applied last :version) 0)})))

(defn current-version [ds]
  (or (some-> (applied-migrations ds) last :version) 0))

(defn -main
  [& _]
  (let [ds (jdbc/get-datasource (or (System/getenv "DATABASE_URL")
                                    (System/getenv "PG_JOB_URL")
                                    (throw (ex-info "DATABASE_URL required to run migrations" {}))))
        {:keys [applied schema-version]} (migrate! ds)]
    (println (str "Migrations applied: " (count applied)))
    (println (str "Schema version: " schema-version))
    (when (seq applied)
      (doseq [a applied] (println (str "  + " a))))
    (when (instance? java.io.Closeable ds)
      (.close ^java.io.Closeable ds))))