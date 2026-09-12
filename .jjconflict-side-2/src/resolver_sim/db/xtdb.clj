(ns resolver-sim.db.xtdb
  "XTDB pgwire connection and SQL utilities for simulation persistence.

   Provides the datasource factory, timestamp/keyword/EDN type coercions,
   and SQL literal builders used by resolver-sim.db.store.

   SQL literal builders (sql-str, sql-bool, sql-long, sql-ts)
   ────────────────────────────────────────────────────────────
   The PostgreSQL JDBC driver in preferQueryMode=simple wraps each `?`
   substitution in parentheses: VALUES (('v1'),('v2'),...).
   XTDB 2.x SQL parser rejects double-parenthesised VALUES expressions.
   We therefore embed literals directly in INSERT strings.
   String values are single-quote-escaped to prevent injection."
  (:require [next.jdbc            :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.edn          :as edn]
            [clojure.string       :as str]))

;; ---------------------------------------------------------------------------
;; Connection
;; ---------------------------------------------------------------------------

(def ^:private defaults
  {:dbtype          "postgresql"
   :host            "localhost"
   :port            5432
   :dbname          "xtdb"
   :user            "xtdb"
   :sslmode         "disable"
   ;; Simple query protocol: driver embeds param values as literals in the
   ;; SQL string before sending.  Required for XTDB 2.x DML compatibility.
   :preferQueryMode "simple"})

(defn ->datasource
  "Create a next.jdbc datasource pointing at the XTDB pgwire endpoint.
   Pass an override map to change host / port / dbname at runtime."
  ([]          (->datasource {}))
  ([overrides] (jdbc/get-datasource (merge defaults overrides))))

(defn datasource-defaults
  "The default XTDB pgwire connection parameters (host / port / db / user).
   Useful for tooling that must verify it is targeting the expected composed
   local XTDB before running a destructive operation."
  []
  defaults)

;; ---------------------------------------------------------------------------
;; Type coercion utilities
;; ---------------------------------------------------------------------------

(defn ->ts
  "Convert a java.util.Date to a java.sql.Timestamp."
  ^java.sql.Timestamp [inst]
  (java.sql.Timestamp. (.getTime ^java.util.Date inst)))

(def epoch
  "Epoch-start used as the default _valid_from so that data inserted without
   an explicit start is found by any realistic valid-time query."
  (->ts #inst "2000-01-01"))

(defn parse-ts
  "Parse a timestamp string returned by XTDB in simple-query mode.
   XTDB returns timestamps as \"yyyy-MM-dd HH:mm:ss+HH\" (short offset form).
   Normalises to ISO-8601 and returns a java.util.Date, or nil for null."
  [s]
  (when s
    (let [normalised (-> s
                         (str/replace #"([+-]\d{2})(?::(\d{2}))?$"
                                      (fn [[_ hh mm]] (str hh ":" (or mm "00"))))
                         (str/replace " " "T"))]
      (java.util.Date/from
       (.toInstant (java.time.OffsetDateTime/parse normalised))))))

(defn kw->str
  "Serialise a keyword preserving its namespace: :use/accepted → \"use/accepted\".
   Returns the string unchanged, coerces non-keyword values via str."
  [k]
  (when k
    (cond
      (string?  k) k
      (keyword? k) (if (namespace k)
                     (str (namespace k) "/" (clojure.core/name k))
                     (clojure.core/name k))
      :else (str k))))

(defn ->edn
  "Serialise a value to an EDN string, or nil if v is nil."
  [v]
  (when v (pr-str v)))

(defn parse-edn
  "Deserialise an EDN string, or nil for nil / empty strings."
  [s]
  (when (and s (pos? (count s)))
    (edn/read-string s)))

(def opts
  "next.jdbc result-set builder that returns unqualified keyword maps."
  {:builder-fn rs/as-unqualified-maps})

;; ---------------------------------------------------------------------------
;; SQL literal builders
;; ---------------------------------------------------------------------------

(defn sql-str
  "Escape and quote a string value for embedding in a SQL literal."
  [s]
  (if (nil? s)
    "NULL"
    (str "'" (str/replace (str s) "'" "''") "'")))

(defn sql-bool [b] (if b "TRUE" "FALSE"))

(defn sql-long [n] (if (nil? n) "NULL" (str (long n))))

(defn sql-ts
  "Format a java.util.Date as an XTDB-compatible TIMESTAMP literal.
   Falls back to epoch when d is nil."
  [d]
  (let [inst (if d (.toInstant ^java.util.Date d) (.toInstant ^java.util.Date epoch))]
    (str "TIMESTAMP '" (.format java.time.format.DateTimeFormatter/ISO_INSTANT inst) "'")))

;; ---------------------------------------------------------------------------
;; Bitemporal query clauses
;;
;; XTDB is bitemporal: every row has an independent valid-time timeline (when a
;; fact is effective in the domain, "as best known") and system-time timeline
;; (when the database learned it, "as we knew it then"). These helpers own the
;; FOR ... clause formatting so callers pass a normalized timestamp value, never
;; an arbitrary preformatted SQL fragment.
;; ---------------------------------------------------------------------------

(defn- as-of-clause [dimension ^java.util.Date d]
  (str "FOR " dimension " AS OF TIMESTAMP '"
       (.format java.time.format.DateTimeFormatter/ISO_INSTANT (.toInstant d)) "'"))

(defn valid-as-of
  "FOR VALID_TIME AS OF <instant>. 'What is effective at T, using today's
   best-known history.'"
  ^String [^java.util.Date d]
  (as-of-clause "VALID_TIME" d))

(defn system-as-of
  "FOR SYSTEM_TIME AS OF <instant>. 'What did the database know at T?'"
  ^String [^java.util.Date d]
  (as-of-clause "SYSTEM_TIME" d))

(def all-system-time
  "FOR ALL SYSTEM_TIME — show every system-time version."
  "FOR ALL SYSTEM_TIME")

(def all-valid-time
  "FOR ALL VALID_TIME — show every valid-time version."
  "FOR ALL VALID_TIME")
