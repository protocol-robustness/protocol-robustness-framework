(ns resolver-sim.research.researcher-challenges-devnet-demo
  "Create an explicitly non-authoritative Challenge 001 local-devnet bundle."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.network.shortfall-pilot-adapter :as pilot-adapter]
            [resolver-sim.research.shortfall-pilot :as pilot])
  (:import [java.nio.file Files Paths]
           [java.nio.file.attribute FileAttribute]))

(def ^:private example-root "examples/researcher-challenges/shortfall-stress/")

(defn- read-edn [path]
  (edn/read-string (slurp path)))

(defn- subject-root [candidate]
  (reference/sha256-ref
   (canonical/domain-hash "challenge-001-subject.v1" (:subject candidate))))

(defn- demo-root [label]
  (reference/sha256-ref (canonical/domain-hash :evidence-record
                                               {:local-demo/label label})))

(defn- write-edn! [directory filename value]
  (spit (io/file (str directory) filename) (str (pr-str value) "\n")
        :encoding "UTF-8"))

(defn prepare!
  "Write configuration/profile/evidence/history for a local demonstration.

   The reviewer public key is supplied separately. No private key is read or
   copied, and the generated bundle cannot authorize publication or funding.
   network-scope may be :devnet or :testnet; testnet only selects the
   signed-file protocol namespace and does not publish or activate the bundle."
  ([reviewer-public-key-path output-directory]
   (prepare! reviewer-public-key-path output-directory :devnet))
  ([reviewer-public-key-path output-directory network-scope]
   (when-not (contains? #{:devnet :testnet} network-scope)
     (throw (ex-info "Demo network scope must be :devnet or :testnet"
                     {:reason :unsupported-demo-network-scope
                      :network-scope network-scope})))
   (let [candidate (read-edn (str example-root "challenge-001-scope-candidate.v1.edn"))
         base-request {:schema-version "pro-rata-allocation-request.v1"
                       :mechanism/version 1
                       :allocation/id :researcher-pilot/local-devnet-demo
                       :available 150
                       :rows [{:row/id :claim/a :obligation/id :obligation/a
                               :requested 200 :weight 200 :cap 20}
                              {:row/id :claim/b :obligation/id :obligation/b
                               :requested 200 :weight 200}
                              {:row/id :claim/c :obligation/id :obligation/c
                               :requested 200 :weight 200}]
                       :rounding-policy :largest-remainder
                       :tie-break-policy :canonical-row-id}
         inputs (mapv #(assoc base-request :redistribution-policy %)
                      [:unallocated :redistribute-cap-excess])
         comparison (pilot/execute-comparison inputs)
         policy (challenges/build-reward-policy "TEST-POINTS")
         challenge (challenges/build-challenge
                    {:id (:challenge/id candidate)
                     :subject-root (subject-root candidate)
                     :subject-revision (get-in candidate
                                               [:source/revision-observation
                                                :source-content-manifest/root])
                     :contribution-type :adversarial-finding
                     :acceptance-criteria
                     {:proposition-id (get-in candidate [:subject :proposition])
                      :metric-id (get-in candidate [:subject :measure])
                      :result-classifications #{:counterexample-found}}
                     :base-reward 100
                     :capacity 2
                     :reward-policy-root (:artifact/root policy)})
         config (challenges/configuration
                 (keyword (name network-scope) "challenge-001-demo")
                 (slurp reviewer-public-key-path) policy [challenge])
         profile (pilot/build-challenge-001-profile config candidate comparison)
         metadata {:subject-root (:subject/root challenge)
                   :environment-root (demo-root [:environment (:artifact/root comparison)])
                   :runner-root (demo-root [:runner (:artifact/root comparison)])
                   :assignment-fields
                   {:research-assignment/id :assignment/challenge-001-local-demo
                    :research-assignment/policy-hash (demo-root [:assignment-policy (:artifact/root config)])
                    :research-assignment/review-round-hash (demo-root [:review-round (:artifact/root config)])
                    :research-assignment/target
                    {:target/kind :governance-mandated
                     :target/public-force-authorisation-scope-hash (demo-root :local-demo-scope)
                     :target/workflow-id 0
                     :target/reason :research}
                    :research-assignment/plan-root (demo-root [:plan (:artifact/root comparison)])}}
         research-input (pilot/build-context metadata comparison)
         evidence {(:artifact/root comparison) comparison}
         history []
         _ (do (pilot/validate-context! config profile (:artifact/root challenge)
                                        research-input evidence)
               (pilot-adapter/replay config profile history evidence))
         output (Paths/get output-directory (make-array String 0))]
    ;; Fail closed on existing paths so a demo command cannot replace a user's
    ;; trusted bundle or history.
     (Files/createDirectory output (make-array FileAttribute 0))
     (doseq [[filename value]
             [["configuration.edn" config]
              ["profile.edn" profile]
              ["comparison.edn" comparison]
              ["research-input.edn" research-input]
              ["evidence.edn" evidence]
              ["history.edn" history]
              ["demo-status.edn"
               {:artifact/schema "researcher-challenge-local-demo-status.v1"
                :demo/status :test-only-not-authorized
                :demo/network-scope network-scope
                :configuration/root (:artifact/root config)
                :profile/root (:artifact/root profile)
                :challenge/root (:artifact/root challenge)
                :candidate/root (:scope/candidate-root profile)
                :comparison/root (:artifact/root comparison)
                :research-input/root (get-in research-input
                                             [:analysis-output :research-analysis-output/root])
                :source-content-manifest/root
                (get-in candidate [:source/revision-observation
                                   :source-content-manifest/root])
                :reward/currency "TEST-POINTS"
                :funding/authorized? false
                :publication/authorized? false
                :activation/authorized? false}]]]
       (write-edn! output filename value))
     {:output-directory (str output)
      :configuration/root (:artifact/root config)
      :profile/root (:artifact/root profile)
      :challenge/root (:artifact/root challenge)
      :candidate/root (:scope/candidate-root profile)
      :comparison/root (:artifact/root comparison)
      :research-input/root (get-in research-input
                                   [:analysis-output :research-analysis-output/root])
      :status :test-only-not-authorized})))

(defn -main [& args]
  (when-not (<= 2 (count args) 3)
    (throw (ex-info "Usage: reviewer-public-key.pem NEW_OUTPUT_DIRECTORY [devnet|testnet]"
                    {:reason :invalid-arguments})))
  (let [[reviewer-public-key-path output-directory scope] args
        network-scope (case scope
                        nil :devnet
                        "devnet" :devnet
                        "testnet" :testnet
                        (throw (ex-info "Scope must be devnet or testnet"
                                        {:reason :unsupported-demo-network-scope
                                         :scope scope})))]
    (prn (prepare! reviewer-public-key-path output-directory network-scope))))
