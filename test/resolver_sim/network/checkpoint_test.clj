(ns resolver-sim.network.checkpoint-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.shell :as shell]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.network.checkpoint :as checkpoint])
  (:import [java.security KeyPair KeyPairGenerator]
           [java.nio.file Files]
           [java.util Base64]))

(defn- hash-ref [domain value]
  (str "sha256:" (hc/domain-hash domain value)))

(defn- keypair []
  (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519")))

(defn- public-key-content [^KeyPair pair]
  (str "-----BEGIN PUBLIC KEY-----\n"
       (.encodeToString (Base64/getEncoder) (.getEncoded (.getPublic pair)))
       "\n-----END PUBLIC KEY-----\n"))

(defn- member [i pair]
  {:principal/id (str "researcher:" i)
   :key/id (str "key:" i)
   :key/public-key (public-key-content pair)
   :member/roles [:witness]
   :member/active? true
   :key/valid-from-epoch 0
   :key/revoked-at-epoch nil})

(defn- event [id dependencies]
  (let [body {:event/schema "checkpoint-test-event.v1"
              :event/id id
              :event/depends-on dependencies}]
    (assoc body :event/root (hash-ref "checkpoint-test-event.v1" body))))

(defn- fixture
  ([] (fixture [] {}))
  ([events] (fixture events {}))
  ([events opts]
   (let [pairs (mapv (fn [_] (keypair)) (range 3))
         members (mapv member (range 3) pairs)
         genesis-root (hash-ref "checkpoint-test-genesis.v1" {:id "research-network"})
         policy-root (hash-ref "checkpoint-test-policy.v1" {:threshold 2})
         membership-root (hash-ref "checkpoint-test-membership.v1" {:members (mapv :principal/id members)})
         policy (merge {:policy/root policy-root
                        :policy/threshold 2
                        :policy/fault-bound 1
                        :policy/role-thresholds {}}
                       (:policy opts))
         membership {:membership/root membership-root :membership/members members}
         initial-state {:state/accepted-event-roots #{}}
         genesis {:network/genesis-root genesis-root
                  :checkpoint/root (hash-ref "checkpoint-test-anchor.v1" {:genesis genesis-root})
                  :checkpoint/sequence 0
                  :checkpoint/resulting-epoch 0
                  :checkpoint/resulting-policy-root policy-root
                  :checkpoint/resulting-membership-root membership-root
                  :state initial-state
                  :state/accepted-event-roots #{}}
         event-roots (vec (sort (map :event/root events)))
         new-state {:state/accepted-event-roots (set event-roots)}
         state-root (hash-ref "checkpoint-test-state.v1" {:accepted-event-roots event-roots})
         replay-result {:valid? true
                        :dag-state-root state-root
                        :frontier-roots event-roots
                        :resulting-policy-root policy-root
                        :resulting-membership-root membership-root
                        :resulting-epoch 0
                        :state new-state}
         cp (checkpoint/build-checkpoint
             {:network/genesis-root genesis-root
              :checkpoint/epoch 0 :checkpoint/resulting-epoch 0
              :checkpoint/sequence 1
              :checkpoint/previous-root (:checkpoint/root genesis)
              :checkpoint/accepted-event-manifest-root (checkpoint/event-manifest-root event-roots)
              :checkpoint/accepted-event-count (count events)
              :checkpoint/dag-state-root state-root
              :checkpoint/frontier-roots event-roots
              :checkpoint/governing-policy-root policy-root
              :checkpoint/governing-membership-root membership-root
              :checkpoint/resulting-policy-root policy-root
              :checkpoint/resulting-membership-root membership-root
              :checkpoint/proposer-id "publisher:test"
              :checkpoint/claimed-time nil})
         context {:network/genesis-root genesis-root
                  :predecessor genesis
                  :policy policy
                  :membership membership
                  :events events
                  :verify-event (fn [e]
                                  {:valid? (= (:event/root e)
                                              (hash-ref (:event/schema e) (dissoc e :event/root)))})
                  :verify-completeness (fn [_pred roots _policy]
                                         {:valid? (and (= (set roots) (set event-roots))
                                                       (not= false (:complete? opts)))})
                  :replay (fn [_prior _ordered _policy _membership]
                            (if (= false (:replay-valid? opts))
                              {:valid? false :reason :test/replay-rejected}
                              replay-result))}]
     {:pairs pairs :members members :genesis genesis :policy policy
      :membership membership :events events :state-root state-root
      :checkpoint cp :context context})))

(defn- endorsement [fixture i store-path]
  (checkpoint/endorse-checkpoint!
   (:checkpoint fixture) (:context fixture)
   {:principal/id (:principal/id (nth (:members fixture) i))
    :key/id (:key/id (nth (:members fixture) i))
    :private-key (.getPrivate ^KeyPair (nth (:pairs fixture) i))
    :public-key (.getPublic ^KeyPair (nth (:pairs fixture) i))
    :store-path store-path}))

(defn- signed-certificate [fixture indexes dir]
  (let [endorsements (mapv (fn [i]
                             (endorsement fixture i (.resolve dir (str "witness-" i ".edn"))))
                           indexes)]
    (checkpoint/build-certificate (:checkpoint fixture) endorsements)))

(deftest canonical-checkpoint-reconstructs-from-explicit-trusted-inputs
  (let [f (fixture)
        result (checkpoint/verify-checkpoint-transition (:checkpoint f) (:context f))]
    (is (:valid? result))
    (is (= :locally-valid (:status result)))
    (is (= (:state-root f) (get-in result [:state :dag-state-root])))))

(deftest checkpoint-rejects-wrong-network-parent-sequence-and-roots
  (let [f (fixture)
        cp (:checkpoint f)
        context (:context f)]
    (is (= :checkpoint/wrong-network-genesis
           (first (:errors (checkpoint/verify-checkpoint-transition
                            cp (assoc context :network/genesis-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"))))))
    (is (= :checkpoint/nonconsecutive-sequence
           (first (:errors (checkpoint/verify-checkpoint-transition
                            (checkpoint/build-checkpoint (assoc (dissoc cp :checkpoint/root) :checkpoint/sequence 3))
                            context)))))
    (is (false? (:valid? (checkpoint/verify-checkpoint-transition
                          (update cp :checkpoint/root str "a") context))))))

(deftest checkpoint-rejects-epoch-skips
  (let [f (fixture)
        cp (checkpoint/build-checkpoint
            (assoc (dissoc (:checkpoint f) :checkpoint/root)
                   :checkpoint/resulting-epoch 2))]
    (is (= :invalid (:status (checkpoint/verify-checkpoint-transition cp (:context f)))))))

(deftest event-omission-missing-dependency-and-replay-mismatch-fail-closed
  (let [a (event "a" [])
        b (event "b" [(:event/root a)])
        f (fixture [a b])
        incomplete (assoc (:context f) :events [a])
        dangling (event "dangling" [(hash-ref "test" {:missing true})])
        dangling-f (fixture [dangling])
        replay-f (fixture [] {:replay-valid? false})]
    (let [result (checkpoint/verify-checkpoint-transition (:checkpoint f) incomplete)]
      (is (= :invalid (:status result)))
      (is (some #{:checkpoint/manifest-binding-mismatch :checkpoint/manifest-incomplete}
                (:errors result))))
    (is (= :manifest/dangling-dependency
           (first (:errors (checkpoint/verify-checkpoint-transition
                            (:checkpoint dangling-f) (:context dangling-f))))))
    (let [result (checkpoint/verify-checkpoint-transition
                  (:checkpoint replay-f) (:context replay-f))]
      (is (= :stale-or-unknown (:status result)))
      (is (some #{:test/replay-rejected} (:errors result))))))

(deftest checkpoint-certificate-uses-pinned-eligibility-and-derived-threshold
  (let [f (fixture)
        dir (Files/createTempDirectory "checkpoint-cert-test" (make-array java.nio.file.attribute.FileAttribute 0))
        cert (signed-certificate f [0 1] dir)
        verified (checkpoint/verify-checkpoint (:checkpoint f) cert (:context f))]
    (is (:valid? verified))
    (is (= :certified (:status verified)))
    (is (= 2 (:eligible-signer-count verified)))
    (is (false? (get-in verified [:quorum-safety :intersection-bound-satisfied?])))))

(deftest invalid-threshold-label-does-not-grant-intersection-safety
  (let [f (fixture [] {:policy {:policy/require-intersection-safety? true}})
        dir (Files/createTempDirectory "checkpoint-unsafe-threshold" (make-array java.nio.file.attribute.FileAttribute 0))
        cert (signed-certificate f [0 1] dir)
        verified (checkpoint/verify-checkpoint (:checkpoint f) cert (:context f))]
    (is (false? (:valid? verified)))
    (is (some #{:certificate/quorum-safety-bound-not-met} (:errors verified)))))

(deftest revoked-duplicate-and-replayed-endorsements-fail
  (let [f (fixture)
        dir (Files/createTempDirectory "checkpoint-adversarial" (make-array java.nio.file.attribute.FileAttribute 0))
        endorsements [(endorsement f 0 (.resolve dir "w0.edn"))
                      (endorsement f 1 (.resolve dir "w1.edn"))]
        valid-cert (checkpoint/build-certificate (:checkpoint f) endorsements)
        duplicate-cert (checkpoint/build-certificate (:checkpoint f) [(first endorsements) (first endorsements)])
        revoked (assoc-in f [:membership :membership/members 1 :key/revoked-at-epoch] 0)
        revoked-context (assoc (:context f) :membership (:membership revoked))
        wrong-network-cert (let [bad (assoc valid-cert :network/genesis-root
                                            "sha256:0000000000000000000000000000000000000000000000000000000000000000")]
                             (assoc bad :certificate/root (checkpoint/certificate-root bad)))]
    (is (some #{:certificate/duplicate-or-missing-principal}
              (:errors (checkpoint/verify-certificate (:checkpoint f) duplicate-cert
                                                      (:policy f) (:membership f)))))
    (is (some #{:certificate/ineligible-or-revoked-key :certificate/threshold-not-met}
              (:errors (checkpoint/verify-certificate (:checkpoint f) valid-cert
                                                      (:policy revoked-context) (:membership revoked)))))
    (is (some #{:certificate/wrong-network}
              (:errors (checkpoint/verify-certificate (:checkpoint f) wrong-network-cert
                                                      (:policy f) (:membership f)))))))

(deftest malformed-endorsement-envelope-is-a-typed-invalid-result
  (let [f (fixture)
        dir (Files/createTempDirectory "checkpoint-malformed-signature-test"
                                       (make-array java.nio.file.attribute.FileAttribute 0))
        valid-certificate (signed-certificate f [0 1] dir)
        body (assoc-in (dissoc valid-certificate :certificate/root)
                       [:certificate/endorsements 0 :signature] nil)
        malformed-certificate
        (assoc body :certificate/root (checkpoint/certificate-root body))
        result (checkpoint/verify-certificate (:checkpoint f) malformed-certificate
                                              (:policy f) (:membership f))]
    (is (= :invalid (:status result)))
    (is (false? (:valid? result)))
    (is (some #{:certificate/invalid-envelope-shape} (:errors result)))))

(deftest durable-witness-lock-survives-restart-and-blocks-equivocation
  (let [f (fixture)
        dir (Files/createTempDirectory "checkpoint-lock-test" (make-array java.nio.file.attribute.FileAttribute 0))
        store (.resolve dir "witness.edn")
        first-signature (endorsement f 0 store)
        restarted-signature (endorsement f 0 store)
        competing (checkpoint/build-checkpoint
                   (assoc (dissoc (:checkpoint f) :checkpoint/root)
                          :checkpoint/proposer-id "other-publisher"))
        error (try
                (checkpoint/endorse-checkpoint!
                 competing (:context f)
                 {:principal/id (:principal/id (first (:members f)))
                  :key/id (:key/id (first (:members f)))
                  :private-key (.getPrivate ^KeyPair (first (:pairs f)))
                  :public-key (.getPublic ^KeyPair (first (:pairs f)))
                  :store-path store})
                nil
                (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))]
    (is (false? (:reused? first-signature)))
    (is (true? (:reused? restarted-signature)))
    (is (= (:signature first-signature) (:signature restarted-signature)))
    (is (= :witness/equivocation-lock error))))

(deftest durable-witness-lock-recovers-across-fresh-processes
  (let [f (fixture)
        dir (Files/createTempDirectory "checkpoint-lock-process-test"
                                       (make-array java.nio.file.attribute.FileAttribute 0))
        store (.resolve dir "witness.edn")
        package-file (.resolve dir "input.edn")
        member (first (:members f))
        private-key-base64 (.encodeToString (Base64/getEncoder)
                                            (.getEncoded (.getPrivate ^KeyPair (first (:pairs f)))))
        write-package! (fn [candidate]
                         (spit (.toFile package-file)
                               (pr-str {:checkpoint candidate
                                        :predecessor (:genesis f)
                                        :policy (:policy f)
                                        :membership (:membership f)
                                        :principal-id (:principal/id member)
                                        :key-id (:key/id member)
                                        :private-key-base64 private-key-base64
                                        :store-path (.toString store)})))
        run-process! (fn []
                       (let [result (shell/sh "clojure" "-M:test" "-m"
                                              "resolver-sim.network.checkpoint-witness-fresh-process"
                                              (.toString package-file)
                                              :dir (System/getProperty "user.dir"))]
                         (is (= 0 (:exit result)) (:err result))
                         (edn/read-string (:out result))))
        competing (checkpoint/build-checkpoint
                   (assoc (dissoc (:checkpoint f) :checkpoint/root)
                          :checkpoint/proposer-id "other-publisher"))]
    (write-package! (:checkpoint f))
    (let [first-process (run-process!)
          second-process (run-process!)]
      (is (false? (:reused? first-process)))
      (is (true? (:reused? second-process)))
      (is (= (:signature first-process) (:signature second-process)))
      (is (Files/exists store (make-array java.nio.file.LinkOption 0)))
      (write-package! competing)
      (is (= {:error :witness/equivocation-lock} (run-process!))))))

(deftest witness-refuses-an-unenrolled-key-without-consuming-the-enrolled-lock
  (let [f (fixture)
        dir (Files/createTempDirectory "checkpoint-key-binding-test"
                                       (make-array java.nio.file.attribute.FileAttribute 0))
        store (.resolve dir "witness.edn")
        member (first (:members f))
        attacker (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        error (try
                (checkpoint/endorse-checkpoint!
                 (:checkpoint f) (:context f)
                 {:principal/id (:principal/id member)
                  :key/id (:key/id member)
                  :private-key (.getPrivate attacker)
                  :public-key (.getPublic attacker)
                  :store-path store})
                nil
                (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))
        store-created-after-rejection? (Files/exists store (make-array java.nio.file.LinkOption 0))
        first-valid (endorsement f 0 store)
        second-valid (endorsement f 1 (.resolve dir "witness-1.edn"))
        certificate (checkpoint/build-certificate (:checkpoint f) [first-valid second-valid])
        verification (checkpoint/verify-certificate (:checkpoint f) certificate
                                                    (:policy f) (:membership f))]
    (is (= :witness/signing-key-mismatch error))
    (is (false? store-created-after-rejection?))
    (is (:valid? verification))
    (is (= :certified (:status verification)))))

(deftest persistence-failure-never-returns-a-signature
  (let [f (fixture)
        error (try
                (endorsement f 0 "/proc/prf-checkpoint-test/witness.edn")
                nil
                (catch Exception e
                  (or (:reason (ex-data e)) :filesystem-error)))]
    (is (= :witness/durable-lock-write-failed error))))

(deftest incompatible-certified-successors-are-preserved-as-conflicts
  (let [f (fixture)
        dir (Files/createTempDirectory "checkpoint-conflict-test" (make-array java.nio.file.attribute.FileAttribute 0))
        cert (signed-certificate f [0 1 2] dir)
        altered (checkpoint/build-checkpoint
                 (assoc (dissoc (:checkpoint f) :checkpoint/root)
                        :checkpoint/proposer-id "fork-publisher"))
        context (-> (:context f)
                    (assoc :checkpoint/sequence 0)
                    (assoc-in [:predecessor :state] {:state/accepted-event-roots #{}}))
        altered-cert (checkpoint/build-certificate altered
                                                   (mapv (fn [i]
                                                           (let [d (.resolve dir (str "fork-" i ".edn"))]
                                                             (checkpoint/endorse-checkpoint!
                                                              altered context
                                                              {:principal/id (:principal/id (nth (:members f) i))
                                                               :key/id (:key/id (nth (:members f) i))
                                                               :private-key (.getPrivate ^KeyPair (nth (:pairs f) i))
                                                               :public-key (.getPublic ^KeyPair (nth (:pairs f) i))
                                                               :store-path d})))
                                                         [0 1 2]))
        a (checkpoint/verify-checkpoint (:checkpoint f) cert (:context f))
        b (checkpoint/verify-checkpoint altered altered-cert context)
        conflict (checkpoint/detect-conflicts [{:checkpoint (:checkpoint f) :verification a}
                                               {:checkpoint altered :verification b}])]
    (is (= :certified (:status a)))
    (is (= :certified (:status b)))
    (is (= :conflicting (:status conflict)))
    (is (= 2 (count (:conflict/evidence conflict))))))

(deftest changed-authority-roots-require-explicit-old-authority-authorization
  (let [f (fixture)
        cp (:checkpoint f)
        next-policy (hash-ref "checkpoint-test-policy.v1" {:threshold 3})
        next-membership (hash-ref "checkpoint-test-membership.v1" {:members [:next]})
        candidate (checkpoint/build-checkpoint
                   (-> (dissoc cp :checkpoint/root)
                       (assoc :checkpoint/resulting-policy-root next-policy
                              :checkpoint/resulting-membership-root next-membership
                              :checkpoint/resulting-epoch 1)))
        denied (checkpoint/verify-checkpoint-transition candidate (:context f))
        transition-event (event "authority-transition" [])
        authorized-fixture (fixture [transition-event])
        authorized-candidate
        (checkpoint/build-checkpoint
         (-> (dissoc (:checkpoint authorized-fixture) :checkpoint/root)
             (assoc :checkpoint/resulting-policy-root next-policy
                    :checkpoint/resulting-membership-root next-membership
                    :checkpoint/resulting-epoch 1)))
        authorized-context
        (-> (:context authorized-fixture)
            (assoc :verify-event (fn [_] {:valid? true :authority-transition? true
                                          :transition/authorized? true}))
            (assoc :replay (fn [_ events _ _]
                             {:valid? true
                              :dag-state-root (:checkpoint/dag-state-root authorized-candidate)
                              :frontier-roots (mapv :event/root events)
                              :resulting-policy-root next-policy
                              :resulting-membership-root next-membership
                              :resulting-epoch 1
                              :state {:state/accepted-event-roots
                                      (set (map :event/root events))}})))
        allowed (checkpoint/verify-checkpoint-transition authorized-candidate authorized-context)]
    (is (some #{:checkpoint/unauthorized-authority-transition} (:errors denied)))
    (is (:valid? allowed))
    (is (= 1 (get-in allowed [:state :resulting-epoch])))))

(deftest consecutive-certified-history-reconstructs-from-a-pinned-anchor
  (let [f (fixture)
        dir (Files/createTempDirectory "checkpoint-history-test"
                                       (make-array java.nio.file.attribute.FileAttribute 0))
        cp1 (:checkpoint f)
        cert1 (signed-certificate f [0 1] (.resolve dir "first"))
        pred1 (merge (:genesis f)
                     (select-keys cp1 [:network/genesis-root :checkpoint/root
                                       :checkpoint/sequence :checkpoint/resulting-epoch
                                       :checkpoint/resulting-policy-root
                                       :checkpoint/resulting-membership-root])
                     {:state {:state/accepted-event-roots #{}}
                      :state/accepted-event-roots #{}})
        cp2 (checkpoint/build-checkpoint
             (-> (dissoc cp1 :checkpoint/root)
                 (assoc :checkpoint/sequence 2
                        :checkpoint/previous-root (:checkpoint/root cp1)
                        :checkpoint/proposer-id "publisher:second")))
        f2 (assoc f :checkpoint cp2 :context (assoc (:context f) :predecessor pred1))
        cert2 (signed-certificate f2 [0 1] (.resolve dir "second"))
        adapters (dissoc (:context f) :network/genesis-root :predecessor :events :policy :membership)
        result (checkpoint/verify-history
                (:genesis f)
                [{:checkpoint cp1 :certificate cert1 :events []}
                 {:checkpoint cp2 :certificate cert2 :events []}]
                (assoc adapters :resolve-authority (fn [_ _ _]
                                                     {:policy (:policy f)
                                                      :membership (:membership f)})))
        portable (edn/read-string (pr-str {:checkpoint cp2 :certificate cert2}))]
    (is (:valid? result))
    (is (= :certified (:status result)))
    (is (= (:checkpoint/root cp2) (get-in result [:head :checkpoint/root])))
    (is (= (:checkpoint/root cp2) (get-in portable [:checkpoint :checkpoint/root])))
    (is (= (:certificate/root cert2) (get-in portable [:certificate :certificate/root])))))

(deftest edn-checkpoint-package-reconstructs-in-a-fresh-process
  (let [f (fixture)
        dir (Files/createTempDirectory "checkpoint-fresh-process-cert"
                                       (make-array java.nio.file.attribute.FileAttribute 0))
        package {:checkpoint (:checkpoint f)
                 :certificate (signed-certificate f [0 1] dir)
                 :predecessor (:genesis f)
                 :policy (:policy f)
                 :membership (:membership f)
                 :events []}
        package-file (Files/createTempFile "checkpoint-package" ".edn"
                                           (make-array java.nio.file.attribute.FileAttribute 0))
        _ (spit (.toFile package-file) (pr-str package))
        verify-in-fresh-process (fn []
                                  (shell/sh "clojure" "-M:test" "-m"
                                            "resolver-sim.network.checkpoint-fresh-process"
                                            (.toString package-file)
                                            :dir (System/getProperty "user.dir")))
        first-process (verify-in-fresh-process)
        second-process (verify-in-fresh-process)
        first-verification (edn/read-string (:out first-process))
        second-verification (edn/read-string (:out second-process))]
    (is (= 0 (:exit first-process)) (:err first-process))
    (is (= 0 (:exit second-process)) (:err second-process))
    (is (= :certified (:status first-verification)))
    (is (= :certified (:status second-verification)))
    (is (= (:checkpoint/root (:checkpoint f)) (:checkpoint/root first-verification)))
    (is (= (:checkpoint/root first-verification) (:checkpoint/root second-verification)))))

(deftest head-observations-authenticate-freshness-and-independent-sources
  (let [f (fixture)
        now (java.time.Instant/parse "2026-10-22T12:00:00Z")
        nonce "0123456789abcdef"
        sources (mapv (fn [i group]
                        {:source/id (str "observer:" i)
                         :source/key-id (str "observer-key:" i)
                         :network/genesis-root (get-in f [:genesis :network/genesis-root])
                         :source/public-key (.getPublic ^KeyPair (nth (:pairs f) i))
                         :source/independence-group group})
                      [0 1] [:operator-a :operator-b])
        observations (mapv (fn [i]
                             (checkpoint/sign-head-observation
                              {:genesis-root (get-in f [:genesis :network/genesis-root])
                               :source-id (str "observer:" i)
                               :key-id (str "observer-key:" i)
                               :checkpoint (:checkpoint f)
                               :client-nonce nonce
                               :issued-at (.toString now)
                               :private-key (.getPrivate ^KeyPair (nth (:pairs f) i))}))
                           [0 1])
        history {:status :certified
                 :head (:checkpoint f)
                 :checkpoints [{:status :certified
                                :checkpoint/sequence 0
                                :checkpoint/previous-root nil
                                :checkpoint/root (get-in f [:genesis :checkpoint/root])}
                               {:status :certified
                                :checkpoint/sequence 1
                                :checkpoint/previous-root (get-in f [:genesis :checkpoint/root])
                                :checkpoint/root (get-in f [:checkpoint :checkpoint/root])}]}
        result (checkpoint/verify-observed-heads
                observations sources
                {:network/genesis-root (get-in f [:genesis :network/genesis-root])
                 :certified-head history :now now :expected-nonce nonce})
        same-operator (checkpoint/verify-observed-heads
                       observations (assoc-in sources [1 :source/independence-group] :operator-a)
                       {:network/genesis-root (get-in f [:genesis :network/genesis-root])
                        :certified-head history :now now :expected-nonce nonce})
        one-source-unavailable (checkpoint/verify-observed-heads
                                [(first observations)] sources
                                {:network/genesis-root (get-in f [:genesis :network/genesis-root])
                                 :certified-head history :now now :expected-nonce nonce})
        missing-independence-group
        (checkpoint/verify-observed-heads
         observations (assoc-in sources [1 :source/independence-group] nil)
         {:network/genesis-root (get-in f [:genesis :network/genesis-root])
          :certified-head history :now now :expected-nonce nonce})
        invalid-source-key
        (checkpoint/verify-observed-heads
         observations (assoc-in sources [1 :source/public-key] "not-a-public-key")
         {:network/genesis-root (get-in f [:genesis :network/genesis-root])
          :certified-head history :now now :expected-nonce nonce})
        stale (checkpoint/sign-head-observation
               {:genesis-root (get-in f [:genesis :network/genesis-root])
                :source-id "observer:0" :key-id "observer-key:0"
                :checkpoint (:checkpoint f) :client-nonce nil
                :issued-at (.toString (.minusSeconds now 60))
                :private-key (.getPrivate ^KeyPair (first (:pairs f)))})
        nonce-bound-stale (checkpoint/sign-head-observation
                           {:genesis-root (get-in f [:genesis :network/genesis-root])
                            :source-id "observer:0" :key-id "observer-key:0"
                            :checkpoint (:checkpoint f) :client-nonce nonce
                            :issued-at (.toString (.minusSeconds now 60))
                            :private-key (.getPrivate ^KeyPair (first (:pairs f)))})
        nonce-bound-fractionally-stale (checkpoint/sign-head-observation
                                        {:genesis-root (get-in f [:genesis :network/genesis-root])
                                         :source-id "observer:0" :key-id "observer-key:0"
                                         :checkpoint (:checkpoint f) :client-nonce nonce
                                         :issued-at (.toString (.minusMillis now 30500))
                                         :private-key (.getPrivate ^KeyPair (first (:pairs f)))})
        nonce-bound-future (checkpoint/sign-head-observation
                            {:genesis-root (get-in f [:genesis :network/genesis-root])
                             :source-id "observer:0" :key-id "observer-key:0"
                             :checkpoint (:checkpoint f) :client-nonce nonce
                             :issued-at (.toString (.plusSeconds now 31))
                             :private-key (.getPrivate ^KeyPair (first (:pairs f)))})
        one-source-behind (checkpoint/sign-head-observation
                           {:genesis-root (get-in f [:genesis :network/genesis-root])
                            :source-id "observer:1" :key-id "observer-key:1"
                            :checkpoint (:genesis f) :client-nonce nonce
                            :issued-at (.toString now)
                            :private-key (.getPrivate ^KeyPair (second (:pairs f)))})
        invalid-signature (assoc-in (first observations)
                                    [:observation/signature :signature :value]
                                    "not-a-signature")
        wrong-genesis (assoc (first observations)
                             :network/genesis-root
                             (hash-ref "checkpoint-test-other-network.v1" {:id "other"}))]
    (is (:valid? result))
    (is (= :current-observed (:status result)))
    (is (= 2 (:source-count result)))
    (is (= :stale-or-unknown (:status same-operator)))
    (is (= :stale-or-unknown (:status one-source-unavailable)))
    (is (false? (:valid? one-source-unavailable)))
    (is (= :invalid (:status missing-independence-group)))
    (is (= [:observation/invalid-source-config]
           (:errors missing-independence-group)))
    (is (= :invalid (:status invalid-source-key)))
    (is (= :invalid
           (:status (checkpoint/verify-observed-heads
                     observations sources
                     {:network/genesis-root (get-in f [:genesis :network/genesis-root])
                      :certified-head history :now now :expected-nonce nonce :threshold 1}))))
    (is (= :stale-or-unknown
           (:status (checkpoint/verify-head-observation
                     (first observations)
                     (first sources)
                     {:now now :expected-nonce "fedcba9876543210"}))))
    (is (= :stale-or-unknown
           (:status (checkpoint/verify-head-observation
                     stale (first sources) {:now now :max-age-seconds 30}))))
    (is (= :stale-or-unknown
           (:status (checkpoint/verify-head-observation
                     nonce-bound-stale (first sources)
                     {:now now :expected-nonce nonce :max-age-seconds 30}))))
    (is (= :stale-or-unknown
           (:status (checkpoint/verify-head-observation
                     nonce-bound-fractionally-stale (first sources)
                     {:now now :expected-nonce nonce :max-age-seconds 30}))))
    (is (= :stale-or-unknown
           (:status (checkpoint/verify-head-observation
                     nonce-bound-future (first sources)
                     {:now now :expected-nonce nonce :max-age-seconds 30
                      :max-future-skew-seconds 0}))))
    (is (= :stale-or-unknown
           (:status (checkpoint/verify-observed-heads
                     [(first observations) one-source-behind] sources
                     {:network/genesis-root (get-in f [:genesis :network/genesis-root])
                      :certified-head history :now now :expected-nonce nonce}))))
    (let [invalid-signature-result
          (checkpoint/verify-observed-heads
           [invalid-signature (second observations)] sources
           {:network/genesis-root (get-in f [:genesis :network/genesis-root])
            :certified-head history :now now :expected-nonce nonce})
          wrong-genesis-result
          (checkpoint/verify-observed-heads
           [wrong-genesis (second observations)] sources
           {:network/genesis-root (get-in f [:genesis :network/genesis-root])
            :certified-head history :now now :expected-nonce nonce})]
      (is (= :invalid (:status invalid-signature-result)))
      (is (some #{:observation/invalid-signature}
                (:errors invalid-signature-result)))
      (is (= :invalid (:status wrong-genesis-result)))
      (is (= [:observation/wrong-network] (:errors wrong-genesis-result))))))

(deftest incomplete-history-packages-never-claim-certification
  (let [f (fixture)
        events [(event "required-event" [])]
        with-event (fixture events)
        adapters-for (fn [fixture]
                       (assoc (dissoc (:context fixture)
                                      :network/genesis-root :predecessor :events
                                      :policy :membership)
                              :resolve-authority
                              (fn [_ _ _]
                                {:policy (:policy fixture)
                                 :membership (:membership fixture)})))
        now (java.time.Instant/parse "2026-10-22T12:00:00Z")
        nonce "checkpoint-history-nonce-1234"
        sources (mapv (fn [i]
                        {:source/id (str "history-source:" i)
                         :source/key-id (str "history-key:" i)
                         :network/genesis-root (get-in f [:genesis :network/genesis-root])
                         :source/public-key (.getPublic ^KeyPair (nth (:pairs f) i))
                         :source/independence-group (keyword (str "history-operator-" i))})
                      [0 1])
        observations (mapv (fn [i]
                             (checkpoint/sign-head-observation
                              {:genesis-root (get-in f [:genesis :network/genesis-root])
                               :source-id (str "history-source:" i)
                               :key-id (str "history-key:" i)
                               :checkpoint (:checkpoint f)
                               :client-nonce nonce
                               :issued-at (.toString now)
                               :private-key (.getPrivate ^KeyPair (nth (:pairs f) i))}))
                           [0 1])
        unendorsed (checkpoint/verify-history
                    (:genesis f)
                    [{:checkpoint (:checkpoint f) :events []}]
                    (adapters-for f))
        unendorsed-observations
        (checkpoint/verify-observed-heads
         observations sources
         {:network/genesis-root (get-in f [:genesis :network/genesis-root])
          :certified-head unendorsed :now now :expected-nonce nonce})
        missing-checkpoint (checkpoint/verify-history
                            (:genesis f) [{}] (adapters-for f))
        missing-manifest (checkpoint/verify-history
                          (:genesis f)
                          [{:checkpoint (dissoc (:checkpoint f)
                                                :checkpoint/accepted-event-manifest-root)
                            :events []}]
                          (adapters-for f))
        missing-event-body (checkpoint/verify-history
                            (:genesis with-event)
                            [{:checkpoint (:checkpoint with-event) :events []}]
                            (adapters-for with-event))]
    (is (= :locally-valid (:status unendorsed)))
    (is (not= :certified (:status unendorsed)))
    (is (= :stale-or-unknown (:status unendorsed-observations)))
    (is (false? (:valid? unendorsed-observations)))
    (is (= :invalid (:status missing-checkpoint)))
    (is (= :invalid (:status missing-manifest)))
    (is (= :invalid (:status missing-event-body)))))

(deftest head-observations-report-same-sequence-fork-as-conflicting
  (let [f (fixture)
        now (java.time.Instant/parse "2026-10-22T12:00:00Z")
        pairs (:pairs f)
        root-a (get-in f [:checkpoint :checkpoint/root])
        root-b (hash-ref "checkpoint-test-fork.v1" {:parent root-a})
        sources (mapv (fn [i]
                        {:source/id (str "observer:" i)
                         :source/key-id (str "observer-key:" i)
                         :network/genesis-root (get-in f [:genesis :network/genesis-root])
                         :source/public-key (.getPublic ^KeyPair (nth pairs i))
                         :source/independence-group (keyword (str "operator-" i))})
                      [0 1])
        observations (mapv (fn [i root]
                             (checkpoint/sign-head-observation
                              {:genesis-root (get-in f [:genesis :network/genesis-root])
                               :source-id (str "observer:" i)
                               :key-id (str "observer-key:" i)
                               :checkpoint {:checkpoint/root root :checkpoint/sequence 1}
                               :client-nonce "0123456789abcdef"
                               :issued-at (.toString now)
                               :private-key (.getPrivate ^KeyPair (nth pairs i))}))
                           [0 1] [root-a root-b])
        agreeing-observations (mapv (fn [i]
                                      (checkpoint/sign-head-observation
                                       {:genesis-root (get-in f [:genesis :network/genesis-root])
                                        :source-id (str "observer:" i)
                                        :key-id (str "observer-key:" i)
                                        :checkpoint {:checkpoint/root root-a :checkpoint/sequence 1}
                                        :client-nonce "0123456789abcdef"
                                        :issued-at (.toString now)
                                        :private-key (.getPrivate ^KeyPair (nth pairs i))}))
                                    [0 1])
        history {:status :conflicting
                 :conflict/evidence [{:checkpoint {:checkpoint/root root-a
                                                   :checkpoint/sequence 1
                                                   :checkpoint/previous-root (get-in f [:genesis :checkpoint/root])}
                                      :verification {:status :certified}}
                                     {:checkpoint {:checkpoint/root root-b
                                                   :checkpoint/sequence 1
                                                   :checkpoint/previous-root (get-in f [:genesis :checkpoint/root])}
                                      :verification {:status :certified}}]}
        result (checkpoint/verify-observed-heads
                observations sources
                {:network/genesis-root (get-in f [:genesis :network/genesis-root])
                 :certified-head history :now now :expected-nonce "0123456789abcdef"})
        result-when-sources-agree-on-one-branch
        (checkpoint/verify-observed-heads
         agreeing-observations sources
         {:network/genesis-root (get-in f [:genesis :network/genesis-root])
          :certified-head history :now now :expected-nonce "0123456789abcdef"})
        result-with-higher-currentness-threshold
        (checkpoint/verify-observed-heads
         observations sources
         {:network/genesis-root (get-in f [:genesis :network/genesis-root])
          :certified-head history :now now :expected-nonce "0123456789abcdef"
          :threshold 3})]
    (is (= :conflicting (:status result)))
    (is (false? (:valid? result)))
    (is (= :conflicting (:status result-when-sources-agree-on-one-branch)))
    (is (= :conflicting (:status result-with-higher-currentness-threshold)))))

(deftest malformed-observation-inputs-return-typed-invalid-results
  (is (= :invalid
         (:status (checkpoint/verify-observed-heads
                   :not-an-observation-sequence [] {}))))
  (is (= :invalid
         (:status (checkpoint/verify-observed-heads
                   [] :not-a-source-sequence {})))))

(deftest malformed-history-entries-return-a-typed-invalid-result
  (let [f (fixture)
        result (checkpoint/verify-history (:genesis f) :not-an-entry-sequence
                                          (:context f))]
    (is (= :invalid (:status result)))
    (is (false? (:valid? result))))
  (let [result (checkpoint/verify-history nil [] {})]
    (is (= :stale-or-unknown (:status result)))
    (is (false? (:valid? result))))
  (let [f (fixture)
        result (checkpoint/verify-history
                (:genesis f) [{:checkpoint :malformed :events :not-an-event-sequence}]
                (:context f))]
    (is (= :invalid (:status result)))
    (is (false? (:valid? result)))))
