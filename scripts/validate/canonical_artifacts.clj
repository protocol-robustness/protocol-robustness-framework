#!/usr/bin/env bb
;; Narrow, deterministic validator for the proposed definition inventory.
;; It does not load the manifest from application/runtime namespaces.
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.set :as set]
         '[clojure.string :as str])

(def baseline-identity-sha256
  ;; SHA-256 of the sorted [id name schema owner algorithm domain] tuples.
  ;; Change only with an explicitly reviewed registry-baseline update.
  "0d0af1b9e7e487f334c11f6a7029a6026caa75af9383e899db03747eb4a6c71a")

(defn fail! [issues message]
  (swap! issues conj message))

(defn keys-equal? [value expected]
  (and (map? value) (= expected (set (keys value)))))

(defn digest-hex [value]
  (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes value "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) bytes))))

(defn source-ref? [value]
  (and (vector? value)
       (#{2 3} (count value))
       (#{:prf :prf-evm :prf-canonical :prf-pro-rata-allocation} (first value))))

(defn source-refs [data]
  (->> (tree-seq coll? seq data)
       (filter source-ref?)
       distinct))

(defn valid-owner? [owner]
  (and (keyword? owner)
       (or (#{:canonical :pro-rata-allocation :capital-lifecycle :Shortfall} owner)
           (= "other" (namespace owner)))))

(defn validate! [manifest prf-root evm-root canonical-root pro-rata-root]
  (let [issues (atom [])
        entries (:registry/entries manifest)
        by-id (into {} (map (juxt :artifact/id identity) entries))
        roots {:prf prf-root :prf-evm evm-root
               :prf-canonical canonical-root
               :prf-pro-rata-allocation pro-rata-root}
        identity-tuples
        (->> entries
             (sort-by (comp str :artifact/id))
             (mapv (fn [entry]
                     [(:artifact/id entry) (:artifact/name entry)
                      (:artifact/schema entry) (:artifact/owner entry)
                      (get-in entry [:artifact/hash :algorithm])
                      (get-in entry [:artifact/hash :domain])])))
        identity-digest (digest-hex (pr-str identity-tuples))
        domains (group-by #(get-in % [:artifact/hash :domain])
                          (filter #(map? (:artifact/hash %)) entries))
        expected-shared (into {}
                              (keep (fn [[domain members]]
                                      (when (> (count members) 1)
                                        [domain (set (map :artifact/id members))])))
                              domains)
        declared-shared (into {}
                              (map (fn [{:keys [domain artifacts]}]
                                     [domain (set artifacts)]))
                              (:registry/shared-domains manifest))
        parent-graph (into {}
                           (map (fn [entry]
                                  [(:artifact/id entry)
                                   (set (get-in entry [:artifact/inputs :artifacts]))]))
                           entries)
        cyclic-nodes
        (loop [pending (set (keys parent-graph))]
          (let [ready (set (filter (fn [id]
                                     (empty? (set/intersection
                                              pending (get parent-graph id))))
                                   pending))
                remaining (set/difference pending ready)]
            (if (or (empty? remaining) (= pending remaining))
              remaining
              (recur remaining))))]
    (when-not (keys-equal? manifest
                            #{:registry/schema :registry/status :registry/encoding
                              :registry/shared-domains :registry/entries})
      (fail! issues "registry fields must match the closed v1 shape"))
    (let [deps (edn/read-string (slurp (io/file prf-root "deps.edn")))]
      (doseq [[repo root alias] [[:prf-canonical canonical-root 'prf/canonical]
                                 [:prf-pro-rata-allocation pro-rata-root 'prf/pro-rata-allocation]]]
        (let [expected (get-in deps [:deps alias :git/sha])
              actual-result (shell/sh "git" "-C" (.getPath (io/file root)) "rev-parse" "HEAD")
              actual (when (zero? (:exit actual-result)) (str/trim (:out actual-result)))
              status-result (shell/sh "git" "-C" (.getPath (io/file root)) "status" "--porcelain")]
          (when-not (and (string? expected) (= expected actual)
                         (zero? (:exit status-result)) (str/blank? (:out status-result)))
            (fail! issues (str (name repo) " source root must be clean at deps.edn pin "
                                (pr-str expected) "; found " (pr-str actual)))))))
    (when-not (keys-equal? (:registry/encoding manifest)
                            #{:scheme :definition :normative})
      (fail! issues "encoding fields must match the closed v1 shape"))
    (when-not (and (= "canonical-hash-scheme.v1"
                      (get-in manifest [:registry/encoding :scheme]))
                   (source-ref? (get-in manifest [:registry/encoding :definition]))
                   (vector? (get-in manifest [:registry/encoding :normative])))
      (fail! issues "encoding must reference the frozen canonical hash scheme"))
    (doseq [shared (:registry/shared-domains manifest)]
      (when-not (and (keys-equal? shared #{:domain :artifacts})
                     (string? (:domain shared))
                     (vector? (:artifacts shared)))
        (fail! issues "shared-domain entries must match the closed v1 shape")))
    (when-not (= "canonical-artifacts.v1" (:registry/schema manifest))
      (fail! issues "registry schema must remain canonical-artifacts.v1"))
    (when-not (= :proposed (:registry/status manifest))
      (fail! issues "registry must remain explicitly :proposed and non-runtime"))
    (when-not (and (vector? entries) (seq entries))
      (fail! issues "registry entries must be a non-empty vector"))
    (when-not (= (count entries) (count by-id))
      (fail! issues "artifact IDs must be unique"))
    (when-not (= (count entries) (count (set (map :artifact/name entries))))
      (fail! issues "canonical artifact names must be unique"))
    (when-not (= baseline-identity-sha256 identity-digest)
      (fail! issues (str "frozen identity baseline changed: " identity-digest
                         " (review and update validator pin explicitly)")))
    (when-not (= expected-shared declared-shared)
      (fail! issues "shared-domain declarations must exactly match repeated domains"))
    (when-not (= (count (:registry/shared-domains manifest))
                 (count declared-shared))
      (fail! issues "shared-domain declarations must not repeat a domain"))
    (when (seq cyclic-nodes)
      (fail! issues (str "artifact input relationships contain a cycle: "
                         (pr-str (sort-by str cyclic-nodes)))))
    (doseq [entry entries]
      (let [id (:artifact/id entry)
            hash-def (:artifact/hash entry)
            status (:artifact/status entry)
            impls (:artifact/implementations entry)
            conformance (:artifact/conformance entry)]
        (when-not (and (map? entry)
                       (set/subset? #{:artifact/id :artifact/name :artifact/schema
                                      :artifact/owner :artifact/class :artifact/status
                                      :artifact/hash :artifact/inputs :artifact/implementations
                                      :artifact/conformance :artifact/normative}
                                    (set (keys entry)))
                       (set/subset? (set (keys entry))
                                    #{:artifact/id :artifact/name :artifact/schema
                                      :artifact/owner :artifact/class :artifact/status
                                      :artifact/hash :artifact/inputs :artifact/implementations
                                      :artifact/conformance :artifact/normative
                                      :artifact/successor-of}))
          (fail! issues (str id ": entry fields must match the closed v1 shape")))
        (when-not (keys-equal? (:artifact/inputs entry) #{:artifacts :related})
          (fail! issues (str id ": inputs must match the closed v1 shape")))
        (when-not (and (vector? (get-in entry [:artifact/inputs :artifacts]))
                       (vector? (get-in entry [:artifact/inputs :related])))
          (fail! issues (str id ": input artifact and related references must be vectors")))
        (when-not (keyword? id)
          (fail! issues (str id ": artifact ID must be a keyword")))
        (when-not (and (string? (:artifact/name entry))
                       (or (nil? (:artifact/schema entry))
                           (string? (:artifact/schema entry))))
          (fail! issues (str id ": name/schema must be strings or a nil schema")))
        (when-not (valid-owner? (:artifact/owner entry))
          (fail! issues (str id ": exactly one approved semantic owner is required")))
        (when-not (#{:A :D} (:artifact/class entry))
          (fail! issues (str id ": only canonical semantic/configuration classes A or D are registered")))
        (when-not (#{:frozen-current :frozen-historical :successor-frozen
                     :implemented-current :constrained :specified-unregistered} status)
          (fail! issues (str id ": unknown definition status")))
        (if (= :specified-unregistered status)
          (when-not (nil? hash-def)
            (fail! issues (str id ": specified/unregistered entries must not claim a hash")))
          (when-not (and (map? hash-def)
                         (= #{:algorithm :domain :registration :derivation} (set (keys hash-def)))
                         (= :sha256 (:algorithm hash-def))
                         (string? (:domain hash-def))
                         (source-ref? (:registration hash-def))
                         (source-ref? (:derivation hash-def))
                         (not (re-find #"(?i)keccak" (:domain hash-def))))
            (fail! issues (str id ": registered roots require a closed PRF SHA-256 hash definition"))))
        (when-not (= #{:clojure :rust :solidity} (set (keys impls)))
          (fail! issues (str id ": implementation languages must use the closed set")))
        (doseq [[language {:keys [status sources]}] impls]
          (when-not (keys-equal? (get impls language) #{:status :sources})
            (fail! issues (str id ": implementation coverage must match the closed v1 shape for " language)))
          (when-not (vector? sources)
            (fail! issues (str id ": implementation sources must be a vector for " language)))
          (when-not (#{:implemented :opaque-consumer :projection-only :missing :not-required} status)
            (fail! issues (str id ": unknown implementation status for " language)))
          (when (if (#{:implemented :opaque-consumer :projection-only} status)
                  (empty? sources)
                  (seq sources))
            (fail! issues (str id ": implementation status/source references disagree for " language))))
        (when-not (keys-equal? conformance #{:status :sources})
          (fail! issues (str id ": conformance fields must match the closed v1 shape")))
        (when-not (vector? (:sources conformance))
          (fail! issues (str id ": conformance sources must be a vector")))
        (when-not (#{:present :partial :missing} (:status conformance))
          (fail! issues (str id ": unknown conformance status")))
        (when (if (= :missing (:status conformance))
                (seq (:sources conformance))
                (empty? (:sources conformance)))
          (fail! issues (str id ": conformance status/source references disagree")))
        (when (and (= :implemented (get-in impls [:clojure :status]))
                   (= :implemented (get-in impls [:rust :status])))
          (when-not (and (#{:present :partial} (:status conformance))
                         (some #(re-find #"(?i)(test|vector|conformance)"
                                         (second %))
                               (:sources conformance)))
            (fail! issues (str id ": cross-language implementations need referenced conformance tests/vectors"))))
        (doseq [parent (get-in entry [:artifact/inputs :artifacts])]
          (when-not (contains? by-id parent)
            (fail! issues (str id ": unresolved parent artifact " parent)))
          (let [parent-owner (:artifact/owner (by-id parent))
                owner (:artifact/owner entry)]
            (when (and (= owner :canonical) (not= parent-owner :canonical))
              (fail! issues (str id ": canonical mechanics cannot depend on domain-owned artifacts")))
            (when (and (= owner :pro-rata-allocation)
                       (= parent-owner :capital-lifecycle))
              (fail! issues (str id ": pro-rata semantics cannot depend on capital-lifecycle artifacts")))))
        (when-let [successor-of (:artifact/successor-of entry)]
          (when-not (contains? by-id successor-of)
            (fail! issues (str id ": unresolved successor-of artifact " successor-of)))
          (when (= (:artifact/name entry) (:artifact/name (by-id successor-of)))
            (fail! issues (str id ": successor must retain a distinct artifact name")))
          (when (= (get-in entry [:artifact/hash :domain])
                   (get-in (by-id successor-of) [:artifact/hash :domain]))
            (fail! issues (str id ": successor must use a new hash domain")))))
      )
    (doseq [ref (source-refs manifest)]
      (let [[repo path symbol] ref
            file (io/file (get roots repo) path)]
        (when-not (and (string? path)
                       (not (.isAbsolute (java.nio.file.Paths/get path (make-array String 0))))
                       (not (some #{".."} (str/split path #"/")))
                       (.exists file))
          (fail! issues (str "invalid or missing source reference: " (pr-str ref))))
        (when (and (= 3 (count ref))
                   (not (or (string? symbol) (keyword? symbol))))
          (fail! issues (str "source reference symbol must be text or keyword: " (pr-str ref))))))
    (let [results @issues]
      (if (seq results)
        (do (binding [*out* *err*]
              (doseq [issue results] (println "ERROR:" issue)))
            (System/exit 1))
        (println (format "canonical-artifacts.v1 valid: %d entries, %d source references, identity baseline %s"
                         (count entries) (count (source-refs manifest)) identity-digest))))))

(defn -main [& args]
  (when-not (= 5 (count args))
    (binding [*out* *err*]
      (println "Usage: bb scripts/validate/canonical_artifacts.clj <manifest.edn> <prf-root> <prf-evm-root> <prf-canonical-root> <prf-pro-rata-allocation-root>"))
    (System/exit 2))
  (let [[manifest-path prf-root evm-root canonical-root pro-rata-root] args]
    (validate! (edn/read-string (slurp manifest-path))
               (io/file prf-root) (io/file evm-root)
               (io/file canonical-root) (io/file pro-rata-root))))

(apply -main *command-line-args*)
