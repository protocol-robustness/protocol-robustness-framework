(ns resolver-sim.research.runner-transport.proof
  "SP1 proof delivery and coordinator-side accounting-proof admission."
  (:require [clojure.java.io :as io]
            [resolver-sim.accounting.reductions :as accounting]
            [resolver-sim.research.accounting-proof-admission :as accounting-proof-admission]
            [resolver-sim.research.execution-control :as control]
            [resolver-sim.research.native-accounting-work :as native-accounting]
            [resolver-sim.research.remote-execution :as remote]
            [resolver-sim.research.runner-transport.http :as transport-http])
  (:import [java.nio.file AtomicMoveNotSupportedException Files Path StandardCopyOption]
           [java.security MessageDigest]
           [java.util Base64]))

(def ^:dynamic *runtime* nil)
(def ^:private runtime-keys
  #{:coordinator-state :update-coordinator-state! :runner-state
    :update-runtime-state! :post-edn!})

(defn with-runtime [runtime f]
  (when-not (and (map? runtime) (= runtime-keys (set (keys runtime))) (fn? f))
    (throw (ex-info "Invalid proof transport runtime" {})))
  (binding [*runtime* runtime] (f)))

(defn- runtime-fn [key]
  (or (get *runtime* key)
      (throw (ex-info "Proof transport called without runtime services"
                      {:missing-runtime-key key}))))
(defn- coordinator-state [& args]
  (apply (runtime-fn :coordinator-state) args))
(defn- update-coordinator-state! [& args]
  (apply (runtime-fn :update-coordinator-state!) args))
(defn- runner-state [& args] (apply (runtime-fn :runner-state) args))
(defn- update-runtime-state! [& args]
  (apply (runtime-fn :update-runtime-state!) args))
(defn- post-edn! [& args] (apply (runtime-fn :post-edn!) args))

(defn admit-native-accounting-proof!
  "Coordinator-side alternative admission for a completed native accounting
   assignment. The coordinator structurally validates execution lineage and
   runs the real SP1 verifier before ingesting the partial. The existing V1
   delivery path and its arithmetic recomputation remain unchanged."
  [coordinator {:keys [assignment execution proof-file metadata-file
                       generation-receipt verifier-participation verifier-reference
                       verifier-registry-extension-map verification-cutpoint]}]
  (let [assignment-root (:research-run-assignment/root assignment)
        state (coordinator-state coordinator)
        intent (get-in state [:dispatch-intents assignment-root])
        work (:execution-work intent)]
    (when-not (and (remote/valid-assignment? assignment)
                   (= assignment (get-in state [:assignments assignment-root]))
                   (= assignment (:work/assignment work))
                   (:execute? intent)
                   (native-accounting/valid-work? work))
      (throw (ex-info "Proof-backed accounting admission has no matching assignment"
                      {:cause :accounting-assignment-mismatch})))
    (let [admission (accounting-proof-admission/verify-and-build!
                     {:work work :execution execution :proof-file proof-file
                      :metadata-file metadata-file :generation-receipt generation-receipt
                      :verifier-participation verifier-participation
                      :verifier-reference verifier-reference
                      :verifier-registry-extension-map verifier-registry-extension-map
                      :verification-cutpoint verification-cutpoint})
          plan-root (get-in work [:accounting/partition-plan :accounting-partition-plan/root])
          plan-context (select-keys work [:accounting/request :accounting/partition-plan])
          partial (:accounting-partial execution)
          admission-root (:accounting-proof-backed-admission/root admission)
          execution-root (native-accounting/execution-root execution)
          result-key [assignment-root execution-root]
          previous (get-in state [:native-accounting-results result-key])]
      (when (and previous
                 (not= (:native-accounting-execution previous) execution))
        (throw (ex-info "Accounting assignment already has another execution"
                        {:cause :accounting-execution-conflict})))
      (update-coordinator-state!
       coordinator
       (fn [current]
         (let [current-intent (get-in current [:dispatch-intents assignment-root])
               _ (when-not (and (= assignment (get-in current [:assignments assignment-root]))
                                (= work (:execution-work current-intent))
                                (:execute? current-intent))
                   (throw (ex-info "Proof admission assignment changed during verification"
                                   {:cause :accounting-assignment-mismatch})))
               prior-context (get-in current [:accounting-contexts plan-root])
               existing (get-in current [:accounting-proof-admissions admission-root])]
           (when (and prior-context (not= prior-context plan-context))
             (throw (ex-info "Accounting request/plan context substitution" {})))
           (when (and existing (not= existing admission))
             (throw (ex-info "Proof admission root collision" {})))
           (-> current
               (assoc-in [:accounting-contexts plan-root] plan-context)
               (assoc-in [:native-accounting-results result-key]
                         {:job (:work/job work) :assignment assignment
                          :native-accounting-execution execution})
               (assoc-in [:accounting-proof-admissions admission-root] admission)
               (update-in [:accounting-partials plan-root]
                          (fnil accounting/ingest-partial {}) partial)))))
      {:transport/status (if (and previous
                                  (get-in state [:accounting-proof-admissions admission-root]))
                           :already-admitted :accepted)
       :accounting-proof-backed-admission/root admission-root
       :accounting-partial-result/root (:accounting-partial-result/root partial)
       :canonical-accounting-partial/root
       (:canonical-accounting-partial/root admission)
       :research-proof-verification-evidence/root
       (:research-proof-verification-evidence/root admission)})))

