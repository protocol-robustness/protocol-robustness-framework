(ns scripts.demos.export-public-demo
  "Generate the public-demo.v1 JSON artifacts consumed by the product site.

   Usage:
     clojure -M -m scripts.demos.export-public-demo            # write all (default)
     clojure -M -m scripts.demos.export-public-demo --id <id>  # write one
     clojure -M -m scripts.demos.export-public-demo --check    # compare all, exit 1 on drift
     clojure -M -m scripts.demos.export-public-demo --out <path> --id <id>

   Default outputs live in site/generated/demos/<id>.json. Only
   presentation-safe facts derived from the executable PRF demos are emitted.
   Missing required evidence fails the run (fail closed)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]

            [resolver-sim.demos.public.current-head :as current-head]
            [resolver-sim.demos.public.liquidity-shortfall :as liquidity-shortfall]
            [resolver-sim.demos.public.reordered-evidence :as reordered-evidence]))

(defn- default-out [demo-id]
  (str "site/generated/demos/" demo-id ".json"))

(defn- projectors
  "Registry of public-demo projections, keyed by demo id."
  []
  {"current-head"       {:write (fn [] (current-head/json-str))}
   "liquidity-shortfall" {:write (fn [] (liquidity-shortfall/json-str))}
   "reordered-evidence" {:write (fn [] (reordered-evidence/json-str))}})

(defn write! [path demo-id]
  (let [write-fn (get-in (projectors) [demo-id :write])]
    (when-not write-fn
      (throw (ex-info (str "unknown public demo: " demo-id)
                      {:demo/id demo-id})))
    (io/make-parents path)
    (spit path (write-fn))
    (println (str "wrote " path))))

(defn check! [path demo-id]
  (let [write-fn (get-in (projectors) [demo-id :write])
        _ (when-not write-fn
            (throw (ex-info (str "unknown public demo: " demo-id)
                            {:demo/id demo-id})))
        fresh (write-fn)
        committed (when (.exists (io/file path)) (slurp path))]
    (if (= fresh committed)
      (do (println (str "OK: " path " matches the executable PRF result"))
          true)
      (do (println (str "DRIFT: " path " does not match the executable PRF result"
                        (when (nil? committed) " (missing committed artifact)")))
          false))))

(defn -main [& args]
  (let [args (vec (or args []))
        check? (some #{"--check"} args)
        out (let [i (.indexOf args "--out")]
              (when (and (not= i -1) (seq (drop (inc i) args)))
                (nth args (inc i))))
        demo-id (let [i (.indexOf args "--id")]
                  (if (and (not= i -1) (seq (drop (inc i) args)))
                    (nth args (inc i))
                    nil))
        ids (if demo-id [demo-id] (keys (projectors)))
        path (or out (default-out (or demo-id (first ids))))
        all-ok? (reduce
                 (fn [ok? id]
                   (let [p (or out (default-out id))
                         ok (if check?
                              (check! p id)
                              (do (write! p id) true))]
                     (and ok? ok)))
                 true
                 ids)]
    (when (not all-ok?)
      (System/exit 1))))
