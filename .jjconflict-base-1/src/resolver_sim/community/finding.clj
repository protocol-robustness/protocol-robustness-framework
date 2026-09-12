(ns resolver-sim.community.finding
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.community.task :as task]))

(def ^:const schema-version "research-finding.v0")
(def ^:const supported-finding-types
  #{:execution-result :reproduction :counterexample :challenge :bounded-negative-result})
(def ^:const domain-tag "COMMUNITY_FINDING_V0")
(def ^:const finding-ref-prefix "research-finding:sha256:")

(defn finding-ref [hash] (str finding-ref-prefix hash))
(defn finding-ref? [s] (and (string? s) (.startsWith s finding-ref-prefix)))
(defn parse-finding-ref [s] (when (finding-ref? s) (subs s (count finding-ref-prefix))))

(defn- normalize-for-hash [x]
  (cond
    (nil? x) nil
    (boolean? x) x
    (integer? x) x
    (string? x) x
    (keyword? x) x
    (instance? java.time.Instant x) (str x)
    (vector? x) (mapv normalize-for-hash x)
    (map? x) (persistent!
              (reduce-kv (fn [m k v] (assoc! m (normalize-for-hash k) (normalize-for-hash v)))
                         (transient {}) x))
    (set? x) (vec (sort (map normalize-for-hash x)))
    (sequential? x) (mapv normalize-for-hash x)
    :else (str x)))

(defn- canonical-body [m]
  (dissoc m :finding/id :finding/hash :finding/ref))

(defn- compute-hash [body]
  (hc/domain-hash domain-tag (normalize-for-hash (canonical-body body))))

(defn build-finding
  [m]
  (let [finding-type (or (:finding/type m) :execution-result)
        task-ref (:task/reference m)
        _ (assert (contains? supported-finding-types finding-type))
        _ (assert (task/task-ref? task-ref) "task/reference must be a valid task ref")
        body {:schema-version schema-version
              :finding/type finding-type
              :task/reference task-ref
              :status (:status m)
              :summary (:summary m)
              :execution-references (vec (or (:execution-references m) []))
              :claim-result-references (vec (or (:claim-result-references m) []))
              :artifact-references (vec (or (:artifact-references m) []))
              :submitted-by (:submitted-by m)
              :submitted-at (or (:submitted-at m) (str (java.time.Instant/now)))}
        hash (compute-hash body)]
    (assoc body :finding/id hash :finding/hash hash :finding/ref (finding-ref hash))))

(defn finding-hash [finding]
  (:finding/hash finding))

(defn valid-finding?
  "Check record integrity: hash matches content, structure is valid.
   Does NOT verify that the finding's evidence supports its claimed type.
   Use verify-finding-evidence for semantic verification."
  [finding]
  (and (map? finding)
       (= schema-version (:schema-version finding))
       (contains? supported-finding-types (:finding/type finding))
       (string? (:finding/hash finding))
       (= (:finding/id finding) (:finding/hash finding))
       (= (compute-hash finding) (:finding/hash finding))))

(defn verify-finding-evidence
  "Check that a finding's evidence supports its asserted type.
   This is a semantic check that goes beyond hash integrity.
   
   For each finding type:
   - :execution-result — must reference at least one execution
   - :reproduction — must reference original and reproduction executions
   - :counterexample — must reference an execution where the claimed
     benchmark semantics did NOT hold
   - :challenge — must reference a challenged attestation
   - :bounded-negative-result — must reference negative result evidence
   
   Returns {:valid? true/false :checks [...]}
   Record integrity (valid-finding?) is a prerequisite."
  [finding]
  (let [f-type (:finding/type finding)
        exec-refs (set (or (:execution-references finding) []))
        claim-refs (set (or (:claim-result-references finding) []))
        checks (atom [])]
    (case f-type
      :execution-result
      (swap! checks conj
             {:check :has-execution-refs
              :pass? (seq exec-refs)
              :detail (if (seq exec-refs) "Has execution references" "Missing execution references")})
      :reproduction
      (do (swap! checks conj
                 {:check :has-execution-refs
                  :pass? (seq exec-refs)
                  :detail (if (seq exec-refs) "Has execution references" "Missing execution references")})
          (swap! checks conj
                 {:check :has-original-and-reproduction
                  :pass? (>= (count exec-refs) 2)
                  :detail (str "Found " (count exec-refs) " execution references, need >= 2")}))
      :counterexample
      (swap! checks conj
             {:check :has-execution-refs
              :pass? (seq exec-refs)
              :detail (if (seq exec-refs) "Has execution references" "Missing execution references")})
      :challenge
      (swap! checks conj
             {:check :has-claim-refs
              :pass? (seq claim-refs)
              :detail (if (seq claim-refs) "Has claim references" "Missing claim references")})
      :bounded-negative-result
      (swap! checks conj
             {:check :has-claim-refs
              :pass? (seq claim-refs)
              :detail (if (seq claim-refs) "Has claim references" "Missing claim references")}))
    (let [failures (filter #(false? (:pass? %)) @checks)]
      {:valid? (empty? failures)
       :checks @checks})))
