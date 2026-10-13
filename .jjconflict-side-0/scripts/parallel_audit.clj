(ns scripts.parallel-audit
  "Static hard-hazard scan shared by scripts.audit-parallel-safety,
   scripts.run-sew-tests and scripts.parallel-test-runner.

   This is the single source of truth for namespace-parallel hazard detection.
   The `parallel-excluded-namespaces` sets in the two runners are DERIVED from
   this scan (see `hard-hazard-namespaces`) rather than hand-maintained lists,
   so the exclusion set can no longer drift from the audit.

   Hazard classes scanned (see review §7):
     hard  — definitely parallel-unsafe: direct System/out or System/err
             writes (bypass dynamic capture), raw Java threads / executors,
             fixed temp/target/coverage/cache paths, ports/servers, JVM
             property mutation, var intern/alter-var-root on other namespaces.
     soft  — investigate: defonce/atom shared state, random seeds, scenario
             runs with :parallel?, global clocks."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def search-roots
  ["test" "protocols_src/test" "src" "protocols_src"])

(defn ns->path
  [sym]
  (-> (str sym) (str/replace "-" "_") (str/replace "." "/") (str ".clj")))

(defn locate-file
  [sym]
  (let [rel (ns->path sym)]
    (some (fn [root]
            (let [f (io/file root rel)]
              (when (.exists f) (.getPath f))))
          search-roots)))

(def hard-patterns
  {:system-out-err
   {:msg "Direct writes to System/out or System/err bypass dynamic *out*/capture and leak into the coordinator stream."
    :patterns [#"System/out\.|System/err\."]}
   :jvm-state
   {:msg "JVM-wide state mutation races between namespaces."
    :patterns [#"System/setProperty" #"System/clearProperty" #"System/setOut" #"System/setErr" #"System/setIn"]}
   :raw-threads
   {:msg "Raw Java threads/executors do not convey dynamic bindings; work dispatched off-thread may miss per-namespace evidence/reporting context."
    :patterns [#"newFixedThreadPool" #"newCachedThreadPool" #"newSingleThreadExecutor"
               #"newScheduledThreadPool" #"\.newThread\(" #"new Thread\b"]}
   :ports-servers
   {:msg "Fixed ports/local servers collide between concurrent namespaces."
    :patterns [#"new ServerSocket" #"ServerSocket" #"localhost:\d+" #"java\.net\.Socket" #"\.bind\("
              #"(?<![\w]):\d{4,}\b"]}
   :fixed-path-writes
   {:msg "Writes to fixed/shared paths (target/, out/, coverage/, caches) clobber across namespaces."
    :patterns [#"\"target/" #"\"out/" #"\"coverage/" #"\.cpcache" #"\"/tmp/" #"\"/dev/shm/"]}
   :var-mutation
   {:msg "alter-var-root / intern / var-set on shared vars is process-global."
    :patterns [#"alter-var-root" #"var-set" #"\bintern\s" #"with-redefs"]}})

(def soft-patterns
  {:shared-state
   {:msg "defonce/atom defined at namespace scope may hold cross-test state."
    :patterns [#"defonce" #"^\(def .*atom"]}
   :random
   {:msg "Environment/clock-dependent values make ordering assertions flaky."
    :patterns [#"rand-int" #"Math/random" #"Random\." #"UUID/randomUUID"
               #"System/currentTimeMillis" #"Instant/now"]}
   :parallel-scenarios
   {:msg "scenario_runner execution with :parallel? has no canonical evidence ordering; ordering assertions under it are not locally deterministic."
    :patterns [#":parallel?\s+true"]}})

(defn- scan-lines
  [lines pattern-map]
  (let [entries (map-indexed (fn [i l] {:n (inc i) :text l}) lines)]
    (reduce-kv
     (fn [acc cat {:keys [patterns]}]
       (reduce (fn [acc2 rx]
                 (reduce (fn [acc3 {:keys [n text]}]
                           (if-let [m (re-find rx text)]
                             (update acc3 cat (fnil conj [])
                                     {:n n :text text :match (str m)})
                             acc3))
                         acc2
                         entries))
               acc
               patterns))
     {}
     pattern-map)))

(defn audit-one
  [sym]
  (let [path (locate-file sym)
        missing? (nil? path)]
    {:namespace sym
     :path (or path :missing)
     :hard (if missing? {} (scan-lines (str/split-lines (slurp path)) hard-patterns))
     :soft (if missing? {} (scan-lines (str/split-lines (slurp path)) soft-patterns))}))

(defn hard-hazard-namespaces
  "Return the subset of `syms` that carry at least one hard hazard per
   `hard-patterns`.  This is the canonical input for deriving a lane's
   parallel-excluded-namespaces, so the exclusion set stays in lock-step with
   the audit."
  [syms]
  (filterv (comp seq :hard) (map audit-one syms)))

(defn hard-hazard-syms
  "Vector of namespace symbols (from coll `syms`) whose source carries a hard
   hazard, in input order.  Convenience wrapper over `hard-hazard-namespaces`."
  [syms]
  (mapv :namespace (hard-hazard-namespaces syms)))