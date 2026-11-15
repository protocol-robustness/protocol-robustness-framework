(ns scripts.scenarios.gen-yield
  "Deterministic materialization and read-only verification for yield scenarios.

   Usage:
     bb yield:generate --seed 42 --count 3 --out /tmp/yield-run
     bb yield:verify --input /tmp/yield-run"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.random :as random]
            [clojure.test.check.rose-tree :as rose]
            [clojure.tools.cli :as cli]
            [resolver-sim.generators.yield.core :as ycore]
            [resolver-sim.generators.yield.events :as yevents]
            [resolver-sim.generators.yield.scenario :as yscenario]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.scenario.normalize :as normalize]
            [resolver-sim.yield.presets :as presets]
            [resolver-sim.yield.registry :as yreg]
            [resolver-sim.yield.scenario-commitment :as commitments]))

(def manifest-schema "prf.gen-yield-manifest.v1")
(def generator-id "resolver-sim.generators.yield.scenario/gen-yield-scenario")
(def generator-version "prf-gen-yield.v1")
(def rng-algorithm "test.check-1.1.1/JavaUtilSplittableRandom-split-v1")

(def ^:private profile-config-aliases
  {:yield.profile/aave-v3-like :aave-v3})

(def ^:private generation-options
  [["-p" "--profile PROFILE" "Registered yield profile"]
   [nil "--preset PRESET" "Registered yield preset"]
   [nil "--token TOKEN" "Token symbol (USDC, USDT, or DAI)"]
   [nil "--failure-mode MODE" "Failure mode; may be repeated" :default [] :multi true :update-fn conj]
   [nil "--liquidity-mode MODE" "Liquidity mode: available, shortfall, or haircut"]
   [nil "--seed INTEGER" "Signed 64-bit seed" :default 1 :parse-fn #(Long/parseLong %)]
   [nil "--count INTEGER" "Number of scenarios (1..100)" :default 1 :parse-fn #(Long/parseLong %)]
   [nil "--max-events INTEGER" "Maximum events per scenario (1..5)" :default 5 :parse-fn #(Long/parseLong %)]
   ["-o" "--out DIRECTORY" "New output directory"]])

(def ^:private verification-options
  [["-i" "--input PATH" "Manifest file or generated output directory"]])

(def ^:private source-resource-paths
  ["scripts/scenarios/gen_yield.clj"
   "resolver_sim/generators/yield/core.clj"
   "resolver_sim/generators/yield/events.clj"
   "resolver_sim/generators/yield/scenario.clj"
   "resolver_sim/yield/scenario_commitment.clj"
   "clojure/test/check/random.clj"
   "clojure/test/check/generators.cljc"
   "META-INF/maven/org.clojure/test.check/pom.properties"])

(defn- fail!
  [message data]
  (throw (ex-info message data)))

(defn- sha256
  [^bytes bytes]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and 0xff %)) digest)))))

(defn- bytes-of
  [url]
  (with-open [input (.openStream ^java.net.URL url)]
    (.readAllBytes input)))

(defn- generator-source-commitment
  []
  (let [version-url (io/resource "META-INF/maven/org.clojure/test.check/pom.properties")
        properties (java.util.Properties.)
        _ (when-not version-url
            (fail! "Cannot identify the test.check runtime dependency" {}))
        _ (with-open [input (.openStream ^java.net.URL version-url)]
            (.load properties input))
        test-check-version (.getProperty properties "version")
        _ (when-not (= "1.1.1" test-check-version)
            (fail! "Unsupported test.check version for the frozen RNG profile"
                   {:expected "1.1.1" :observed test-check-version}))
        sources (mapv (fn [path]
                        (let [url (io/resource path)]
                          (when-not url
                            (fail! "Cannot identify a required generator source resource"
                                   {:resource path}))
                          [path (sha256 (bytes-of url))]))
                      source-resource-paths)
        body {:schema "prf.gen-yield-source.v1"
              :test.check/version test-check-version
              :clojure/version (clojure-version)
              :sources sources}]
    (sha256 (.getBytes (pr-str body) java.nio.charset.StandardCharsets/UTF_8))))

(defn- named-id
  [s ids label]
  (when s
    (or (some (fn [id]
                (when (or (= s (name id)) (= s (str id))) id)) ids)
        (fail! (str "Unsupported " label) {:value s :supported (mapv str ids)}))))

