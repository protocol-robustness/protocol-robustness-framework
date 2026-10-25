(ns resolver-sim.network.checkpoint
  "Canonical, offline network checkpoint construction and verification.

   This namespace verifies certified state transitions supplied by a caller. It
   is not a network transport, peer-discovery, fork-choice, or global-latestness
   implementation. Authority policy and membership are resolved from an
   independently trusted predecessor and event-replay adapter."
  (:require [clojure.edn :as edn]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.evidence.finalization-signature :as signatures]
            [resolver-sim.benchmark.signing :as signing])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files LinkOption OpenOption Path Paths StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute PosixFilePermission]))

(def checkpoint-schema "network-checkpoint.v1")
(def certificate-schema "checkpoint-certificate.v1")
(def observation-schema "head-observation.v1")
(def checkpoint-domain :network-checkpoint-v1)
(def manifest-domain :checkpoint-event-manifest-v1)
(def certificate-domain :checkpoint-certificate-v1)
(def observation-domain :head-observation-v1)

(def checkpoint-fields
  #{:checkpoint/schema :network/genesis-root :checkpoint/epoch
    :checkpoint/resulting-epoch :checkpoint/sequence :checkpoint/previous-root
    :checkpoint/accepted-event-manifest-root :checkpoint/accepted-event-count
    :checkpoint/dag-state-root :checkpoint/frontier-roots
    :checkpoint/governing-policy-root :checkpoint/governing-membership-root
    :checkpoint/resulting-policy-root :checkpoint/resulting-membership-root
    :checkpoint/proposer-id :checkpoint/claimed-time :checkpoint/root})

(def certificate-fields
  #{:certificate/schema :network/genesis-root :checkpoint/root
    :checkpoint/sequence :checkpoint/epoch :policy/root :membership/root
    :certificate/endorsements :certificate/root})

(def observation-fields
  #{:observation/schema :network/genesis-root :source/id :source/key-id
    :observation/scope :checkpoint/root :checkpoint/sequence
    :observation/client-nonce :observation/issued-at
    :observation/signature :observation/root})

