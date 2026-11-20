(ns resolver-sim.research.researcher-challenges-devnet-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [resolver-sim.research.researcher-challenges-devnet :as devnet]
            [resolver-sim.research.researcher-challenges-test :as helpers]
            [resolver-sim.research.shortfall-pilot :as pilot]
            [resolver-sim.research.shortfall-pilot-test :as shortfall-fixture])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.file Files]))

(defn- write-fixture! [directory fixture history]
  (let [path (fn [name] (str directory "/" name))]
    (spit (path "configuration.edn") (pr-str (:config fixture)))
    (spit (path "profile.edn") (pr-str (:profile fixture)))
    (spit (path "evidence.edn") (pr-str (:evidence fixture)))
    (spit (path "history.edn") (pr-str history))
    {:config (path "configuration.edn") :profile (path "profile.edn")
     :evidence (path "evidence.edn") :history (path "history.edn")}))

(defn- start-fixture! [fixture history evidence]
  (let [directory (.toFile (Files/createTempDirectory
                            "researcher-challenge-devnet-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        paths (write-fixture! directory (assoc fixture :evidence evidence) history)
        server (devnet/start! {:config-path (:config paths)
                               :history-path (:history paths)
                               :profile-path (:profile paths)
                               :evidence-path (:evidence paths)})]
    {:directory directory :paths paths :server server}))

(defn- request! [url method body]
  (let [builder (HttpRequest/newBuilder)
        _ (.uri builder (URI/create url))
        _ (.header builder "Accept" "application/edn")
        _ (if (= method "POST")
            (do (.header builder "Content-Type" "application/edn")
                (.POST builder (HttpRequest$BodyPublishers/ofString body)))
            (.GET builder))
        response (.send (HttpClient/newHttpClient) (.build builder)
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (edn/read-string (.body response))}))

(defn- stop-fixture! [{:keys [directory server]}]
  ((:stop server))
  (doseq [file (reverse (file-seq directory))] (.delete file)))

(deftest devnet-is-loopback-only-and-bounds-request-size
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"loopback"
                        (devnet/start! {:bind-address "0.0.0.0"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"loopback"
                        (devnet/start! {:bind-address "localhost"})))
  (let [fixture (#'shortfall-fixture/challenge-001-fixture)
        service (start-fixture! fixture [] (:evidence fixture))]
    (try
      (let [before (slurp (get-in service [:paths :history]))
            response (request! (str (:base-url (:server service)) "/v1/actions")
                               "POST" (str "{:action/padding \"" (apply str (repeat (* 1024 1024) "x")) "\"}"))]
        (is (= 413 (:status response)))
        (is (= before (slurp (get-in service [:paths :history])))))
      (finally (stop-fixture! service)))))

(deftest local-devnet-refuses-real-asset-configuration
  (let [fixture (#'shortfall-fixture/challenge-001-fixture)
        directory (.toFile (Files/createTempDirectory
                            "researcher-challenge-mainnet-config-"
                            (make-array java.nio.file.attribute.FileAttribute 0)))
        config (assoc-in (:config fixture) [:reward/policy :reward/currency] "USDC")
        paths (write-fixture! directory (assoc fixture :config config) [])]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"TEST-\* reward currency"
                            (devnet/start! {:config-path (:config paths)
                                            :history-path (:history paths)
                                            :profile-path (:profile paths)
                                            :evidence-path (:evidence paths)})))
      (finally
        (doseq [file (reverse (file-seq directory))] (.delete file))))))

(deftest loopback-api-admits-and-replays-signed-challenge-flow
  (let [fixture (#'shortfall-fixture/challenge-001-fixture)
        enrolled (#'helpers/enroll fixture [] :alice)
        claimed (#'helpers/claim-task fixture enrolled :alice (:definition fixture))
        submitted (#'shortfall-fixture/submission fixture claimed (:context fixture))
        submission (#'helpers/latest fixture submitted)
        reviewed (#'helpers/approve fixture submitted (:definition fixture) submission :accepted :eligible)
        service (start-fixture! fixture [] (:evidence fixture))]
    (try
      (let [base (:base-url (:server service))
            health (request! (str base "/healthz") "GET" nil)
            config (request! (str base "/v1/configuration") "GET" nil)]
        (is (= 200 (:status health)))
        (is (= :local-devnet (get-in health [:body :service/environment])))
        (is (= (:artifact/root (:config fixture))
               (get-in config [:body :artifact/root]))))
      (doseq [action reviewed]
        (let [response (request! (str (:base-url (:server service)) "/v1/actions")
                                 "POST" (pr-str action))]
          (is (= 201 (:status response)) (pr-str (:body response)))))
      (is (= reviewed (edn/read-string (slurp (get-in service [:paths :history])))))
      (let [duplicate (request! (str (:base-url (:server service)) "/v1/actions")
                                "POST" (pr-str (last reviewed)))]
        (is (= 422 (:status duplicate)))
        (is (= reviewed (edn/read-string (slurp (get-in service [:paths :history]))))))
      (finally (stop-fixture! service)))))

(deftest devnet-submission-endpoint-runs-challenge-bounds-before-write
  (let [fixture (#'shortfall-fixture/challenge-001-fixture)
        initial (#'shortfall-fixture/before-submission fixture)
        outside-inputs (mapv #(-> %
                                  (assoc-in [:rows 0 :requested] 1000001)
                                  (assoc-in [:rows 0 :weight] 1000001))
                             (#'shortfall-fixture/candidate-inputs))
        outside (pilot/execute-comparison outside-inputs)
        context (pilot/build-context (:metadata fixture) outside)
        proposed (#'shortfall-fixture/submission fixture initial context)
        service (start-fixture! fixture initial {(:artifact/root outside) outside})]
    (try
      (let [before (slurp (get-in service [:paths :history]))
            response (request! (str (:base-url (:server service)) "/v1/actions")
                               "POST" (pr-str (last proposed)))]
        (is (= 422 (:status response)))
        (is (= :challenge-001-input-outside-bounds (get-in response [:body :error/code])))
        (is (= before (slurp (get-in service [:paths :history])))))
      (finally (stop-fixture! service)))))
