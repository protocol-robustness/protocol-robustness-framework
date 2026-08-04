(ns resolver-sim.trace.conformance.reconcile-runner
  "Trace-domain planned-vs-observed reconciliation (G2a adoption).

   Maps the observed receipts of the trace pipeline onto the conformance plan
   steps and reconciles them:

     :schema-validation    <- conformance receipt (schema validator, per source)
     :semantic-validation  <- conformance receipt (semantic validator, per source)
     :sync-integrity       <- manifest source-sha256 == on-disk source sha256
     :capability-check     <- schema+semantic+replay exercised for the subject
     :replay               <- Solidity replay receipt (per destination)
     :reconciliation       <- derived
     :attestation          <- derived

   Subjects are manifest entry ids; byte-synced-only entries are explicit
   exclusions.  The result is written to results/conformance/
   trace-reconciliation.json and consumed by scripts/reconcile.py."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.conformance.plan :as plan]
            [resolver-sim.conformance.reconciliation :as rec]
            [resolver-sim.conformance.profile :as profile]))

(defn sha256-hex [path]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream path)]
      (let [buf (byte-array 8192)]
        (loop [n (.read in buf)]
          (when (pos? n) (.update digest buf 0 n) (recur (.read in buf))))))
    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))))

(defn- entry-blocks [raw]
  (re-seq #"\{:id[^}]*\}" (subs raw (.indexOf raw ":traces"))))

(defn- entry-field [block k]
  (second (re-find (re-pattern (str ":" k "\\s+\"([^\"]+)\"")) block)))

(defn load-manifest [manifest-path]
  (let [raw (slurp manifest-path)
        blocks (entry-blocks raw)]
    (mapv (fn [b]
            {:id (entry-field b "id")
             :source (entry-field b "source")
             :destination (entry-field b "destination")
             :source-sha256 (entry-field b "source-sha256")})
          blocks)))

(defn conformance-receipt [repo-root source]
  (let [p (io/file repo-root (str source ".conformance.json"))]
    (when (.exists p)
      (json/read-str (slurp p) :key-fn keyword))))

(defn validator-status [cr kind]
  (->> (:validators cr)
       (some (fn [v] (when (= (name kind) (:validation/kind v)) (:validation/status v))))
       (= "pass")))

(defn replay-receipts [sew-repo]
  (let [dir (io/file sew-repo "out" "receipts")]
    (if-not (.isDirectory dir)
      []
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName %) ".json"))
           (mapv (fn [f] (json/read-str (slurp f) :key-fn keyword)))))))

(defn subject-observation
  "Build the observed receipts for one subject id."
  [repo-root sew-repo subject-set-root entry]
  (let [sid (:id entry)
        cr (conformance-receipt repo-root (:source entry))
        src-ok? (= (:source-sha256 entry)
                   (sha256-hex (str repo-root "/" (:source entry))))
        replay (some (fn [r] (when (= (:destination entry) (:fixture_path r)) r))
                     (replay-receipts sew-repo))
        replay-ok? (and replay (= "pass" (:replay_status replay)))
        root (fn [step]
               {:step/id step
                :subject/id sid
                :subject/root (if cr (:subject/root cr)
                                  (str "sha256:" sid))
                :subject-set/root subject-set-root
                :status :pass})]
    (cond-> []
      (and cr (validator-status cr :schema))
      (conj (root :schema-validation))
      (and cr (validator-status cr :semantic))
      (conj (root :semantic-validation))
      src-ok? (conj (root :sync-integrity))
      (and cr (validator-status cr :schema)
           (validator-status cr :semantic)
           replay-ok?)
      (conj (root :capability-check))
      replay-ok? (conj (root :replay)))))

(defn reconcile-trace!
  "Reconcile the trace pipeline against the conformance plan.
   Returns the reconciliation result map."
  [repo-root sew-repo manifest-path profile-path]
  (let [entries (->> (load-manifest manifest-path)
                     (filter :destination)) ; CDRS trace entries only
        profile (profile/load-profile profile-path)
        ;; subjects: contract-replayed (have a replay receipt); exclusions:
        ;; byte-synced-only
        receipts (replay-receipts sew-repo)
        replayed-dests (set (map :fixture_path receipts))
        included (filterv #(contains? replayed-dests (:destination %)) entries)
        excluded (filterv #(not (contains? replayed-dests (:destination %))) entries)
        subject-ids (mapv :id included)
        subject-set-root (hc/domain-hash "conformance.subject-set.v1"
                                         (vec (sort subject-ids)))
        subject-set {:subject-set/root subject-set-root
                     :subjects subject-ids}
        p (plan/build-plan profile subject-set)
        observations (vec (mapcat #(subject-observation repo-root sew-repo subject-set-root %)
                                  included))
        result (rec/reconcile p observations subject-set)]
    (assoc result
           :included-subjects subject-ids
           :excluded-subjects (mapv :id excluded)
           :exclusion-reasons (into {}
                                    (map (fn [e] [(:id e) "byte-synchronised only - not replayed"]))
                                    excluded))))

(defn -main
  "CLI: reconcile the trace pipeline against the conformance plan.
   Usage: -m resolver-sim.trace.conformance.reconcile-runner
          [--sew-repo <path>] [--manifest <path>] [--profile <path>] [--out <path>]"
  [& args]
  (letfn [(get-arg [k]
            (let [flag (str "--" k)]
              (some (fn [[a b]] (when (= flag a) b))
                    (partition 2 1 args))))]
    (let [sew-repo (or (get-arg "sew-repo") "../sew-protocol")
          manifest (or (get-arg "manifest") "etc/trace-solidity-manifest.edn")
          profile-path (or (get-arg "profile")
                           "etc/conformance/profiles/sew-trace-equivalence.v1.edn")
          out (or (get-arg "out") "results/conformance/trace-reconciliation.json")
          result (reconcile-trace! "." sew-repo manifest profile-path)
          json-out (json/write-str result
                                   {:indent true
                                    :key-fn (fn [k] (if (keyword? k)
                                                      (if-let [ns (namespace k)]
                                                        (str ns "/" (name k))
                                                        (name k))
                                                      k))})]
      (io/make-parents out)
      (spit out json-out)
      (println (str "reconciliation/status: " (:reconciliation/status result)))
      (println (str "included: " (count (:included-subjects result))
                    " excluded: " (count (:excluded-subjects result))))
      (println (str "wrote: " out))
      (when (= :fail (:reconciliation/status result))
        (println "missing:" (mapv :subject/id (:missing-steps result)))
        (System/exit 1)))))
