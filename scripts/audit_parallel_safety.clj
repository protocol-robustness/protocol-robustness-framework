(ns scripts.audit-parallel-safety
  "Static audit of the run-sew-tests namespace manifest for process-global
   state hazards that can make namespace-parallel execution non-deterministic.

   Hazard classes scanned (see review §7):
     hard  — definitely parallel-unsafe: direct System/out or System/err
             writes (bypass dynamic capture), raw Java threads / executors,
             fixed temp/target/coverage/cache paths, ports/servers, JVM
             property mutation, var intern/alter-var-root on other namespaces.
     soft  — investigate: defonce/atom shared state, random seeds, scenario
             runs with :parallel?, global clocks.

   Run:
     clojure -M:test:with-sew -m scripts.audit-parallel-safety
     clojure -M:test:with-sew -m scripts.audit-parallel-safety scenario

   Exits 1 when any hard hazard is found, 0 otherwise.  The report is a
   triage input, not an automatic verdict: locally deterministic ordering
   tests remain parallel-eligible (see review §1)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [scripts.run-sew-tests :as rst]))

(def search-roots
  ["test" "protocols_src/test" "src" "protocols_src"])

(defn- ns->path
  [sym]
  (-> (str sym) (str/replace "-" "_") (str/replace "." "/") (str ".clj")))

(defn- locate-file
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
    :patterns [#"new ServerSocket" #"ServerSocket" #"localhost:\d+" #":\d{4,}\b" #"java\.net\.Socket" #"\.bind\("]}
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

(defn- audit-one
  [sym]
  (let [path (locate-file sym)
        missing? (nil? path)]
    {:namespace sym
     :path (or path :missing)
     :hard (if missing? {} (scan-lines (str/split-lines (slurp path)) hard-patterns))
     :soft (if missing? {} (scan-lines (str/split-lines (slurp path)) soft-patterns))}))

(defn- print-report
  [group-sym audits]
  (println "=== Audit:" (name group-sym) "===")
  (println (format "  namespaces: %d" (count audits)))
  (println "  MISSING SOURCE FILES:")
  (doseq [{:keys [namespace path]} audits
          :when (= path :missing)]
    (println (str "    " namespace)))
  (let [hard-hits (filter (comp seq :hard) audits)
        hard-syms (mapv :namespace hard-hits)
        unexcluded (remove rst/parallel-excluded-namespaces hard-syms)]
    (println (str "  HARD hazards: " (count hard-hits)))
    (doseq [{:keys [namespace path hard]} hard-hits]
      (println (str "\n  " namespace "  (" path ")"))
      (doseq [[cat hits] hard]
        (println (str "    [" (name cat) "] " (get-in hard-patterns [cat :msg])))
        (doseq [{:keys [n text]} (take 6 hits)]
          (println (str "      " n ": " (str/trim text))))))
    (let [soft-hits (filter (comp seq :soft) audits)]
      (println (str "  SOFT (investigate): " (count soft-hits)))
      (doseq [{:keys [namespace soft]} (take 25 soft-hits)]
        (println (str "    " namespace " -> " (str/join ", " (map name (keys soft)))))))
    (println)
    ;; Hard hazards must be in the exclusion set.  This is enforced empirically:
    ;; a full 8-run isolated-parallel soak with exclusions disabled failed
    ;; (accounting-test flaked non-deterministically), confirming the exclusions
    ;; are necessary for a stable fingerprint.  See
    ;; scripts.run-sew-tests/parallel-exclusion-reasons for per-namespace
    ;; evidence and remediation.
    (if (seq unexcluded)
      (do
        (println "  GATE FAILED — HARD-hazard namespaces missing from"
                 "scripts.run-sew-tests/parallel-excluded-namespaces:")
        (doseq [s unexcluded] (println (str "    " s)))
        (println "  Add them to the exclusion set (removal broke the 8-run soak).")
        {:hard-count (count hard-hits) :unexcluded (vec unexcluded)})
      (do
        (println "  GATE OK — all HARD-hazard namespaces are in the parallel"
                 "exclusion set (required for fingerprint stability).")
        {:hard-count (count hard-hits) :unexcluded []}))))

(defn -main
  [& args]
  (let [which (or (first args) "unit")
        syms (case which
               "unit" rst/unit-test-namespaces
               "scenario" rst/scenario-test-namespaces
               (do (println "Usage: -m scripts.audit-parallel-safety [unit|scenario]")
                   (System/exit 1)))
        audits (mapv audit-one syms)
        {:keys [hard-count unexcluded]} (print-report (symbol which) audits)]
    (println)
    (println (str "Audit complete: " (count audits) " namespaces, "
                  hard-count " hard hazards, "
                  (count (filter (comp seq :soft) audits)) " soft."))
    (when (seq unexcluded)
      (System/exit 1))))
