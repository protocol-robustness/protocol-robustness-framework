(ns resolver-sim.research.partial-fill-file-profile
  "Single-authority, file-backed admission and serial commit for partial-fill research.

   This is an offline model. It does not execute token transfers or establish
   distributed ordering, payment authority, or chain finality."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.partial-fill-file-artifacts :as artifacts])
  (:import [java.io PushbackReader StringReader]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption OpenOption Path StandardOpenOption]
           [java.util.concurrent ConcurrentHashMap]))

(def ^:const profile-schema artifacts/profile-schema)
(def ^:const operation-schema artifacts/operation-schema)
(def ^:const submission-schema artifacts/submission-schema)
(def ^:const source-schema artifacts/source-schema)
(def ^:const preparation-schema artifacts/preparation-schema)
(def ^:const record-schema "partial-fill-file-journal-record.v1")
(def ^:private lock-objects (ConcurrentHashMap.))
(declare admission-root commit-root preparation-root completion-root register-source!)

(defn- root? [value] (hash-ref/valid-sha256-ref? value))
(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))
(defn- nonnegative-integer? [value]
  (and (integer? value) (not (neg? value))))
(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))
(defn- canonical-roots? [roots]
  (and (vector? roots) (= roots (vec (sort roots)))
       (= (count roots) (count (set roots))) (every? root? roots)))

(def operation-root artifacts/operation-root)
(def valid-operation? artifacts/valid-operation?)
(def build-operation artifacts/build-operation)
(def submission-root artifacts/submission-root)
(def submission-snapshot-root artifacts/submission-snapshot-root)
(def build-source artifacts/build-source)
(def build-preparation artifacts/build-preparation)
(def build-submission artifacts/build-submission)
(defn- record-root [record]
  (root "PRF_PARTIAL_FILL_FILE_JOURNAL_RECORD_V1"
        (dissoc record :journal/record-root)))

(defn- make-record [sequence previous-root authority-root kind payload]
  (let [body {:artifact/schema record-schema
              :journal/sequence sequence
              :journal/previous-root previous-root
              :authority/root authority-root
              :record/kind kind
              :record/payload payload}]
    (assoc body :journal/record-root (record-root body))))

(defn- initial-state [operation authority-root]
  {:operation operation
   :authority-root authority-root
   :journal/sequence -1
   :journal/root nil
   :records []
   :forensic-nodes []
   :sources {}
   :submission-events {}
   :submissions {}
   :preparations {}
   :idempotency {}
   :admissions {}
   :admission-order []
   :committed-admissions #{ }
   :committed-admissions-in-order []
   :commit-by-admission {}
   :paid-by-entitlement {}
   :reserved-by-entitlement {}
   :completion nil})

(def economic-projection artifacts/economic-projection)
(def economic-projection-root artifacts/economic-projection-root)

(defn- entitlement-for [operation entitlement-root]
  (some #(when (= entitlement-root (:entitlement/root %)) %) 
        (:operation/entitlements operation)))

(defn- verify-submission! [operation submission]
  (when-not (map? submission)
    (throw (ex-info "Submission journal payload must be a map"
                    {:reason :partial-fill-file/invalid-submission})))
  (let [entitlement (entitlement-for operation (:submission/entitlement-root submission))]
    (when-not (and (= submission-schema (:artifact/schema submission))
                   (= #{:artifact/schema :submission/idempotency-root
                        :submission/operation-root :submission/entitlement-root
                        :submission/beneficiary-root :submission/amount
                        :submission/asset-root :submission/policy-root
                        :submission/source-roots :submission/submitter-root
                        :submission/root}
                      (set (keys submission)))
                   (every? root? [(:submission/idempotency-root submission)
                                  (:submission/operation-root submission)
                                  (:submission/entitlement-root submission)
                                  (:submission/beneficiary-root submission)
                                  (:submission/asset-root submission)
                                  (:submission/policy-root submission)
                                  (:submission/submitter-root submission)
                                  (:submission/root submission)])
                   (canonical-roots? (:submission/source-roots submission))
                   (seq (:submission/source-roots submission))
                   (= (:submission/root submission) (submission-root submission))
                   (= (:operation/root operation) (:submission/operation-root submission))
                   (= (:operation/asset-root operation) (:submission/asset-root submission))
                   (= (:operation/policy-root operation) (:submission/policy-root submission))
                   entitlement
                   (= (:beneficiary/root entitlement) (:submission/beneficiary-root submission))
                   (positive-integer? (:submission/amount submission))
                   (<= (:submission/amount submission) (:entitlement/amount entitlement)))
      (throw (ex-info "Submission does not bind to this operation and entitlement"
                      {:reason :partial-fill-file/invalid-submission
                       :submission/root (:submission/root submission)})))
    entitlement))