(defn- parse-options!
  [{:keys [options arguments errors summary]}]
  (when (seq errors)
    (fail! (str/join "; " errors) {:errors errors}))
  (when (seq arguments)
    (fail! "Unexpected positional arguments" {:arguments arguments}))
  (when-not (and (integer? (:seed options))
                 (<= Long/MIN_VALUE (:seed options) Long/MAX_VALUE))
    (fail! "--seed must be a signed 64-bit integer" {:seed (:seed options)}))
  (when-not (<= 1 (:count options) 100)
    (fail! "--count must be between 1 and 100" {:count (:count options)}))
  (when-not (<= 1 (:max-events options) 5)
    (fail! "--max-events must be between 1 and 5 for the registered generator" 
           {:max-events (:max-events options)}))
  (when-not (:out options)
    (fail! "--out is required" {}))
  (let [profile (named-id (:profile options) ycore/known-profile-ids "profile")
        preset (named-id (:preset options) ycore/known-preset-ids "preset")
        token (named-id (:token options) ycore/known-tokens "token")
        failure-modes (when (seq (:failure-mode options))
                        (set (map #(named-id % ycore/known-failure-modes "failure mode")
                                  (:failure-mode options))))
        liquidity-mode (named-id (:liquidity-mode options)
                                 [:available :shortfall :haircut]
                                 "liquidity mode")]
    (when (and profile preset)
      (fail! "--profile and --preset are mutually exclusive" {}))
    (assoc options
           :profile-id profile
           :preset-id preset
           :token-id token
           :failure-modes failure-modes
           :liquidity-mode-id liquidity-mode)))

(defn- option-config
  [options]
  (cond
    (:preset-id options)
    (or (presets/preset->yield-config (:preset-id options))
        (fail! "Selected preset has no yield configuration" {:preset (:preset-id options)}))

    (:profile-id options)
    (ycore/yield-config-for-profile (get profile-config-aliases (:profile-id options)
                                          (:profile-id options))
                                    (or (:token-id options) "USDC"))

    :else nil))

(defn- config-generator
  [options]
  (if-let [config (option-config options)]
    (gen/return config)
    ycore/gen-yield-config))

(defn- selected-token
  [config options]
  (or (:token-id options)
      (some-> config :modules vals first :tokens keys first)
      "USDC"))

(defn- config-module-id
  [config]
  (or (some-> config :modules keys first)
      (fail! "Generated yield config has no module" {})))

(defn- apply-config-options
  [config options]
  (let [token (selected-token config options)
        module-id (config-module-id config)
        token-config (or (get-in config [:modules module-id :tokens token])
                         (some-> config :modules vals first :tokens vals first)
                         {})
        token-config (cond-> token-config
                       (:failure-modes options)
                       (assoc :failure-modes (:failure-modes options))

                       (and (:failure-modes options) (empty? (:failure-modes options)))
                       (dissoc :failure-modes)

                       (:liquidity-mode-id options)
                       (assoc :liquidity-mode (:liquidity-mode-id options))

                       (= :shortfall (or (:liquidity-mode-id options)
                                         (:liquidity-mode token-config)))
                       (assoc :shortfall {:available-ratio 0.5
                                          :reason :liquidity-shortfall})

                       (and (:liquidity-mode-id options)
                            (not= :shortfall (:liquidity-mode-id options)))
                       (dissoc :shortfall))]
    {:config {:modules {module-id {:tokens {token token-config}}}}
     :token token
     :module-id module-id}))

(defn- adapt-events
  [events token module-id]
  (mapv (fn [event]
          (update event :params
                  (fn [params]
                    (cond-> params
                      (contains? params :token) (assoc :token token)
                      (contains? params :module-id) (assoc :module-id (name module-id))))))
        events))

(defn- scenario-generator
  [options]
  (let [event-options (filterv #(<= (count %) (:max-events options))
                               yevents/yield-event-sequences)]
    (when (empty? event-options)
      (fail! "No registered event sequence fits --max-events"
             {:max-events (:max-events options)}))
    (gen/fmap
     (fn [[config events]]
       (let [{normalized-config :config token :token module-id :module-id}
             (apply-config-options config options)
             profile (or (:profile-id options)
                         (:profile-id (yreg/resolve-yield-profile
                                       (if (keyword? module-id) module-id (keyword module-id)))))]
         (yscenario/build-yield-scenario
          {:seed (:seed options)
           :scenario-id "pending"
           :profile profile
           :yield-config normalized-config
           :events (adapt-events events token module-id)})))
     (gen/tuple (config-generator options) (gen/elements event-options)))))

(defn- indexed-rngs
  [seed count]
  (loop [rng (random/make-random seed)
         index 0
         result []]
    (if (= index count)
      result
      (let [[next-rng sample-rng] (random/split rng)]
        (recur next-rng (inc index) (conj result sample-rng))))))

