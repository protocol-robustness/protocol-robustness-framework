(ns scripts.verify-context-isolation
  "Focused test proving that two concurrently created namespace contexts have
   distinct artifact roots and distinct evidence identities/locks, and that
   shared-sequential mode instead shares the root identities (the baseline it
   is meant to reproduce).

   Run:
     clojure -M:test:with-sew -m scripts.verify-context-isolation

   Exits 0 on success, 1 on any failure."
  (:require [clojure.java.io :as io]
            [scripts.run-sew-tests :as rst]))

(defn- context-identities-now
  [mode run-id idx]
  (rst/context-identities mode "unit" run-id idx 'scripts.verify-context-isolation))

(defn- distinct-identities?
  "Two contexts are isolated iff every identity key differs (identical? for
   objects, path inequality for artifact roots)."
  [a b]
  (every? (fn [k]
            (if (= k :artifact-dir)
              (not= (get a k) (get b k))
              (not (identical? (get a k) (get b k)))))
          (keys a)))

(defn- shared-identities?
  "Shared mode binds nothing fresh, so node/attestation registries resolve to
   the same root atoms across contexts."
  [a b]
  (and (identical? (:node-registry a) (:node-registry b))
       (identical? (:attestation-registry a) (:attestation-registry b))))

(defn run-checks
  []
  (let [run-id (str "verify-" (System/currentTimeMillis))
        t1 (future (context-identities-now :isolated-parallel run-id 0))
        t2 (future (context-identities-now :isolated-parallel run-id 1))
        iso-a @t1
        iso-b @t2
        shared-a (context-identities-now :shared-sequential run-id 0)
        shared-b (context-identities-now :shared-sequential run-id 1)
        checks
        [{:name "artifact dirs created and distinct"
          :pass (and (every? #(.exists (io/file (:artifact-dir %))) [iso-a iso-b])
                     (not= (:artifact-dir iso-a) (:artifact-dir iso-b)))}
         {:name "node registries distinct"
          :pass (not (identical? (:node-registry iso-a) (:node-registry iso-b)))}
         {:name "node persistence locks distinct"
          :pass (not (identical? (:node-lock iso-a) (:node-lock iso-b)))}
         {:name "attestation registries distinct"
          :pass (not (identical? (:attestation-registry iso-a) (:attestation-registry iso-b)))}
         {:name "evidence registries distinct"
          :pass (not (identical? (:evidence-registry iso-a) (:evidence-registry iso-b)))}
         {:name "scenario-evidence atoms distinct"
          :pass (not (identical? (:scenario-evidence iso-a) (:scenario-evidence iso-b)))}
         {:name "chain cursors distinct"
          :pass (not (identical? (:chain-cursor iso-a) (:chain-cursor iso-b)))}
         {:name "isolated contexts fully isolated"
          :pass (distinct-identities? iso-a iso-b)}
         {:name "shared-sequential shares root registries"
          :pass (shared-identities? shared-a shared-b)}]]
    (println "context-isolation checks:")
    (doseq [{:keys [name pass]} checks]
      (println (str (if pass "  PASS  " "  FAIL  ") name)))
    (when-not (every? :pass checks)
      (println "\nisolated-a:" (pr-str iso-a))
      (println "\nisolated-b:" (pr-str iso-b))
      (System/exit 1))
    (println "\ncontext isolation: OK")))

(defn -main
  [& _]
  (require 'resolver-sim.evidence.chain)
  (run-checks))
