(ns resolver-sim.scripts.evidence-pack
  "Entry point for bb evidence:pack and bb evidence:verify.
   Args are passed via system property to avoid Clojure CLI arg forwarding issues."
  (:require [resolver-sim.forensic.evidence-pack :as ep])
  (:gen-class))

(defn -main
  "Usage: clojure -M:with-sew -m resolver-sim.scripts.evidence-pack <pack|verify> <run-id-or-dir> [<out-dir>] [--tar]"
  [& args]
  (let [[action & rest-args] args]
    (case action
      "pack"
      (let [run-id-or-dir (first rest-args)
            remaining (rest rest-args)
            has-tar? (some #{"--tar"} remaining)
            pack-dir (first (remove #{"--tar"} remaining))
            opts (cond-> {}
                   pack-dir (assoc :pack/dir pack-dir)
                   has-tar? (assoc :pack/tar true))]
        (when-not run-id-or-dir
          (println "Usage: pack <run-id-or-dir> [<out-dir>] [--tar]")
          (System/exit 1))
        (ep/pack! run-id-or-dir opts)
        (System/exit 0))
      "verify"
      (let [pack-dir (first rest-args)]
        (when-not pack-dir
          (println "Usage: verify <pack-dir>")
          (System/exit 1))
        (let [result (ep/verify pack-dir)]
          (System/exit (if (:valid? result) 0 1))))
      (println "Usage: evidence-pack <pack|verify> <args>")
      (System/exit 1))))
