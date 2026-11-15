(ns resolver-sim.commands.dispute-review
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [resolver-sim.dispute-review :as review]))

(def ^:private options
  [["-i" "--input FILE" "EDN review request for initialization"]
   ["-r" "--review DIR" "Review package directory to verify"]])

(defn- parse [args]
  (let [{:keys [options errors summary arguments]} (cli/parse-opts args options)]
    (when (seq errors) (throw (ex-info (str/join "; " errors) {})))
    (when (seq arguments) (throw (ex-info (str "Unexpected arguments: " (pr-str arguments)) {})))
    {:options options :summary summary}))

(defn new-review [{:keys [cmd/raw-args]}]
  (let [{:keys [options summary]} (parse raw-args)]
    (if-not (:input options)
      (do (println summary) {:exit-code 2})
      (let [request (edn/read-string (slurp (:input options)))
            created (review/initialize! request (review/root-dir))]
        (println (str "id=" (:id created)))
        (println (str "directory=" (:directory created)))
        {:exit-code 0}))))

(defn verify-review [{:keys [cmd/raw-args]}]
  (let [{:keys [options summary]} (parse raw-args)]
    (if-not (:review options)
      (do (println summary) {:exit-code 2})
      (let [result (review/verify (:review options))]
        (prn result)
        {:exit-code (if (or (= :failed (:schema result))
                            (= :failed (:content-identity result))
                            (= :failed (get-in result [:source-identities :status]))
                            (= :failed (get-in result [:subject-identity :status]))
                            (= :failed (get-in result [:policy-identity :status]))
                            (pos? (get-in result [:evidence :failed] 0))
                            (= :failed (get-in result [:reproducibility :status])))
                      1 0)}))))
