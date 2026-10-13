(ns resolver-sim.commands.resubmission-issue-admission-v2
  "Stateless signer-v2 for fenced resubmission admission authorization.
   This command never reads or mutates family state and is not compatible with
   legacy `resubmission issue` receipt issuance."
  (:require [buddy.core.keys :as keys]
            [resolver-sim.resubmission.admission-authorization :as auth]
            [resolver-sim.signed-external-decision :as sed]
            [clojure.edn :as edn]))

(def request-domain "PRF_RESUBMISSION_ADMISSION_SIGN_REQUEST_V2")
(def request-kind :resubmission-admission-sign-request)
(def response-kind :resubmission-admission-sign-response)
(def error-kind :resubmission-admission-sign-error)

(defn request-valid?
  [request]
  (and (map? request)
       (= request-kind (:request/kind request))
       (= 2 (:request/version request))
       (map? (:signing/payload request))
       (= (:request/hash request) (sed/request-hash request-domain request))))

(defn decide
  [{:keys [private-key key-id]} request]
  (when-not (request-valid? request)
    (throw (ex-info "invalid admission signing request" {:reason :invalid-request})))
  (when-not (and private-key key-id)
    (throw (ex-info "signing authority not configured" {:reason :missing-signing-authority})))
  (let [payload (:signing/payload request)
        evidence (auth/sign payload private-key key-id)]
    {:response/kind response-kind
     :response/version 2
     :request/id (:request/id request)
     :authorization/evidence evidence
     :authorization/evidence-root (:authorization/evidence-root evidence)}))

(defn run
  "CLI handler. Reads one EDN request from stdin and emits one EDN response.
   Keys come from options or PRF_VALIDATOR_KEY / PRF_VALIDATOR_KEY_ID."
  [{:keys [validator-key key-id]}]
  (let [key-path (or validator-key (System/getenv "PRF_VALIDATOR_KEY"))
        authority-id (or key-id (System/getenv "PRF_VALIDATOR_KEY_ID"))]
    (try
      (let [request (edn/read {:eof nil} *in*)
            response (decide {:private-key (when key-path (keys/private-key key-path))
                              :key-id authority-id}
                             request)]
        (println (pr-str response))
        {:exit-code 0 :message "admission authorization issued"})
      (catch Exception e
        (println (pr-str {:response/kind error-kind
                          :response/version 2
                          :error/reason (or (:reason (ex-data e)) :admission-sign-error)
                          :error/detail (.getMessage e)}))
        {:exit-code 1 :message (.getMessage e)}))))
