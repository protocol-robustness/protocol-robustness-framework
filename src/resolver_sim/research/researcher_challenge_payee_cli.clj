(ns resolver-sim.research.researcher-challenge-payee-cli
  "Offline CLI for researcher-signed payout destination consent artifacts."
  (:require [clojure.edn :as edn]
            [resolver-sim.research.researcher-challenge-payee :as payee])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Paths StandardOpenOption]))

(defn- read-edn-file [path]
  (let [value (edn/read-string (slurp path))]
    (when-not (map? value)
      (throw (ex-info "Expected one EDN map" {:path path})))
    value))

(defn- write-new-edn! [path value]
  (Files/write (Paths/get path (make-array String 0))
               (.getBytes (str (pr-str value) "\n") StandardCharsets/UTF_8)
               (into-array StandardOpenOption [StandardOpenOption/CREATE_NEW
                                               StandardOpenOption/WRITE]))
  path)

(defn -main [& args]
  (case (first args)
    "build"
    (let [[_ config-path history-path policy-path request-path operator-public-path
           researcher-private-path output-path] args]
      (when-not (and (= 8 (count args))
                     (every? some? [config-path history-path policy-path request-path
                                    operator-public-path researcher-private-path output-path]))
        (throw (ex-info "Usage: build CONFIG HISTORY POLICY REQUEST OPERATOR_PUBLIC RESEARCHER_PRIVATE OUTPUT"
                        {:usage :invalid-arguments})))
      (let [config (read-edn-file config-path)
            history (edn/read-string (slurp history-path))
            policy (read-edn-file policy-path)
            request (read-edn-file request-path)
            trusted-keys {:operator (slurp operator-public-path)
                          :reviewer (:reviewer/public-key config)}
            unsigned (payee/build-authorization config history policy trusted-keys request)
            signed (payee/sign-authorization unsigned researcher-private-path)
            verification (payee/verify-authorization
                          config history signed policy trusted-keys)]
        (write-new-edn! output-path signed)
        (prn {:valid? true :authorization/root (:authorization/root verification)
              :policy/root (:payout/policy-root verification) :output output-path})))

    "verify"
    (let [[_ config-path history-path policy-path authorization-path operator-public-path] args]
      (when-not (and (= 6 (count args))
                     (every? some? [config-path history-path policy-path authorization-path
                                    operator-public-path]))
        (throw (ex-info "Usage: verify CONFIG HISTORY POLICY AUTHORIZATION OPERATOR_PUBLIC"
                        {:usage :invalid-arguments})))
      (prn (payee/verify-authorization
            (read-edn-file config-path)
            (edn/read-string (slurp history-path))
            (read-edn-file authorization-path)
            (read-edn-file policy-path)
            {:operator (slurp operator-public-path)
             :reviewer (:reviewer/public-key (read-edn-file config-path))})))

    (throw (ex-info "Expected build or verify operation"
                    {:usage "build CONFIG HISTORY POLICY REQUEST OPERATOR_PUBLIC RESEARCHER_PRIVATE OUTPUT | verify CONFIG HISTORY POLICY AUTHORIZATION OPERATOR_PUBLIC"}))))
