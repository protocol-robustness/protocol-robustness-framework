#!/usr/bin/env bb
(ns profile-view
  "Generate and verify constrained profile workspaces from `profiles/*.edn`.

   Clojure files are linked individually after following declared entry
   namespaces and their resolver-sim requires. This preserves namespace paths
   without exposing a broad ancestor directory. Other profile material is
   always declared explicitly in the component manifest."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(def repo-root (fs/path (System/getProperty "user.dir")))
(def profiles-dir (fs/path repo-root "profiles"))
(def source-roots ["src" "protocols_src"])

(defn read-edn [path] (edn/read-string (slurp (str path))))
(defn profile-files [] (fs/glob profiles-dir "*.edn"))

(defn profiles-by-id []
  (into {}
        (keep (fn [path]
                (let [profile (read-edn path)]
                  (when (:profile/id profile) [(:profile/id profile) profile]))))
        (profile-files)))

(defn profile-by-name [name]
  (let [path (fs/path profiles-dir (str name ".edn"))]
    (when-not (fs/exists? path)
      (throw (ex-info "Unknown profile" {:profile name :path (str path)})))
    (read-edn path)))

(defn merge-profile
  ([profiles profile] (merge-profile profiles profile #{}))
  ([profiles profile visiting]
   (let [id (:profile/id profile)]
     (when (contains? visiting id)
       (throw (ex-info "Profile inheritance cycle" {:profile id :visiting visiting})))
     (if-let [parent-id (:profile/extends profile)]
       (let [parent (get profiles parent-id)]
         (when-not parent
           (throw (ex-info "Unknown parent profile" {:profile id :parent parent-id})))
         (let [resolved-parent (merge-profile profiles parent (conj visiting id))]
           (-> (merge resolved-parent profile)
               (assoc :profile/components
                      (vec (distinct (concat (:profile/components resolved-parent)
                                             (:profile/components profile))))))))
       profile))))

(defn component-closure [components ids]
  (letfn [(visit [seen visiting id]
            (when-not (contains? components id)
              (throw (ex-info "Profile selects an unavailable component" {:component id})))
            (when (contains? visiting id)
              (throw (ex-info "Component dependency cycle" {:component id :visiting visiting})))
            (if (contains? seen id)
              seen
              (reduce #(visit %1 (conj visiting id) %2)
                      (conj seen id)
                      (get-in components [id :component/requires]))))]
    (reduce #(visit %1 #{} %2) #{} ids)))

(defn ns->relative-path [sym]
  (str (-> (str sym)
           (str/replace "-" "_")
           (str/replace "." "/"))
       ".clj"))

(defn source-file [ns-sym]
  (let [relative (ns->relative-path ns-sym)]
    (some (fn [root]
            (let [path (fs/path repo-root root relative)]
              (when (fs/exists? path) path)))
          source-roots)))

(defn required-namespaces [path]
  (->> (re-seq #"\[\s*(resolver-sim(?:\.[A-Za-z0-9_-]+)+)" (slurp (str path)))
       (map second)
       (map symbol)
       set))

(defn clojure-closure [entry-namespaces]
  (loop [pending (vec entry-namespaces) seen #{} files #{}]
    (if-let [ns-sym (peek pending)]
      (if (contains? seen ns-sym)
        (recur (pop pending) seen files)
        (let [path (source-file ns-sym)]
          (when-not path
            (throw (ex-info "Cannot locate selected namespace" {:namespace ns-sym})))
          (recur (into (pop pending) (required-namespaces path))
                 (conj seen ns-sym)
                 (conj files path))))
      files)))

(defn clear-directory! [path]
  (when (fs/exists? path) (fs/delete-tree path))
  (fs/create-dirs path))

(def forbidden-source-roots #{".profile-view" "target" "results"})

(defn safe-source! [source]
  (let [source (.toRealPath (fs/path source) (make-array java.nio.file.LinkOption 0))
        relative (.normalize (.relativize repo-root source))
        first-segment (first (str/split (str relative) #"/"))]
    (when (or (.startsWith relative "..") (contains? forbidden-source-roots first-segment))
      (throw (ex-info "Profile source is outside the allowed repository material"
                      {:source (str source) :relative (str relative)})))
    source))

(defn symlink! [view-root source]
  (let [source (safe-source! source)
        relative (.relativize repo-root source)
        target (fs/path view-root relative)]
    (fs/create-dirs (fs/parent target))
    (when (fs/exists? target) (fs/delete target))
    (java.nio.file.Files/createSymbolicLink target source (make-array java.nio.file.attribute.FileAttribute 0))))


(defn runtime-profile [profile]
  (when-let [runtime (:profile/runtime profile)]
    (let [request-path (fs/path repo-root ".profile-view" "runtime-request.edn")
          request (:runtime/options runtime)]
      (spit (str request-path) (pr-str request))
      (let [{:keys [exit out err]} (process/shell {:dir (str repo-root)
                                                    :continue true
                                                    :out :string
                                                    :err :string}
                                                   "clojure" "-M" "-m"
                                                   "resolver-sim.execution.runtime-profile"
                                                   (str request-path))]
        (when-not (zero? exit)
          (throw (ex-info "JVM runtime-profile authority rejected request"
                          {:exit exit :stderr err})))
        (try
          (edn/read-string out)
          (catch Exception e
            (throw (ex-info "JVM runtime-profile authority returned invalid EDN"
                            {:output out} e))))))))

(defn generated-deps [view-root]
  (let [base (read-edn (fs/path repo-root "deps.edn"))]
    (spit (str (fs/path view-root "deps.edn"))
          (pr-str (assoc (select-keys base [:mvn/repos :deps])
                         :paths ["src" "protocols_src" "resources"]
                         :aliases {:profile/check {:extra-paths ["test"]}})))))

(defn resolved-profile [name]
  (let [profiles (profiles-by-id)
        profile (merge-profile profiles (profile-by-name name))
        manifest (read-edn (fs/path profiles-dir "components.edn"))
        components (:components manifest)
        selected (component-closure components (:profile/components profile))]
    {:profile profile
     :components (select-keys components selected)
     :component-ids (vec (sort selected))}))

(defn closure-report [name]
  (let [{:keys [components component-ids]} (resolved-profile name)
        owned (set (mapcat :component/owned-namespaces (vals components)))
        resolved (clojure-closure (mapcat :component/entry-namespaces (vals components)))
        groups (frequencies
                (map #(str/join "/" (take 3 (str/split (str (.relativize repo-root %)) #"/")))
                     resolved))]
    {:profile name
     :component-ids component-ids
     :owned-namespaces (vec (sort owned))
     :owned-namespace-count (count owned)
     :transitive-source-files (vec (sort (map #(str (.relativize repo-root %)) resolved)))
     :transitive-source-file-count (count resolved)
     :namespace-groups groups}))

(defn diff! [left right]
  (let [a (closure-report left)
        b (closure-report right)
        added-components (vec (sort (set/difference (set (:component-ids b))
                                                     (set (:component-ids a)))))
        added-owned (vec (sort (set/difference (set (:owned-namespaces b))
                                                (set (:owned-namespaces a)))))
        added-source (vec (sort (set/difference (set (:transitive-source-files b))
                                                 (set (:transitive-source-files a)))))
        added-groups (into {} (keep (fn [[k n]]
                                      (let [delta (- n (get (:namespace-groups a) k 0))]
                                        (when (pos? delta) [k delta]))))
                              (:namespace-groups b))]
    (println "PROFILE DIFF" left "->" right)
    (println "ADDED COMPONENTS" (pr-str added-components))
    (println "ADDED OWNED NAMESPACES" (count added-owned))
    (doseq [ns (sort added-owned)] (println " " ns))
    (println "ADDED TRANSITIVE SOURCE FILES" (count added-source))
    (doseq [path added-source] (println " " path))
    (println "ADDED NAMESPACE GROUPS" (pr-str added-groups))
    (println "COUNTS" {:left (:transitive-source-file-count a)
                         :right (:transitive-source-file-count b)})))

(defn generate! [name]
  (let [{:keys [profile components component-ids] :as resolved} (resolved-profile name)
        view-root (fs/path repo-root ".profile-view" name)
        source-files (sort-by str (clojure-closure (mapcat :component/entry-namespaces (vals components))))
        materials (sort-by :path (mapcat :component/material (vals components)))
        runtime-profile (runtime-profile profile)]
    (clear-directory! view-root)
    (doseq [source source-files] (symlink! view-root source))
    (doseq [{:keys [path kind] :as material} materials]
      (when-not (keyword? kind)
        (throw (ex-info "Profile material requires a kind" {:material material})))
      (let [source (fs/path repo-root path)]
        (when-not (fs/exists? source)
          (throw (ex-info "Declared profile material is missing" {:path path})))
        (symlink! view-root source)))
    (generated-deps view-root)
    (when runtime-profile
      (spit (str (fs/path view-root "runtime-options.edn")) (pr-str runtime-profile)))
    (spit (str (fs/path view-root "PROFILE.edn"))
          (pr-str (assoc resolved
                         :profile/generator :profile-view.v1
                         :profile/view-path (str ".profile-view/" name)
                         :profile/resolved-source-files (mapv #(str (.relativize repo-root %)) source-files)
                         :profile/resolved-material materials
                         :profile/runtime-profile runtime-profile
                         :profile/source-file-count (count source-files))))
    (println "Generated" (str view-root) "with" (count source-files) "Clojure files")
    view-root))

(defn namespace-present? [view-root ns-sym]
  (some #(fs/exists? (fs/path view-root % (ns->relative-path ns-sym)))
        source-roots))

(defn check! [name]
  (let [view-root (generate! name)
        {:keys [components]} (resolved-profile name)
        all-components (:components (read-edn (fs/path profiles-dir "components.edn")))
        expected (set (mapcat :component/entry-namespaces (vals components)))
        owned (into {} (map (juxt key #(set (:component/owned-namespaces (val %)))) all-components))
        selected-ids (set (keys components))
        forbidden (->> owned
                       (filter #(get-in all-components [(key %) :component/absence-enforced?]))
                       (remove #(contains? selected-ids (key %)))
                       (mapcat val)
                       set)
        present? #(namespace-present? view-root %)]
    (doseq [ns-sym expected]
      (when-not (present? ns-sym)
        (throw (ex-info "Selected namespace absent from profile view" {:namespace ns-sym}))))
    (doseq [ns-sym forbidden]
      (when (and (not (contains? expected ns-sym)) (present? ns-sym))
        (throw (ex-info "Excluded bounty namespace leaked into profile view"
                        {:namespace ns-sym :profile name}))))
    (let [{:keys [exit]} (process/shell {:dir (str view-root) :continue true}
                                        "clojure" "-M" "-e"
                                        (str "(doseq [n '" (vec expected) "] (require n))"))]
      (when-not (zero? exit)
        (throw (ex-info "Generated profile cannot load its declared entry namespaces"
                        {:profile name :exit exit}))))
    (println "Profile boundary and load checks passed:" name)))

(defn describe! [name]
  (prn (merge (resolved-profile name) (closure-report name))))

(let [[command name right] *command-line-args*]
  (case command
    "describe" (describe! name)
    "diff" (diff! name right)
    "view" (generate! name)
    "check" (check! name)
    (throw (ex-info "Usage: bb scripts/profile_view.clj <describe|view|check> <profile-name>"
                    {:command command}))))
