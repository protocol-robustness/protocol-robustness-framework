(ns resolver-sim.custody.client
  "AUTH-K4 custody client (PRF side).

   Talks to the local custody daemon over a Unix domain socket. The PRF process
   never loads or receives the governed private key. The only exposed operation
   signs a closed, frozen governed-authority-signing-request.v1; there is NO
   arbitrary signing API (no sign(bytes), sign(hash), sign-message, or
   export-private-key)."
  (:require [clojure.edn :as edn]
            [resolver-sim.custody.contract :as contract])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels SocketChannel]))

(defn connect
  "Open a UDS connection to the custody daemon at `socket-path`."
  [socket-path]
  (let [channel (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect channel (UnixDomainSocketAddress/of socket-path))
    channel))

(defn- read-frame [in]
  (let [sb (StringBuilder.)]
    (loop [ch (.read in)]
      (cond
        (= -1 ch) nil
        (= (int \newline) ch) (edn/read-string (str sb))
        :else (do (.append sb (char ch)) (recur (.read in)))))))

(defn sign-k3-request!
  "Send a closed governed-authority-signing-request.v1 to the daemon and return
   the signing response. The caller supplies only the request body; the daemon
   derives the signing digest internally.

   Returns a contract signing response {:custody/result :signed ...} or
   {:custody/result :refused :reason kw}."
  [channel request]
  (with-open [conn channel]
    (let [out (Channels/newOutputStream conn)
          in (Channels/newInputStream conn)]
      (.write out (.getBytes (str (pr-str request) "\n") "UTF-8"))
      (.flush out)
      (or (read-frame in) (contract/refused-response :daemon/unavailable)))))

(defn close! [channel]
  (try (.close channel) (catch Exception _ nil)))