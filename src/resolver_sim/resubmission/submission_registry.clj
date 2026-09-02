(ns resolver-sim.resubmission.submission-registry
  "Canonical content inventory for one submitted resubmission attempt. This
   registry commits submitted content; it is not an authority-selection artifact."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def schema "attempt-submission-registry.v1")
(def domain :prf-attempt-submission-registry-v1)
(def required-roles [:results :certificate :execution-evidence])
(def role-kinds {:results :results-artifact
                 :certificate :allocation-certificate
                 :execution-evidence :execution-evidence})

(defn projection [registry]
  (select-keys registry [:artifact/schema :submission-registry/entries]))

(defn root [registry]
  (ref/sha256-ref (hc/domain-hash domain (projection registry))))

(defn entry-valid? [entry]
  (and (= #{:entry/role :artifact/kind :artifact/root} (set (keys entry)))
       (contains? role-kinds (:entry/role entry))
       (= (role-kinds (:entry/role entry)) (:artifact/kind entry))
       (ref/valid-sha256-ref? (:artifact/root entry))))

(defn valid? [registry]
  (let [entries (:submission-registry/entries registry)
        roles (mapv :entry/role entries)]
    (and (map? registry)
         (= #{:artifact/schema :submission-registry/entries :attempt-submission-registry/root}
            (set (keys registry)))
         (= schema (:artifact/schema registry))
         (vector? entries)
         (every? entry-valid? entries)
         (= required-roles roles)
         (= (count roles) (count (set roles)))
         (= (:attempt-submission-registry/root registry) (root registry)))))

(defn build [registry]
  (let [built (assoc registry :artifact/schema schema)]
    (assoc built :attempt-submission-registry/root (root built))))
