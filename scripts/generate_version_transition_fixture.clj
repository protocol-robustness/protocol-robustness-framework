#!/usr/bin/env clojure
;; Regenerate test/fixtures/review/version_transition_v2_v3.edn.
;;
;; Rebuilds the version-transition fixture from the same deterministic review
;; round, reports, and researcher positions, and writes the current v2-shaped
;; and v3 certificate projections and their roots.  Run after any change to
;; the certificate schema or hash projection.
(require '[clojure.java.io :as io]
         '[resolver-sim.benchmark.review.three-member-certificate :as tmc]
         '[resolver-sim.benchmark.review-round :as rr]
         '[resolver-sim.benchmark.researcher-position :as rp]
         '[resolver-sim.hash.canonical :as hc])

(def ^:private fixture-path
  "test/fixtures/review/version_transition_v2_v3.edn")

(def ^:private header
  ";; Review-certificate version-transition fixture (v2 -> v3).\n
;;\n
;; The same researcher positions, run reports, and review round, under the\n
;; three-member-research-certificate.v2 and .v3 schemas.  The certificate\n
;; roots intentionally differ - v3 is NOT derived from v2 by filling the five\n
;; new dimensions with a fabricated status; dimensions the researchers never\n
;; assessed are reported as :not-evaluable in v3.\n
;;\n
;; Regenerate with: scripts/generate_version_transition_fixture.clj\n")

(defn- make-report
  [id outcome-hash]
  (let [report {:schema-version "researcher-run-report.v1"
                :researcher/id id
                :researcher-run-report/outcome-hash outcome-hash
                :benchmark/content-root "sha256:cr"
                :benchmark/model-root "sha256:m"
                :benchmark/evaluation-policy-root "sha256:ep"
                :execution/content-root "sha256:cr"
                :execution/model-root "sha256:m"
                :execution/model-instance-root "sha256:mi"
                :execution/plan-root "sha256:plan"
                :execution/parameter-domain-root "sha256:domain"
                :execution/sampling-policy-root "sha256:samp"
                :execution/generated-case-set-root "sha256:c"
                :researcher-run-report/hash nil
                :researcher/signature nil}]
    (assoc report :researcher-run-report/hash
           (str "sha256:" (hc/domain-hash :researcher-run-report report)))))

(defn- make-position
  [id]
  (rp/build-position
   {:benchmark/content-root "sha256:cr"
    :researcher/id id
    :outcome-hash "sha256:A"
    :dimensions {:model-state {:status :adequate}
                 :model-authority {:status :adequate}
                 :incentives-strategies {:status :adequate}
                 :evidence {:status :sufficient}
                 :claims {:status :supported}
                 :publication {:status :publish}}}))

(defn- review-round
  []
  (rr/build-review-round
   {:benchmark/content-root "sha256:cr"
    :review-round/purpose :model-admission
    :review-round/members
    [{:researcher/id "a" :role :model-steward}
     {:researcher/id "b" :role :independent-reproducer}
     {:researcher/id "c" :role :adversarial-reviewer}]
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

(def v2-dimension-set
  (into #{} [:model-state :model-transitions :model-authority :model-adversary
             :model-parameters :model-cases
             :incentives-participants :incentives-strategies :incentives-coalitions
             :reproduction :evidence :claims :publication]))

(defn- as-v2-shaped
  [cert]
  (let [keep (fn [m] (into {} (filter (fn [[k _]] (contains? v2-dimension-set k))) m))]
    (-> cert
        (assoc :schema-version "three-member-research-certificate.v2")
        (update :model-consensus keep)
        (update :incentive-consensus keep)
        (update :other-consensus keep)
        (dissoc :supersedes-certificate-root :certificate/hash
                :review-member-canonical-indices))))

(defn generate!
  []
  (let [round (review-round)
        reports (mapv #(make-report % "sha256:A") ["a" "b" "c"])
        positions (mapv make-position ["a" "b" "c"])
        cert (tmc/build-certificate
              {:review-round round :reports reports :positions positions})
        v2-body (as-v2-shaped cert)
        v2-root (str "sha256:" (hc/domain-hash :three-member-certificate v2-body))
        v3-body (dissoc cert :certificate/hash :review-member-canonical-indices)
        v3-root (str "sha256:" (hc/domain-hash :three-member-certificate v3-body))
        fixture {:fixture/schema :review-certificate-version-transition.v1
                 :description "Same researcher positions, reports, and review round under the v2 and v3 certificate schemas."
                 :schema-version/v2 "three-member-research-certificate.v2"
                 :schema-version/v3 "three-member-research-certificate.v3"
                 :v2-dimension-count 13
                 :v3-dimension-count 18
                 :review-round/id (:review-round/id round)
                 :review-round round
                 :reports reports
                 :positions positions
                 :v2-shaped-body v2-body
                 :v3-body v3-body
                 :certificate-root/v2 v2-root
                 :certificate-root/v3 v3-root
                 :roots-differ? (not= v2-root v3-root)}]
    (spit fixture-path (str header (pr-str fixture) "\n"))
    (println "wrote" fixture-path)
    (println "v2 root" v2-root)
    (println "v3 root" v3-root)))

(generate!)
