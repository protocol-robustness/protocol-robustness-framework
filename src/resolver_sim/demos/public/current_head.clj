(ns resolver-sim.demos.public.current-head
  "Public-demo projection for the current-head resubmission admission story.
   The projection executes the real chain facade; the site only renders its
   returned admission result."
  (:require [clojure.data.json :as json]
            [clojure.walk :as walk]
            [resolver-sim.demos.public.validate :as validate]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.resubmission.chain :as chain]))

(def schema-id "public-demo.v1")
(def demo-id "current-head")

(defn- request [receipt sequence parent link idem basis]
  {:receipt-hash receipt :sequence sequence :parent-receipt-hash parent
   :link-hash link :idempotency-key idem :basis-root basis})

(defn- run []
  (let [c (chain/new-chain "sha256:PUBLIC-DEMO-FAMILY")
        r1 (chain/admit-compat! c (request "sha256:R1" 1 nil "sha256:L1" "sha256:I1" "sha256:B1"))
        r2 (chain/admit-compat! c (request "sha256:R2" 2 "sha256:R1" "sha256:L2" "sha256:I2" "sha256:B2"))
        head-before (chain/current-head c)
        stale (chain/admit-compat! c (request "sha256:R3" 3 "sha256:R1" "sha256:L3" "sha256:I3" "sha256:B3"))
        head-after (chain/current-head c)
        root (hc/hash-with-intent {:hash/intent :evidence-content}
                                  {:r1 r1 :r2 r2 :stale stale
                                   :head-before head-before :head-after head-after})]
    {:r1 r1 :r2 r2 :stale stale :head-before head-before :head-after head-after :root root}))

(defn- sorted-maps [x]
  (walk/postwalk #(if (map? %) (into (sorted-map) %) %) x))

(defn project []
  (let [{:keys [r1 r2 stale head-before head-after root]} (run)
        artifact
        {"schema" schema-id
         "demo" {"id" demo-id "version" 1
                 "question" "Can a resubmission extend an older, superseded result?"}
         "scenario" {"records" {"order" "R1 → R2 (the verified history)"
                                  "items" ["R1: first rejected attempt" "R2: admitted successor and current head"]}}
         "baseline" {"label" "Current verified head" "value" (str head-before) "admitted" (= :admitted (:admission-status r2))}
         "change" {"label" "Submit R3 against stale parent" "from" "sha256:R2" "to" "sha256:R1"
                   "detail" "R1 exists, but R2 has already superseded it as the chain head."}
         "outcome" {"admitted" (= :admitted (:admission-status stale))
                    "failed-checks" [(name (:reason stale))]}
         "why" "A resubmission chain is linear: only its current head may receive a successor. The stale candidate is refused, and the current head remains unchanged."
         "evidence" {"committed-hash" root "input-root" "sha256:PUBLIC-DEMO-FAMILY"
                     "lines" [["head before submission" (str head-before)]
                              ["candidate parent" "sha256:R1 (stale)"]
                              ["admission result" (name (:admission-status stale))]
                              ["head after submission" (str head-after)]]
                     "checks" [{"id" (name (:reason stale)) "status" "fail"
                                "detail" "candidate parent is not the current head"}
                               {"id" "head-unchanged" "status" (if (= head-before head-after) "pass" "fail")
                                "detail" "a rejected candidate does not move the head"}]}
         "commitments" {"baseline" "admitted" "after-change" "not-admitted"}
         "source" {"notebook" "resubmission_chain" "demo-notebook" "resubmission_chain"
                   "cli" "clojure -M:test -e \"(require 'resolver-sim.resubmission.resubmission-test)\""
                   "scenario-ns" "resolver-sim.resubmission.chain"
                   "projection-ns" "resolver-sim.demos.public.current-head"
                   "schema" schema-id "result-root" root
                   "input-root" "sha256:PUBLIC-DEMO-FAMILY"}}]
    (validate/validate-artifact! (sorted-maps artifact))))

(defn json-str [] (str (json/write-str (project)) "\n"))
