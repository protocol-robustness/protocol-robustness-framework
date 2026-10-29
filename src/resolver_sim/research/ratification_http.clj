(ns resolver-sim.research.ratification-http
  "Loopback-only HTTP adapter for the PRF research-ratification demo.

   This server is deliberately fixture-backed. It accepts external signatures,
   delegates all contribution and ratification semantics to the existing PRF
   implementation, and does not supply production identity or policy approval."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.benchmark.researcher-force-authorisation :as decisions]
            [resolver-sim.research.ratification :as ratification])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetAddress InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.util.concurrent Executors]))

(defn- fail! [status code message]
  (throw (ex-info message {:http/status status :api/code code})))

(defn- sha256-root? [value]
  (and (string? value) (boolean (re-matches #"sha256:[0-9a-f]{64}" value))))

(defn- keyword-view [value]
  (if (keyword? value)
    (if-let [space (namespace value)] (str space "/" (name value)) (name value))
    (str value)))

(defn- resolve-path [base path]
  (let [file (io/file path)]
    (.getCanonicalPath (if (.isAbsolute file) file (io/file (or base ".") path)))))

(defn- build-fixture [config config-path]
  (when-not (true? (:demo-only? config))
    (fail! 500 "INVALID_POLICY" "Ratification HTTP server requires :demo-only? true"))
  (let [host (or (:host config) "127.0.0.1")
        address (InetAddress/getByName host)
        _ (when-not (.isLoopbackAddress address)
            (fail! 500 "INVALID_POLICY" "Demo server must bind to a loopback address"))
        policy (ratification/build-policy (:policy config))
        target (ratification/build-target (assoc (:target config)
                                                  :policy-root (:policy/root policy)))
        researchers (mapv (fn [researcher]
                            (update researcher :public-key-path
                                    #(resolve-path (.getParent (io/file config-path)) %)))
                          (:researchers config))
        researcher-ids (map :researcher/id researchers)
        principals (map :principal/id researchers)
        signing-key-ids (map :signing-key/id researchers)
        claim-assessment (:claim-assessment config)
        _ (when-not (and (seq researchers)
                         (= (count researchers) (count (set researcher-ids)))
                         (= (count researchers) (count (set principals)))
                         (= (count researchers) (count (set signing-key-ids)))
                         (= (set principals) (set (:policy/eligible-principals policy))))
            (fail! 500 "INVALID_POLICY"
                   "Fixture researchers must uniquely bind every policy-eligible principal"))
        token-index (into {} (map (juxt :token identity) researchers))
        _ (when (or (some #(not (and (string? (:token %)) (seq (:token %)))) researchers)
                    (not= (count researchers) (count token-index)))
            (fail! 500 "INVALID_POLICY" "Each demo researcher needs a unique nonempty bearer token"))
        _ (when-not (and (string? (:state-path config)) (seq (:state-path config)))
            (fail! 500 "INVALID_POLICY" "Fixture config requires a state-path"))
        _ (when-not (and (map? claim-assessment)
                         (contains? #{:not-established :counterexample-found}
                                    (:status claim-assessment))
                         (contains? #{:unverified :unavailable}
                                    (:method-verification claim-assessment))
                         (string? (:explanation claim-assessment))
                         (seq (:explanation claim-assessment)))
            (fail! 500 "INVALID_POLICY"
                   "Demo fixture must keep the claim unestablished until a method verifier is supplied"))
        _ (doseq [{:keys [public-key-path researcher/id]} researchers]
            (when-not (.isFile (io/file public-key-path))
              (fail! 500 "INVALID_POLICY" (str "Public key file is missing for fixture researcher " id))))
        key-bindings (into {}
                           (map (fn [researcher]
                                  (let [researcher-id (:researcher/id researcher)]
                                    [researcher-id {:researcher/id researcher-id
                                                    :principal/id (:principal/id researcher)
                                                    :public-key-path (:public-key-path researcher)}]))
                                researchers))]
    {:host host :port (long (or (:port config) 8787))
     :state-path (resolve-path (.getParent (io/file config-path)) (:state-path config))
     :target target :policy policy :researchers researchers
     :token-index token-index :key-bindings key-bindings
     :claim-assessment (assoc claim-assessment :assessment-source :demo-fixture)}))

(defn- read-store [path]
  (let [file (io/file path)]
    (if (.exists file)
      (let [value (edn/read-string (slurp file))]
        (when-not (and (map? value) (vector? (:contributions value)))
          (fail! 500 "RECONSTRUCTION_FAILED" "Persisted contribution store is malformed"))
        value)
      {:contributions []})))

(defn- atomic-store! [path value]
  (let [target (java.nio.file.Paths/get path (make-array String 0))
        parent (.getParent target)
        temp (java.nio.file.Paths/get (str path ".tmp") (make-array String 0))]
    (when parent (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0)))
    (Files/write temp (.getBytes (str (pr-str value) "\n") StandardCharsets/UTF_8)
                 (into-array StandardOpenOption [StandardOpenOption/CREATE
                                                 StandardOpenOption/TRUNCATE_EXISTING
                                                 StandardOpenOption/WRITE]))
    (Files/move temp target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                            StandardCopyOption/REPLACE_EXISTING]))
    value))

(defn- reconstructed [fixture store]
  (ratification/reconstruct-ratification
   {:target (:target fixture) :policy (:policy fixture)
    :contributions (mapv :contribution (:contributions store))
    :key-bindings (:key-bindings fixture)}))

(defn- parse-kind [kind]
  (let [parsed (when (string? kind) (keyword kind))]
    (when-not (contains? ratification/contribution-kinds parsed)
      (fail! 422 "INVALID_INPUT" "Unknown contribution kind"))
    parsed))

(defn- required-evidence? [kind]
  (contains? #{:challenge :reproduction} kind))

(defn- intent [fixture researcher request]
  (let [kind (parse-kind (:kind request))
        evidence-root (:evidenceRoot request)
        dissent-reason (:dissentReason request)
        researcher-id (:researcher/id researcher)
        principal-id (:principal/id researcher)
        signing-key-id (:signing-key/id researcher)]
    (when-not (= (set (keys request))
                 (cond-> #{:kind} (contains? request :evidenceRoot) (conj :evidenceRoot)
                   (contains? request :dissentReason) (conj :dissentReason)))
      (fail! 422 "INVALID_INPUT" "Intent request contains missing or unknown fields"))
    (when (and (required-evidence? kind) (not (sha256-root? evidence-root)))
      (fail! 422 "INVALID_EVIDENCE_ROOT" "Challenge and reproduction require a sha256 evidence root"))
    (when (and (some? evidence-root) (not (sha256-root? evidence-root)))
      (fail! 422 "INVALID_EVIDENCE_ROOT" "Evidence root must be sha256:<64 lowercase hex>"))
    (when (and (not= :approve kind)
               (not (and (string? dissent-reason) (seq (str/trim dissent-reason)))))
      (fail! 422 "INVALID_INPUT" "A non-approval contribution requires dissentReason"))
    (when (and (= :approve kind) (some? dissent-reason))
      (fail! 422 "INVALID_INPUT" "An approval cannot include dissentReason"))
    (let [outcome-root (ratification/contribution-subject-root
                        {:target-root (:target/root (:target fixture))
                         :principal-id principal-id :kind kind :evidence-root evidence-root})]
      (cond-> {"targetRoot" (:target/root (:target fixture))
               "principalId" (keyword-view principal-id)
               "researcherId" researcher-id
               "signingKeyId" signing-key-id
               "kind" (name kind)
               "outcomeRoot" outcome-root
               "authorisationId" (:target/root (:target fixture))
               "requestRoot" (:target/root (:target fixture))
               "reviewRoundRoot" (:target/round-root (:target fixture))
               "decision" (if (= :approve kind) "approve" "dissent")
               "dissentReasonRequired" (not= :approve kind)}
        evidence-root (assoc "evidenceRoot" evidence-root)
        dissent-reason (assoc "dissentReason" dissent-reason))))

(defn- decision-from-json [value]
  (let [signature (:signature value)
        decision-keys #{:schemaVersion :researcherId :authorisationId :requestRoot
                        :reviewRoundRoot :outcomeRoot :decision :decisionHash
                        :signingKeyId :dissentReason :signature}
        required-decision-keys (disj decision-keys :dissentReason)
        signature-keys #{:algorithm :value :signedAt}]
    (when-not (and (map? value)
                   (every? #(contains? value %) required-decision-keys)
                   (every? decision-keys (keys value))
                   (map? signature)
                   (= signature-keys (set (keys signature))))
      (fail! 422 "INVALID_INPUT" "Signed decision contains missing or unknown fields"))
    (cond-> {:schema-version (:schemaVersion value)
             :researcher/id (:researcherId value)
             :authorisation/id (:authorisationId value)
             :authorisation/request-root (:requestRoot value)
             :review-round/hash (:reviewRoundRoot value)
             :outcome/root (:outcomeRoot value)
             :decision (some-> (:decision value) keyword)
             :decision/hash (:decisionHash value)
             :signature {:algorithm (some-> (:algorithm signature) keyword)
                         :value (:value signature)
                         :signed-at (:signedAt signature)}}
      (contains? value :signingKeyId) (assoc :signing-key/id (:signingKeyId value))
      (contains? value :dissentReason) (assoc :dissent/reason (:dissentReason value)))))

(defn- decision-view [decision]
  (cond-> {"schemaVersion" (:schema-version decision)
           "researcherId" (:researcher/id decision)
           "authorisationId" (:authorisation/id decision)
           "requestRoot" (:authorisation/request-root decision)
           "reviewRoundRoot" (:review-round/hash decision)
           "outcomeRoot" (:outcome/root decision)
           "decision" (name (:decision decision))
           "decisionHash" (:decision/hash decision)
           "signature" {"algorithm" (name (get-in decision [:signature :algorithm]))
                        "value" (get-in decision [:signature :value])
                        "signedAt" (get-in decision [:signature :signed-at])}}
    (:signing-key/id decision) (assoc "signingKeyId" (:signing-key/id decision))
    (:dissent/reason decision) (assoc "dissentReason" (:dissent/reason decision))))

(defn- ratification-view [result]
  {"schemaVersion" (:schema/version result)
   "targetRoot" (:ratification/target-root result)
   "policyRoot" (:ratification/policy-root result)
   "contributionRoots" (:ratification/contribution-roots result)
   "approvalCount" (:ratification/approval-count result)
   "dissentCount" (:ratification/dissent-count result)
   "challengeRoots" (:ratification/challenge-roots result)
   "status" (name (:ratification/status result))
   "operationalAuthority" false
   "root" (:ratification/root result)
   "verification" "verified"})

(defn- target-view [target]
  (cond-> {"schemaVersion" (:schema/version target)
           "domain" (keyword-view (:target/domain target))
           "subjectRoot" (:target/subject-root target)
           "evidenceRoot" (:target/evidence-root target)
           "roundRoot" (:target/round-root target)
           "policyRoot" (:target/policy-root target)
           "targetSchema" (:target/schema target)
           "root" (:target/root target)}
    (:target/predecessor-root target)
    (assoc "predecessorRoot" (:target/predecessor-root target))))

(defn- policy-view [policy]
  {"schemaVersion" (:schema/version policy)
   "id" (keyword-view (:policy/id policy))
   "authorityRoot" (:policy/authority-root policy)
   "eligiblePrincipals" (mapv keyword-view (:policy/eligible-principals policy))
   "threshold" (:policy/threshold policy)
   "allowDissent" (:policy/allow-dissent? policy)
   "challengesBlock" (:policy/challenges-block? policy)
   "root" (:policy/root policy)})

(defn- contribution-view [entry]
  (let [contribution (:contribution entry)]
    (cond-> {"contributionRoot" (:contribution/root contribution)
             "principalId" (keyword-view (:contribution/principal-id contribution))
             "kind" (name (:contribution/kind contribution))
             "decision" (decision-view (:contribution/decision contribution))
             "signatureStatus" "verified"
             "recordedAt" (:recorded-at entry)}
      (:contribution/evidence-root contribution)
      (assoc "evidenceRoot" (:contribution/evidence-root contribution)))))

(defn- workspace-view [fixture store]
  (let [result (reconstructed fixture store)]
    {"demoOnly" true
     "policyApprovalStatus" "not-approved"
     "target" (target-view (:target fixture))
     "policy" (policy-view (:policy fixture))
     "contributions" (mapv contribution-view (:contributions store))
     "ratification" (ratification-view result)
     "claimAssessment" {"assessmentSource" "demo-fixture"
                        "status" (name (get-in fixture [:claim-assessment :status]))
                        "methodVerification" (name (get-in fixture [:claim-assessment :method-verification]))
                        "explanation" (get-in fixture [:claim-assessment :explanation])}}))

(defn- bearer-researcher [fixture ^HttpExchange exchange]
  (let [header (.getFirst (.getRequestHeaders exchange) "Authorization")
        token (when (and (string? header) (str/starts-with? header "Bearer "))
                (subs header 7))]
    (or (get (:token-index fixture) token)
        (fail! 403 "INELIGIBLE_PRINCIPAL" "Bearer token is not configured for this demo fixture"))))

(defn- json-body [^HttpExchange exchange]
  (try
    (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)
    (catch Exception _
      (fail! 422 "INVALID_INPUT" "Request body must be valid JSON"))))

(defn- append-contribution! [fixture store-atom store-lock researcher request]
  (let [kind (parse-kind (:kind request))
        decision (decision-from-json (:decision request))
        evidence-root (:evidenceRoot request)
        researcher-id (:researcher/id researcher)
        principal-id (:principal/id researcher)
        signing-key-id (:signing-key/id researcher)
        public-key-path (:public-key-path researcher)
        expected-outcome (ratification/contribution-subject-root
                          {:target-root (:target/root (:target fixture))
                           :principal-id principal-id :kind kind :evidence-root evidence-root})
        binding (get (:key-bindings fixture) researcher-id)
        signature-check (decisions/verify-signed-decision-v2 decision public-key-path)]
    (when-not (= (set (keys request)) (cond-> #{:kind :decision}
                                        (contains? request :evidenceRoot) (conj :evidenceRoot)))
      (fail! 422 "INVALID_INPUT" "Contribution contains missing or unknown fields"))
    (when (and (required-evidence? kind) (not (sha256-root? evidence-root)))
      (fail! 422 "INVALID_EVIDENCE_ROOT" "Challenge and reproduction require a sha256 evidence root"))
    (when-not (and (:valid? signature-check)
                   (= researcher-id (:researcher/id decision))
                   (= (:principal/id binding) principal-id)
                   (= signing-key-id (:signing-key/id decision))
                   (= (:target/root (:target fixture)) (:authorisation/id decision))
                   (= (:target/root (:target fixture)) (:authorisation/request-root decision))
                   (= (:target/round-root (:target fixture)) (:review-round/hash decision))
                   (= expected-outcome (:outcome/root decision))
                   (or (= :approve kind)
                       (and (string? (:dissent/reason decision))
                            (seq (str/trim (:dissent/reason decision)))))
                   (= (if (= :approve kind) :approve :dissent) (:decision decision)))
      (fail! 422 "INVALID_SIGNATURE" (or (:reason signature-check)
                                         "Signed decision does not match this contribution intent")))
    (let [contribution (try
                         (ratification/build-contribution
                          {:target (:target fixture) :principal-id principal-id
                           :decision decision :kind kind :evidence-root evidence-root})
                         (catch clojure.lang.ExceptionInfo error
                           (fail! 422 "INVALID_SIGNATURE" (.getMessage error))))]
      (locking store-lock
        (let [store @store-atom
              duplicate (some #(when (= (:researcher/id decision)
                                        (get-in % [:contribution :contribution/decision :researcher/id])) %)
                               (:contributions store))]
          (if duplicate
            (if (= (:decision/hash decision)
                   (get-in duplicate [:contribution :contribution/decision :decision/hash]))
              {:entry duplicate :ratification (ratification-view (reconstructed fixture store))
               :retry? true}
              (fail! 409 "DUPLICATE_CONTRIBUTION" "Researcher already submitted a different contribution"))
            (let [entry {:contribution contribution :recorded-at (str (java.time.Instant/now))}
                  next-store (update store :contributions conj entry)]
              (try
                (let [result (reconstructed fixture next-store)]
                  (atomic-store! (:state-path fixture) next-store)
                  (reset! store-atom next-store)
                  {:entry entry :ratification (ratification-view result) :retry? false})
                (catch clojure.lang.ExceptionInfo error
                  (fail! 409 "RECONSTRUCTION_FAILED" (.getMessage error))))))))))

(defn- respond! [^HttpExchange exchange status value]
  (let [bytes (.getBytes (json/write-str value :escape-unicode false) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "Content-Type" "application/json; charset=utf-8")
    (.sendResponseHeaders exchange status (long (alength bytes)))
    (with-open [out (.getResponseBody exchange)] (.write out bytes))))

(defn- route! [fixture store-atom store-lock ^HttpExchange exchange]
  (let [parts (->> (str/split (.getPath (.getRequestURI exchange)) #"/") (remove str/blank?) vec)
        method (.getRequestMethod exchange)
        root (nth parts 2 nil)
        suffix (nth parts 3 nil)]
    (when-not (= "v1" (first parts))
      (fail! 404 "NOT_FOUND" "Unknown API route"))
    (when-not (= "targets" (second parts))
      (fail! 404 "NOT_FOUND" "Unknown API route"))
    (when-not (= root (:target/root (:target fixture)))
      (fail! 404 "NOT_FOUND" "No immutable target exists for this root"))
    (cond
      (and (= method "GET") (= 3 (count parts)))
      (respond! exchange 200 (workspace-view fixture @store-atom))

      (and (= method "GET") (= 4 (count parts)) (= "ratification" suffix))
      (respond! exchange 200 (ratification-view (reconstructed fixture @store-atom)))

      (and (= method "POST") (= 4 (count parts)) (= "contribution-intents" suffix))
      (let [researcher (bearer-researcher fixture exchange)]
        (respond! exchange 200 (intent fixture researcher (json-body exchange))))

      (and (= method "POST") (= 4 (count parts)) (= "contributions" suffix))
      (let [researcher (bearer-researcher fixture exchange)
            result (append-contribution! fixture store-atom store-lock researcher
                                         (json-body exchange))]
        (respond! exchange 201 {"contributionRoot" (get-in result [:entry :contribution :contribution/root])
                                "targetRoot" (:target/root (:target fixture))
                                "signatureStatus" "verified"
                                "ratification" (:ratification result)
                                "idempotentRetry" (:retry? result)}))

      :else (fail! 405 "INVALID_INPUT" "Method or route is not supported"))))

(defn start!
  "Start the demo API from an explicit EDN fixture config. Defaults to loopback
   only and refuses non-loopback binds. Returns a stop handle for local use."
  [config-path]
  (let [config-file (io/file config-path)
        config (edn/read-string (slurp config-file))
        fixture (build-fixture config (.getCanonicalPath config-file))
        store-atom (atom (read-store (:state-path fixture)))
        store-lock (Object.)
        _ (reconstructed fixture @store-atom)
        server (HttpServer/create (InetSocketAddress. (:host fixture) (int (:port fixture))) 0)
        executor (Executors/newFixedThreadPool 4)]
    (.setExecutor server executor)
    (.createContext server "/v1/targets"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (try
                          (route! fixture store-atom store-lock exchange)
                          (catch clojure.lang.ExceptionInfo error
                            (respond! exchange (or (:http/status (ex-data error)) 400)
                                      {"code" (or (:api/code (ex-data error)) "INVALID_INPUT")
                                       "message" (.getMessage error)}))
                          (catch Exception _
                            (respond! exchange 500 {"code" "RECONSTRUCTION_FAILED"
                                                   "message" "Request could not be reconstructed"}))))))
    (.start server)
    {:server server :executor executor :fixture fixture
     :stop (fn [] (.stop server 0) (.shutdown executor))}))

(defn -main [& args]
  (let [config-path (first args)]
    (when-not config-path
      (throw (ex-info "Usage: clojure -M -m resolver-sim.research.ratification-http FIXTURE.edn" {})))
    (let [{:keys [host port stop fixture]} (start! config-path)]
      (println (str "PRF research-ratification demo API listening on http://" host ":" port))
      (println (str "Target root: " (get-in fixture [:target :target/root])))
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop))
      @(promise))))
