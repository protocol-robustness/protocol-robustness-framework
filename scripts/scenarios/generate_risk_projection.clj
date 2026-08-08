(ns scripts.scenarios.generate-risk-projection
  "Generate the full P0–P4 risk artifact set over an event-evidence bundle:

     risk-projection.v1        (exposure series, corpus-safe metrics)
     scenario-distribution.v1  (explicit empirical weighting, per outcome)
     var-projection.v1         (VaR p95/p99, ES, tail attribution, per outcome)

   Usage:
     clojure -M:test -m scripts.scenarios.generate-risk-projection
       <bundle-dir> <trace-dir> <output-dir>

   Writes (into output-dir, default tmp/risk-projection):
     risk-projection.edn        risk-card.html
     distribution.exposure.edn  distribution.loss.edn
     var-projection.exposure.edn  var-card.exposure.html
     var-projection.loss.edn      var-card.loss.html

   Cards are presentation-only and OUTSIDE every committed root. Each artifact
   root is re-verified before writing; the process exits non-zero on mismatch."
  (:require [clojure.java.io :as io]
            [resolver-sim.notebook-support.speds.risk :as risk]
            [resolver-sim.notebook-support.speds.risk-render :as render]
            [resolver-sim.notebook-support.speds.var :as var]
            [resolver-sim.notebook-support.speds.var-render :as var-render]))

(defn- fail! [msg]
  (binding [*out* *err*]
    (println msg))
  (System/exit 1))

(defn- latest-bundle-dir
  "Pick the most recent results/test-artifacts-<ts> directory that actually
   contains event-evidence JSONs (test-suite runs leave sparse artifact dirs
   that must not be chosen)."
  []
  (let [candidates (->> (io/file "results")
                        (.listFiles)
                        (filter #(re-matches #"test-artifacts-\d{8}T\d{6}Z" (.getName %)))
                        (sort-by #(.getName %))
                        reverse)]
    (or (some (fn [d]
                (let [ev (io/file d "event-evidence")]
                  (when (and (.isDirectory ev)
                             (seq (.listFiles ev)))
                    (.getAbsolutePath ev))))
              candidates)
        (fail! "no results/test-artifacts-<ts> bundle with event-evidence found"))))

(defn- emit!
  "Write one artifact EDN + optional rendered card. Verifies the given root
   recomputation against the stored root before writing."
  [output-dir artifact-name artifact root-fn root-path card-fn]
  (let [stored (get-in artifact root-path)
        fresh  (root-fn artifact)
        slug   (clojure.core/name artifact-name)]
    (when (not= stored fresh)
      (fail! (str slug " root verification FAILED")))
    (io/make-parents (io/file output-dir (str slug ".edn")))
    (spit (io/file output-dir (str slug ".edn"))
          (pr-str artifact))
    (when card-fn
      (spit (io/file output-dir (str card-fn ".html"))
            (var-render/render-card-html artifact)))
    (str output-dir "/" slug ".edn")))

(defn -main
  [& args]
  (let [[bundle-dir trace-dir output-dir]
        (or (and (= 3 (count args)) args)
            [nil nil nil])
        bundle-dir (or bundle-dir (latest-bundle-dir))
        trace-dir (or trace-dir (.getAbsolutePath (io/file "data/fixtures/traces")))
        output-dir (or output-dir "tmp/risk-projection")
        projection (risk/project {:bundle-dir bundle-dir
                                  :trace-dir trace-dir
                                  :run-id "generator"})
        risk-verify (risk/verify-root projection)]
    (when (not= :pass (:status risk-verify))
      (fail! (str "risk-projection root verification FAILED: " risk-verify)))
    (io/make-parents (io/file output-dir "risk-projection.edn"))
    (spit (io/file output-dir "risk-projection.edn")
          (pr-str projection))
    (spit (io/file output-dir "risk-card.html")
          (render/render-card-html projection))
    (let [exposure-dist (var/build-distribution projection :per-scenario-peak-exposure)
          loss-dist     (var/build-distribution projection :per-scenario-max-event-loss)
          exposure-var  (var/build-var-projection projection exposure-dist)
          loss-var      (var/build-var-projection projection loss-dist)]
      (emit! output-dir :distribution.exposure exposure-dist
             var/verify-distribution-root [:distribution/root] nil)
      (emit! output-dir :distribution.loss loss-dist
             var/verify-distribution-root [:distribution/root] nil)
      (emit! output-dir :var-projection.exposure exposure-var
             var/verify-var-root [:var/root] "var-card.exposure")
      (emit! output-dir :var-projection.loss loss-var
             var/verify-var-root [:var/root] "var-card.loss"))
    (println (str "bundle:  " bundle-dir))
    (println (str "traces:  " trace-dir))
    (println (str "rows:    " (count (:rows (:projection projection)))))
    (println (str "root:    " (get-in projection [:risk-projection/root :canonical/hash])))
    (println (str "wrote:   " output-dir "/risk-projection.edn  + risk-card.html"))
    (println (str "wrote:   " output-dir "/distribution.{exposure,loss}.edn"))
    (println (str "wrote:   " output-dir "/var-projection.{exposure,loss}.edn  + var-card.*.html"))
    (println "verify:  pass")))