(defn- event-node [record prior-node]
  (let [kind (:record/kind record)
        payload (:record/payload record)
        actor-root (or (when (= kind :submission) (:submission/submitter-root payload))
                       (when (= kind :prepared) (:preparation/worker-root payload))
                       (:authority/root record))
        role (case kind :operation-opened :declared
               :source :declared :submission :declared :prepared :validated
               :admitted :validated :committed :derived :completed :derived)
        causal-roots (->> (concat [(:journal/record-root record)
                                   (:journal/previous-root record)]
                                  (case kind
                                    :operation-opened [(:operation/root (:operation payload))]
                                    :source [(:source/root payload)
                                             (:source/operation-root payload)
                                             (:source/evidence-root payload)]
                                    :submission (concat [(:submission/root payload)
                                                         (:submission/operation-root payload)
                                                         (:submission/asset-root payload)
                                                         (:submission/policy-root payload)]
                                                        ;; Keep every declared provenance
                                                        ;; artifact as a typed cause.
                                                        (:submission/source-roots payload))
                                    :prepared [(:preparation/root payload)
                                               (:preparation/submission-root payload)
                                               (:preparation/snapshot-root payload)
                                               (:preparation/result-root payload)]
                                    :admitted [(:admission/root payload)
                                               (:submission/root payload)
                                               (:preparation/root payload)]
                                    :committed [(:commit/root payload)
                                                (:admission/root payload)
                                                (:projection/before-root payload)
                                                (:projection/after-root payload)]
                                    :completed [(:completion/root payload)
                                                (:projection/root payload)]))
                           (remove nil?) distinct sort vec)]
    (forensic/build-forensic-evidence-node
     {:subject {:subject/root (:journal/record-root record)
                :subject/kind :partial-fill/file-profile-event}
      :role role :visibility :commitment-only
      :actor {:actor/root actor-root
              :actor/role (case kind :submission :executor :prepared :worker :system)}
      :observation-key (case kind
                         :operation-opened :partial-fill/operation-opened
                         :source :partial-fill/source-recorded
                         :submission :partial-fill/submission-recorded
                         :prepared :partial-fill/submission-prepared
                         :admitted :partial-fill/submission-admitted
                         :committed :partial-fill/claim-committed
                         :completed :partial-fill/operation-completed)
      :observed-root (:journal/record-root record)
      :causal-parent-roots causal-roots
      :parent-nodes (cond-> [] prior-node (conj (:node-hash prior-node)))
      :consequence? (contains? #{:committed :completed} kind)})))

(defn- valid-source? [operation source]
  (and (map? source)
       (= #{:artifact/schema :source/operation-root :source/evidence-root :source/root}
          (set (keys source)))
       (= source-schema (:artifact/schema source))
       (= (:operation/root operation) (:source/operation-root source))
       (every? root? [(:source/evidence-root source) (:source/root source)])
       (= (:source/root source)
          (root "PRF_PARTIAL_FILL_FILE_SOURCE_V1" (dissoc source :source/root)))))

(defn- valid-preparation? [operation submission preparation]
  (and (map? preparation)
       (= #{:artifact/schema :preparation/submission-root
            :preparation/snapshot-root :preparation/worker-root
            :preparation/result-root :preparation/root}
          (set (keys preparation)))
       (= preparation-schema (:artifact/schema preparation))
       (= (:submission/root submission) (:preparation/submission-root preparation))
       (= (submission-snapshot-root operation submission)
          (:preparation/snapshot-root preparation))
       (every? root? [(:preparation/worker-root preparation)
                      (:preparation/result-root preparation)
                      (:preparation/root preparation)])
       (= (:preparation/root preparation)
          (preparation-root (dissoc preparation :preparation/root)))))

(defn- terminal? [state]
  (some? (:completion state)))

(defn- ensure-open! [state]
  (when (terminal? state)
    (throw (ex-info "File-profile operation is already complete"
                    {:reason :partial-fill-file/operation-complete}))))

(defn- append-state-record [state record]
  (let [kind (:record/kind record)
        payload (:record/payload record)
        record-index (:journal/sequence record)
        state (-> state
                  (assoc :journal/sequence record-index
                         :journal/root (:journal/record-root record))
                  (update :records conj record))]
    (case kind
      :operation-opened
      (do
        (when-not (and (= #{:operation :profile/schema} (set (keys payload)))
                       (= profile-schema (:profile/schema payload))
                       (valid-operation? (:operation payload)))
          (throw (ex-info "Invalid operation-opened journal payload" {})))
        state)
      :source
      (let [source payload
            source-root (:source/root source)]
        (when-not (and (not (terminal? state))
                       (empty? (:submissions state))
                       (valid-source? (:operation state) source)
                       (not (contains? (:sources state) source-root)))
          (throw (ex-info "Invalid, late, or duplicate source record"
                          {:reason :partial-fill-file/invalid-source})))
        (assoc-in state [:sources source-root] source))
      :submission
      (let [submission payload
            idempotency (:submission/idempotency-root submission)]
        (ensure-open! state)
        (verify-submission! (:operation state) submission)
        (when-not (every? #(contains? (:sources state) %)
                          (:submission/source-roots submission))
          (throw (ex-info "Submission refers to an unrecorded source"
                          {:reason :partial-fill-file/unknown-source
                           :source-roots (:submission/source-roots submission)})))
        (when (or (contains? (:submissions state) (:submission/root submission))
                  (contains? (:idempotency state) idempotency))
          (throw (ex-info "Duplicate submission record in journal" {})))
        (-> state
            (assoc-in [:submission-events (:submission/root submission)]
                      (:journal/record-root record))
            (assoc-in [:submissions (:submission/root submission)] submission)
            (assoc-in [:idempotency idempotency] (:submission/root submission))))
      :prepared
      (let [preparation payload
            submission-root (:preparation/submission-root preparation)
            submission (get-in state [:submissions submission-root])
            preparation-root* (:preparation/root preparation)]
        (when-not (and (not (terminal? state)) submission
                       (not (contains? (:admissions state) submission-root))
                       (valid-preparation? (:operation state) submission preparation)
                       (not (contains? (:preparations state) preparation-root*)))
          (throw (ex-info "Invalid or conflicting preparation record"
                          {:reason :partial-fill-file/invalid-preparation
                           :preparation/root preparation-root*})))
        (assoc-in state [:preparations preparation-root*] preparation))
      :admitted
      (let [submission-root (:submission/root payload)
            preparation-root* (:preparation/root payload)
            admission-root* (:admission/root payload)
            admission-sequence (:admission/sequence payload)
            submission (get-in state [:submissions submission-root])]
        (let [preparation (get-in state [:preparations preparation-root*])
              submission-preparations (filter #(= submission-root
                                                   (:preparation/submission-root %))
                                              (vals (:preparations state)))]
        (when-not (and (not (terminal? state))
                       (= #{:submission/root :preparation/root :admission/sequence
                            :authority/root :admission/root}
                          (set (keys payload)))
                       (= (:authority-root state) (:authority/root payload))
                       submission
                       preparation
                       (= submission-root (:preparation/submission-root preparation))
                       (= 1 (count (set (map :preparation/result-root
                                             submission-preparations))))
                       (= (inc (count (:admission-order state))) admission-sequence)
                       (= admission-root*
                          (admission-root {:submission/root submission-root
                                           :preparation/root preparation-root*
                                           :admission/sequence admission-sequence
                                           :authority/root (:authority-root state)}))
                       (not (contains? (:admissions state) submission-root)))
          (throw (ex-info "Invalid or out-of-order admission in journal" {})))
        (let [entitlement-root (:submission/entitlement-root submission)]
          (when (> (+ (get-in state [:paid-by-entitlement entitlement-root] 0)
                      (get-in state [:reserved-by-entitlement entitlement-root] 0)
                      (:submission/amount submission))
                   (:entitlement/amount
                    (entitlement-for (:operation state) entitlement-root)))
            (throw (ex-info "Admission journal overbooks entitlement"
                            {:reason :partial-fill-file/entitlement-overdraw})))
          (-> state
              (assoc-in [:admissions submission-root]
                        {:admission/root admission-root*
                         :admission/sequence admission-sequence
                         :submission/root submission-root
                         :event/root (:journal/record-root record)})
              (update :admission-order conj submission-root)
              (update-in [:reserved-by-entitlement entitlement-root] (fnil +' 0)
                         (:submission/amount submission))))))
      :committed
      (let [admission-root* (:admission/root payload)
            commit-root* (:commit/root payload)
            commit-sequence (:commit/sequence payload)
            before-root (:projection/before-root payload)
            after-root (:projection/after-root payload)
            submission-root (some (fn [[s-root admission]]
                                    (when (= admission-root* (:admission/root admission)) s-root))
                                  (:admissions state))
            submission (get-in state [:submissions submission-root])
            entitlement-root (:submission/entitlement-root submission)
            amount (:submission/amount submission)
            expected-before (economic-projection-root state)
            prior-uncommitted (first (remove (:committed-admissions state)
                                             (:admission-order state)))
            expected-sequence (inc (count (:committed-admissions state)))
            after-state (update-in state [:paid-by-entitlement entitlement-root]
                                   (fnil +' 0) amount)
            expected-after (economic-projection-root after-state)]
        (when-not (and (not (terminal? state))
                       (= #{:admission/root :submission/root :commit/sequence
                            :projection/before-root :projection/after-root
                            :commit/effect :commit/root}
                          (set (keys payload)))
                       (= :modeled-liability-update (:commit/effect payload))
                       (= submission-root (:submission/root payload))
                       submission
                       (= prior-uncommitted submission-root)
                       (= expected-sequence commit-sequence)
                       (= expected-before before-root)
                       (= expected-after after-root)
                       (= commit-root* (commit-root (dissoc payload :commit/root))))
          (throw (ex-info "Invalid, stale, or out-of-order commit record"
                          {:reason :partial-fill-file/commit-mismatch})))
        (-> after-state
            (update-in [:reserved-by-entitlement entitlement-root] - amount)
            (update :committed-admissions conj submission-root)
            (update :committed-admissions-in-order conj submission-root)
            (assoc-in [:commit-by-admission admission-root*] commit-root*)))
      :completed
      (let [payload-base (dissoc payload :completion/root)
            expected-root (completion-root payload-base)]
        (when-not (and (= #{:completion/root :operation/root :projection/root
                            :admission/count :commit/count :prior-journal/root}
                          (set (keys payload)))
                       (not (terminal? state))
                       (= (:operation/root (:operation state)) (:operation/root payload))
                       (= (count (:admission-order state)) (:admission/count payload))
                       (= (count (:committed-admissions state)) (:commit/count payload))
                       (= (:admission-order state) (vec (:committed-admissions-in-order state)))
                       (= (set (keys (:submissions state)))
                          (set (keys (:admissions state))))
                       (= (:journal/previous-root record) (:prior-journal/root payload))
                       (= (economic-projection-root state) (:projection/root payload))
                       (= expected-root (:completion/root payload)))
          (throw (ex-info "Invalid or premature completion record"
                          {:reason :partial-fill-file/invalid-completion})))
        (assoc state :completion payload))
      (throw (ex-info "Unsupported file-profile journal record" {:kind kind})))))

(defn- valid-record-shape? [record]
  (and (map? record)
       (= #{:artifact/schema :journal/sequence :journal/previous-root
            :authority/root :record/kind :record/payload :journal/record-root}
          (set (keys record)))
       (= record-schema (:artifact/schema record))
       (map? (:record/payload record))))

(defn- valid-record-header? [state record]
  (and (valid-record-shape? record)
       (= (inc (:journal/sequence state)) (:journal/sequence record))
       (= (:journal/root state) (:journal/previous-root record))
       (= (:authority-root state) (:authority/root record))
       (= (:journal/record-root record) (record-root record))))

(defn- read-one-edn [line]
  (let [eof (Object.)]
    (with-open [reader (PushbackReader. (StringReader. line))]
      (let [value (edn/read {:eof eof} reader)
            trailing (edn/read {:eof eof} reader)]
        (when (identical? eof value)
          (throw (ex-info "Empty file-profile journal record" {})))
        (when-not (identical? eof trailing)
          (throw (ex-info "Trailing data after file-profile journal record" {})))
        value))))

(defn- parse-journal-bytes [^bytes bytes]
  (let [text (String. bytes StandardCharsets/UTF_8)
        newline-index (.lastIndexOf text "\n")
        valid-text (if (neg? newline-index) "" (subs text 0 (inc newline-index)))
        split-lines (if (empty? valid-text) [] (str/split valid-text #"\n" -1))
        lines (if (seq split-lines) (pop (vec split-lines)) [])
        truncated? (and (pos? (.length text))
                        (not= newline-index (dec (.length text))))]
    (when (some empty? lines)
      (throw (ex-info "Blank complete file-profile journal line"
                      {:reason :partial-fill-file/malformed-record})))
    {:records (try
                (mapv read-one-edn lines)
                (catch Exception error
                  (throw (ex-info "Malformed complete file-profile journal record"
                                  {:reason :partial-fill-file/malformed-record}
                                  error))))
     :valid-byte-count (alength (.getBytes valid-text StandardCharsets/UTF_8))
     :truncated? truncated?}))

(defn- replay-journal! [^Path path]
  (let [bytes (Files/readAllBytes path)
        {:keys [records valid-byte-count truncated?]} (parse-journal-bytes bytes)
        genesis (first records)
        operation (get-in genesis [:record/payload :operation])
        authority-root (:authority/root genesis)]
    (when-not (valid-record-shape? genesis)
      (throw (ex-info "Malformed file-profile journal genesis record"
                      {:reason :partial-fill-file/malformed-record})))
    (when-not (and (seq records)
                   (= :operation-opened (:record/kind genesis))
                   (valid-operation? operation)
                   (root? authority-root))
      (throw (ex-info "Missing or invalid file-profile journal genesis"
                      {:reason :partial-fill-file/invalid-genesis})))
    (let [initial (initial-state operation authority-root)
          rebuilt (reduce (fn [state record]
                            (when-not (valid-record-shape? record)
                              (throw (ex-info "Malformed file-profile journal record"
                                              {:reason :partial-fill-file/malformed-record})))
                            (when-not (valid-record-header? state record)
                              (throw (ex-info "File-profile journal chain/root mismatch"
                                              {:reason :partial-fill-file/journal-chain-mismatch
                                               :sequence (:journal/sequence record)})))
                            (let [next-state
                                  (try
                                    (append-state-record state record)
                                    (catch clojure.lang.ExceptionInfo error
                                      (if (:reason (ex-data error))
                                        (throw error)
                                        (throw (ex-info "Invalid file-profile journal record"
                                                        {:reason :partial-fill-file/invalid-record
                                                         :sequence (:journal/sequence record)
                                                         :kind (:record/kind record)}
                                                        error))))
                                    (catch Exception error
                                      (throw (ex-info "Invalid file-profile journal record"
                                                      {:reason :partial-fill-file/invalid-record
                                                       :sequence (:journal/sequence record)
                                                       :kind (:record/kind record)}
                                                      error))))]
                              (if (= :operation-opened (:record/kind record))
                                (do (when-not (and (= -1 (:journal/sequence state))
                                                   (zero? (:journal/sequence record))
                                                   (= operation (get-in record [:record/payload :operation])))
                                      (throw (ex-info "Invalid file-profile genesis record" {})))
                                    (assoc next-state :journal/sequence 0))
                                next-state)))
                          initial records)]
      (when truncated?
        (with-open [channel (FileChannel/open path
                                             (into-array OpenOption [StandardOpenOption/WRITE]))]
          (.truncate channel (long valid-byte-count))
          (.force channel true)))
      (assoc rebuilt :forensic-nodes
             (loop [remaining records previous nil nodes []]
               (if-let [record (first remaining)]
                 (let [node (event-node record previous)]
                   (recur (next remaining) node (conj nodes node)))
                 nodes))))))

(defn- path-lock [^Path path]
  (let [key (str (.toAbsolutePath path))
        created (Object.)
        existing (.putIfAbsent lock-objects key created)]
    (or existing created)))

(defn- force-parent-directory! [^Path path]
  (let [parent (.getParent (.toAbsolutePath path))]
    (when parent
      (with-open [channel (FileChannel/open parent
                                           (into-array OpenOption [StandardOpenOption/READ]))]
        (.force channel true)))))

(defn- with-exclusive-file [^Path path f]
  (locking (path-lock path)
    (when-let [parent (.getParent (.toAbsolutePath path))]
      (when-not (Files/isDirectory parent (make-array LinkOption 0))
        (throw (ex-info "File-profile parent directory must already exist"
                        {:reason :partial-fill-file/parent-directory-missing
                         :path (str parent)}))))
    (with-open [channel (FileChannel/open path
                                         (into-array OpenOption [StandardOpenOption/CREATE
                                                                 StandardOpenOption/READ
                                                                 StandardOpenOption/WRITE]))
                lock (.lock channel)]
      (f channel))))

(defn- append-record! [^Path path ^FileChannel channel record new-file?]
  (let [bytes (.getBytes (str (pr-str record) "\n") StandardCharsets/UTF_8)
        buffer (ByteBuffer/wrap bytes)]
    (.position channel (.size channel))
    (while (.hasRemaining buffer) (.write channel buffer))
    (.force channel true)
    (when new-file? (force-parent-directory! path))
    record))

(defn- torn-genesis-prefix? [^bytes bytes ^bytes expected-genesis]
  (and (pos? (alength bytes))
       (< (alength bytes) (alength expected-genesis))
       (java.util.Arrays/equals
        bytes
        (java.util.Arrays/copyOf expected-genesis (alength bytes)))))

(defn- commit-root [payload]
  (root "PRF_PARTIAL_FILL_FILE_COMMIT_V1" payload))

(defn- preparation-root [payload]
  (root "PRF_PARTIAL_FILL_FILE_PREPARATION_V1" payload))

(defn- admission-root [payload]
  (root "PRF_PARTIAL_FILL_FILE_ADMISSION_V1" payload))

(defn- completion-root [payload]
  (root "PRF_PARTIAL_FILL_FILE_COMPLETION_V1" payload))

(defn- write-event! [ledger channel state kind payload]
  (let [record (make-record (inc (:journal/sequence state)) (:journal/root state)
                            (:authority-root state) kind payload)
        next-state (append-state-record state record)
        node (event-node record (peek (:forensic-nodes state)))
        next-state (update next-state :forensic-nodes (fnil conj []) node)]
    (append-record! (:path ledger) channel record false)
    next-state))

(defn- refresh-state! [ledger channel]
  (let [state (replay-journal! (:path ledger))]
    (reset! (:state ledger) state)
    state))

(defn create-ledger!
  "Create or reopen a file-backed profile. Existing files must match the exact
   operation and ordering-authority roots supplied by the caller."
  [path operation authority-root]
  (when-not (and (valid-operation? operation) (root? authority-root))
    (throw (ex-info "Invalid file-profile ledger identity" {})))
  (let [path (if (instance? Path path)
               (.toAbsolutePath (.normalize ^Path path))
               (.toAbsolutePath (.normalize (.toPath (io/file path)))))
        ledger {:path path :operation operation :authority-root authority-root
                :state (atom nil)}]
    (with-exclusive-file
      path
      (fn [channel]
        (let [genesis (make-record 0 nil authority-root :operation-opened
                                   {:operation operation
                                    :profile/schema profile-schema})
              genesis-bytes (.getBytes (str (pr-str genesis) "\n") StandardCharsets/UTF_8)]
          (when (pos? (.size channel))
            (let [bytes (Files/readAllBytes path)
                  {:keys [records truncated?]} (parse-journal-bytes bytes)]
              (when (and (empty? records) truncated?
                         (torn-genesis-prefix? bytes genesis-bytes))
                (.truncate channel 0)
                (.force channel true))))
          (when (zero? (.size channel))
            (append-record! path channel genesis true)))
        ;; Re-sync the directory entry on every open. If a previous create
        ;; returned after a platform-specific directory force failure, a later
        ;; successful open gets another chance before accepting the ledger.
        (force-parent-directory! path)
        (let [state (replay-journal! path)]
          (when-not (and (= operation (:operation state))
                         (= authority-root (:authority-root state)))
            (throw (ex-info "File-profile journal belongs to different operation or authority"
                            {:reason :partial-fill-file/journal-owner-mismatch})))
          (reset! (:state ledger) state))))
    ledger))

(defn record-source!
  "Record an immutable operation source before any submissions are accepted."
  [ledger source]
  (let [path (:path ledger)]
    (when-not (valid-source? (:operation ledger) source)
      (throw (ex-info "Source does not bind to this operation"
                      {:reason :partial-fill-file/invalid-source})))
    (with-exclusive-file
      path
      (fn [channel]
        (let [state (refresh-state! ledger channel)
              source-root (:source/root source)]
          (cond
            (= source (get-in state [:sources source-root]))
            {:status :duplicate :source/root source-root}

            (or (terminal? state) (seq (:submissions state)))
            (throw (ex-info "Sources must be recorded before submissions"
                            {:reason :partial-fill-file/source-phase-closed}))

            (contains? (:sources state) source-root)
            (throw (ex-info "Source root is already bound to different content"
                            {:reason :partial-fill-file/source-conflict}))

            :else
            (let [next-state (write-event! ledger channel state :source source)]
              (reset! (:state ledger) next-state)
              {:status :recorded :source/root source-root})))))))

(defn ledger-state [ledger]
  @(:state ledger))

(defn submissions [ledger]
  (:submissions (ledger-state ledger)))

(defn admissions [ledger]
  (:admissions (ledger-state ledger)))

(defn preparations [ledger]
  (:preparations (ledger-state ledger)))

(defn frozen-snapshot-root
  "Return the immutable source/operation snapshot root for a submission."
  [ledger submission-root]
  (let [state (ledger-state ledger)
        submission (get-in state [:submissions submission-root])]
    (when-not submission
      (throw (ex-info "Unknown submission for frozen snapshot"
                      {:reason :partial-fill-file/unknown-submission})))
    (submission-snapshot-root (:operation state) submission)))

(defn submit!
  "Record an immutable proposal. Arrival is append order only; no wall clock is
   trusted. A repeated idempotency key with identical content is a no-op."
  [ledger submission]
  (let [path (:path ledger)]
    (verify-submission! (:operation ledger) submission)
    (with-exclusive-file
      path
      (fn [channel]
        (let [state (refresh-state! ledger channel)
              idem (:submission/idempotency-root submission)
              prior-root (get-in state [:idempotency idem])]
          (cond
            (and prior-root (= submission (get-in state [:submissions prior-root])))
            {:status :duplicate :submission/root prior-root}

            prior-root
            (throw (ex-info "Idempotency key already binds different submission"
                            {:reason :partial-fill-file/idempotency-conflict
                             :idempotency/root idem}))

            :else
            (do
              (ensure-open! state)
              (when-not (every? #(contains? (:sources state) %)
                                (:submission/source-roots submission))
                (throw (ex-info "Submission refers to an unrecorded source"
                                {:reason :partial-fill-file/unknown-source
                                 :source-roots (:submission/source-roots submission)})))
              (let [next-state (write-event! ledger channel state :submission submission)]
                (reset! (:state ledger) next-state)
                {:status :submitted :submission/root (:submission/root submission)}))))))))

(defn prepare!
  "Record a worker result bound to the submission's frozen operation/source snapshot."
  [ledger preparation]
  (let [path (:path ledger)]
    (with-exclusive-file
      path
      (fn [channel]
        (let [state (refresh-state! ledger channel)
              submission (get-in state [:submissions
                                        (:preparation/submission-root preparation)])
              prep-root (:preparation/root preparation)
              prior (get-in state [:preparations prep-root])]
          (when-not (and submission
                         (valid-preparation? (:operation state) submission preparation))
            (throw (ex-info "Preparation is stale or not bound to this submission snapshot"
                            {:reason :partial-fill-file/stale-preparation
                             :preparation/root prep-root})))
          (cond
            (and prior (= prior preparation))
            {:status :duplicate :preparation/root (:preparation/root prior)}

            prior
            (throw (ex-info "Preparation root is already bound to different content"
                            {:reason :partial-fill-file/preparation-conflict
                             :preparation/root prep-root}))

            (contains? (:admissions state) (:preparation/submission-root preparation))
            (throw (ex-info "Cannot add a preparation after admission"
                            {:reason :partial-fill-file/stale-preparation
                             :preparation/root prep-root}))

            :else
            (do
              (ensure-open! state)
              (let [next-state (write-event! ledger channel state :prepared preparation)]
                (reset! (:state ledger) next-state)
                {:status :prepared :preparation/root prep-root}))))))))

(defn admit!
  "Admit a prepared proposal and reserve entitlement capacity. Sequence
   numbers are assigned by the local ordering authority, never workers."
  [ledger submission-root]
  (let [path (:path ledger)]
    (with-exclusive-file
      path
      (fn [channel]
        (let [state (refresh-state! ledger channel)
              submission (get-in state [:submissions submission-root])]
          (when-not submission
            (throw (ex-info "Cannot admit an unknown submission"
                            {:reason :partial-fill-file/unknown-submission})))
          (if-let [existing (get-in state [:admissions submission-root])]
            {:status :duplicate :admission/root (:admission/root existing)}
            (do
              (ensure-open! state)
              (let [candidates (->> (vals (:preparations state))
                                    (filter #(= submission-root
                                                (:preparation/submission-root %)))
                                    (sort-by :preparation/root)
                                    vec)
                    result-roots (set (map :preparation/result-root candidates))]
                (when (empty? candidates)
                  (throw (ex-info "Cannot admit without worker preparation"
                                  {:reason :partial-fill-file/missing-preparation})))
                (when (> (count result-roots) 1)
                  (throw (ex-info "Worker preparations conflict for frozen input"
                                  {:reason :partial-fill-file/preparation-conflict
                                   :result-roots (vec (sort result-roots))})))
                (let [preparation-root* (:preparation/root (first candidates))
                      entitlement-root (:submission/entitlement-root submission)
                      entitlement (entitlement-for (:operation state) entitlement-root)
                      paid (get-in state [:paid-by-entitlement entitlement-root] 0)
                      reserved (get-in state [:reserved-by-entitlement entitlement-root] 0)
                      amount (:submission/amount submission)]
                  (when (> (+ paid reserved amount) (:entitlement/amount entitlement))
                    (throw (ex-info "Admission exceeds remaining entitlement"
                                    {:reason :partial-fill-file/entitlement-overdraw
                                     :entitlement/root entitlement-root
                                     :paid paid :reserved reserved :requested amount})))
                  (let [seq-no (inc (count (:admission-order state)))
                        payload-base {:submission/root submission-root
                                      :preparation/root preparation-root*
                                      :admission/sequence seq-no
                                      :authority/root (:authority-root state)}
                        payload (assoc payload-base
                                       :admission/root (admission-root payload-base))
                        next-state (write-event! ledger channel state :admitted payload)]
                    (reset! (:state ledger) next-state)
                    {:status :admitted :admission/root (:admission/root payload)
                     :admission/sequence seq-no}))))))))))

(defn commit!
  "Serially commit the admitted model effect. This records a liability
   projection only; it neither observes nor performs a token transfer."
  [ledger admission-root]
  (let [path (:path ledger)]
    (with-exclusive-file
      path
      (fn [channel]
        (let [state (refresh-state! ledger channel)
              admission-entry (some (fn [[_ entry]]
                                     (when (= admission-root (:admission/root entry)) entry))
                                   (:admissions state))]
          (when-not admission-entry
            (throw (ex-info "Cannot commit an unknown admission"
                            {:reason :partial-fill-file/unknown-admission})))
          (let [submission-root (:submission/root admission-entry)
                submission (get-in state [:submissions submission-root])]
            (if (contains? (:committed-admissions state) submission-root)
              {:status :duplicate :commit/root
               (get-in state [:commit-by-admission admission-root])}
              (let [next-pending (first (remove (:committed-admissions state)
                                                (:admission-order state)))]
                (ensure-open! state)
                (when-not (= submission-root next-pending)
                  (throw (ex-info "Commit must follow admission order"
                                  {:reason :partial-fill-file/out-of-order-commit
                                   :next-submission/root next-pending
                                   :requested-submission/root submission-root})))
                (let [before (economic-projection-root state)
                      amount (:submission/amount submission)
                      after-state (update-in state [:paid-by-entitlement
                                                    (:submission/entitlement-root submission)]
                                             (fnil +' 0) amount)
                      after (economic-projection-root after-state)
                      seq-no (inc (count (:committed-admissions state)))
                      payload-base {:admission/root admission-root
                                    :submission/root submission-root
                                    :commit/sequence seq-no
                                    :projection/before-root before
                                    :projection/after-root after
                                    :commit/effect :modeled-liability-update}
                      payload (assoc payload-base :commit/root (commit-root payload-base))
                      next-state (write-event! ledger channel state :committed payload)
                      next-state (assoc-in next-state [:commit-by-admission admission-root]
                                            (:commit/root payload))]
                  (reset! (:state ledger) next-state)
                  {:status :committed :commit/root (:commit/root payload)
                   :projection/root after :commit/sequence seq-no})))))))))

(defn complete!
  "Close the model once every recorded submission is admitted and committed."
  [ledger]
  (let [path (:path ledger)]
    (with-exclusive-file
      path
      (fn [channel]
        (let [state (refresh-state! ledger channel)]
          (if-let [existing (:completion state)]
            {:status :duplicate :completion/root (:completion/root existing)}
            (do
              (ensure-open! state)
              (when-not (and (= (set (keys (:submissions state)))
                                (set (keys (:admissions state))))
                             (= (:admission-order state)
                                (:committed-admissions-in-order state)))
                (throw (ex-info "Cannot complete with pending or unadmitted submissions"
                                {:reason :partial-fill-file/incomplete-operation
                                 :submission-count (count (:submissions state))
                                 :admission-count (count (:admissions state))
                                 :commit-count (count (:committed-admissions state))})))
              (let [payload-base {:operation/root (:operation/root (:operation state))
                                  :projection/root (economic-projection-root state)
                                  :admission/count (count (:admission-order state))
                                  :commit/count (count (:committed-admissions state))
                                  :prior-journal/root (:journal/root state)}
                    payload (assoc payload-base
                                   :completion/root (completion-root payload-base))
                    next-state (write-event! ledger channel state :completed payload)]
                (reset! (:state ledger) next-state)
                {:status :completed
                 :completion/root (:completion/root payload)
                 :projection/root (:projection/root payload)}))))))))

(defn forensic-nodes [ledger]
  (:forensic-nodes (ledger-state ledger)))

(defn- rooted-values [value]
  (cond
    (root? value) [value]
    (map? value) (mapcat rooted-values (vals value))
    (sequential? value) (mapcat rooted-values value)
    :else []))

(defn validate-forensic-evidence [ledger]
  (let [state (ledger-state ledger)
        roots (set (rooted-values (:records state)))
        records (:records state)
        nodes (forensic-nodes ledger)
        record-roots (mapv :journal/record-root records)
        node-record-roots (mapv #(get-in % [:extensions :forensic/subject :subject/root]) nodes)
        event-coverage-valid? (and (= (count records) (count nodes))
                                   (= record-roots node-record-roots))
        graph (forensic/validate-forensic-evidence-graph
               {:nodes nodes :artifact-roots roots})]
    (-> graph
        (assoc :valid? (and (:valid? graph) event-coverage-valid?)
               :integrity-valid? (and (:integrity-valid? graph) event-coverage-valid?)
               :event-coverage-valid? event-coverage-valid?)
        (update :errors (fnil into [])
                (when-not event-coverage-valid?
                  [{:error :partial-fill-file/forensic-event-coverage
                    :journal-record-count (count records)
                    :forensic-node-count (count nodes)}]))
        (update :summary assoc
                :journal-record-count (count records)
                :event-coverage-valid? event-coverage-valid?))))
