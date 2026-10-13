(ns resolver-sim.contract-model.replay.analysis-test
  "Focused tests for the primitive-native bond-identity selector used by the
   AP-ID-001 scenario. The selector must resolve the earliest unambiguous
   repeated-bond group (same workflow + same funder, distinct appeal ordinals)
   from authoritative checkpoint projections, failing closed on zero results or
   on an ambiguous multiple-group coexistence."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.contract-model.replay.analysis :as analysis]))

(defn- open-record [id ordinal funder]
  {:bond/id id
   :workflow/id 0
   :appeal/ordinal ordinal
   :asset :USDC
   :bond/posted-amount 100
   :bond/posting-fee 10
   :bond/custodied-amount 90
   :bond/remaining-custody 90
   :bond/payer funder
   :bond/funder funder
   :posting-operation/id (str "post-" id)
   :bond/status :open})

(defn- world-with-bonds [records]
  {:appeal-bond/records records
   :appeal-bond/dispositions {}
   :appeal-bond/claims {}})

(deftest earliest-coexistence-point-yields-distinct-round-bonds
  ;; seq 0: only bond-a (ordinal 0) exists. seq 5: bond-b (ordinal 1) posted.
  ;; The selector must pick the earliest point (seq 5) where both coexist.
  (let [result {:world-checkpoints
                {0 (world-with-bonds {"bond-a" (open-record "bond-a" 0 "0xchallenger")})
                 5 (world-with-bonds {"bond-a" (open-record "bond-a" 0 "0xchallenger")
                                      "bond-b" (open-record "bond-b" 1 "0xchallenger")})}}
        pair (#'analysis/earliest-coexistence-bond-pair result)]
    (is (some? pair))
    (is (= 2 (count pair)))
    (is (= ["bond-a" "bond-b"] (mapv :bond/id pair)))
    (is (= [0 1] (mapv :appeal/ordinal pair)))))

(deftest no-repeated-bond-group-fails-closed
  (let [result {:world-checkpoints
                {0 (world-with-bonds {"bond-a" (open-record "bond-a" 0 "0xchallenger")})
                 5 (world-with-bonds {"bond-a" (open-record "bond-a" 0 "0xchallenger")
                                      "bond-c" (open-record "bond-c" 0 "0xother")})}}
        pair (#'analysis/earliest-coexistence-bond-pair result)]
    ;; bond-c is a different funder, so no single funder has two rounds.
    (is (nil? pair))))

(deftest multiple-repeated-bond-groups-fails-closed
  ;; Two funders each with their own repeated rounds at the same coexistence
  ;; point would make the ground-truth pair ambiguous; the selector must nil out.
  (let [result {:world-checkpoints
                {0 (world-with-bonds {})}
                5 {:appeal-bond/records
                   {"bond-a" (open-record "bond-a" 0 "0xf1")
                    "bond-b" (open-record "bond-b" 1 "0xf1")
                    "bond-c" (open-record "bond-c" 0 "0xf2")
                    "bond-d" (open-record "bond-d" 1 "0xf2")}
                   :appeal-bond/dispositions {}
                   :appeal-bond/claims {}}}
        pair (#'analysis/earliest-coexistence-bond-pair result)]
    (is (nil? pair))))
