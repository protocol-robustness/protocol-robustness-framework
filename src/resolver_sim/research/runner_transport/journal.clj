(ns resolver-sim.research.runner-transport.journal
  "Low-level append/checkpoint journal mechanics for research runner runtime state.

   This module preserves runtime state only. Artifact validation and the
   semantic interpretation of recovered state remain with the caller."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException Files StandardCopyOption
            StandardOpenOption]))

(def checkpoint-interval 128)

(defn append-vector? [before after]
  (and (vector? before) (vector? after) (<= (count before) (count after))
       (= before (subvec after 0 (count before)))))

(defn state-delta [before after]
  (letfn [(walk [path old new old? new?]
            (cond
              (and old? new? (= old new)) []
              (not new?) [{:op :delete :path path}]
              (not old?) [{:op :set :path path :value new}]
              (and (map? old) (map? new))
              (mapcat (fn [k]
                        (walk (conj path k) (get old k) (get new k)
                              (contains? old k) (contains? new k)))
                      (sort-by pr-str (into (set (keys old)) (keys new))))
              (append-vector? old new)
              [{:op :append :path path :values (subvec new (count old))}]
              :else [{:op :set :path path :value new}]))]
    (vec (walk [] before after true true))))

(defn apply-state-delta [state changes]
  (reduce (fn [current {:keys [op path value values]}]
            (case op
              :set (if (empty? path) value (assoc-in current path value))
              :delete (if (empty? path) {} (update-in current (butlast path) dissoc (last path)))
              :append (update-in current path into values)
              (throw (ex-info "Unknown runtime journal operation" {:op op}))))
          state changes))

(defn checkpoint-record [schema owner-fields state sequence]
  (merge {:artifact/schema schema :journal/record :checkpoint
          :journal/sequence sequence :runner-runtime/state state}
         owner-fields))

(defn- parse-complete-journal-lines [path]
  (let [raw (slurp path)
        all-lines (str/split raw #"\n" -1)
        lines (butlast all-lines)
        valid-bytes (reduce + 0 (map #(+ (alength (.getBytes ^String % StandardCharsets/UTF_8)) 1) lines))]
    {:records (mapv edn/read-string (remove empty? lines))
     :terminated? (or (empty? raw) (.endsWith ^String raw "\n"))
     :valid-bytes valid-bytes}))

(defn recover-journal
  [path expected-schema legacy-schema owner-fields state-key initial-state
   valid-state? truncate-incomplete-tail?]
  (if-not (and path (.exists (io/file path)))
    {:state initial-state :sequence 0 :format :new}
    (let [raw (slurp path)
          first-form (try (edn/read-string raw) (catch Exception _ nil))]
      (if (= legacy-schema (:artifact/schema first-form))
        (let [state (get first-form state-key)]
          (when-not (and (every? (fn [[k v]] (= v (get first-form k))) owner-fields)
                         (valid-state? state))
            (throw (ex-info "Invalid or foreign legacy runtime journal" {:state-path path})))
          {:state state :sequence 0 :format :legacy})
        (let [{:keys [records terminated? valid-bytes]} (parse-complete-journal-lines path)
              header (first records)]
          (when-not (and (= expected-schema (:artifact/schema header))
                         (= :checkpoint (:journal/record header))
                         (integer? (:journal/sequence header))
                         (not (neg? (:journal/sequence header)))
                         (every? (fn [[k v]] (= v (get header k))) owner-fields)
                         (valid-state? (get header state-key)))
            (throw (ex-info "Invalid or foreign runtime journal" {:state-path path})))
          (let [recovered
                (reduce (fn [{:keys [state sequence]} record]
                          (let [next-sequence (:journal/sequence record)]
                            (when-not (= (inc sequence) next-sequence)
                              (throw (ex-info "Runtime journal sequence discontinuity"
                                              {:state-path path :expected (inc sequence)
                                               :actual next-sequence})))
                            (case (:journal/record record)
                              :delta (let [next-state (apply-state-delta state (:journal/changes record))]
                                       (when-not (valid-state? next-state)
                                         (throw (ex-info "Invalid runtime journal delta result"
                                                         {:state-path path :sequence next-sequence})))
                                       {:state next-state :sequence next-sequence})
                              :checkpoint (let [next-state (get record state-key)]
                                            (when-not (valid-state? next-state)
                                              (throw (ex-info "Invalid runtime journal checkpoint"
                                                              {:state-path path :sequence next-sequence})))
                                            {:state next-state :sequence next-sequence})
                              (throw (ex-info "Unknown runtime journal record"
                                              {:state-path path :record (:journal/record record)})))))
                        {:state (get header state-key)
                         :sequence (:journal/sequence header)} (rest records))]
            (when (and truncate-incomplete-tail? (not terminated?))
              (with-open [channel (FileChannel/open (.toPath (io/file path))
                                                    (into-array StandardOpenOption [StandardOpenOption/WRITE]))]
                (.truncate channel (long valid-bytes))))
            (assoc recovered :format :v2)))))))

(defn append-record! [path record]
  (with-open [channel (FileChannel/open (.toPath (io/file path))
                                        (into-array StandardOpenOption
                                                    [StandardOpenOption/CREATE StandardOpenOption/WRITE
                                                     StandardOpenOption/APPEND]))]
    (let [bytes (.getBytes (str (pr-str record) "\n") StandardCharsets/UTF_8)
          buffer (ByteBuffer/wrap bytes)]
      (while (.hasRemaining buffer) (.write channel buffer)))))

(defn replace-with-checkpoint! [path record]
  (let [target (.toPath (io/file path))
        parent (or (.getParent target) (.toPath (io/file ".")))]
    (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
    (let [temporary (Files/createTempFile parent ".research-runtime-journal-" ".edn"
                                          (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (Files/writeString temporary (str (pr-str record) "\n") StandardCharsets/UTF_8
                           (make-array java.nio.file.OpenOption 0))
        (try
          (Files/move temporary target
                      (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                      StandardCopyOption/REPLACE_EXISTING]))
          (catch AtomicMoveNotSupportedException _
            (Files/move temporary target
                        (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
        (finally (Files/deleteIfExists temporary))))))
