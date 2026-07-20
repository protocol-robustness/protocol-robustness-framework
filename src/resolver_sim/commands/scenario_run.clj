(ns resolver-sim.commands.scenario-run
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.io.paths :as paths])
  (:import [java.nio.file Files LinkOption Path Paths]
           [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util UUID]
           [java.security MessageDigest]
           [java.math BigInteger]))

(def ^:private formats #{:summary :failures :standard :verbose :audit})
(def ^:private options [[nil "--run-root DIR"] [nil "--output-dir DIR"] [nil "--scenario-output-dir DIR"] [nil "--save-output DIR"] [nil "--report-format FORMAT" :parse-fn keyword] [nil "--sensitivity-profile PROFILE" :parse-fn keyword] ["-v" "--verbose"] ["-f" "--failures"] ["-s" "--summary"] ["-a" "--audit"]])
(defn- count-opt [args opt] (count (filter #(or (= % opt) (str/starts-with? % (str opt "="))) args)))
(defn parse-request [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args options)
        roots (filter #(pos? (count-opt args %)) ["--run-root" "--output-dir" "--scenario-output-dir"])
        shorts (keep #(when (get options %) %) [:verbose :failures :summary :audit])
        chosen (concat shorts (when (:report-format options) [(:report-format options)]))
        errs (cond-> (vec errors)
               (empty? arguments) (conj "Specify at least one scenario reference")
               (> (count roots) 1) (conj "Specify only one output-root option")
               (and (seq roots) (> (count-opt args (first roots)) 1)) (conj (str "Specify " (first roots) " only once"))
               (:save-output options) (conj "--save-output is not supported for scenario bundles; use an explicit export command instead")
               (> (count chosen) 1) (conj "Specify at most one report-format option")
               (and (seq chosen) (not (formats (first chosen)))) (conj (str "Unsupported report format: " (name (first chosen))))
               (and (:sensitivity-profile options) (not (#{:public :internal} (:sensitivity-profile options)))) (conj "Sensitivity profile must be public or internal"))]
    (if (seq errs) {:ok? false :errors errs :summary summary}
      {:ok? true :request {:scenario/ref (first arguments) :scenario/refs arguments :run/root (or (:run-root options) (:output-dir options) (:scenario-output-dir options)) :report-format (or (first chosen) :standard) :sensitivity/profile (or (:sensitivity-profile options) :public)}
       :warnings (cond-> [] (:output-dir options) (conj "--output-dir is deprecated; use --run-root") (:scenario-output-dir options) (conj "--scenario-output-dir is deprecated; use --run-root"))})))
(defn- hash-prefix [s] (let [d (MessageDigest/getInstance "SHA-256")] (.update d (.getBytes (str s) "UTF-8")) (subs (format "%064x" (BigInteger. 1 (.digest d))) 0 12)))
(defn scenario-slug [ref] (let [base (-> ref (str/replace #"^.*/" "") (str/replace #"\.(edn|json)$" "") (str/replace #"[^A-Za-z0-9._-]+" "-") (str/replace #"(^-+|-+$)" ""))] (str (if (str/blank? base) "scenario" base) "-" (hash-prefix ref))))
(defn generate-run-id ([] (generate-run-id (Instant/now) (UUID/randomUUID))) ([instant uuid] (str "run-" (.format (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'") (.atZone instant ZoneOffset/UTC)) "-" (subs (str uuid) 0 12))))
(defn- state [^Path root] (cond (not (Files/exists root (make-array LinkOption 0))) :absent (not (Files/isDirectory root (make-array LinkOption 0))) :not-a-directory (or (Files/exists (.resolve root "COMPLETED") (make-array LinkOption 0)) (Files/exists (.resolve root paths/completion) (make-array LinkOption 0))) :completed (or (Files/exists (.resolve root "manifest") (make-array LinkOption 0)) (Files/exists (.resolve root "scenarios") (make-array LinkOption 0)) (Files/exists (.resolve root paths/run-state) (make-array LinkOption 0))) :incomplete :else (with-open [s (Files/list root)] (if (.hasNext (.iterator s)) :unrelated :empty))))
(defn- child [^Path root & xs] (reduce #(.resolve ^Path %1 ^String %2) root xs))
(defn build-run-context [{:keys [scenario/ref run/root report-format sensitivity/profile] :as request} {:keys [project-root run-id] :or {project-root "."}}]
  (when-not (and (string? ref) (not (str/blank? ref)))
    (throw (ex-info "Scenario reference is required" {:request request})))
  (let [id (or run-id (generate-run-id))
        slug (scenario-slug ref)
        input (or root (str paths/runs-root "/" slug "-" id))
        envelope (lifecycle/build-run-envelope
                  {:run-id id :run-type :scenario :run-root input
                   :project-root project-root :sensitivity-profile profile})
        root (:run/root envelope)]
    (merge envelope
           {:scenario/ref ref :scenario/slug slug :report-format report-format
            :inputs/dir (child root "inputs" "scenarios")
            :scenario/root (child root "scenarios" slug)
            :execution/dir (child root "scenarios" slug "execution")
            :forensic/dir (child root "scenarios" slug "forensic")
            :summaries/dir (child root "scenarios" slug "summaries")
            :replay/file (child root "scenarios" slug "execution" "replay-output.json")})))