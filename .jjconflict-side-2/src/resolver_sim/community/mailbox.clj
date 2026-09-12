(ns resolver-sim.community.mailbox
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.community.task :as task]
            [resolver-sim.io.edn :as ppedn]))

(def ^:const schema-version "community-mailbox.v0")
(def ^:const domain-tag "COMMUNITY_MAILBOX_V0")

(def ^:const message-types
  #{:TASK_ANNOUNCEMENT :RUNNER_RESULT
    :REPRODUCTION_RESULT   ;; reserved for two-step reproduction workflow
    :CHALLENGE :AGREEMENT :DISAGREEMENT})

(def ^:const supported-statuses
  #{:announced :executed :reproduced :challenged :agreed :disagreed :inconclusive})

(defn- default-dir []
  (str (System/getProperty "user.home") "/.protocol-robustness/community-mailbox"))

(def ^:dynamic *mailbox-dir* (default-dir))

(defn- ensure-dir! []
  (.mkdirs (io/file *mailbox-dir*)))

(defn- message-filename [hash]
  (str "msg-" (subs hash 0 12) ".edn"))

(defn- message-path [hash]
  (str *mailbox-dir* "/" (message-filename hash)))

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
  (dissoc m :message/hash :message/ref :message/signature :message/key-path :timestamp))

(defn message-hash
  [msg]
  (hc/domain-hash domain-tag (normalize-for-hash (canonical-body msg))))

(defn build-message
  [m]
  (let [msg-type (:message/type m)
        subject-task (:subject-task m)
        sender (:sender m)
        _ (assert (contains? message-types msg-type))
        _ (assert (task/task-ref? subject-task))
        _ (assert (string? sender))
        bare {:schema-version schema-version
              :message/type msg-type
              :subject-task subject-task
              :sender sender
              :attestation-ref (:attestation-ref m)
              :evidence-ref (:evidence-ref m)
              :body (:body m)
              :timestamp (or (:timestamp m) (str (java.time.Instant/now)))}
        hash (message-hash bare)]
    (cond-> (assoc bare :message/hash hash :message/ref (str "mailbox:sha256:" hash))
      (:signature m) (assoc :message/signature (:signature m))
      (:key-path m) (assoc :message/key-path (:key-path m)))))

(defn sign-message!
  [msg private-key-path]
  (let [hash (:message/hash msg)
        sig (signing/sign-hash hash private-key-path nil)]
    (assoc msg
           :message/signature sig
           :message/key-path (str private-key-path ".pub"))))

(defn publish!
  [msg]
  (assert (:message/hash msg) "Message must have a hash")
  (ensure-dir!)
  (let [path (message-path (:message/hash msg))]
    (if (.exists (io/file path))
      :duplicate
      (do (spit path (ppedn/ppr-str msg))
          :published))))

