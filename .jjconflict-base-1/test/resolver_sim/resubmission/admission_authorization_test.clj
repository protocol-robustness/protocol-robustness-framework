(ns resolver-sim.resubmission.admission-authorization-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.resubmission.admission :as admission]
            [resolver-sim.resubmission.admission-authorization :as auth]
            [resolver-sim.support.ed25519 :as ed]))

(defn root [c] (str "sha256:" (apply str (repeat 64 c))))

(def payload
  {:signing/payload-root (root "p")
   :signing/partition-key [:resubmission-family "sha256:F"]
   :signing/reservation-id (root "r")
   :signing/fence 4
   :signing/expected-state-version 7
   :signing/candidate-root (root "c")
   :signing/validation-root (root "v")
   :signing/proposed-ordering-root (root "o")})

(deftest authorization-is-bound-to-every-fenced-payload-field
  (let [kp (ed/keypair :admission-v2)
        evidence (auth/sign payload (:private-key kp) "validator-v2")]
    (is (auth/verify payload evidence (:public-hex kp)))
    (is (= (:authorization/evidence-root evidence) (auth/evidence-root evidence)))
    (doseq [changed [(assoc payload :signing/reservation-id (root "x"))
                     (assoc payload :signing/fence 5)
                     (assoc payload :signing/candidate-root (root "x"))
                     (assoc payload :signing/validation-root (root "x"))
                     (assoc payload :signing/proposed-ordering-root (root "x"))
                     (assoc payload :signing/payload-root (root "x"))]]
      (is (false? (auth/verify changed evidence (:public-hex kp)))))
    (is (false? (auth/verify payload evidence (:public-hex (ed/keypair :other)))))
    (is (false? (auth/verify payload {:attempt-receipt/schema "submission-attempt-receipt.v1"}
                             (:public-hex kp))))))
