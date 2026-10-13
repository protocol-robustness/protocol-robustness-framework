(ns resolver-sim.research.researcher-challenges-cli
  "Offline signed-file participation interface; no HTTP service or key custody."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.io.edn :as pretty]
            [resolver-sim.network.researcher-challenges :as challenges])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files StandardCopyOption StandardOpenOption]))

(defn- read-file [path]
  (edn/read-string (slurp path)))

(defn- test-persist-barrier! [stage]
  ;; Opt-in process-kill qualification seam. Marker/release files are harness
  ;; events only; they are never read into the signed history or artifact roots.
  (let [directory (System/getProperty "resolver-sim.test.persist-barrier-dir")
        selected (System/getProperty "resolver-sim.test.persist-barrier-stage")]
    (when (and directory (= (name stage) selected))
      (let [marker (io/file directory "reached")
            release (io/file directory "release")
            deadline (+ (System/nanoTime) (* 120 1000000000))]
        (spit marker (name stage))
        (loop []
          (when (and (not (.exists release)) (< (System/nanoTime) deadline))
            (Thread/sleep 10)
            (recur)))))))

(defn- write-history! [path history]
  (let [target (.toAbsolutePath (.toPath (io/file path)))
        directory (.getParent target)
        temporary (Files/createTempFile directory ".researcher-challenges-" ".edn"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (.toFile temporary) (pretty/ppr-str history))
      (test-persist-barrier! :before-force)
      (with-open [channel (FileChannel/open temporary
                                            (into-array StandardOpenOption [StandardOpenOption/WRITE]))]
        (.force channel true))
      (test-persist-barrier! :after-force)
      (Files/move temporary target
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                  StandardCopyOption/REPLACE_EXISTING]))
      (test-persist-barrier! :after-replace)
      (finally (Files/deleteIfExists temporary)))))

(defn persist-edn!
  "Reuse the file-forced atomic replacement primitive for offline trust state.
   Caller must hold the appropriate OS lock. No directory-fsync claim."
  [path value]
  (write-history! path value))

(defn- apply-history-file!
  [config-path history-path action-path checkpoint-path policy-path history-check]
  (with-open [channel (FileChannel/open (.toPath (io/file (str history-path ".lock")))
                                        (into-array StandardOpenOption [StandardOpenOption/CREATE
                                                                        StandardOpenOption/WRITE]))
              _lock (.lock channel)]
    (let [config (read-file config-path)
          policy (when policy-path (read-file policy-path))
          history (if (.exists (io/file history-path)) (read-file history-path) [])
          _ (when checkpoint-path
              (if policy-path
                (challenges/verify-controlled-history-checkpoint config policy history (read-file checkpoint-path))
                (challenges/verify-history-checkpoint config history (read-file checkpoint-path))))
          result (if policy-path
                   (challenges/append-controlled-action config policy history (read-file action-path))
                   (challenges/append-action config history (read-file action-path)))]
      (when history-check (history-check config (:history result)))
      (write-history! history-path (:history result))
      {:history/root (get-in result [:state :history/root])
       :consequence (:consequence (last (get-in result [:state :events])))})))

(defn apply-file!
  "Serialize V1 file submissions under an OS lock. Existing semantics unchanged."
  ([config-path history-path action-path]
   (apply-file! config-path history-path action-path nil))
  ([config-path history-path action-path checkpoint-path]
   (apply-history-file! config-path history-path action-path checkpoint-path nil nil)))

(defn apply-checked-file!
  "Run an additional rooted method gate inside the existing admission lock,
   after signed replay and before durable replacement. A failed gate writes nothing."
  [config-path history-path action-path checkpoint-path history-check]
  (when-not (fn? history-check)
    (throw (ex-info "Method admission requires a history validator" {})))
  (apply-history-file! config-path history-path action-path checkpoint-path nil history-check))

(defn apply-controlled-file!
  "Explicit opt-in, using the same lock/fsync/atomic replacement path as V1."
  [config-path policy-path history-path action-path checkpoint-path]
  (when-not policy-path
    (throw (ex-info "Controlled admission requires an explicit policy file" {:reason :missing-control-policy})))
  (apply-history-file! config-path history-path action-path checkpoint-path policy-path nil))

(defn apply-controlled-checked-file!
  "Compose an opt-in controlled-history append with a domain gate under the
   same lock and before durable replacement. Rejection leaves history intact."
  [config-path policy-path history-path action-path checkpoint-path history-check]
  (when-not policy-path
    (throw (ex-info "Controlled admission requires an explicit policy file" {:reason :missing-control-policy})))
  (when-not (fn? history-check)
    (throw (ex-info "Controlled method admission requires a history validator" {})))
  (apply-history-file! config-path history-path action-path checkpoint-path policy-path history-check))

