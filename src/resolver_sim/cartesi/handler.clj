(ns resolver-sim.cartesi.handler
  (:require [clojure.data.json :as json]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.logging :as log]
            [resolver-sim.config.defaults :as defaults])
  (:import [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.net URI]
           [java.nio.charset StandardCharsets])
  (:gen-class))

(def rollup-server (System/getenv "ROLLUP_HTTP_SERVER_URL"))

(defn hex->bytes [hex]
  (let [hex (if (.startsWith hex "0x") (subs hex 2) hex)]
    (byte-array (map #(unchecked-byte (Integer/parseInt % 16))
                     (map #(apply str %) (partition 2 hex))))))

(defn bytes->hex [bytes]
  (let [sb (StringBuilder. "0x")]
    (doseq [b bytes]
      (.append sb (format "%02x" (bit-and b 0xff))))
    (.toString sb)))

(defn string->hex [s]
  (bytes->hex (.getBytes s StandardCharsets/UTF_8)))

(defn hex->string [hex]
  (String. (hex->bytes hex) StandardCharsets/UTF_8))

(def client (HttpClient/newHttpClient))

(defn post [url body]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))]
    (.send client request (HttpResponse$BodyHandlers/ofString))))

(defn emit-notice [payload]
  (let [url (str rollup-server "/notice")
        body (json/write-str {:payload (string->hex (json/write-str payload))})]
    (post url body)))

(defn emit-report [payload]
  (let [url (str rollup-server "/report")
        body (json/write-str {:payload (string->hex (json/write-str payload))})]
    (post url body)))

(def state (atom {:total-simulations 0
                  :passes 0
                  :fails 0
                  :invalids 0
                  :history []}))

(def max-history (defaults/default [:cartesi :max-history] 10))

(defn- default-protocol []
  (preg/get-protocol preg/default-protocol-id))

(defn update-state! [result]
  (swap! state (fn [s]
                 (let [outcome (:outcome result)
                       s' (-> s
                              (update :total-simulations inc)
                              (update (case outcome
                                        :pass :passes
                                        :fail :fails
                                        :invalid :invalids
                                        (do (log/warn! :unknown-outcome {:outcome outcome})
                                            :invalids)) inc))
                       history (:history s')
                       entry {:id (:scenario-id result)
                              :outcome (if (keyword? outcome) (name outcome) outcome)
                              :events-processed (:events-processed result)
                              :halt-reason (let [hr (:halt-reason result)]
                                             (if (keyword? hr) (name hr) hr))}]
                   (assoc s' :history (vec (take max-history (cons entry history))))))))

(defn handle-advance [data]
  (println "Received advance request data: " (pr-str data))
  (try
    (let [payload-hex (:payload data)
          scenario-str (hex->string payload-hex)]
      (println "Scenario string: " scenario-str)
      (let [scenario (json/read-str scenario-str :key-fn keyword)
            _ (println "Executing scenario: " (:scenario-id scenario))
            result (replay/replay-with-protocol (default-protocol) scenario)]
        (println "Simulation result outcome: " (:outcome result))
        (update-state! result)
        (emit-notice result)
        "accept"))
    (catch Exception e
      (println "Error handling advance: " (.getMessage e))
      (.printStackTrace e)
      (emit-report {:error (.getMessage e)})
      "reject")))

(defn handle-inspect [data]
  (println "Received inspect request data")
  (try
    (let [payload-hex (:payload data)
          query (if (and payload-hex (not= payload-hex "0x"))
                  (hex->string payload-hex)
                  "all")
          response (case query
                     "metrics" (dissoc @state :history)
                     "history" {:history (:history @state)}
                     @state)]
      (emit-report response)
      "accept")
    (catch Exception e
      (println "Error handling inspect:" (.getMessage e))
      (emit-report {:error (.getMessage e)})
      "accept")))

(defn -main [& args]
  (println "HTTP rollup_server url is " rollup-server)
  (loop [status "accept"]
    (let [finish-body (json/write-str {:status status})
          response (post (str rollup-server "/finish") finish-body)
          res-status (.statusCode response)]
      (println "Received finish status " res-status)
      (if (= res-status 202)
        (do
          (println "No pending rollup request, trying again")
          (Thread/sleep 1000)
          (recur "accept"))
        (let [rollup-req (json/read-str (.body response) :key-fn keyword)
              req-type (:request_type rollup-req)
              data (:data rollup-req)
              next-status (case req-type
                            "advance_state" (handle-advance data)
                            "inspect_state" (handle-inspect data)
                            (do (println "Unknown request type" req-type) "reject"))]
          (recur next-status))))))
