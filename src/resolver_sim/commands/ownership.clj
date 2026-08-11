(ns resolver-sim.commands.ownership
  "Content-authority classification audit command.

   Delegates to scripts/ownership_audit.clj (the read-only audit). Registers as
   a :fast backstop-tier command so it runs in backstop:fast / backstop /
   backstop:full, and is also invocable directly via `bb check:ownership`."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]))

(defn- run-audit
  [args]
  (let [result (apply sh/sh "clojure" "-M" "-m" "scripts.ownership-audit" args)]
    (when-not (str/blank? (:out result))
      (println (:out result)))
    (when-not (str/blank? (:err result))
      (binding [*out* *err*] (println (:err result))))
    (:exit result)))

(defn check
  "Run the content-authority audit in --check mode. Returns
   {:exit-code N :message str}. Fails (non-zero) on any classification error."
  [{:keys [manifest]}]
  (let [args (cond-> ["--check"]
               manifest (conj "--manifest" manifest))
        exit (run-audit args)]
    {:exit-code exit
     :message (if (zero? exit)
                "content-authority audit passed"
                "content-authority audit failed")}))

(defn report
  "Run the audit in --report mode (never fails)."
  [{:keys [manifest]}]
  (let [args (cond-> ["--report"]
               manifest (conj "--manifest" manifest))
        exit (run-audit args)]
    {:exit-code exit
     :message "content-authority report"}))

(defn rootzones
  "Print the informational rootzone split."
  [{:keys [manifest]}]
  (let [args (cond-> ["--rootzones"]
               manifest (conj "--manifest" manifest))
        exit (run-audit args)]
    {:exit-code exit
     :message "content-authority rootzones report"}))