(defn- persisted-proof-path [runner content-root]
  (when-not (:transport/state-path runner)
    (throw (ex-info "Durable proof delivery requires a runner journal" {})))
  (let [directory (.resolveSibling (.toPath (io/file (:transport/state-path runner)))
                                   (str (.getFileName (.toPath (io/file (:transport/state-path runner))))
                                        ".proof-artifacts"))]
    (.resolve (Files/createDirectories directory (make-array java.nio.file.attribute.FileAttribute 0))
              (subs content-root 7))))

(defn- path-of [value]
  (if (instance? Path value) value (.toPath (io/file value))))

(defn persist-proof-file! [runner proof-file]
  (let [bytes (Files/readAllBytes (path-of proof-file))]
    (when (or (zero? (alength ^bytes bytes)) (> (alength ^bytes bytes) (* 24 1024 1024)))
      (throw (ex-info "SP1 proof size is outside the accounting transport limit" {})))
    (let [content-root (str "sha256:" (transport-http/hex
                                       (.digest (MessageDigest/getInstance "SHA-256") bytes)))
          target (persisted-proof-path runner content-root)]
      (when-not (Files/exists target (make-array java.nio.file.LinkOption 0))
        (let [temporary (Files/createTempFile (.getParent target) ".proof-" ".tmp"
                                              (make-array java.nio.file.attribute.FileAttribute 0))]
          (try
            (Files/write temporary bytes (make-array java.nio.file.OpenOption 0))
            (try
              (Files/move temporary target
                          (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                          StandardCopyOption/REPLACE_EXISTING]))
              (catch AtomicMoveNotSupportedException _
                (Files/move temporary target
                            (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
            (finally (Files/deleteIfExists temporary)))))
      {:proof/content-root content-root :proof/file (str target)})))

(defn- proof-delivery-attempt! [runner endpoint job assignment execution delivery-bundle logical-cutpoint]
  (if (contains? (:cancellations (runner-state runner))
                 (:research-execution-job/root job))
    {:delivery/status :cancelled-before-delivery}
    (let [bytes (Files/readAllBytes (path-of (:proof/file delivery-bundle)))
          observed-content-root
          (str "sha256:" (transport-http/hex
                           (.digest (MessageDigest/getInstance "SHA-256") bytes)))
          _ (when-not (= (:proof/content-root delivery-bundle) observed-content-root)
              (throw (ex-info "Persisted proof artifact content changed" {})))
          response (post-edn! endpoint "/v1/accounting-proof"
                              {:transport/kind :research/native-accounting-proof-delivery
                               :job job :assignment assignment
                               :native-accounting-execution execution
                               :proof/base64 (.encodeToString (Base64/getEncoder) bytes)
                               :proof/metadata (:proof/metadata delivery-bundle)
                               :research-proof-generation-receipt
                               (:research-proof-generation-receipt delivery-bundle)
                               :logical-cutpoint logical-cutpoint})
          body (:transport/response response)]
      (cond
        (nil? (:http/status response))
        {:delivery/status :unreachable
         ;; Preserve only runtime transport diagnostics here. They are useful
         ;; for distinguishing connection/setup failures from coordinator
         ;; rejection and are deliberately excluded from canonical artifacts.
         :transport/error (:transport/error response)
         :transport/exception-class (:transport/exception-class response)}
        (and (= 200 (:http/status response))
             (contains? #{:accepted :already-admitted} (:transport/status body)))
        {:delivery/status (:transport/status body)
         :accounting-proof-backed-admission/root
         (:accounting-proof-backed-admission/root body)
         :reachability
         (control/build-reachability
          {:job-root (:research-execution-job/root job) :route-kind :proof-job-delivery
           :subject-root (:proof/content-root delivery-bundle)
           :source-root (:runner/reference (:runner-participation runner))
           :destination-root (:transport/coordinator-root runner)
           :declared-reachable? true :outcome :realized
           :attempt-cutpoint logical-cutpoint})}
        :else {:delivery/status :rejected
               :transport/status (:transport/status body)
               :transport/cause (:cause body)
               :transport/diagnostic (:diagnostic body)
               :transport/http-status (:http/status response)}))))

(defn deliver-native-accounting-proof!
  "Attach a real, already-generated proof to completed native accounting work
   and deliver it. The work/job/assignment/execution are taken from the
   runner's durable journal; only the proof output is supplied. Proof bytes are
   persisted before the pending delivery state, so restart retries delivery
   without rerunning accounting or proving. The coordinator owns verification
   policy."
  [{:keys [runner endpoint work-root proof-file metadata generation-receipt
           logical-cutpoint]}]
  (let [completed (get-in (runner-state runner) [:completed-work work-root])
        work (get-in (runner-state runner) [:work-items work-root])
        job (:job completed) assignment (:assignment completed)
        execution (:native-accounting-execution completed)]
    (when-not (and completed execution (native-accounting/valid-work? work)
                   (= (:work/assignment work) assignment)
                   (= (:research-native-accounting-work/root work) work-root)
                   (map? metadata) (map? generation-receipt)
                   (Files/isRegularFile (path-of proof-file)
                                        (make-array java.nio.file.LinkOption 0))
                   (integer? logical-cutpoint) (not (neg? logical-cutpoint)))
      (throw (ex-info "Proof delivery must target completed journaled accounting work" {})))
    (let [persisted (persist-proof-file! runner proof-file)
          bundle (merge persisted {:proof/metadata metadata
                                   :research-proof-generation-receipt generation-receipt})]
      (update-runtime-state!
       runner
       (fn [state]
         (let [prior (get-in state [:completed-work work-root :proof-delivery])]
           (when (and prior (not= (select-keys prior [:proof/content-root :proof/metadata
                                                      :research-proof-generation-receipt])
                                  (select-keys bundle [:proof/content-root :proof/metadata
                                                       :research-proof-generation-receipt])))
             (throw (ex-info "Conflicting proof bundle for completed accounting work" {})))
           (assoc-in state [:completed-work work-root :proof-delivery]
                     (merge bundle (select-keys prior [:proof-deliveries :delivery]))))))
      (let [delivered (proof-delivery-attempt! runner endpoint job assignment execution
                                               (get-in (runner-state runner)
                                                       [:completed-work work-root :proof-delivery])
                                               logical-cutpoint)]
        (update-runtime-state!
         runner
         (fn [state]
           (-> state
               (assoc-in [:completed-work work-root :proof-delivery :delivery] delivered)
               (update-in [:completed-work work-root :proof-delivery :proof-deliveries]
                          (fnil conj []) delivered))))
        delivered))))

(defn retry-undelivered-accounting-proofs! [runner endpoint logical-cutpoint]
  (when-not (and (map? endpoint) (integer? logical-cutpoint)
                 (not (neg? logical-cutpoint)))
    (throw (ex-info "Invalid proof-delivery retry parameters" {})))
  (let [pending (->> (:completed-work (runner-state runner))
                     (filter (fn [[_ entry]]
                               (contains? #{:pending :unreachable}
                                          (get-in entry [:proof-delivery :delivery :delivery/status]))))
                     (sort-by key))]
    (mapv
     (fn [[work-root completed]]
       (let [delivered (proof-delivery-attempt!
                        runner endpoint (:job completed) (:assignment completed)
                        (:native-accounting-execution completed) (:proof-delivery completed)
                        logical-cutpoint)]
         (update-runtime-state!
          runner
          (fn [state]
            (-> state
                (assoc-in [:completed-work work-root :proof-delivery :delivery] delivered)
                (update-in [:completed-work work-root :proof-delivery :proof-deliveries]
                           (fnil conj []) delivered))))
         {:work-root work-root :delivery delivered}))
     pending)))

