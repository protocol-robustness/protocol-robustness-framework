(ns resolver-sim.research.researcher-challenge-payout-policy-cli
  "Create and verify dual-signed researcher payout policies."
  (:require [clojure.edn :as edn]
            [resolver-sim.research.researcher-challenge-payout-policy :as policy])
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
    (let [[_ config-path request-path operator-public-path operator-private-path
           reviewer-private-path output-path] args]
      (when-not (and (= 7 (count args))
                     (every? some? [config-path request-path operator-public-path
                                    operator-private-path reviewer-private-path output-path]))
        (throw (ex-info "Usage: build CONFIG REQUEST OPERATOR_PUBLIC OPERATOR_PRIVATE REVIEWER_PRIVATE OUTPUT"
                        {:usage :invalid-arguments})))
      (let [config (read-edn-file config-path)
            request (read-edn-file request-path)
            trusted-keys {:operator (slurp operator-public-path)
                          :reviewer (:reviewer/public-key config)}
            unsigned (policy/build-policy config request)
            signed (-> unsigned
                       (policy/sign-policy :operator operator-private-path)
                       (policy/sign-policy :reviewer reviewer-private-path))
            verification (policy/verify-policy config signed trusted-keys)]
        (write-new-edn! output-path signed)
        (prn {:valid? true :policy/root (:policy/root verification)
              :demo-only? (:policy/demo-only? verification) :output output-path})))

    "verify"
    (let [[_ config-path policy-path operator-public-path] args]
      (when-not (and (= 4 (count args))
                     (every? some? [config-path policy-path operator-public-path]))
        (throw (ex-info "Usage: verify CONFIG POLICY OPERATOR_PUBLIC"
                        {:usage :invalid-arguments})))
      (let [config (read-edn-file config-path)]
        (prn (policy/verify-policy
              config
              (read-edn-file policy-path)
              {:operator (slurp operator-public-path)
               :reviewer (:reviewer/public-key config)})))

      (throw (ex-info "Expected build or verify operation"
                      {:usage "build CONFIG REQUEST OPERATOR_PUBLIC OPERATOR_PRIVATE REVIEWER_PRIVATE OUTPUT | verify CONFIG POLICY OPERATOR_PUBLIC"})))))
