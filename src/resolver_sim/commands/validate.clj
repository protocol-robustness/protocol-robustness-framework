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

(defn- ok?
  [label f]
  (print (str "▶ " label "... "))
  (flush)
  (let [ok (try (zero? (f)) true (catch Exception _ false))]
    (println (if ok "PASS" "FAIL"))
    ok))

(defn run
  "Run the structural validation pipeline: lint, notebook checks, SPEDS."
  [{:keys [strict? json?] :as opts}]
  (println "Structural validation...")
  (let [lint-ok   (ok? "Lint (src:test)" #(sh "clojure" "-M:lint/core"))
        nb-ok     (ok? "Notebook namespace check"
                       #(sh "scripts/with-test-artifact-lock.sh" "clojure"
                            "-M:with-sew"
                            "-e" "(require (quote [resolver-sim.notebook-support.checks]) (quote [notebooks.report])) (System/exit 0)"))
        all-nb-ok (ok? "All notebooks load" #(sh "bb" "test:notebooks"))
        speds-ok  (ok? "SPEDS tests" #(sh "bb" "test:speds"))]
    (if (and lint-ok nb-ok all-nb-ok speds-ok)
      (do (println "VALIDATION PASSED")
          {:exit-code 0 :message "Validation passed"})
      (do (println "VALIDATION FAILED")
          {:exit-code 1 :message "Validation failed"}))))

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
  (let [exit (sh "clojure" "-M:lint/core")]
    (if (zero? exit)
      (do (println "  Lint passed")
          {:exit-code 0 :message "Lint passed"})
      {:exit-code 1 :message "Lint failed"})))
