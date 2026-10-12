(ns resolver-sim.research.researcher-challenges-process-test
  "Literal JVM qualification of the offline signed-file interface, not a
   multi-host or network-delivery qualification."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [resolver-sim.evidence.node :as node]
            [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.research.researcher-challenges-test :as fixture]
            [resolver-sim.network.shortfall-pilot-adapter :as shortfall-adapter]
            [resolver-sim.research.shortfall-pilot :as shortfall]
            [resolver-sim.research.shortfall-pilot-test :as shortfall-fixture]
            [resolver-sim.research.shortfall-publication :as publication])
  (:import [java.io File]
           [java.nio.file Files]
           [java.security KeyPairGenerator]
           [java.util Base64]
           [java.util.concurrent TimeUnit]))

(defn- key-pair! [directory]
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        path (.toFile (Files/createTempFile (.toPath directory) "private-" ".pem"
                                            (make-array java.nio.file.attribute.FileAttribute 0)))
        pem (fn [kind bytes]
              (str "-----BEGIN " kind "-----\n"
                   (.encodeToString (Base64/getEncoder) bytes)
                   "\n-----END " kind "-----\n"))]
    (spit path (pem "PRIVATE KEY" (.getEncoded (.getPrivate pair))))
    {:private-path (.getAbsolutePath path)
     :public (pem "PUBLIC KEY" (.getEncoded (.getPublic pair)))}))

(defn- child!
  "Fresh process, absolute classpath and isolated working directory. Stream to
   files so failed processes cannot deadlock on a full stdout/stderr pipe."
  [directory & args]
  (let [command-name (first args)
        timeout-seconds (if (contains? #{"state" "control-state" "summary"
                                         "control-summary" "review-dossier"
                                         "control-review-dossier" "control-forensic"}
                                       command-name)
                          180
                          60)
        classpath (str/join File/pathSeparator
                            (map #(.getAbsolutePath (io/file %))
                                 (str/split (System/getProperty "java.class.path")
                                            (re-pattern File/pathSeparator))))
        output (Files/createTempFile (.toPath directory) "stdout-" ".edn"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        errors (Files/createTempFile (.toPath directory) "stderr-" ".txt"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        command (into [(str (System/getProperty "java.home") "/bin/java")
                       "-cp" classpath "clojure.main" "-m"
                       "resolver-sim.research.researcher-challenges-cli"] args)
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.directory directory)
                          (.redirectOutput (.toFile output))
                          (.redirectError (.toFile errors))))]
    (try
      (when-not (.waitFor process timeout-seconds TimeUnit/SECONDS)
        (throw (ex-info "Researcher CLI child timed out"
                        {:command command-name :timeout-seconds timeout-seconds})))
      {:exit (.exitValue process) :stdout (slurp (.toFile output))
       :stderr (slurp (.toFile errors))}
      (finally
        (when (.isAlive process)
          (.destroyForcibly process)
          (.waitFor process 10 TimeUnit/SECONDS))))))

(defn- start-barrier-child! [directory barrier-directory stage & args]
  (let [classpath (str/join File/pathSeparator
                            (map #(.getAbsolutePath (io/file %))
                                 (str/split (System/getProperty "java.class.path")
                                            (re-pattern File/pathSeparator))))
        output (Files/createTempFile (.toPath directory) "barrier-stdout-" ".edn"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        errors (Files/createTempFile (.toPath directory) "barrier-stderr-" ".txt"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        command (into [(str (System/getProperty "java.home") "/bin/java")
                       (str "-Dresolver-sim.test.persist-barrier-dir=" (.getAbsolutePath barrier-directory))
                       (str "-Dresolver-sim.test.persist-barrier-stage=" stage)
                       "-cp" classpath "clojure.main" "-m"
                       "resolver-sim.research.researcher-challenges-cli"] args)
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.directory directory)
                          (.redirectOutput (.toFile output))
                          (.redirectError (.toFile errors))))]
    {:process process :output (.toFile output) :errors (.toFile errors)}))