(defn- control-command! [[command config-path policy-path history-path target checkpoint-path]]
  (if (= "control-apply" command)
    (apply-controlled-file! config-path policy-path history-path target checkpoint-path)
    (let [config (read-file config-path) policy (read-file policy-path)
          history (if (.exists (io/file history-path)) (read-file history-path) [])]
      (case command
        "control-state" (challenges/replay-controlled config policy history)
        "control-checkpoint" (challenges/controlled-history-checkpoint config policy history)
        "control-verify-checkpoint"
        (let [checkpoint (read-file target)]
          (challenges/verify-controlled-history-checkpoint config policy history checkpoint)
          {:checkpoint/status :verified :checkpoint checkpoint})
        "control-summary" (challenges/controlled-researcher-summary config policy history target)
        "control-forensic" (challenges/controlled-forensic-nodes config policy history)
        "control-review-dossier"
        (do (when checkpoint-path
              (challenges/verify-controlled-history-checkpoint config policy history (read-file checkpoint-path)))
            (challenges/controlled-review-dossier config policy history target))
        "control-request"
        (let [state (if checkpoint-path
                      (challenges/verify-controlled-history-checkpoint config policy history (read-file checkpoint-path))
                      (challenges/replay-controlled config policy history))
              request (read-file target)
              _ (when-not (= #{:actor :kind :challenge-root :nonce :payload} (set (keys request)))
                  (throw (ex-info "Invalid control-aware request" {})))
              payload (cond-> (:payload request)
                        (= :enroll (:kind request)) (update :public-key challenges/canonical-public-key))]
          (if (contains? #{:revoke-participant :withdraw-review} (:kind request))
            (let [a (challenges/control-action config policy (:history/root state)
                                               (:kind request) (:nonce request) payload)]
              (when-not (and (= (:actor/root a) (:actor request)) (nil? (:challenge-root request)))
                (throw (ex-info "Control request must use pinned reviewer and no challenge override" {})))
              a)
            (do (when (contains? (:revocations state) (:actor request))
                  (throw (ex-info "Participant revoked" {:reason :participant-revoked})))
                (challenges/action config (:history/root state) (:actor request) (:kind request)
                                   (:challenge-root request) (:nonce request) payload))))))))

(defn -main [& args]
  (case (first args)
    "control-policy" (let [[_ config history] args]
                       (println (pretty/ppr-str
                                 (challenges/build-control-policy
                                  (read-file config) (if (.exists (io/file history)) (read-file history) [])))))
    ("control-request" "control-apply" "control-state" "control-checkpoint"
                       "control-verify-checkpoint" "control-summary" "control-review-dossier" "control-forensic")
    (println (pretty/ppr-str (control-command! args)))
    "prepare" (let [[_ input-path] args]
                (println (pretty/ppr-str (challenges/prepare-configuration (read-file input-path)))))
    "checkpoint" (let [[_ config history] args]
                   (println (pretty/ppr-str
                             (challenges/history-checkpoint
                              (read-file config)
                              (if (.exists (io/file history)) (read-file history) [])))))
    "verify-checkpoint" (let [[_ config history checkpoint] args
                              config (read-file config)
                              history (if (.exists (io/file history)) (read-file history) [])
                              checkpoint (read-file checkpoint)]
                          (challenges/verify-history-checkpoint config history checkpoint)
                          (println (pretty/ppr-str {:checkpoint/status :verified
                                                    :checkpoint checkpoint})))
    "request" (let [[_ config-path history-path request-path checkpoint-path] args
                    config (read-file config-path)
                    history (if (.exists (io/file history-path)) (read-file history-path) [])
                    state (if checkpoint-path
                            (challenges/verify-history-checkpoint config history (read-file checkpoint-path))
                            (challenges/replay config history))
                    request (read-file request-path)
                    payload (cond-> (:payload request)
                              (= :enroll (:kind request))
                              (update :public-key challenges/canonical-public-key))]
                (when-not (= #{:actor :kind :challenge-root :nonce :payload} (set (keys request)))
                  (throw (ex-info "Request requires actor, kind, challenge-root, nonce and payload" {})))
                (println (pretty/ppr-str
                          (challenges/action config (:history/root state)
                                             (:actor request) (:kind request) (:challenge-root request)
                                             (:nonce request) payload))))
    "apply" (let [[_ config history signed-action checkpoint] args]
              (println (pretty/ppr-str (apply-file! config history signed-action checkpoint))))
    "sign" (let [[_ unsigned-action private-key] args]
             (println (pretty/ppr-str (challenges/sign-action (read-file unsigned-action) private-key))))
    "catalog" (let [[_ config] args]
                (println (pretty/ppr-str (:challenges (read-file config)))))
    "state" (let [[_ config history] args]
              (println (pretty/ppr-str
                        (challenges/replay (read-file config)
                                           (if (.exists (io/file history)) (read-file history) [])))))
    "summary" (let [[_ config history researcher] args]
                (println (pretty/ppr-str
                          (challenges/researcher-summary (read-file config) (read-file history) researcher))))
    "review-dossier" (let [[_ config history submission checkpoint] args
                           config (read-file config) history (read-file history)]
                       (when checkpoint
                         (challenges/verify-history-checkpoint config history (read-file checkpoint)))
                       (println (pretty/ppr-str (challenges/review-dossier config history submission))))
    (throw (ex-info "Usage: prepare INPUT | checkpoint CONFIG HISTORY | verify-checkpoint CONFIG HISTORY CHECKPOINT | request CONFIG HISTORY REQUEST [CHECKPOINT] | apply CONFIG HISTORY SIGNED_ACTION [CHECKPOINT] | sign ACTION PRIVATE_KEY | catalog CONFIG | state CONFIG HISTORY | summary CONFIG HISTORY RESEARCHER_ROOT | review-dossier CONFIG HISTORY SUBMISSION_ROOT [CHECKPOINT] | control-policy CONFIG HISTORY | control-{request,apply,state,checkpoint,verify-checkpoint,summary,review-dossier,forensic} CONFIG POLICY HISTORY [TARGET] [CHECKPOINT]" {}))))
