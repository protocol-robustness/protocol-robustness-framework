(ns scripts.test-artifact-isolation
  "Verify that binding *artifact-dir* per-namespace prevents cross-contamination."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.util.evidence :as ev]))

(defn- make-ev [n tag]
  (ev/make-evidence-record
   {:artifact-kind :transition
    :step 1 :block-time 1000
    :before {:counter (dec n)}
    :after {:counter n}
    :action {:type :increment :n n}
    :result {:ok true}
    :attribution {:ctx/run-id tag
                  :ctx/scenario-id tag
                  :ctx/step 1
                  :ctx/event-id (str "evt-" n)}}))

(let [tmp-dir (str (System/getProperty "java.io.tmpdir")
                   "/artifact-isolation-test-" (java.util.UUID/randomUUID))
      dir-a (str tmp-dir "/namespace-A")
      dir-b (str tmp-dir "/namespace-B")
      _ (.mkdirs (io/file dir-a))
      _ (.mkdirs (io/file dir-b))]
  ;; Run two isolated scenarios in parallel
  (let [fut-a (future
                (binding [evcfg/*artifact-dir* dir-a]
                  (chain/with-fresh-registry
                    (chain/with-fresh-chain-cursor
                      (let [ev (make-ev 1 "namespace-A")]
                        (chain/register-evidence! ev)
                        (chain/register-scenario-snapshot!)))
                    (chain/finalize-and-write!))))
        fut-b (future
                (binding [evcfg/*artifact-dir* dir-b]
                  (chain/with-fresh-registry
                    (chain/with-fresh-chain-cursor
                      (let [ev (make-ev 1 "namespace-B")]
                        (chain/register-evidence! ev)
                        (chain/register-scenario-snapshot!)))
                    (chain/finalize-and-write!))))]
    @fut-a
    @fut-b)
  ;; Check isolation
  (let [files-a (set (map str (file-seq (io/file dir-a))))
        files-b (set (map str (file-seq (io/file dir-b))))
        overlap (clojure.set/intersection files-a files-b)
        has-evidence-a (some #(re-find #"evidence" %) files-a)
        has-evidence-b (some #(re-find #"evidence" %) files-b)]
    (println "=== Artifact isolation test ===")
    (println (str "Namespace A files: " (count files-a) " has-evidence: " (boolean has-evidence-a)))
    (println (str "Namespace B files: " (count files-b) " has-evidence: " (boolean has-evidence-b)))
    (println (str "Overlapping files: " (count overlap)))
    (let [passed (and (zero? (count overlap)) has-evidence-a has-evidence-b)]
      (if passed
        (println "PASS: No cross-namespace artifact contamination")
        (do (println "FAIL: Artifact isolation breached")
            (System/exit 1)))))
  ;; Cleanup
  (doseq [f (reverse (doall (file-seq (io/file tmp-dir))))]
    (.delete f)))
