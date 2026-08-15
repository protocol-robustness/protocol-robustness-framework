(ns resolver-sim.cancellation.operation-test
  (:require [clojure.test :refer [deftest is]] [resolver-sim.cancellation.operation :as op]))
(defn sha [n] (format "sha256:%064x" n))
(defn operation []
  {:operation/schema op/schema-version :operation/purpose :cancellation/execution :event/id "cancel-1" :protocol/id :sew
   :target {:kind :sew/escrow :id "e1" :snapshot-root (sha 1) :state-before-root (sha 2) :lifecycle-head-root (sha 3)}
   :request {:caller/id "alice" :action :cancel :requested-at 1} :policy {:id :sew/party-cancellation :root (sha 4)}
   :evaluation {:inputs-root (sha 5) :base-decision :ordinary :decision {:derived-effects-root (sha 6)}}
   :preconditions/root (sha 7) :authorization {:kind :ordinary :root (sha 7)}
   :execution {:status :applied :effects-root (sha 8) :state-after-root (sha 9)}})
(deftest operation-is-the-only-cancellation-operation-statement
  (let [o (operation) root (op/operation-root o)]
    (is (op/operation-complete? o))
    (is (op/operation-root-valid? (assoc o :operation/root root)))
    (is (op/operation-complete? (assoc o :authority/mode :override)))
    (is (not (op/operation-complete? (assoc-in o [:execution :effects-root] (sha 6)))))))
