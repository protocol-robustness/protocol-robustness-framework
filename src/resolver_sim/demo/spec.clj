(ns resolver-sim.demo.spec
  "Demo spec schema and validation.

   A Demo Spec is an EDN file under demos/<id>/demo.edn that defines
   what the demo does, what commands to run, and how to interpret results."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

(def required-top-level
  #{:demo/id :demo/title :sections})

(def section-keys
  #{:id :title :command :text :display :cwd :explain :expected-artifacts :pause-ms})

(def display-types
  #{:title-card :section-header :command-output :summary})

;; ── Defaults ─────────────────────────────────────────────────────────────────

(def default-playback
  {:mode :auto
   :terminal {:cols 120 :rows 36}
   :default-pause-ms 3000
   :title-pause-ms 4000
   :result-pause-ms 8000
   :header-pause-ms 2000
   :final-pause-ms 6000})

;; ── Validation ──────────────────────────────────────────────────────────────

(defn validate
  "Validate a demo spec map. Returns {:valid true} or {:valid false :errors [...]}."
  [spec]
  (let [errors (atom [])]
    (doseq [k required-top-level]
      (when (nil? (get spec k))
        (swap! errors conj (str "Missing required key: " k))))
    (when (and (:demo/id spec) (not (keyword? (:demo/id spec))))
      (swap! errors conj ":demo/id must be a keyword"))
    (when-let [sections (:sections spec)]
      (when-not (vector? sections)
        (swap! errors conj ":sections must be a vector"))
      (doseq [s sections]
        (when-not (:id s)
          (swap! errors conj "Each section must have an :id"))
        (when-not (:title s)
          (swap! errors conj "Each section must have a :title"))
        (when (and (:command s) (:text s))
          (swap! errors conj (str "Section " (:id s) " cannot have both :command and :text")))))
    (if (empty? @errors)
      {:valid true}
      {:valid false :errors @errors})))

;; ── Loading ─────────────────────────────────────────────────────────────────

(defn load-spec [dir]
  (let [f (io/file dir "demo.edn")]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn find-spec [id]
  (let [id-str (if (keyword? id) (name id) id)
        demo-dir (if (or (keyword? id)
                         (not (or (.contains id-str "/")
                                  (.contains id-str "."))))
                   (str "demos/" id-str) id-str)]
    (load-spec demo-dir)))

;; ── Accessors ───────────────────────────────────────────────────────────────

(defn demo-id [spec] (:demo/id spec))
(defn demo-title [spec] (:demo/title spec))
(defn sections [spec] (:sections spec))

(defn scenario-command
  "The command that executes the demo's underlying scenario, used to detect the
   scenario outcome. Prefers the section with :id :run, else the first section
   that carries a command."
  [spec]
  (or (some (fn [s] (when (= :run (:id s)) (:command s))) (sections spec))
      (some :command (sections spec))))

(defn playback-config
  "Get playback config merged with defaults."
  [spec]
  (merge default-playback (:playback spec)))

(defn pause-ms
  "Get pause for a section, falling back to type-specific default."
  [section pb-config]
  (or (:pause-ms section)
      (case (:display section)
        :title-card (:title-pause-ms pb-config)
        :summary (:result-pause-ms pb-config)
        (:default-pause-ms pb-config))))
