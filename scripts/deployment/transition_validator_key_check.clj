(ns scripts.deployment.transition-validator-key-check
  "Read-only check that an external Foundry keystore controls the validator
  address pinned by a bounded local-authority policy. This does not sign or
  approve a transition."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.lang ProcessBuilder$Redirect]
           [java.util UUID]))

(defn- canonical-uuid [value]
  (when (string? value)
    (try
      (str/lower-case (str (UUID/fromString value)))
      (catch IllegalArgumentException _ nil))))

(defn- transition-policy [value]
  (let [candidate (or (:local-authority-policy value) value)]
    (or (:local-authority-policy candidate) candidate)))

(defn- normalize-address [value]
  (when (and (string? value) (re-matches #"(?i)0x[0-9a-f]{40}" value))
    (str/lower-case value)))

(defn evaluate-binding
  "Pure comparison used by the CLI and tests. UUIDs identify keystore files;
  the policy key ID is an independent semantic label."
  [{:keys [policy-key-id policy-address keystore-id expected-keystore-id
           keystore-address derived-address]}]
  (let [keystore-uuid (canonical-uuid keystore-id)
        expected-uuid (canonical-uuid expected-keystore-id)
        policy-address (normalize-address policy-address)
        keystore-address (when keystore-address
                           (normalize-address (str "0x" (str/replace keystore-address #"(?i)^0x" ""))))
        derived-address (normalize-address derived-address)
        checks {:keystore-id-valid (some? keystore-uuid)
                :expected-keystore-id-valid (some? expected-uuid)
                :keystore-id-matches-expected (and keystore-uuid expected-uuid
                                                   (= keystore-uuid expected-uuid))
                :policy-address-valid (some? policy-address)
                :derived-address-valid (some? derived-address)
                :derived-address-matches-policy (and policy-address derived-address
                                                     (= policy-address derived-address))
                :keystore-address-matches-derived (or (nil? keystore-address)
                                                      (and derived-address
                                                           (= keystore-address derived-address)))}
        passed? (every? true? (vals checks))]
    {:schema "prf-transition-validator-keystore-check.v1"
     :status (if passed? "PASS" "FAIL")
     :policyKeyId policy-key-id
     :policyAddress policy-address
     :keystoreId keystore-uuid
     :expectedKeystoreId expected-uuid
     :keystoreAddress keystore-address
     :derivedAddress derived-address
     :checks checks
     :privateKeyPrinted false
     :signatureCreated false
     :interpretation "A matching address proves this keystore controls the policy-pinned EVM address. The keystore UUID and policy key ID are separate identifiers; this check does not create an approval or attest to external custody."}))

(defn- read-policy [path]
  (let [value (edn/read-string (slurp (io/file path)))
        policy (transition-policy value)
        key-id (:authority/transition-validator-key-id policy)
        address (:authority/transition-validator-address policy)]
    (when-not (and (string? key-id) (normalize-address address))
      (throw (ex-info "policy file lacks a valid transition-validator key ID/address" {:path path})))
    {:policy-key-id key-id :policy-address address}))

(defn- read-keystore [path]
  (let [file (io/file path)]
    (when-not (.isFile file)
      (throw (ex-info "keystore path must identify a regular JSON keystore file" {:path path})))
    (let [data (json/read-str (slurp file))
          keystore-id (get data "id")
          keystore-address (get data "address")]
      (when-not (and (canonical-uuid keystore-id)
                     (or (nil? keystore-address)
                         (normalize-address (str "0x" (str/replace keystore-address #"(?i)^0x" "")))))
        (throw (ex-info "keystore JSON must contain a UUID id and optional valid address" {:path path})))
      {:keystore-id keystore-id :keystore-address keystore-address})))

(defn- cast-address [keystore-path password-file]
  (when (and (nil? password-file) (nil? (System/console)))
    (throw (ex-info "no console is available; provide a protected external password file" {})))
  (let [args (cond-> ["cast" "wallet" "address" "--keystore" (.getAbsolutePath (io/file keystore-path))]
               password-file (into ["--password-file" (.getAbsolutePath (io/file password-file))]))
        builder (ProcessBuilder. ^java.util.List args)]
    (.redirectInput builder ProcessBuilder$Redirect/INHERIT)
    (.redirectError builder ProcessBuilder$Redirect/INHERIT)
    (let [process (.start builder)
          output (slurp (.getInputStream process))
          exit-code (.waitFor process)
          addresses (re-seq #"(?i)0x[0-9a-f]{40}" output)]
      (when-not (zero? exit-code)
        (throw (ex-info "cast could not derive the keystore address" {:exit-code exit-code})))
      (when-not (= 1 (count addresses))
        (throw (ex-info "cast returned an unexpected address result" {:output-line-count (count (str/split-lines output))})))
      (first addresses))))

(defn check-files
  "Return a machine-readable check result. Never emits a signature or private
  key; Cast decrypts the keystore only in its own process to derive the address."
  [policy-path keystore-path expected-keystore-id password-file]
  (let [policy (read-policy policy-path)
        keystore (read-keystore keystore-path)
        derived-address (cast-address keystore-path password-file)]
    (evaluate-binding (merge policy keystore
                             {:expected-keystore-id expected-keystore-id
                              :derived-address derived-address}))))

(defn -main [& args]
  (let [[policy-path keystore-path expected-keystore-id password-file & extra] args]
    (when (or (nil? policy-path) (nil? keystore-path) (nil? expected-keystore-id) (seq extra))
      (binding [*out* *err*]
        (println "Usage: clojure -M:test -m scripts.deployment.transition-validator-key-check <policy-package.edn> <encrypted-keystore.json> <expected-keystore-uuid> [password-file]"))
      (System/exit 2))
    (try
      (let [result (check-files policy-path keystore-path expected-keystore-id password-file)]
        (println (json/write-str result))
        (when (= "FAIL" (:status result)) (System/exit 1)))
      (catch Exception error
        (println (json/write-str {:schema "prf-transition-validator-keystore-check.v1"
                                  :status "ERROR"
                                  :error (.getMessage error)
                                  :privateKeyPrinted false
                                  :signatureCreated false}))
        (System/exit 1)))))