(defn- start-shortfall-barrier-child! [directory barrier-directory stage & args]
  (let [classpath (str/join File/pathSeparator
                            (map #(.getAbsolutePath (io/file %))
                                 (str/split (System/getProperty "java.class.path")
                                            (re-pattern File/pathSeparator))))
        output (Files/createTempFile (.toPath directory) "shortfall-barrier-stdout-" ".edn"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        errors (Files/createTempFile (.toPath directory) "shortfall-barrier-stderr-" ".txt"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        command (into [(str (System/getProperty "java.home") "/bin/java")
                       (str "-Dresolver-sim.test.persist-barrier-dir=" (.getAbsolutePath barrier-directory))
                       (str "-Dresolver-sim.test.persist-barrier-stage=" stage)
                       "-cp" classpath "clojure.main" "-m"
                       "resolver-sim.research.shortfall-pilot-cli"] args)
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.directory directory)
                          (.redirectOutput (.toFile output))
                          (.redirectError (.toFile errors))))]
    {:process process :output (.toFile output) :errors (.toFile errors)}))

(defn- shortfall-child! [directory & args]
  (let [classpath (str/join File/pathSeparator
                            (map #(.getAbsolutePath (io/file %))
                                 (str/split (System/getProperty "java.class.path")
                                            (re-pattern File/pathSeparator))))
        output (Files/createTempFile (.toPath directory) "shortfall-stdout-" ".edn"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        errors (Files/createTempFile (.toPath directory) "shortfall-stderr-" ".txt"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        command (into [(str (System/getProperty "java.home") "/bin/java") "-cp" classpath
                       "clojure.main" "-m" "resolver-sim.research.shortfall-pilot-cli"] args)
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.directory directory)
                          (.redirectOutput (.toFile output))
                          (.redirectError (.toFile errors))))]
    (try
      (when-not (.waitFor process 180 TimeUnit/SECONDS)
        (throw (ex-info "Shortfall pilot CLI child timed out" {:args args})))
      {:exit (.exitValue process) :stdout (slurp (.toFile output))
       :stderr (slurp (.toFile errors))}
      (finally
        (when (.isAlive process)
          (.destroyForcibly process)
          (.waitFor process 10 TimeUnit/SECONDS))))))

(defn- start-publication-barrier-child! [directory barrier-directory stage & args]
  (let [classpath (str/join File/pathSeparator
                            (map #(.getAbsolutePath (io/file %))
                                 (str/split (System/getProperty "java.class.path")
                                            (re-pattern File/pathSeparator))))
        output (Files/createTempFile (.toPath directory) "publication-barrier-stdout-" ".edn"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        errors (Files/createTempFile (.toPath directory) "publication-barrier-stderr-" ".txt"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        command (into [(str (System/getProperty "java.home") "/bin/java")
                       (str "-Dresolver-sim.test.persist-barrier-dir=" (.getAbsolutePath barrier-directory))
                       (str "-Dresolver-sim.test.persist-barrier-stage=" stage)
                       "-cp" classpath "clojure.main" "-m"
                       "resolver-sim.research.shortfall-publication"] args)
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.directory directory)
                          (.redirectOutput (.toFile output))
                          (.redirectError (.toFile errors))))]
    {:process process :output (.toFile output) :errors (.toFile errors)}))

(defn- publication-child! [directory & args]
  (let [classpath (str/join File/pathSeparator
                            (map #(.getAbsolutePath (io/file %))
                                 (str/split (System/getProperty "java.class.path")
                                            (re-pattern File/pathSeparator))))
        output (Files/createTempFile (.toPath directory) "publication-stdout-" ".edn"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        errors (Files/createTempFile (.toPath directory) "publication-stderr-" ".txt"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        command (into [(str (System/getProperty "java.home") "/bin/java") "-cp" classpath
                       "clojure.main" "-m" "resolver-sim.research.shortfall-publication"] args)
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.directory directory)
                          (.redirectOutput (.toFile output))
                          (.redirectError (.toFile errors))))]
    (try
      (when-not (.waitFor process 180 TimeUnit/SECONDS)
        (throw (ex-info "Shortfall publication CLI child timed out" {:args args})))
      {:exit (.exitValue process) :stdout (slurp (.toFile output))
       :stderr (slurp (.toFile errors))}
      (finally
        (when (.isAlive process)
          (.destroyForcibly process)
          (.waitFor process 10 TimeUnit/SECONDS))))))

