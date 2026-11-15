(ns resolver-sim.contract-model.replay.analysis-test
  "Focused tests for the primitive-native bond-identity selector used by the
   AP-ID-001 scenario. The selector must resolve the earliest unambiguous
   repeated-bond group (same workflow + same funder, distinct appeal ordinals)
   from authoritative checkpoint projections, failing closed on zero results or
   on an ambiguous multiple-group coexistence."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew.replay.appeal-bond :as appeal-bond]))

(defn- open-record [ordinal funder]
  (let [id (str "appeal-bond:0:" funder ":" ordinal)]
    {:bond/id id
     :workflow/id 0
     :dispute/id "dispute:0"
     :appeal/ordinal ordinal
     :escalation/from-level ordinal
     :escalation/to-level (inc ordinal)
     :asset :USDC
     :bond/posted-amount 100
     :bond/posting-fee 10
     :bond/custodied-amount 90
     :bond/remaining-custody 90
     :bond/payer funder
     :bond/funder funder
     :posting-operation/id (str "post-" id)
     :posting-cause/type :appeal
     :posting-cause/root "posting-cause-root"
     :bond/status :open}))

(defn- world-with-bonds [records]
  {:appeal-bond/records records
   :appeal-bond/dispositions {}
   :appeal-bond/claims {}})

(deftest earliest-coexistence-point-yields-distinct-round-bonds
  ;; seq 0: only bond-a (ordinal 0) exists. seq 5: bond-b (ordinal 1) posted.
  ;; The selector must pick the earliest point (seq 5) where both coexist.
  (let [result {:world-checkpoints
                {0 (world-with-bonds {"appeal-bond:0:0xchallenger:0" (open-record 0 "0xchallenger")})
                 5 (world-with-bonds {"appeal-bond:0:0xchallenger:0" (open-record 0 "0xchallenger")
                                      "appeal-bond:0:0xchallenger:1" (open-record 1 "0xchallenger")})}}
        pair (appeal-bond/earliest-coexistence-bond-pair result)]
    (is (some? pair))
    (is (= 2 (count pair)))
    (is (= ["appeal-bond:0:0xchallenger:0" "appeal-bond:0:0xchallenger:1"]
           (mapv :bond/id pair)))
    (is (= [0 1] (mapv :appeal/ordinal pair)))))

(deftest no-repeated-bond-group-fails-closed
  (let [result {:world-checkpoints
                {0 (world-with-bonds {"appeal-bond:0:0xchallenger:0" (open-record 0 "0xchallenger")})
                 5 (world-with-bonds {"appeal-bond:0:0xchallenger:0" (open-record 0 "0xchallenger")
                                      "appeal-bond:0:0xother:0" (open-record 0 "0xother")})}}
        pair (appeal-bond/earliest-coexistence-bond-pair result)]
    ;; bond-c is a different funder, so no single funder has two rounds.
    (is (nil? pair))))

(deftest multiple-repeated-bond-groups-fails-closed
  ;; Two funders each with their own repeated rounds at the same coexistence
  ;; point would make the ground-truth pair ambiguous; the selector must nil out.
  (let [result {:world-checkpoints
                {0 (world-with-bonds {})}
                5 {:appeal-bond/records
                   {"appeal-bond:0:0xf1:0" (open-record 0 "0xf1")
                    "appeal-bond:0:0xf1:1" (open-record 1 "0xf1")
                    "appeal-bond:0:0xf2:0" (open-record 0 "0xf2")
                    "appeal-bond:0:0xf2:1" (open-record 1 "0xf2")}
                   :appeal-bond/dispositions {}
                   :appeal-bond/claims {}}}
        pair (appeal-bond/earliest-coexistence-bond-pair result)]
    (is (nil? pair))))
