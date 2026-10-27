(ns scripts.conformance.pro-rata-vector-drift
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def drift-prefix "PRF_VECTOR_DRIFT_V1|")
(def allocation-vectors
  #{"allocation-rounding-001.edn" "cap-clamping-001.edn"
    "redistribute-cap-excess-001.edn" "floor-residual-001.edn"
    "zero-weight-001.edn" "negative-allocation-001.edn"})
(def transition-vectors #{"canonical-transition-001.edn" "negative-transition-001.edn"})

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- files-by-name [dir]
  (let [root (io/file dir)]
    (when-not (.isDirectory root)
      (fail! "Vector directory does not exist" {:directory (str root)}))
    (into (sorted-map)
          (map (fn [^java.io.File f] [(.getName f) f]))
          (filter #(.isFile ^java.io.File %)
                  (or (seq (.listFiles root)) [])))))

(defn- read-edn-files [dir expected-names]
  (let [actual (files-by-name dir)
        actual-names (set (keys actual))]
    (when-not (= expected-names actual-names)
      (fail! "Vector file set differs from the pinned corpus"
             {:directory (str dir)
              :missing (sort (set/difference expected-names actual-names))
              :extra (sort (set/difference actual-names expected-names))}))
    (into (sorted-map)
          (map (fn [[name file]]
                 [name (edn/read-string (slurp file))]))
          actual)))