(defn- await-barrier! [{:keys [process errors]} barrier-directory]
  (let [marker (io/file barrier-directory "reached")
        deadline (+ (System/nanoTime) (* 60 1000000000))]
    (loop []
      (cond
        (.exists marker) (slurp marker)
        (not (.isAlive process))
        (throw (ex-info "Admission process exited before crash barrier"
                        {:exit (.exitValue process) :stderr (slurp errors)}))
        (>= (System/nanoTime) deadline)
        (throw (ex-info "Admission process did not reach crash barrier"
                        {:stderr (slurp errors)}))
        :else (do (Thread/sleep 20) (recur))))))

(defn- successful! [result]
  (when-not (zero? (:exit result))
    (throw (ex-info "Researcher CLI child failed" result)))
  (edn/read-string (:stdout result)))

(defn- write-edn! [directory filename body]
  (let [path (.getAbsolutePath (io/file directory filename))]
    (spit path (pr-str body))
    path))

(deftest separate-participant-operator-and-observer-processes
  (let [directory (.toFile (Files/createTempDirectory "researcher-process-"
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        role-dir (fn [role] (doto (io/file directory role) (.mkdir)))
        operator (role-dir "operator") alice-dir (role-dir "alice") bob-dir (role-dir "bob")
        observer (role-dir "observer")
        demo (fixture/qualification-demo)
        config (:configuration demo)
        config-path (write-edn! operator "configuration.edn" config)
        history-path (.getAbsolutePath (io/file operator "history.edn"))
        checkpoint (successful! (child! operator "checkpoint" config-path history-path))
        checkpoint-path (write-edn! operator "checkpoint.edn" checkpoint)
        alice (key-pair! alice-dir) bob (key-pair! bob-dir)
        request-path (fn [dir key]
                       (write-edn! dir "request.edn"
                                   {:actor (challenges/researcher-reference (:network/root config) (:public key))
                                    :kind :enroll :challenge-root nil :nonce "enroll-1"
                                    :payload {:public-key (:public key) :disclosure :pseudonymous}}))]
    (try
      (let [alice-request (request-path alice-dir alice)
            bob-request (request-path bob-dir bob)
            alice-unsigned (successful! (child! alice-dir "request" config-path history-path alice-request checkpoint-path))
            bob-unsigned (successful! (child! bob-dir "request" config-path history-path bob-request checkpoint-path))
            alice-input (write-edn! alice-dir "unsigned.edn" alice-unsigned)
            bob-input (write-edn! bob-dir "unsigned.edn" bob-unsigned)
            forged (successful! (child! bob-dir "sign" alice-input (:private-path bob)))
            forged-path (write-edn! bob-dir "forged.edn" forged)
            alice-signed (successful! (child! alice-dir "sign" alice-input (:private-path alice)))
            alice-action (write-edn! alice-dir "signed.edn" alice-signed)
            bob-stale (successful! (child! bob-dir "sign" bob-input (:private-path bob)))
            bob-stale-path (write-edn! bob-dir "stale.edn" bob-stale)]
        (is (not (zero? (:exit (child! operator "apply" config-path history-path forged-path checkpoint-path)))))
        (is (not (.exists (io/file history-path))))
        (successful! (child! operator "apply" config-path history-path alice-action checkpoint-path))
        (let [before (slurp history-path)]
          (is (= [alice-signed] (edn/read-string before)))
          (is (not (zero? (:exit (child! operator "apply" config-path history-path alice-action)))))
          (is (not (zero? (:exit (child! operator "apply" config-path history-path bob-stale-path)))))
          (is (not (zero? (:exit (child! bob-dir "request" config-path history-path bob-request checkpoint-path)))))
          (is (= before (slurp history-path))))
        (let [current (successful! (child! operator "checkpoint" config-path history-path))
              current-path (write-edn! operator "current.edn" current)
              rebuilt (successful! (child! bob-dir "request" config-path history-path bob-request current-path))
              rebuilt-path (write-edn! bob-dir "rebuilt.edn" rebuilt)
              signed (successful! (child! bob-dir "sign" rebuilt-path (:private-path bob)))
              signed-path (write-edn! bob-dir "current-signed.edn" signed)]
          (is (not= (:action/root bob-unsigned) (:action/root rebuilt)))
          (successful! (child! operator "apply" config-path history-path signed-path current-path))
          (let [state (successful! (child! observer "state" config-path history-path))]
            (is (= 2 (count (:researchers state))))
            (is (empty? (:reviews state)))
            (is (= (challenges/replay config (edn/read-string (slurp history-path))) state)))))
      ;; A separate observer has only public configuration/history/checkpoint;
      ;; replay reconstructs accepted work and obligations without signing keys.
      (let [public-config (write-edn! observer "configuration.edn" config)
            public-history (write-edn! observer "accepted-history.edn" (:signed-actions demo))
            public-checkpoint (write-edn! observer "accepted-checkpoint.edn"
                                          (challenges/history-checkpoint config (:signed-actions demo)))
            verification (successful! (child! observer "verify-checkpoint" public-config public-history public-checkpoint))
            researcher (:researcher/root (first (:researcher-summaries demo)))
            summary (successful! (child! observer "summary" public-config public-history researcher))]
        (is (= :verified (:checkpoint/status verification)))
        (is (= (first (:researcher-summaries demo)) summary))
        (is (= 3 (:accepted-contribution-count summary)))
        (is (= 3 (count (:accepted-work summary))))
        (is (every? :reward-obligation (:accepted-work summary)))
        (is (not-any? #(str/includes? (slurp %) "BEGIN PRIVATE KEY")
                      (filter #(.isFile %) (file-seq observer)))))
      (finally
        (doseq [file (reverse (file-seq directory))] (.delete file))))))

(deftest controlled-admission-survives-fresh-processes
  ;; Actual signing and admission run in different JVMs. Restart means a fresh
  ;; reader of the persisted history, not crash/power-loss qualification.
  (let [directory (.toFile (Files/createTempDirectory "researcher-control-process-"
                                                      (make-array java.nio.file.attribute.FileAttribute 0)))
        operator (doto (io/file directory "operator") (.mkdir))
        signer (doto (io/file directory "signer") (.mkdir))
        observer (doto (io/file directory "observer") (.mkdir))
        f (#'fixture/fixture)
        config (:config f)
        history (#'fixture/enroll f [] :alice)
        history (reduce (fn [h [definition label]]
                          (#'fixture/complete f h :alice definition label))
                        history (map vector (take 3 (:definitions f)) [:first :second :third]))
        prior (challenges/replay config history)
        submission (:consequence (nth (:events prior) (- (count history) 2)))
        review (get-in prior [:reviews (:submission/root submission)])
        config-path (write-edn! operator "configuration.edn" config)
        history-path (write-edn! operator "history.edn" history)
        key-path (.getAbsolutePath (.toFile (Files/createTempFile (.toPath signer) "reviewer-" ".pem"
                                                                  (make-array java.nio.file.attribute.FileAttribute 0))))
        _ (spit key-path (str "-----BEGIN PRIVATE KEY-----\n"
                              (.encodeToString (Base64/getEncoder)
                                               (.getEncoded (get-in f [:reviewer :private])))
                              "\n-----END PRIVATE KEY-----\n"))]
    (try
      (let [policy (successful! (child! operator "control-policy" config-path history-path))
            policy-path (write-edn! operator "policy.edn" policy)
            checkpoint (successful! (child! observer "control-checkpoint" config-path policy-path history-path))
            checkpoint-path (write-edn! operator "checkpoint.edn" checkpoint)
            request (write-edn! operator "withdraw-request.edn"
                                {:actor (:authority-ref f) :kind :withdraw-review :challenge-root nil
                                 :nonce "withdraw-process"
                                 :payload (#'fixture/withdraw-payload submission review)})
            unsigned (successful! (child! operator "control-request" config-path policy-path history-path request checkpoint-path))
            unsigned-path (write-edn! signer "unsigned.edn" unsigned)
            signed (successful! (child! signer "sign" unsigned-path key-path))
            signed-path (write-edn! operator "signed.edn" signed)]
        (is (= policy (challenges/build-control-policy config history)))
        (successful! (child! operator "control-apply" config-path policy-path history-path signed-path checkpoint-path))
        (let [persisted (slurp history-path)
              state (successful! (child! observer "control-state" config-path policy-path history-path))
              summary (successful! (child! observer "control-summary" config-path policy-path history-path (:alice-ref f)))
              dossier (successful! (child! observer "control-review-dossier" config-path policy-path history-path (:submission/root submission)))]
          (is (= state (challenges/replay-controlled config policy (edn/read-string persisted))))
          (is (= 2 (:accepted-contribution-count summary)))
          (is (= 3 (count (:historical-accepted-work summary))))
          (is (= (:reviews prior) (:reviews state)))
          (is (= :superseded (:review/status dossier)))
          (is (every? :reward-obligation (:historical-accepted-work summary)))
          (is (not (zero? (:exit (child! operator "control-apply" config-path policy-path history-path signed-path)))))
          (is (not (zero? (:exit (child! observer "control-verify-checkpoint" config-path policy-path history-path checkpoint-path)))))
          (is (= persisted (slurp history-path))))
        (let [state (challenges/replay-controlled config policy (edn/read-string (slurp history-path)))
              cp (successful! (child! observer "control-checkpoint" config-path policy-path history-path))
              cp-path (write-edn! operator "revocation-checkpoint.edn" cp)
              request-path (write-edn! operator "revoke-request.edn"
                                       {:actor (:authority-ref f) :kind :revoke-participant :challenge-root nil
                                        :nonce "revoke-process" :payload (#'fixture/revoke-payload f state :alice)})
              unsigned (successful! (child! operator "control-request" config-path policy-path history-path request-path cp-path))
              action-path (write-edn! signer "revoke-unsigned.edn" unsigned)
              signed-path (write-edn! operator "revoke-signed.edn"
                                      (successful! (child! signer "sign" action-path key-path)))]
          (successful! (child! operator "control-apply" config-path policy-path history-path signed-path cp-path))
          (let [persisted (slurp history-path)
                state (successful! (child! observer "control-state" config-path policy-path history-path))
                claim-path (write-edn! operator "revoked-claim.edn"
                                       {:actor (:alice-ref f) :kind :claim
                                        :challenge-root (:artifact/root (nth (:definitions f) 3))
                                        :nonce "after-revocation" :payload {}})
                nodes (successful! (child! observer "control-forensic" config-path policy-path history-path))]
            (is (contains? (:revocations state) (:alice-ref f)))
            (is (= state (challenges/replay-controlled config policy (edn/read-string persisted))))
            (is (not (zero? (:exit (child! operator "control-request" config-path policy-path history-path claim-path)))))
            (is (= persisted (slurp history-path)))
            (is (:valid? (node/validate-node-dag nodes)))
            (is (not-any? #(str/includes? (slurp %) "BEGIN PRIVATE KEY")
                          (filter #(.isFile %) (file-seq observer)))))))
      (finally
        (doseq [file (reverse (file-seq directory))] (.delete file))))))

(deftest controlled-admission-literal-process-kill-at-durable-write-boundaries
  (doseq [[stage expected-committed?] [["before-force" false]
                                       ["after-force" false]
                                       ["after-replace" true]]]
    (let [directory (.toFile (Files/createTempDirectory "researcher-control-kill-"
                                                        (make-array java.nio.file.attribute.FileAttribute 0)))
          barrier-directory (doto (io/file directory "barrier") (.mkdir))
          f (#'fixture/fixture)
          config (:config f)
          history (#'fixture/enroll f [] :alice)
          policy (challenges/build-control-policy config history)
          state (challenges/replay-controlled config policy history)
          action (challenges/control-action config policy (:history/root state)
                                            :revoke-participant "crash-revoke"
                                            (#'fixture/revoke-payload f state :alice))
          signed (#'fixture/signed action (:reviewer f))
          config-path (write-edn! directory "configuration.edn" config)
          policy-path (write-edn! directory "policy.edn" policy)
          history-path (write-edn! directory "history.edn" history)
          action-path (write-edn! directory "signed-control.edn" signed)
          checkpoint-path (write-edn! directory "checkpoint.edn"
                                      (challenges/controlled-history-checkpoint config policy history))
          original (slurp history-path)
          child (start-barrier-child! directory barrier-directory stage
                                      "control-apply" config-path policy-path history-path action-path checkpoint-path)]
      (try
        (is (= stage (await-barrier! child barrier-directory)))
        (.destroyForcibly ^Process (:process child))
        (is (.waitFor ^Process (:process child) 10 TimeUnit/SECONDS))
        ;; SIGKILL provides no CLI acknowledgement, independent of cutpoint.
        (is (= "" (slurp (:output child))))
        (let [after-crash (slurp history-path)
              committed-before-crash? (not= original after-crash)
              restarted (successful! (child! directory "control-state"
                                             config-path policy-path history-path))]
          (is (= expected-committed? committed-before-crash?))
          (is (= (if expected-committed?
                   (:state (challenges/append-controlled-action config policy history signed))
                   state)
                 restarted))
          (if expected-committed?
            ;; Acknowledgement was lost, but replay sees one durable transition;
            ;; stale redelivery cannot create another transition or obligation.
            (do (is (not (zero? (:exit (child! directory "control-apply"
                                               config-path policy-path history-path action-path)))))
                (is (= after-crash (slurp history-path))))
            ;; Neither pre-force nor post-force/before-replace state was admitted.
            ;; The same signed intent is safe to retry and applies once.
            (do (successful! (child! directory "control-apply"
                                     config-path policy-path history-path action-path checkpoint-path))
                (let [retried (successful! (child! directory "control-state"
                                                   config-path policy-path history-path))]
                  (is (= 1 (count (:revocations retried))))
                  (is (= (count history) (dec (count (:events retried)))))))))
        (finally
          (when (.isAlive ^Process (:process child))
            (.destroyForcibly ^Process (:process child))
            (.waitFor ^Process (:process child) 10 TimeUnit/SECONDS))
          (doseq [file (reverse (file-seq directory))] (.delete file)))))))

(deftest arithmetic-gated-controlled-shortfall-admission-process-kill-boundaries
  (doseq [[stage committed?] [["before-force" false]
                              ["after-force" false]
                              ["after-replace" true]]]
    (let [directory (.toFile (Files/createTempDirectory "shortfall-control-kill-"
                                                        (make-array java.nio.file.attribute.FileAttribute 0)))
          barrier (doto (io/file directory "barrier") (.mkdir))
          f (#'shortfall-fixture/fixture)
          config (:config f)
          initial-history (#'shortfall-fixture/before-submission f)
          action (last (#'shortfall-fixture/submission f initial-history (:context f)))
          policy (challenges/build-control-policy config initial-history)
          profile (shortfall-adapter/build-controlled-profile config (:profile f) policy initial-history)
          expected-history (:history (challenges/append-controlled-action
                                      config policy initial-history action))
          config-path (write-edn! directory "configuration.edn" config)
          profile-path (write-edn! directory "controlled-pilot.edn" profile)
          policy-path (write-edn! directory "control-policy.edn" policy)
          history-path (write-edn! directory "history.edn" initial-history)
          action-path (write-edn! directory "action.edn" action)
          evidence-path (write-edn! directory "evidence.edn" (:evidence f))
          child-holder (atom nil)]
      (try
        (let [{:keys [process] :as child}
              (start-shortfall-barrier-child!
               directory barrier stage "controlled-apply" config-path profile-path policy-path
               history-path action-path evidence-path)]
          (reset! child-holder child)
          ;; The writer barrier is reached only after controlled replay and the
          ;; fresh allocator gate have accepted this submission.
          (is (= stage (await-barrier! child barrier)))
          (.destroyForcibly ^Process process)
          (is (.waitFor ^Process process 10 TimeUnit/SECONDS))
          (is (= "" (slurp (:output child))))
          (let [recovered (edn/read-string (slurp history-path))
                expected (if committed? expected-history initial-history)]
            (is (= expected recovered))
            (let [replay (shortfall-child!
                          directory "controlled-replay" config-path profile-path policy-path
                          history-path evidence-path)]
              (is (zero? (:exit replay)) (:stderr replay))
              (is (= (if committed? 1 0)
                     (count (get-in (edn/read-string (:stdout replay))
                                    [:state :submissions])))))
            (let [retry (shortfall-child!
                         directory "controlled-apply" config-path profile-path policy-path
                         history-path action-path evidence-path)]
              (if committed?
                (do
                  (is (not (zero? (:exit retry))))
                  (is (= expected-history (edn/read-string (slurp history-path)))))
                (do
                  (is (zero? (:exit retry)) (:stderr retry))
                  (is (= expected-history (edn/read-string (slurp history-path)))))))))
        (finally
          (when-let [process (:process @child-holder)]
            (when (.isAlive ^Process process)
              (.destroyForcibly ^Process process)
              (.waitFor ^Process process 10 TimeUnit/SECONDS)))
          (doseq [file (reverse (file-seq directory))] (.delete file)))))))

(deftest controlled-publication-trust-state-process-kill-boundaries
  (doseq [[stage committed?] [["before-force" false]
                              ["after-force" false]
                              ["after-replace" true]]]
    (let [directory (.toFile (Files/createTempDirectory "shortfall-publication-kill-"
                                                        (make-array java.nio.file.attribute.FileAttribute 0)))
          barrier (doto (io/file directory "barrier") (.mkdir))
          f (#'shortfall-fixture/fixture)
          key (key-pair! directory)
          config (:config f)
          policy (challenges/build-control-policy config [])
          operator-key-path (.getAbsolutePath (io/file directory "operator-public-key.pem"))
          candidate (do
                      (write-edn! directory "configuration.edn" config)
                      (write-edn! directory "pilot.edn" (:profile f))
                      (write-edn! directory "control-policy.edn" policy)
                      (write-edn! directory "history.edn" [])
                      (write-edn! directory "evidence.edn" (:evidence f))
                      (spit operator-key-path (:public key))
                      (publication/sign-controlled-publication
                       (publication/build-controlled-publication
                        (:public key) shortfall/method-root (.getAbsolutePath directory) nil)
                       (:private-path key)))
          candidate-path (write-edn! directory "signed-publication.edn" candidate)
          state-path (.getAbsolutePath (io/file directory "reader-state.edn"))
          expected-origin (:publication/root candidate)
          child-holder (atom nil)]
      (try
        (let [{:keys [process] :as child}
              (start-publication-barrier-child!
               directory barrier stage "controlled-advance" (.getAbsolutePath directory)
               operator-key-path
               state-path candidate-path expected-origin)]
          (reset! child-holder child)
          (is (= stage (await-barrier! child barrier)))
          (.destroyForcibly ^Process process)
          (is (.waitFor ^Process process 10 TimeUnit/SECONDS))
          (is (= "" (slurp (:output child))))
          (if committed?
            (is (= candidate (edn/read-string (slurp state-path))))
            (is (not (.exists (io/file state-path)))))
          (let [retry (publication-child!
                       directory "controlled-advance" (.getAbsolutePath directory)
                       operator-key-path
                       state-path candidate-path expected-origin)]
            (is (zero? (:exit retry)) (:stderr retry))
            (is (= candidate (edn/read-string (slurp state-path))))
            (is (= (if committed? false true)
                   (:trust/changed? (edn/read-string (:stdout retry)))))))
        (finally
          (when-let [process (:process @child-holder)]
            (when (.isAlive ^Process process)
              (.destroyForcibly ^Process process)
              (.waitFor ^Process process 10 TimeUnit/SECONDS)))
          (doseq [file (reverse (file-seq directory))] (.delete file)))))))