(def endorsement-fields #{:principal/id :key/id :signature})

(def signature-profile
  {:envelope-schema-version "network-checkpoint-endorsement.v1"
   :payload-type "application/vnd.prf.network-checkpoint.v1"
   :payload-schema-version checkpoint-schema
   :subject-kind checkpoint-schema
   :subject-schema-version checkpoint-schema})

(def observation-signature-profile
  {:envelope-schema-version "head-observation.v1"
   :payload-type "application/vnd.prf.head-observation.v1"
   :payload-schema-version observation-schema
   :subject-kind observation-schema
   :subject-schema-version observation-schema})

(def ^:private hex-root-pattern #"^sha256:[0-9a-f]{64}$")
(def ^:private store-schema "checkpoint-witness-lock-store.v1")
(def ^:private lock-file-permissions
  #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE})

(defn- root [domain body]
  (reference/sha256-ref (canonical/domain-hash domain body)))

(defn- valid-root? [value]
  (and (string? value) (boolean (re-matches hex-root-pattern value))))

(defn- exact-fields? [m fields]
  (and (map? m) (= fields (set (keys m)))))

(defn- nonnegative-int? [value]
  (and (integer? value) (not (neg? value))))

(defn- sorted-unique-roots? [roots]
  (and (vector? roots)
       (every? valid-root? roots)
       (= roots (vec (sort roots)))
       (= (count roots) (count (distinct roots)))))

(defn checkpoint-root [checkpoint]
  (root checkpoint-domain (dissoc checkpoint :checkpoint/root)))

(defn build-checkpoint [fields]
  (let [body (assoc fields :checkpoint/schema checkpoint-schema)
        checkpoint (assoc body :checkpoint/root (checkpoint-root body))]
    checkpoint))

(defn certificate-root [certificate]
  (root certificate-domain (dissoc certificate :certificate/root)))

(defn build-certificate [checkpoint endorsements]
  (let [body {:certificate/schema certificate-schema
              :network/genesis-root (:network/genesis-root checkpoint)
              :checkpoint/root (:checkpoint/root checkpoint)
              :checkpoint/sequence (:checkpoint/sequence checkpoint)
              :checkpoint/epoch (:checkpoint/epoch checkpoint)
              :policy/root (:checkpoint/governing-policy-root checkpoint)
              :membership/root (:checkpoint/governing-membership-root checkpoint)
              :certificate/endorsements
              (->> endorsements
                   (map #(select-keys % [:principal/id :key/id :signature]))
                   (sort-by (juxt :principal/id :key/id))
                   vec)}]
    (assoc body :certificate/root (certificate-root body))))

(defn build-head-observation
  [{:keys [genesis-root source-id key-id checkpoint client-nonce issued-at]} signature-envelope]
  (let [body {:observation/schema observation-schema
              :network/genesis-root genesis-root
              :source/id source-id
              :source/key-id key-id
              :observation/scope "certified-network-head"
              :checkpoint/root (:checkpoint/root checkpoint)
              :checkpoint/sequence (:checkpoint/sequence checkpoint)
              :observation/client-nonce client-nonce
              :observation/issued-at issued-at
              :observation/signature signature-envelope}]
    (assoc body :observation/root (root observation-domain
                                        (dissoc body :observation/signature)))))

(defn head-observation-root [observation]
  (root observation-domain
        (dissoc observation :observation/signature :observation/root)))

(declare valid-signature-envelope?)

(defn- valid-observation-source? [source]
  (let [group (:source/independence-group source)]
    (and (map? source)
         (string? (:source/id source))
         (not (empty? (:source/id source)))
         (string? (:source/key-id source))
         (not (empty? (:source/key-id source)))
         (valid-root? (:network/genesis-root source))
         (instance? java.security.PublicKey (:source/public-key source))
         (or (and (string? group) (not (empty? group)))
             (keyword? group)))))

(defn sign-head-observation
  "Create a signed head report. The signature covers its canonical body,
   including nonce and issue time; callers must supply a trusted clock value."
  [{:keys [genesis-root source-id key-id checkpoint client-nonce issued-at
           private-key]}]
  (let [body {:observation/schema observation-schema
              :network/genesis-root genesis-root
              :source/id source-id
              :source/key-id key-id
              :observation/scope "certified-network-head"
              :checkpoint/root (:checkpoint/root checkpoint)
              :checkpoint/sequence (:checkpoint/sequence checkpoint)
              :observation/client-nonce client-nonce
              :observation/issued-at issued-at}
        observation-root (head-observation-root body)
        envelope (signatures/build-envelope* observation-signature-profile
                                              observation-root key-id private-key)]
    (assoc body
           :observation/signature envelope
           :observation/root observation-root)))

(defn verify-head-observation
  "Authenticate and freshness-check one observation against independently
   configured source identity/key and a verifier-supplied clock.

   `source` requires :source/id, :source/key-id, :network/genesis-root,
   :source/public-key and :source/independence-group. `options` requires
   :now (java.time.Instant) and either :expected-nonce or :max-age-seconds.
   When supplied, :max-age-seconds and :max-future-skew-seconds are enforced
   even for nonce-bound observations."
  [observation source {:keys [now expected-nonce max-age-seconds
                             max-future-skew-seconds]}]
  (let [bad (fn [error] {:status :invalid :valid? false :errors [error]})
        unknown (fn [error] {:status :stale-or-unknown :valid? false :errors [error]})
        issued-at (try (java.time.Instant/parse (:observation/issued-at observation))
                       (catch Exception _ nil))
        nonce (:observation/client-nonce observation)
        signature (:observation/signature observation)
        verified (when (and (valid-observation-source? source)
                            (valid-signature-envelope? signature))
                   (try
                     (signatures/verify-envelope* observation-signature-profile
                                                   signature (:source/public-key source))
                     (catch Exception _ nil)))]
    (cond
      (not (valid-observation-source? source)) (bad :observation/invalid-source-config)
      (not (exact-fields? observation observation-fields)) (bad :observation/invalid-shape)
      (not= observation-schema (:observation/schema observation)) (bad :observation/unsupported-schema)
      (not (valid-root? (:network/genesis-root observation))) (bad :observation/invalid-network-root)
      (not (valid-root? (:checkpoint/root observation))) (bad :observation/invalid-checkpoint-root)
      (not (nonnegative-int? (:checkpoint/sequence observation))) (bad :observation/invalid-sequence)
      (not= "certified-network-head" (:observation/scope observation)) (bad :observation/wrong-scope)
      (not= (:observation/root observation) (head-observation-root observation))
      (bad :observation/root-mismatch)
      (not= (:network/genesis-root source) (:network/genesis-root observation))
      (bad :observation/wrong-network)
      (not= (:source/id source) (:source/id observation)) (bad :observation/wrong-source)
      (not= (:source/key-id source) (:source/key-id observation)) (bad :observation/wrong-key)
      (not (or (and (string? nonce)
                    (re-matches #"[A-Za-z0-9_-]{16,128}" nonce))
               (and (nil? nonce) (nil? expected-nonce)
                    (and (integer? max-age-seconds) (pos? max-age-seconds)))))
      (bad :observation/invalid-nonce)
      (not issued-at) (bad :observation/invalid-issued-at)
      (not (and (valid-signature-envelope? signature)
                (= (:source/key-id source) (get-in signature [:signature :key-id]))
                (:valid? verified)
                (= (:observation/root observation) (get-in signature [:payload :payload-hash]))
                (= (:observation/root observation) (get-in signature [:payload :subject :hash]))
                (= observation-schema (get-in signature [:payload :subject :kind]))))
      (bad :observation/invalid-signature)
      (and expected-nonce (not= expected-nonce nonce)) (unknown :observation/nonce-mismatch)
      (or (not (instance? java.time.Instant now))
          (and (nil? expected-nonce)
               (not (and (integer? max-age-seconds) (pos? max-age-seconds))))
          (and (some? max-age-seconds)
               (not (and (integer? max-age-seconds) (pos? max-age-seconds)))))
      (unknown :observation/freshness-policy-missing)
      (and (some? max-age-seconds)
           (pos? (.compareTo (java.time.Duration/between issued-at now)
                             (java.time.Duration/ofSeconds max-age-seconds))))
      (unknown :observation/expired)
      (or (not (and (integer? (or max-future-skew-seconds 0))
                    (not (neg? (or max-future-skew-seconds 0)))))
          (.isAfter issued-at
                    (.plusSeconds ^java.time.Instant now
                                  (or max-future-skew-seconds 0))))
      (unknown :observation/future-issued-at)
      :else {:status :authenticated :valid? true :errors []
             :source/id (:source/id observation)
             :source/independence-group (:source/independence-group source)
             :checkpoint/root (:checkpoint/root observation)
             :checkpoint/sequence (:checkpoint/sequence observation)
             :observation/root (:observation/root observation)})))

(defn verify-observed-heads
  "Evaluate authenticated observations against independently verified certified
   history. Currentness is limited to the configured sources and freshness
   policy. Distinct URLs or names do not count as independence; configured
   independence groups must differ."
  [observations sources {:keys [network/genesis-root certified-head now
                                expected-nonce max-age-seconds max-future-skew-seconds
                                threshold]}]
  (let [observations-sequential? (sequential? observations)
        sources-sequential? (sequential? sources)
        safe-observations (if observations-sequential? observations [])
        safe-sources (if sources-sequential? sources [])
        source-by-id (into {} (map (juxt :source/id identity) safe-sources))
        checks (mapv (fn [observation]
                      (verify-head-observation observation
                                               (get source-by-id (:source/id observation))
                                               {:now now
                                                :expected-nonce (if (map? expected-nonce)
                                                                  (get expected-nonce (:source/id observation))
                                                                  expected-nonce)
                                                :max-age-seconds max-age-seconds
                                                :max-future-skew-seconds max-future-skew-seconds}))
                    safe-observations)
        valid-checks (filter :valid? checks)
        roots (set (map :checkpoint/root valid-checks))
        groups (set (map :source/independence-group valid-checks))
        ids (map :source/id valid-checks)
        threshold (or threshold 2)
        certified? (contains? #{:certified :conflicting} (:status certified-head))
        head-root (get-in certified-head [:head :checkpoint/root])
        head-sequence (get-in certified-head [:head :checkpoint/sequence])
        known-checkpoints (if (= :conflicting (:status certified-head))
                            (map (fn [{:keys [checkpoint verification]}]
                                   {:checkpoint/root (:checkpoint/root checkpoint)
                                    :checkpoint/sequence (:checkpoint/sequence checkpoint)
                                    :checkpoint/previous-root (:checkpoint/previous-root checkpoint)
                                    :status (get-in verification [:status])})
                                 (:conflict/evidence certified-head))
                            (:checkpoints certified-head))
        verified-checkpoint? (fn [check]
                               (some #(and (= (:checkpoint/root check)
                                              (or (:checkpoint/root %)
                                                  (get-in % [:checkpoint :checkpoint/root])))
                                           (= :certified (or (:status %)
                                                             (get-in % [:verification :status])))
                                           (= (:checkpoint/sequence check)
                                              (or (:checkpoint/sequence %)
                                                  (get-in % [:checkpoint :checkpoint/sequence]))))
                                     known-checkpoints))
        observed-checkpoints (map (fn [check]
                                   (some #(when (and (= (:checkpoint/root check)
                                                        (or (:checkpoint/root %)
                                                            (get-in % [:checkpoint :checkpoint/root])))
                                                      (= :certified (or (:status %)
                                                                        (get-in % [:verification :status]))))
                                             (let [checkpoint (or (:checkpoint %) %)]
                                               {:checkpoint/root (:checkpoint/root checkpoint)
                                                :checkpoint/sequence (:checkpoint/sequence checkpoint)
                                                :checkpoint/previous-root (:checkpoint/previous-root checkpoint)}))
                                         known-checkpoints))
                                  valid-checks)
        all-known? (and certified?
                         (every? verified-checkpoint? valid-checks))
        conflict? (and all-known?
                       (some (fn [[i left]]
                               (some (fn [right]
                                       (and left right
                                            (not= (:checkpoint/root left) (:checkpoint/root right))
                                            (= (:checkpoint/sequence left) (:checkpoint/sequence right))
                                            (= (:checkpoint/previous-root left)
                                               (:checkpoint/previous-root right))))
                                     (drop (inc i) observed-checkpoints)))
                             (map-indexed vector observed-checkpoints))
                       (>= (count (set ids)) 2)
                       (>= (count groups) 2))]
    (cond
      (or (not sources-sequential?)
          (not (every? valid-observation-source? sources))
          (not= (count sources) (count (distinct (map :source/id sources)))))
      {:status :invalid :valid? false :errors [:observation/invalid-source-config]
       :observations checks}
      (not observations-sequential?)
      {:status :invalid :valid? false :errors [:observation/invalid-observations]
       :observations checks}
      (not (and (integer? threshold) (>= threshold 2)))
      {:status :invalid :valid? false :errors [:observation/invalid-source-threshold]
       :observations checks}
      (some #(not= genesis-root (:network/genesis-root %)) observations)
      {:status :invalid :valid? false :errors [:observation/wrong-network]
       :observations checks}
      (some #(= :invalid (:status %)) checks)
      {:status :invalid :valid? false :errors (vec (mapcat :errors (filter #(= :invalid (:status %)) checks)))
       :observations checks}
      (or (= :conflicting (:status certified-head)) conflict?)
      {:status :conflicting :valid? false :errors [:observation/conflicting-heads]
       :observations checks}
      (or (not all-known?)
          (< (count valid-checks) threshold)
          (< (count ids) (count (distinct ids)))
          (< (count groups) threshold)
          (not= 1 (count roots))
          (not= head-root (first roots))
          (not (every? #(= head-sequence (:checkpoint/sequence %)) valid-checks)))
      {:status :stale-or-unknown :valid? false
       :errors (vec (distinct (concat [:observation/insufficient-current-evidence]
                                      (mapcat :errors (remove :valid? checks)))))
       :observations checks}
      :else {:status :current-observed :valid? true :errors []
             :checkpoint/root head-root :checkpoint/sequence head-sequence
             :source-count (count valid-checks) :observations checks})))

(defn- fail [status error & [data]]
  (cond-> {:status status :valid? false :errors [error]}
    data (assoc :error-data data)))

(defn- valid-checkpoint-shape? [checkpoint]
  (try
    (and (exact-fields? checkpoint checkpoint-fields)
         (= checkpoint-schema (:checkpoint/schema checkpoint))
         (valid-root? (:network/genesis-root checkpoint))
         (nonnegative-int? (:checkpoint/epoch checkpoint))
         (nonnegative-int? (:checkpoint/resulting-epoch checkpoint))
         (or (= (:checkpoint/epoch checkpoint) (:checkpoint/resulting-epoch checkpoint))
             (= (inc (:checkpoint/epoch checkpoint)) (:checkpoint/resulting-epoch checkpoint)))
         (nonnegative-int? (:checkpoint/sequence checkpoint))
         (or (nil? (:checkpoint/previous-root checkpoint))
             (valid-root? (:checkpoint/previous-root checkpoint)))
         (valid-root? (:checkpoint/accepted-event-manifest-root checkpoint))
         (nonnegative-int? (:checkpoint/accepted-event-count checkpoint))
         (valid-root? (:checkpoint/dag-state-root checkpoint))
         (sorted-unique-roots? (:checkpoint/frontier-roots checkpoint))
         (valid-root? (:checkpoint/governing-policy-root checkpoint))
         (valid-root? (:checkpoint/governing-membership-root checkpoint))
         (valid-root? (:checkpoint/resulting-policy-root checkpoint))
         (valid-root? (:checkpoint/resulting-membership-root checkpoint))
         (string? (:checkpoint/proposer-id checkpoint))
         (seq (:checkpoint/proposer-id checkpoint))
         (or (nil? (:checkpoint/claimed-time checkpoint))
             (string? (:checkpoint/claimed-time checkpoint)))
         (= (:checkpoint/root checkpoint) (checkpoint-root checkpoint)))
    (catch Exception _ false)))

(defn- ordered-events [events prior-event-roots]
  (let [events (vec events)
        roots (mapv :event/root events)
        by-root (into {} (map (juxt :event/root identity) events))
        roots-set (set roots)
        prior (set prior-event-roots)]
    (when (or (not= (count roots) (count roots-set))
              (not-every? valid-root? roots)
              (some #(not (and (map? %) (vector? (:event/depends-on %)))) events))
      (throw (ex-info "Malformed or duplicate checkpoint event" {:reason :manifest/invalid-event})))
    (doseq [event events
            :let [depends-on (:event/depends-on event)]]
      (when-not (every? #(or (contains? roots-set %) (contains? prior %)) depends-on)
        (throw (ex-info "Checkpoint event has an unresolved dependency"
                        {:reason :manifest/dangling-dependency
                         :event/root (:event/root event)}))))
    (loop [remaining roots-set emitted []]
      (if (empty? remaining)
        (mapv by-root emitted)
        (let [ready (->> remaining
                         (filter (fn [r]
                                   (every? #(or (contains? prior %)
                                                (not (contains? remaining %)))
                                           (:event/depends-on (get by-root r)))))
                         sort vec)]
          (when (empty? ready)
            (throw (ex-info "Checkpoint event manifest contains a dependency cycle"
                            {:reason :manifest/cycle :roots (vec (sort remaining))})))
          (recur (apply disj remaining ready) (into emitted ready)))))))

(defn event-manifest-root [event-roots]
  (root manifest-domain (vec (sort event-roots))))

(defn- invoke-check [f & args]
  (try
    (let [result (apply f args)]
      (if (map? result) result {:valid? false :reason :adapter/non-map-result}))
    (catch Exception e
      {:valid? false :reason :adapter/exception :message (.getMessage e)})))

(defn verify-checkpoint-transition
  "Verify a checkpoint against a trusted predecessor and independently supplied
   policy/membership context.

   Required context keys:
   `:network/genesis-root`, `:predecessor` (trusted checkpoint/state),
   `:policy` and `:membership` resolved from predecessor roots, `:events`,
   `:verify-event` callback, `:verify-completeness` callback, and `:replay`
   callback. The callbacks are trusted verifier code, not packet-provided
   functions. Replay returns the independently derived `:dag-state-root`,
   `:frontier-roots`, resulting authority roots/epoch and state.

   Returns :locally-valid when canonical reconstruction succeeds, or
   :stale-or-unknown with typed errors. It does not establish certification or
   global latestness."
  [checkpoint {:keys [network/genesis-root predecessor policy membership events
                      verify-event verify-completeness replay]}]
  (let [bad (fn [error] (fail :invalid error))
        unknown (fn [error] (fail :stale-or-unknown error))]
    (cond
      (not (valid-checkpoint-shape? checkpoint)) (bad :checkpoint/invalid-canonical-shape-or-root)
      (not= genesis-root (:network/genesis-root checkpoint)) (bad :checkpoint/wrong-network-genesis)
      (not (and (map? predecessor) (valid-root? (:checkpoint/root predecessor))))
      (unknown :checkpoint/missing-trusted-predecessor)
      (not= (:checkpoint/previous-root checkpoint) (:checkpoint/root predecessor))
      (bad :checkpoint/predecessor-root-mismatch)
      (not= (inc (:checkpoint/sequence predecessor)) (:checkpoint/sequence checkpoint))
      (bad :checkpoint/nonconsecutive-sequence)
      (not= (:checkpoint/resulting-epoch predecessor) (:checkpoint/epoch checkpoint))
      (bad :checkpoint/epoch-not-authorized-by-predecessor)
      (not= (:checkpoint/resulting-policy-root predecessor)
            (:checkpoint/governing-policy-root checkpoint))
      (bad :checkpoint/policy-not-authorized-by-predecessor)
      (not= (:checkpoint/resulting-membership-root predecessor)
            (:checkpoint/governing-membership-root checkpoint))
      (bad :checkpoint/membership-not-authorized-by-predecessor)
      (not= (:checkpoint/governing-policy-root checkpoint) (:policy/root policy))
      (bad :checkpoint/untrusted-governing-policy)
      (not= (:checkpoint/governing-membership-root checkpoint) (:membership/root membership))
      (bad :checkpoint/untrusted-governing-membership)
      (not (and (fn? verify-event) (fn? verify-completeness) (fn? replay)))
      (unknown :checkpoint/missing-trusted-verifier-adapter)
      :else
      (try
        (let [ordered (ordered-events events (:state/accepted-event-roots predecessor #{}))
              event-roots (mapv :event/root ordered)
              manifest-check (invoke-check verify-completeness predecessor event-roots policy)
              event-checks (mapv #(invoke-check verify-event %) ordered)
              authority-change? (or (not= (:checkpoint/governing-policy-root checkpoint)
                                          (:checkpoint/resulting-policy-root checkpoint))
                                    (not= (:checkpoint/governing-membership-root checkpoint)
                                          (:checkpoint/resulting-membership-root checkpoint))
                                    (not= (:checkpoint/epoch checkpoint)
                                          (:checkpoint/resulting-epoch checkpoint)))
              invalid-transition-epoch?
              (and (or (not= (:checkpoint/governing-policy-root checkpoint)
                             (:checkpoint/resulting-policy-root checkpoint))
                       (not= (:checkpoint/governing-membership-root checkpoint)
                             (:checkpoint/resulting-membership-root checkpoint)))
                   (not= (inc (:checkpoint/epoch checkpoint))
                         (:checkpoint/resulting-epoch checkpoint)))
              authorized-authority-transition?
              (some #(and (:valid? %) (:authority-transition? %)
                          (:transition/authorized? %)) event-checks)
              replay-result (when (and (:valid? manifest-check)
                                       (every? :valid? event-checks))
                              (invoke-check replay (:state predecessor) ordered policy membership))
              errors (cond-> []
                       (or (not= (:checkpoint/accepted-event-count checkpoint) (count event-roots))
                           (not= (:checkpoint/accepted-event-manifest-root checkpoint)
                                 (event-manifest-root event-roots)))
                       (conj :checkpoint/manifest-binding-mismatch)
                       (not (:valid? manifest-check))
                       (conj (or (:reason manifest-check) :checkpoint/manifest-incomplete))
                       (some (comp not :valid?) event-checks)
                       (conj :checkpoint/invalid-event)
                       (some (comp not :valid?) event-checks)
                       (into (map #(or (:reason %) :event/invalid)
                                  (filter (comp not :valid?) event-checks)))
                       (and authority-change? (not authorized-authority-transition?))
                       (conj :checkpoint/unauthorized-authority-transition)
                       invalid-transition-epoch?
                       (conj :checkpoint/invalid-authority-transition-epoch)
                       (and replay-result (not (:valid? replay-result)))
                       (conj (or (:reason replay-result) :checkpoint/replay-failed))
                       (and (:valid? replay-result)
                            (not= (:checkpoint/dag-state-root checkpoint)
                                  (:dag-state-root replay-result)))
                       (conj :checkpoint/state-root-mismatch)
                       (and (:valid? replay-result)
                            (not= (:checkpoint/frontier-roots checkpoint)
                                  (vec (sort (:frontier-roots replay-result)))))
                       (conj :checkpoint/frontier-mismatch)
                       (and (:valid? replay-result)
                            (not= (:checkpoint/resulting-policy-root checkpoint)
                                  (:resulting-policy-root replay-result)))
                       (conj :checkpoint/resulting-policy-root-mismatch)
                       (and (:valid? replay-result)
                            (not= (:checkpoint/resulting-membership-root checkpoint)
                                  (:resulting-membership-root replay-result)))
                       (conj :checkpoint/resulting-membership-root-mismatch)
                       (and (:valid? replay-result)
                            (not= (:checkpoint/resulting-epoch checkpoint)
                                  (:resulting-epoch replay-result)))
                       (conj :checkpoint/resulting-epoch-mismatch))]
          (if (seq errors)
            {:status (if (some #{:checkpoint/manifest-binding-mismatch
                                 :checkpoint/invalid-event
                                 :checkpoint/unauthorized-authority-transition
                                 :checkpoint/invalid-authority-transition-epoch
                                 :checkpoint/state-root-mismatch
                                 :checkpoint/frontier-mismatch
                                 :checkpoint/resulting-policy-root-mismatch
                                 :checkpoint/resulting-membership-root-mismatch
                                 :checkpoint/resulting-epoch-mismatch}
                               errors)
                       :invalid :stale-or-unknown)
             :valid? false :errors errors
             :checkpoint/root (:checkpoint/root checkpoint)
             :manifest-check manifest-check
             :event-checks event-checks}
            {:status :locally-valid :valid? true :errors []
             :checkpoint/root (:checkpoint/root checkpoint)
             :checkpoint/sequence (:checkpoint/sequence checkpoint)
             :state replay-result :manifest-check manifest-check
             :event-checks event-checks}))
        (catch clojure.lang.ExceptionInfo e
          (let [reason (or (:reason (ex-data e)) :checkpoint/invalid-reconstruction-input)]
            (if (contains? #{:manifest/dangling-dependency :manifest/invalid-event
                             :manifest/cycle} reason)
              (bad reason)
              (unknown reason))))
        (catch Exception _
          (unknown :checkpoint/reconstruction-adapter-failed))))))

(defn- key-active-at? [member epoch]
  (and (= true (:member/active? member))
       (<= (:key/valid-from-epoch member 0) epoch)
       (or (nil? (:key/valid-through-epoch member))
           (< epoch (:key/valid-through-epoch member)))
       (or (nil? (:key/revoked-at-epoch member))
           (< epoch (:key/revoked-at-epoch member)))))

(defn- valid-signature-envelope? [envelope]
  (and (exact-fields? envelope #{:schema-version :payload :signature})
       (exact-fields? (:payload envelope)
                      #{:schema-version :payload-type :payload-hash :subject})
       (exact-fields? (:subject (:payload envelope)) #{:kind :hash})
       (exact-fields? (:signature envelope) #{:algorithm :key-id :value})))

(defn- verify-endorsement [checkpoint membership endorsement]
  (let [member (some #(when (and (= (:principal/id %) (:principal/id endorsement))
                                 (= (:key/id %) (:key/id endorsement))) %) (:membership/members membership))
        envelope (:signature endorsement)
        public-key (when member (try (signing/parse-public-key-content (:key/public-key member))
                                     (catch Exception _ nil)))
        verified (when public-key
                   (signatures/verify-envelope* signature-profile envelope public-key))]
    (cond
      (nil? member) {:valid? false :reason :certificate/unknown-principal-or-key}
      (not (key-active-at? member (:checkpoint/epoch checkpoint)))
      {:valid? false :reason :certificate/ineligible-or-revoked-key}
      (not (valid-signature-envelope? envelope))
      {:valid? false :reason :certificate/invalid-envelope-shape}
      (not= (:key/id endorsement) (get-in envelope [:signature :key-id]))
      {:valid? false :reason :certificate/key-id-mismatch}
      (not (and (:valid? verified)
                (= (:checkpoint/root checkpoint) (get-in envelope [:payload :payload-hash]))
                (= checkpoint-schema (get-in envelope [:payload :subject :kind]))))
      {:valid? false :reason :certificate/invalid-signature}
      :else {:valid? true :member member})))

(defn verify-certificate
  "Verify exact checkpoint endorsements against trusted policy and membership.
   Policy input is a resolved, locally trusted map containing `:policy/root`,
   `:policy/threshold`, optional `:policy/role-thresholds`, and optional
   `:policy/fault-bound`. Membership input contains `:membership/root` and
   enrolled `:membership/members` with principal/key IDs, public key content,
   roles, active state and key validity epochs."
  [checkpoint certificate policy membership]
  (let [bad (fn [error] {:status :invalid :valid? false :errors [error]})]
    (cond
      (not (exact-fields? certificate certificate-fields)) (bad :certificate/invalid-shape)
      (not= certificate-schema (:certificate/schema certificate)) (bad :certificate/unsupported-schema)
      (not (try (= (:certificate/root certificate) (certificate-root certificate))
                (catch Exception _ false))) (bad :certificate/root-mismatch)
      (not= (:network/genesis-root checkpoint) (:network/genesis-root certificate)) (bad :certificate/wrong-network)
      (not= (:checkpoint/root checkpoint) (:checkpoint/root certificate)) (bad :certificate/checkpoint-root-mismatch)
      (not= (:checkpoint/sequence checkpoint) (:checkpoint/sequence certificate)) (bad :certificate/sequence-mismatch)
      (not= (:checkpoint/epoch checkpoint) (:checkpoint/epoch certificate)) (bad :certificate/epoch-mismatch)
      (not= (:checkpoint/governing-policy-root checkpoint) (:policy/root certificate)) (bad :certificate/policy-binding-mismatch)
      (not= (:checkpoint/governing-membership-root checkpoint) (:membership/root certificate)) (bad :certificate/membership-binding-mismatch)
      (not= (:policy/root policy) (:policy/root certificate)) (bad :certificate/untrusted-policy)
      (not= (:membership/root membership) (:membership/root certificate)) (bad :certificate/untrusted-membership)
      (not (and (integer? (:policy/threshold policy)) (pos? (:policy/threshold policy))))
      (bad :certificate/invalid-trusted-threshold)
      (not (and (map? membership) (vector? (:membership/members membership))
                (every? #(and (map? %) (string? (:principal/id %)) (seq (:principal/id %))
                              (string? (:key/id %)) (seq (:key/id %))
                              (string? (:key/public-key %)) (boolean? (:member/active? %))
                              (or (nil? (:key/valid-through-epoch %))
                                  (nonnegative-int? (:key/valid-through-epoch %)))
                              (or (nil? (:key/revoked-at-epoch %))
                                  (nonnegative-int? (:key/revoked-at-epoch %)))
                              (or (nil? (:member/roles %)) (vector? (:member/roles %))
                                  (set? (:member/roles %))))
                        (:membership/members membership))
                (= (count (:membership/members membership))
                   (count (distinct (map :principal/id (:membership/members membership))))
                   (count (distinct (map :key/id (:membership/members membership)))))))
      (bad :certificate/invalid-trusted-membership)
      (> (:policy/threshold policy) (count (:membership/members membership)))
      (bad :certificate/threshold-exceeds-membership)
      (not (vector? (:certificate/endorsements certificate))) (bad :certificate/endorsements-not-vector)
      :else
      (let [endorsements (:certificate/endorsements certificate)
            principals (mapv :principal/id endorsements)
            keys (mapv :key/id endorsements)
            checks (mapv #(verify-endorsement checkpoint membership %) endorsements)
            valid-checks (filter :valid? checks)
            valid-members (map :member valid-checks)
            count-valid (count valid-members)
            role-failures (->> (:policy/role-thresholds policy {})
                               (keep (fn [[role n]]
                                       (when (< (count (filter #(contains? (set (:member/roles %)) role)
                                                               valid-members)) n)
                                         role))) vec)
            n (count (:membership/members membership))
            q (:policy/threshold policy)
            f (:policy/fault-bound policy)
            intersection-safe? (and (integer? f) (not (neg? f)) (> (- (* 2 q) n) f))
            errors (cond-> []
                     (some #(not (exact-fields? % endorsement-fields)) endorsements)
                     (conj :certificate/invalid-endorsement-shape)
                     (not= endorsements (vec (sort-by (juxt :principal/id :key/id) endorsements)))
                     (conj :certificate/endorsements-not-canonical-order)
                     (or (some nil? principals) (some nil? keys)
                         (not= (count principals) (count (distinct principals))))
                     (conj :certificate/duplicate-or-missing-principal)
                     (not= (count keys) (count (distinct keys)))
                     (conj :certificate/duplicate-key)
                     (some (comp not :valid?) checks)
                     (into (map #(or (:reason %) :certificate/invalid-endorsement)
                                (filter (comp not :valid?) checks)))
                     (< count-valid q) (conj :certificate/threshold-not-met)
                     (seq role-failures) (conj :certificate/role-threshold-not-met)
                     (and (:policy/require-intersection-safety? policy)
                          (not intersection-safe?))
                     (conj :certificate/quorum-safety-bound-not-met))]
        (if (seq errors)
          {:status :invalid :valid? false :errors errors
           :eligible-signer-count count-valid :required-threshold q
           :certificate/root (:certificate/root certificate)}
          {:status :certified :valid? true :errors []
           :eligible-signer-count count-valid :required-threshold q
           :certificate/root (:certificate/root certificate)
           :quorum-safety {:intersection-bound-satisfied? (boolean intersection-safe?)
                           :fault-bound f
                           :assumption :non-faulty-members-durably-refuse-conflicting-successors
                           :interpretation (if intersection-safe? :configured-bound-satisfied
                                               :threshold-only-no-byzantine-finality)}})))))

(defn verify-checkpoint
  "Verify local transition and, when supplied, policy-bound certificate."
  [checkpoint certificate context]
  (let [local (verify-checkpoint-transition checkpoint context)]
    (if-not (:valid? local)
      local
      (if (nil? certificate)
        local
        (let [result (verify-certificate checkpoint certificate (:policy context) (:membership context))]
          (assoc result
                 :checkpoint/root (:checkpoint/root checkpoint)
                 :local-verification local))))))

(defn verify-history
  "Replay a linear checkpoint history from an independently pinned genesis.

   `trusted-genesis` is a complete predecessor context. `entries` are ordered
   maps containing `:checkpoint`, optional `:certificate`, and `:events`.
   `resolve-authority` resolves the checkpoint's governing policy/membership
   from trusted transition history; all other adapter functions are trusted
   verifier code supplied in `adapters`."
  [trusted-genesis entries {:keys [resolve-authority] :as adapters}]
  (let [valid-anchor? (and (map? trusted-genesis)
                           (valid-root? (:network/genesis-root trusted-genesis))
                           (valid-root? (:checkpoint/root trusted-genesis))
                           (nonnegative-int? (:checkpoint/sequence trusted-genesis))
                           (nonnegative-int? (:checkpoint/resulting-epoch trusted-genesis))
                           (valid-root? (:checkpoint/resulting-policy-root trusted-genesis))
                           (valid-root? (:checkpoint/resulting-membership-root trusted-genesis)))]
    (cond
      (not valid-anchor?) (fail :stale-or-unknown :checkpoint/missing-trusted-genesis)
      (not (sequential? entries)) (fail :invalid :checkpoint/history-entries-not-sequential)
      :else
      (loop [predecessor trusted-genesis
             remaining (seq entries)
             results []]
        (if-not remaining
          {:status (if (and (seq results) (every? #(= :certified (:status %)) results))
                     :certified :locally-valid)
           :valid? true :checkpoints results :head predecessor}
          (let [{:keys [checkpoint certificate events]} (first remaining)]
            (if (and (some? events) (not (sequential? events)))
              (assoc (fail :invalid :checkpoint/events-not-sequential)
                     :checkpoints results)
              (let [authority (when (fn? resolve-authority)
                                (resolve-authority (:checkpoint/resulting-policy-root predecessor)
                                                   (:checkpoint/resulting-membership-root predecessor)
                                                   (:checkpoint/resulting-epoch predecessor)))
                    context (merge adapters authority
                                   {:network/genesis-root (:network/genesis-root trusted-genesis)
                                    :predecessor predecessor
                                    :events (vec events)})
                    result (verify-checkpoint checkpoint certificate context)]
                (if-not (:valid? result)
                  (assoc result :checkpoints results)
                  (let [local (or (:local-verification result) result)
                        state (:state local)
                        next-predecessor
                        {:network/genesis-root (:network/genesis-root checkpoint)
                         :checkpoint/root (:checkpoint/root checkpoint)
                         :checkpoint/previous-root (:checkpoint/previous-root checkpoint)
                         :checkpoint/sequence (:checkpoint/sequence checkpoint)
                         :checkpoint/epoch (:checkpoint/epoch checkpoint)
                         :checkpoint/resulting-epoch (:checkpoint/resulting-epoch checkpoint)
                         :checkpoint/resulting-policy-root (:checkpoint/resulting-policy-root checkpoint)
                         :checkpoint/resulting-membership-root (:checkpoint/resulting-membership-root checkpoint)
                         :state (:state state)
                         :state/accepted-event-roots
                         (into (set (:state/accepted-event-roots predecessor))
                               (map :event/root events))}
                        result (assoc result
                                      :status (:status result)
                                      :checkpoint/sequence (:checkpoint/sequence checkpoint)
                                      :checkpoint/previous-root (:checkpoint/previous-root checkpoint))]
                    (recur next-predecessor (next remaining) (conj results result))))))))))))

(defn detect-conflicts
  "Retain every incompatible certified successor sharing one parent scope.
   Inputs are already independently verified checkpoint/certificate packages."
  [verified-packages]
  (let [certified (filter #(= :certified (get-in % [:verification :status])) verified-packages)
        by-parent (group-by (fn [{:keys [checkpoint]}]
                              [(:network/genesis-root checkpoint)
                               (:checkpoint/sequence checkpoint)
                               (:checkpoint/previous-root checkpoint)]) certified)
        conflicts (->> by-parent
                       vals
                       (filter #(> (count (set (map (comp :checkpoint/root :checkpoint) %))) 1))
                       (mapv vec))]
    (if (seq conflicts)
      {:status :conflicting :valid? false
       :conflict/evidence (vec (mapcat identity conflicts))
       :conflict/count (count conflicts)}
      {:status :no-conflict-observed :valid? true :conflict/evidence []})))

(defn- lock-scope-key [checkpoint]
  ;; This is an operational store key, not a canonical protocol artifact.
  (pr-str [(:network/genesis-root checkpoint)
           (:checkpoint/epoch checkpoint)
           (:checkpoint/sequence checkpoint)
           (:checkpoint/previous-root checkpoint)
           (:checkpoint/governing-policy-root checkpoint)
           (:checkpoint/governing-membership-root checkpoint)]))

(defn- read-store [path principal-id key-id]
  (if (Files/exists path (make-array LinkOption 0))
    (let [value (edn/read-string (slurp (.toFile path)))]
      (when-not (and (exact-fields? value #{:store/schema :store/principal-id
                                            :store/key-id :store/locks})
                     (= store-schema (:store/schema value))
                     (= principal-id (:store/principal-id value))
                     (= key-id (:store/key-id value))
                     (map? (:store/locks value))
                     (every? #(and (exact-fields? % #{:checkpoint/root :signature})
                                   (valid-root? (:checkpoint/root %)))
                             (vals (:store/locks value))))
        (throw (ex-info "Witness lock store is malformed or bound to another key"
                        {:reason :witness/invalid-lock-store})))
      value)
    {:store/schema store-schema :store/principal-id principal-id
     :store/key-id key-id :store/locks {}}))

(defn- sync-directory! [^Path directory]
  (with-open [channel (FileChannel/open directory (into-array OpenOption [StandardOpenOption/READ]))]
    (.force channel true)))

(defn- atomic-write-store! [^Path path store]
  (let [parent (.getParent path)
        _ (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
        temp (Files/createTempFile parent ".checkpoint-lock-" ".tmp"
                                   (into-array java.nio.file.attribute.FileAttribute []))
        bytes (.getBytes (str (pr-str store) "\n") StandardCharsets/UTF_8)]
    (try
      (Files/setPosixFilePermissions temp lock-file-permissions)
      (with-open [channel (FileChannel/open temp (into-array OpenOption [StandardOpenOption/WRITE
                                                                         StandardOpenOption/TRUNCATE_EXISTING]))]
        (let [buffer (ByteBuffer/wrap bytes)]
          (while (.hasRemaining buffer)
            (.write channel buffer)))
        (.force channel true))
      (Files/move temp path (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                    StandardCopyOption/REPLACE_EXISTING]))
      (sync-directory! parent)
      (catch Exception e
        (Files/deleteIfExists temp)
        (throw (ex-info "Could not durably persist witness lock; no signature returned"
                        {:reason :witness/durable-lock-write-failed}
                        e))))))

(defn endorse-checkpoint!
  "Durably lock and sign one checkpoint for the local witness scope.

   `signer` contains `:principal/id`, `:key/id`, `:private-key`, `:public-key`,
   and `:store-path`. The full local transition context is required so a witness
   never signs a candidate it cannot independently reconstruct. The lock is
   written atomically and forced to disk before the signature is returned."
  [checkpoint context signer]
  (let [principal-id (:principal/id signer)
        key-id (:key/id signer)
        private-key (:private-key signer)
        public-key (:public-key signer)
        store-path (:store-path signer)
        verification (verify-checkpoint-transition checkpoint context)]
    (when-not (:valid? verification)
      (throw (ex-info "Witness refuses to sign an invalid checkpoint"
                      {:reason :witness/checkpoint-not-locally-valid
                       :verification verification})))
    (let [member (some #(when (and (= principal-id (:principal/id %))
                                   (= key-id (:key/id %))) %)
                       (get-in context [:membership :membership/members]))
          epoch (:checkpoint/epoch checkpoint)]
      (when-not (and member (key-active-at? member epoch))
        (throw (ex-info "Witness is not eligible under the checkpoint membership"
                        {:reason :witness/not-eligible-at-checkpoint-epoch})))
      (when-not (and private-key public-key store-path)
        (throw (ex-info "Witness signing key and durable store are required"
                        {:reason :witness/missing-local-signing-material})))
      (let [envelope (signatures/build-envelope* signature-profile
                                                 (:checkpoint/root checkpoint) key-id private-key)
            checked (signatures/verify-envelope* signature-profile envelope public-key)
            _ (when-not (:valid? checked)
                (throw (ex-info "Local key pair failed self-verification"
                                {:reason :witness/local-signature-self-check-failed})))
            path (.toAbsolutePath (Paths/get (str store-path) (make-array String 0)))
            parent (.getParent path)
            lock-path (.resolveSibling path (str (.getFileName path) ".mutex"))
            lock-key (lock-scope-key checkpoint)]
        (try
          (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
          (with-open [lock-channel (FileChannel/open lock-path
                                                     (into-array OpenOption [StandardOpenOption/CREATE
                                                                             StandardOpenOption/WRITE]))
                      _file-lock (.lock lock-channel)]
            (Files/setPosixFilePermissions lock-path lock-file-permissions)
            (let [store (read-store path principal-id key-id)
                  prior (get-in store [:store/locks lock-key])]
              (when (and prior (not= (:checkpoint/root prior) (:checkpoint/root checkpoint)))
                (throw (ex-info "Witness already endorsed a conflicting successor for this scope"
                                {:reason :witness/equivocation-lock
                                 :locked-root (:checkpoint/root prior)
                                 :requested-root (:checkpoint/root checkpoint)})))
              (if prior
                (do
                  (when-not (and (= key-id (get-in prior [:signature :signature :key-id]))
                                 (= (:checkpoint/root checkpoint)
                                    (get-in prior [:signature :payload :payload-hash]))
                                 (valid-signature-envelope? (:signature prior))
                                 (:valid? (signatures/verify-envelope* signature-profile
                                                                       (:signature prior) public-key)))
                    (throw (ex-info "Persisted witness signature failed verification"
                                    {:reason :witness/persisted-signature-invalid})))
                  {:principal/id principal-id :key/id key-id :signature (:signature prior)
                   :checkpoint/root (:checkpoint/root checkpoint) :reused? true})
                (let [next-store (assoc-in store [:store/locks lock-key]
                                           {:checkpoint/root (:checkpoint/root checkpoint)
                                            :signature envelope})]
                  (atomic-write-store! path next-store)
                  {:principal/id principal-id :key/id key-id :signature envelope
                   :checkpoint/root (:checkpoint/root checkpoint) :reused? false}))))
          (catch clojure.lang.ExceptionInfo e
            (if (= :witness/durable-lock-write-failed (:reason (ex-data e)))
              (throw e)
              (throw e)))
          (catch Exception e
            (throw (ex-info "Could not acquire or persist witness lock; no signature returned"
                            {:reason :witness/durable-lock-write-failed}
                            e))))))))
