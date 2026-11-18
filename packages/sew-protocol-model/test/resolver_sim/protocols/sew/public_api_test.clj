(ns resolver-sim.protocols.sew.public-api-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew :as sew]))

(deftest canonical-action-is-exposed-by-the-model-entrypoint
  (is (= "create-escrow"
         (sew/canonical-action {:action :create_escrow})))
  (is (= "grant-force-authorisation"
         (sew/canonical-action {:action "grant-force-authorization"}))))