(defn compare-vector-directories
  "Require exact EDN equality for the specified filenames. Returns the validated vector map."
  ([reference-dir mirror-dir expected-names]
   (compare-vector-directories reference-dir mirror-dir expected-names #{}))
  ([reference-dir mirror-dir expected-names allowed-extra]
  (let [reference-files (files-by-name reference-dir)
        missing-reference (set/difference expected-names (set (keys reference-files)))
        _ (when (seq missing-reference)
            (fail! "PRF reference corpus is missing required vectors"
                   {:directory (str reference-dir) :missing (sort missing-reference)}))
        reference (into (sorted-map)
                        (map (fn [name] [name (edn/read-string (slurp (get reference-files name)))])
                             expected-names))
        reference-ids (map (comp :vector/id val) reference)
        mirror-files (files-by-name mirror-dir)
        expected-mirror-files (into expected-names allowed-extra)
        _ (when-not (= expected-mirror-files (set (keys mirror-files)))
            (fail! "Vector mirror file set differs from its declared scope"
                   {:directory (str mirror-dir)
                    :missing (sort (set/difference expected-mirror-files (set (keys mirror-files))))
                    :extra (sort (set/difference (set (keys mirror-files)) expected-mirror-files))}))
        mirror (into (sorted-map)
                     (map (fn [name] [name (edn/read-string (slurp (get mirror-files name)))])
                          expected-names))
        ids (map (comp :vector/id val) mirror)]
    (when (or (some nil? reference-ids)
              (not= (count reference-ids) (count (distinct reference-ids)))
              (some nil? ids)
              (not= (count ids) (count (distinct ids))))
      (fail! "Vector mirror has missing or duplicate vector identities"
             {:directory (str mirror-dir) :reference-ids reference-ids :ids ids}))
    (when-not (= reference mirror)
      (fail! "Mirrored frozen vectors differ from PRF reference"
             {:reference (str reference-dir) :mirror (str mirror-dir)
              :different (->> expected-names
                              (filter #(not= (get reference %) (get mirror %)))
                              sort vec)}))
    reference)))

(defn- run-command [dir env command]
  (let [{:keys [exit out err]} (apply shell/sh
                                      (concat command
                                              [:dir (str dir) :env env]))]
    (when-not (zero? exit)
      (fail! "Rust conformance test command failed"
             {:directory (str dir) :command command :exit exit :stdout out :stderr err}))
    {:stdout out :stderr err :exit exit}))

(defn- drift-records [{:keys [stdout stderr]}]
  (->> (str (or stdout "") "\n" (or stderr ""))
       str/split-lines
       (keep (fn [line]
               (when-let [idx (str/index-of line drift-prefix)]
                 (subs line idx))))
       (mapv #(str/split % #"\|" -1))))

(defn- record-map [records tag]
  (let [selected (filter #(= tag (nth % 1 nil)) records)
        ids (map #(nth % 2 nil) selected)]
    (when (or (some nil? ids) (not= (count ids) (count (distinct ids))))
      (fail! "Missing or duplicate Rust drift record identifiers" {:tag tag :ids ids}))
    (into {} (map (fn [r] [(nth r 2) (subvec r 3)]) selected))))

(defn- expected-rejections [vector]
  (into (sorted-map)
        (map (fn [mutation]
               [(name (:mutation/id mutation))
                (name (:expected/reason mutation))]))
        (:mutations vector)))

(defn- sha256 [s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- tree-fingerprint [root relative-paths]
  (let [entries (for [relative (sort relative-paths)
                      :let [file (io/file root relative)]
                      :when (.isFile file)]
                  (str relative "\u0000" (sha256 (slurp file)) "\n"))]
    (sha256 (apply str entries))))

(defn- revision [dir]
  (let [{:keys [exit out err]} (shell/sh "jj" "--ignore-working-copy" "log"
                                        "-r" "@" "--no-graph" "-T" "commit_id" :dir (str dir))]
    (when-not (zero? exit)
      (fail! "Unable to identify package source revision" {:directory (str dir) :stderr err}))
    (str/trim out)))

(defn- required-record [records tag id field-count]
  (let [record (get (record-map records tag) id)]
    (when-not (= field-count (count record))
      (fail! "Missing or malformed Rust drift record" {:tag tag :id id :record record}))
    record))

(defn- check-allocation-output! [records vectors]
  (let [positive (record-map records "allocation")
        expected-positive (into {}
                                (for [[file v] vectors :when (not (str/starts-with? file "negative-"))]
                                  [(name (:vector/id v))
                                   {:request (:request/hash (:expected v))
                                    :result (:allocation/hash (:expected v))}]))
        actual-positive (into {} (map (fn [[id [request result]]] [id {:request request :result result}])) positive)
        negative (record-map records "allocation-rejection")
        expected-negative (expected-rejections (get vectors "negative-allocation-001.edn"))]
    (when-not (= (set (keys expected-positive)) (set (keys actual-positive)))
      (fail! "Rust allocation vector coverage differs from frozen EDN vectors"
             {:expected-ids (sort (keys expected-positive)) :actual-ids (sort (keys actual-positive))}))
    (when-not (every? (fn [[id expected]]
                        (let [actual (get actual-positive id)]
                          (and (= (:result expected) (:result actual))
                               (or (nil? (:request expected))
                                   (= (:request expected) (:request actual)))
                               (every? string? (vals actual)))))
                      expected-positive)
      (fail! "Rust allocation roots differ from frozen EDN vectors"
             {:expected expected-positive :actual actual-positive}))
    (when-not (= expected-negative (into (sorted-map)
                                         (map (fn [[id [reason]]] [id reason]))
                                         negative))
      (fail! "Rust allocation rejection results differ from frozen EDN vectors"
             {:expected expected-negative :actual negative}))))

(defn- check-transition-output! [records vectors]
  (let [v (get vectors "canonical-transition-001.edn")
        roots (:roots (:expected v))
        expected [(roots :state-before/root) (roots :effects/root)
                  (roots :state-after/root) (roots :transition/root)]
        actual (required-record records "transition" "canonical-transition-001" 4)
        expected-negative (expected-rejections (get vectors "negative-transition-001.edn"))
        negative (record-map records "transition-rejection")]
    (when-not (= expected actual)
      (fail! "Rust transition roots differ from frozen EDN vector"
             {:expected expected :actual actual}))
    (when-not (= expected-negative (into (sorted-map)
                                         (map (fn [[id [reason]]] [id reason]))
                                         negative))
      (fail! "Rust transition rejection results differ from frozen EDN vectors"
             {:expected expected-negative :actual negative}))))

(defn verify!
  [{:keys [prf-root allocation-root transition-root]}]
  (let [prf-root (.getCanonicalFile (io/file prf-root))
        allocation-root (.getCanonicalFile (io/file allocation-root))
        transition-root (.getCanonicalFile (io/file transition-root))
        reference-dir (io/file prf-root "portable-pro-rata-contract/vectors")
        allocation-dir (io/file allocation-root "clj/test/fixtures/portable-pro-rata-contract/vectors")
        transition-provenance (io/file transition-root "provenance/portable-pro-rata-contract.v1/vectors")
        transition-resources (io/file transition-root "resources/prf/effect_transition_contract/v1/vectors")
        allocation (compare-vector-directories reference-dir allocation-dir allocation-vectors
                                               #{"policy-outcome-conformance-v1.edn"})
        transition-ref (compare-vector-directories reference-dir transition-provenance
                                                   (into allocation-vectors transition-vectors))
        transition-resource-vectors (select-keys transition-ref transition-vectors)
        _ (compare-vector-directories reference-dir transition-resources transition-vectors)
        allocation-run (run-command allocation-root {"PRF_VECTOR_DRIFT" "1"}
                                    ["cargo" "test" "--locked" "--manifest-path" "rust/Cargo.toml" "--" "--nocapture"])
        transition-run (run-command transition-root {"PRF_VECTOR_DRIFT" "1"}
                                    ["cargo" "test" "--locked" "--manifest-path" "rust/Cargo.toml" "--" "--nocapture"])
        allocation-records (drift-records allocation-run)
        transition-records (drift-records transition-run)
        _ (check-allocation-output! allocation-records allocation)
        _ (check-transition-output! transition-records transition-resource-vectors)
        report {:schema "prf-pro-rata-vector-drift-report.v1"
                :status "pass"
                :reference {:revision (revision prf-root)
                            :working-tree-content-sha256
                            (tree-fingerprint prf-root
                                              (concat ["portable-pro-rata-contract/manifest.edn"]
                                                      (map #(str "portable-pro-rata-contract/vectors/" %)
                                                           (keys (files-by-name reference-dir)))))
                            :vectors (sort (keys (files-by-name reference-dir)))}
                :allocation {:revision (revision allocation-root)
                             :working-tree-content-sha256
                             (tree-fingerprint allocation-root
                                               (concat ["rust/Cargo.toml" "rust/Cargo.lock" "rust/src/result.rs"]
                                                       (map #(str "clj/test/fixtures/portable-pro-rata-contract/vectors/" %)
                                                            (keys allocation))))
                             :rust-tests-exit (:exit allocation-run)
                             :verified-vectors (sort (keys allocation))
                             :rust-records allocation-records}
                :transition {:revision (revision transition-root)
                             :working-tree-content-sha256
                             (tree-fingerprint transition-root
                                               (concat ["rust/Cargo.toml" "rust/Cargo.lock" "rust/src/lib.rs"]
                                                       (map #(str "provenance/portable-pro-rata-contract.v1/vectors/" %)
                                                            (keys transition-ref))
                                                       (map #(str "resources/prf/effect_transition_contract/v1/vectors/" %)
                                                            transition-vectors)))
                             :rust-tests-exit (:exit transition-run)
                             :verified-vectors (sort (keys transition-ref))
                             :rust-records transition-records}
                :tools {:rustc (str/trim (:out (shell/sh "rustc" "--version")))
                        :cargo (str/trim (:out (shell/sh "cargo" "--version")))
                        :clojure (clojure-version)}}]
    (assoc report :report-sha256 (sha256 (json/write-str report)))))

(defn- parse-args [args]
  (let [pairs (partition 2 args)
        options (into {} (map vec pairs))
        required ["--prf-root" "--allocation-root" "--transition-root"]]
    (when (or (odd? (count args)) (some #(not (options %)) required))
      (fail! "Usage: bb pro-rata-vectors:check --prf-root PATH --allocation-root PATH --transition-root PATH"
             {:args args}))
    {:prf-root (options "--prf-root")
     :allocation-root (options "--allocation-root")
     :transition-root (options "--transition-root")}))

(defn -main [& args]
  (try
    (println (json/write-str (verify! (parse-args args)) {:pretty true}))
    (catch Throwable e
      (binding [*out* *err*]
        (println (json/write-str {:schema "prf-pro-rata-vector-drift-report.v1"
                                  :status "fail" :error (.getMessage e)
                                  :details (ex-data e)} {:pretty true})))
      (System/exit 1))
    (finally
      (shutdown-agents))))
