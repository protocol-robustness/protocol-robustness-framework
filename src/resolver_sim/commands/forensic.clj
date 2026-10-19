(ns resolver-sim.commands.forensic
  "Registered adapters for the existing Python forensic workflows."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.data.json :as json]
            [resolver-sim.commands.document :as doc]))

(def ^:private contracts
  {:run {:required #{:repo-root :run-request :registry-snapshot :evidence-policy :output-base}
         :paths #{:run-request :registry-snapshot :evidence-policy :output-base :sign}
         :strings #{:label :signing-key-id :tsa-url}
         :flags #{:dry-run :no-harden}}
   :validate {:required #{:repo-root :bundle}
              :paths #{:bundle :run-dir} :strings #{} :flags #{}}
   :verify {:required #{:repo-root :run-dir}
            :paths #{:run-dir :public-key :output}
            :strings #{:expected-protocol} :flags #{:require-protocol-semantics}}
   :reproduce {:required #{:repo-root :run-dir :output-base}
               :paths #{:run-dir :output-base} :strings #{} :flags #{}}})

(defn invocation
  "Build argv without a shell. File paths are relative to the request file."
  [operation input request]
  (let [{:keys [required paths strings flags]} (get contracts operation)
        _ (doc/require! (some? required) "Unsupported forensic operation")
        _ (doc/closed! request required (into paths (concat strings flags)))
        resolve #(doc/resolve-path input %)
        root (resolve (:repo-root request))
        _ (doc/require! (and (.isFile (io/file root "deps.edn"))
                             (.isFile (io/file root "scripts/forensic" (str (name operation) ".py"))))
                        "repo-root must contain the PRF forensic workflow and deps.edn")
        positional (case operation :validate :bundle (:verify :reproduce) :run-dir nil)
        path-args (mapcat (fn [k]
                            (when (and (contains? request k) (not= positional k))
                              [(str "--" (name k)) (resolve (get request k))]))
                          (sort paths))
        string-args (mapcat (fn [k]
                              (when (contains? request k)
                                (doc/require! (and (string? (get request k)) (seq (get request k)))
                                              "Expected a nonempty forensic option string")
                                [(str "--" (name k)) (get request k)])) (sort strings))
        flag-args (keep (fn [k]
                          (when (contains? request k)
                            (doc/require! (boolean? (get request k)) "Forensic flags must be booleans")
                            (when (get request k) (str "--" (name k))))) (sort flags))]
    {:root root
     :argv (vec (concat ["python3" "-m" (str "scripts.forensic." (name operation))]
                        (when positional [(resolve (get request positional))])
                        (when (= operation :run) ["--repo-root" root])
                        path-args string-args flag-args))
     :env (cond-> (assoc (into {} (System/getenv))
                         "PYTHONPATH" (str root java.io.File/pathSeparator (io/file root "scripts")))
            (= operation :run) (assoc "PRF_FORENSIC_COMMAND_JSON" "1"))}))

(defn- execute [operation opts]
  (doc/execute opts
               (fn [request]
                 (let [{:keys [root argv env]} (invocation operation (:input opts) request)
                       result (apply shell/sh (concat argv [:dir root :env env]))
                       report (try (json/read-str (:out result) :key-fn keyword)
                                   (catch Exception _ nil))
                       output {:schema-version "forensic-command-result.v2"
                               :operation operation :repo-root root
                               :exit-code (:exit result) :stderr (:err result)}
                       output (if report
                                (assoc output :report report)
                                (cond-> output (seq (:out result)) (assoc :stdout (:out result))))]
                   (doc/emit opts output (:exit result))))))

(defn run [opts] (execute :run opts))
(defn validate [opts] (execute :validate opts))
(defn verify [opts] (execute :verify opts))
(defn reproduce [opts] (execute :reproduce opts))
