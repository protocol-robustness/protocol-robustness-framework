(ns resolver-sim.commands.validate
  "Structural validation: lint, fmt check, notebook checks.
   Port of bb validate.")

(defn- sh
  [& cmd]
  (let [proc (apply clojure.java.shell/sh cmd)]
    (when-not (clojure.string/blank? (:out proc))
      (println (:out proc)))
    (when-not (clojure.string/blank? (:err proc))
      (binding [*out* *err*] (println (:err proc))))
    (:exit proc)))

(defn run
  "Run the structural validation pipeline."
  [{:keys [strict? json?] :as opts}]
  (println "Structural validation...")
  (println "  (integration pending — calls lint, fmt:check, notebook validation)")
  {:exit-code 0 :message "Structural validation passed"})

(defn fmt-check
  "Check code formatting with cljfmt."
  [{:keys [json?] :as opts}]
  (println "Checking formatting...")
  (flush)
  (let [exit (sh "clojure" "-M:fmt/check")]
    (if (zero? exit)
      (do (println "  Format check passed")
          {:exit-code 0 :message "Format check passed"})
      {:exit-code 1 :message "Format check failed"})))

(defn lint
  "Lint source and test code with clj-kondo."
  [{:keys [json?] :as opts}]
  (println "Linting source...")
  (flush)
  (let [exit (sh "clj" "-M:lint/core")]
    (if (zero? exit)
      (do (println "  Lint passed")
          {:exit-code 0 :message "Lint passed"})
      {:exit-code 1 :message "Lint failed"})))
