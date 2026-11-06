(ns resolver-sim.research.runner-transport.http
  "HTTP wire and shared-secret authentication primitives for research runners.

   HTTP addresses, credentials, and response diagnostics stay runtime-only;
   callers are responsible for validating and rooting semantic artifacts."
  (:require [clojure.edn :as edn])
  (:import [com.sun.net.httpserver HttpExchange]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Duration]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def mac-header "X-PRF-Research-MAC")
(def content-type "application/edn; charset=utf-8")
(def ^:private client (HttpClient/newHttpClient))

(defn utf8 [value]
  (.getBytes (str value) StandardCharsets/UTF_8))

(defn hmac-bytes [secret ^bytes payload]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (utf8 secret) "HmacSHA256"))
    (.doFinal mac payload)))

(defn hex [^bytes bytes]
  (.formatHex (java.util.HexFormat/of) bytes))

(defn authenticated? [secret ^bytes payload supplied]
  (try
    (and (string? supplied)
         (MessageDigest/isEqual (hmac-bytes secret payload)
                                (.parseHex (java.util.HexFormat/of) supplied)))
    (catch IllegalArgumentException _ false)))

(defn response! [^HttpExchange exchange status body]
  (let [payload (utf8 (pr-str body))]
    (.set (.getResponseHeaders exchange) "Content-Type" content-type)
    (.sendResponseHeaders exchange status (alength payload))
    (with-open [out (.getResponseBody exchange)]
      (.write out payload))))

(defn request-body [^HttpExchange exchange]
  (.readAllBytes (.getRequestBody exchange)))

(defn post-edn! [endpoint path message]
  (let [body (pr-str message)
        timeout-ms (or (:transport/request-timeout-ms endpoint) 5000)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str (:transport/base-url endpoint) path)))
                    (.timeout (Duration/ofMillis timeout-ms))
                    (.header "Content-Type" content-type)
                    (.header mac-header
                             (hex (hmac-bytes (:transport/auth-secret endpoint)
                                              (utf8 body))))
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))]
    (try
      (let [response (.send client request (HttpResponse$BodyHandlers/ofString))
            decoded (edn/read-string (.body response))]
        {:http/status (.statusCode response) :transport/response decoded})
      (catch Exception error
        {:http/status nil :transport/error :unreachable
         :transport/exception-class (.getName (class error))}))))

(defn coordinator-endpoint
  "Build runtime-only connection data from a started runner or coordinator."
  ([server auth-secret] (coordinator-endpoint server auth-secret 5000))
  ([server auth-secret request-timeout-ms]
   (when-not (and (string? (:transport/base-url server))
                  (string? auth-secret) (not (empty? auth-secret))
                  (integer? request-timeout-ms) (pos? request-timeout-ms))
     (throw (ex-info "Invalid research runner endpoint" {})))
   {:transport/base-url (:transport/base-url server)
    :transport/auth-secret auth-secret
    :transport/request-timeout-ms request-timeout-ms}))