(defn- generation-options-body
  [options index]
  (-> (select-keys options [:profile-id :preset-id :token-id :failure-modes
                            :liquidity-mode-id :seed :count :max-events])
      (assoc :sample-index index)
      (update-keys (fn [k] (case k
                            :profile-id :profile
                            :preset-id :preset
                            :token-id :token
                            :liquidity-mode-id :liquidity-mode
                            k)))
      (update :failure-modes #(when % (vec (sort-by str %))))))

(defn- write-edn!
  [file value]
  (with-open [writer (io/writer file)]
    (.write writer (str (pr-str value) "\n"))))

(defn- generate!
  [raw-args]
  (let [{:keys [options errors summary arguments]}
        (cli/parse-opts raw-args generation-options)
        options (parse-options! {:options options :errors errors :summary summary :arguments arguments})
        out (io/file (:out options))]
    (when (.exists out)
      (fail! "Output path already exists; refusing to overwrite" {:out (.getPath out)}))
    (when-not (.mkdirs out)
      (fail! "Cannot create output directory" {:out (.getPath out)}))
    (let [generator (scenario-generator options)
          source-commitment (generator-source-commitment)
          scenarios
          (mapv (fn [index rng]
                    (let [scenario (-> (gen/call-gen generator rng (:max-events options))
                                       rose/root
                                       (assoc :protocol "yield-v1"
                                              :scenario-id (format "yield-%d-%04d" (:seed options) index)
                                              :seed (:seed options)))
                          _ (when (> (count (:events scenario)) (:max-events options))
                              (fail! "Generator exceeded the requested event limit"
                                     {:sample-index index
                                      :max-events (:max-events options)
                                      :actual-events (count (:events scenario))}))
                        scenario-commitment (commitments/scenario-commitment scenario)
                        provenance-inputs {:generator/id generator-id
                                           :generator/version generator-version
                                           :generator/source-commitment source-commitment
                                           :rng/algorithm rng-algorithm
                                           :rng/seed (:seed options)
                                           :generation/options (generation-options-body options index)}
                        provenance (commitments/generation-provenance-commitment
                                    scenario provenance-inputs)
                        filename (format "scenario-%04d.edn" index)]
                    (write-edn! (io/file out filename) scenario)
                    {:scenario/file filename
                     :yield-scenario/root (:canonical/hash scenario-commitment)
                     :yield-generation/artifact provenance
                     :yield-generation/root (:canonical/hash provenance)}))
                (range 1 (inc (:count options)))
                (indexed-rngs (:seed options) (:count options)))
          manifest {:manifest/schema manifest-schema
                    :generator/id generator-id
                    :generator/version generator-version
                    :rng/algorithm rng-algorithm
                    :generation/options (dissoc (generation-options-body options 0) :sample-index)
                    :scenario-validation :passed
                    :replay-execution :not-run
                    :admission :not-established
                    :scenarios scenarios}]
      ;; The manifest is written last: an interrupted generation cannot appear complete.
      (write-edn! (io/file out "manifest.edn") manifest)
      (println (str "generated " (count scenarios) " scenario(s) in " (.getPath out)))
      (doseq [entry scenarios]
        (println (str (:scenario/file entry)
                      " scenario=" (:yield-scenario/root entry)
                      " provenance=" (:yield-generation/root entry))))
      {:status :generated :out (.getAbsolutePath out) :manifest "manifest.edn"})))

(defn- read-edn!
  [file]
  (try
    (edn/read-string (slurp file))
    (catch Exception e
      (fail! "Cannot read EDN evidence" {:file (.getPath file) :cause (.getMessage e)}))))

(defn- resolve-manifest
  [input]
  (let [file (io/file input)
        file (if (.isDirectory file) (io/file file "manifest.edn") file)]
    (when-not (and (.isFile file) (= "manifest.edn" (.getName file)))
      (fail! "--input must name a generated directory or its manifest.edn"
             {:input input}))
    file))

(defn- verify!
  [raw-args]
  (let [{:keys [options errors summary arguments]}
        (cli/parse-opts raw-args verification-options)
        _ (when (seq errors) (fail! (str/join "; " errors) {:errors errors}))
        _ (when (seq arguments) (fail! "Unexpected positional arguments" {:arguments arguments}))
        _ (when-not (:input options) (fail! "--input is required" {}))
        manifest-file (resolve-manifest (:input options))
        root (.getCanonicalFile (.getParentFile manifest-file))
        manifest (read-edn! manifest-file)
        allowed-fields #{:manifest/schema :generator/id :generator/version
                         :rng/algorithm :generation/options :scenario-validation
                         :replay-execution :admission :scenarios}]
    (when-not (and (map? manifest)
                   (= allowed-fields (set (keys manifest)))
                   (= manifest-schema (:manifest/schema manifest))
                   (= generator-id (:generator/id manifest))
                   (= generator-version (:generator/version manifest))
                   (= rng-algorithm (:rng/algorithm manifest))
                   (= :passed (:scenario-validation manifest))
                   (= :not-run (:replay-execution manifest))
                   (= :not-established (:admission manifest))
                   (= (:count (:generation/options manifest)) (count (:scenarios manifest)))
                   (vector? (:scenarios manifest))
                   (seq (:scenarios manifest)))
      (fail! "Manifest has an invalid or unsupported shape" {}))
    (let [files (map :scenario/file (:scenarios manifest))]
      (when-not (= (count files) (count (set files)))
        (fail! "Manifest contains duplicate scenario file references" {:files files})))
    (let [source-commitment (generator-source-commitment)
          results
          (mapv (fn [index entry]
                  (let [file (:scenario/file entry)
                        scenario-root (:yield-scenario/root entry)
                        provenance (:yield-generation/artifact entry)
                        provenance-root (:yield-generation/root entry)]
                  (when-not (= #{:scenario/file :yield-scenario/root
                                 :yield-generation/artifact :yield-generation/root}
                               (set (keys entry)))
                    (fail! "Manifest scenario entry has an invalid shape" {:entry entry}))
                  (when-not (and (string? file)
                                 (not (str/blank? file))
                                 (= file (str (.getFileName (.normalize (.toPath (io/file file))))))
                                 (str/ends-with? file ".edn"))
                    (fail! "Manifest scenario path is invalid" {:file file}))
                  (let [path (.getCanonicalFile (io/file root file))
                        root-path (.toPath root)
                        scenario-path (.toPath path)]
                    (when-not (.startsWith scenario-path root-path)
                      (fail! "Manifest scenario escapes its evidence directory" {:file file}))
                    (when-not (.isFile path)
                      (fail! "Manifest references missing scenario evidence" {:file file}))
                    (let [scenario (read-edn! path)
                          calculated (:canonical/hash (commitments/scenario-commitment scenario))
                          provenance-check (commitments/verify-generation-provenance!
                                            scenario provenance)
                          provenance-body (:yield-generation/body provenance)
                          provenance-options (:generation/options provenance-body)]
                      (when-not (= scenario-root calculated)
                        (fail! "Materialized scenario root mismatch"
                               {:file file :expected scenario-root :actual calculated}))
                      (when-not (= provenance-root (:provenance-root provenance-check))
                        (fail! "Generation provenance root mismatch"
                               {:file file :expected provenance-root
                                :actual (:provenance-root provenance-check)}))
                      (when-not (and (= generator-id (:generator/id provenance-body))
                                     (= generator-version (:generator/version provenance-body))
                                     (= rng-algorithm (:rng/algorithm provenance-body))
                                     (= source-commitment (:generator/source-commitment provenance-body))
                                     (= (inc index) (:sample-index provenance-options))
                                     (= (:generation/options manifest)
                                        (dissoc provenance-options :sample-index)))
                        (fail! "Generation provenance does not match this pinned generator implementation"
                               {:file file
                                :expected-source source-commitment
                                :observed-source (:generator/source-commitment provenance-body)}))
                      (when-not (= (format "yield-%d-%04d"
                                           (get-in provenance-body [:rng/seed])
                                           (:sample-index provenance-options))
                                   (:scenario-id scenario))
                        (fail! "Scenario identifier does not match its generation provenance"
                               {:file file :scenario-id (:scenario-id scenario)}))
                      {:file file :scenario-root calculated
                       :provenance-root (:provenance-root provenance-check)
                       :valid? true}))))
                (range) (:scenarios manifest))]
      (println (str "verified " (count results) " scenario(s); roots recomputed from retained EDN"))
      (doseq [{:keys [file scenario-root provenance-root]} results]
        (println (str file " scenario=" scenario-root " provenance=" provenance-root)))
      {:status :verified :results results})))

(defn -main
  [& args]
  (let [[command & raw-args] args]
    (try
      (case command
        "generate" (generate! raw-args)
        "verify" (verify! raw-args)
        (do
          (binding [*out* *err*]
            (println "Usage: gen-yield generate --seed N --count N --out DIR")
            (println "       gen-yield verify --input DIR|manifest.edn"))
          (System/exit 2)))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "gen-yield: " (.getMessage e)))
          (when-let [data (ex-data e)] (prn data)))
        (System/exit 2)))))