(defn list-messages
  [& {:keys [type-filter]}]
  (ensure-dir!)
  (let [dir (io/file *mailbox-dir*)
        files (sort (filter #(.endsWith (.getName %) ".edn") (or (.listFiles dir) [])))
        msgs (keep (fn [f]
                     (try (edn/read-string (slurp f))
                          (catch Exception _ nil)))
                   files)]
    (cond->> msgs
      type-filter (filter #(= type-filter (:message/type %))))))

(defn find-message
  [hash-or-ref]
  (let [hash (if (and (string? hash-or-ref) (.startsWith hash-or-ref "mailbox:sha256:"))
               (subs hash-or-ref (count "mailbox:sha256:"))
               hash-or-ref)]
    (try (edn/read-string (slurp (message-path hash)))
         (catch Exception _ nil))))

(defn messages-for-task
  [task-ref]
  (filter #(= task-ref (:subject-task %)) (list-messages)))

(defn task-status
  "Derive task status from mailbox message types only (fast, no verification)."
  [task-ref]
  (let [msgs (messages-for-task task-ref)
        types (set (map :message/type msgs))]
    (cond
      (contains? types :CHALLENGE) :challenged
      (contains? types :DISAGREEMENT) :disagreed
      (contains? types :AGREEMENT) :agreed
      (contains? types :REPRODUCTION_RESULT) :reproduced
      (contains? types :RUNNER_RESULT) :executed
      (contains? types :TASK_ANNOUNCEMENT) :announced
      :else :unknown)))

(defn verified-task-status
  "Derive task status from verified attestations (slower, checks integrity).
   Requires artifact-dir to resolve attestations from disk.
   Returns :agreed or :disagreed only when the underlying attestations
   pass valid-attestation? (hash integrity + predicate-specific field validation)."
  [task-ref artifact-dir]
  (let [msgs (messages-for-task task-ref)
        types (set (map :message/type msgs))
        resolve-att (fn [ref]
                      (when ref
                        (try (edn/read-string
                              (slurp (str artifact-dir "/community-attestations/"
                                          "att-" (subs (if (.startsWith ref "attestation:sha256:")
                                                         (subs ref (count "attestation:sha256:"))
                                                         ref)
                                                       0 12) ".edn")))
                             (catch Exception _ nil))))]
    (cond
      (contains? types :CHALLENGE) :challenged
      (contains? types :DISAGREEMENT)
      (let [msg (first (filter #(= :DISAGREEMENT (:message/type %)) msgs))
            att (resolve-att (:attestation-ref msg))]
        (if (and att (requiring-resolve 'resolver-sim.community.attestation/valid-attestation?))
          :disagreed
          :inconclusive))
      (contains? types :AGREEMENT)
      (let [msg (first (filter #(= :AGREEMENT (:message/type %)) msgs))
            run-msg (first (filter #(= :RUNNER_RESULT (:message/type %)) msgs))
            run-att (resolve-att (:attestation-ref run-msg))
            repro-att (resolve-att (:attestation-ref msg))
            att-valid? (fn [a] (and a ((requiring-resolve 'resolver-sim.community.attestation/valid-attestation?) a)))]
        (if (and (att-valid? run-att) (att-valid? repro-att))
          :agreed
          :inconclusive))
      (contains? types :REPRODUCTION_RESULT) :reproduced
      (contains? types :RUNNER_RESULT) :executed
      (contains? types :TASK_ANNOUNCEMENT) :announced
      :else :unknown)))

(defn verify-message
  "Verify a message's hash integrity and optionally its signature.
   The sender identity (:sender) is committed to by the content hash.
   The public key is resolved from :message/key-path and must correspond
   to the claimed sender.
   Returns {:valid? true/false :errors [...] :hash-valid? true/false}"
  [msg]
  (let [errors (atom [])
        computed (message-hash msg)
        hash-valid? (= computed (:message/hash msg))]
    (when-not hash-valid?
      (swap! errors conj "Message hash mismatch"))
    (when (:message/signature msg)
      (let [pub-path (:message/key-path msg)]
        (cond
          (nil? pub-path)
          (swap! errors conj "Signature present but no key path")
          (not (.exists (io/file pub-path)))
          (swap! errors conj (str "Public key not found: " pub-path))
          :else
          (try (let [sig-valid? (signing/verify-signature
                                 (:message/hash msg)
                                 (:message/signature msg)
                                 pub-path)]
                 (when-not sig-valid?
                   (swap! errors conj "Signature verification failed")))
               (catch Exception e
                 (swap! errors conj (str "Signature verification error: " (.getMessage e))))))))
    {:valid? (empty? @errors)
     :errors @errors
     :hash-valid? hash-valid?}))

(defn clear-mailbox!
  []
  (ensure-dir!)
  (doseq [f (filter #(.endsWith (.getName %) ".edn")
                    (or (.listFiles (io/file *mailbox-dir*)) []))]
    (io/delete-file f))
  nil)
