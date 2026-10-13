(ns dev.repl
  (:require
   [dev.explore :as explore]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]))

(defn pp
  [x]
  (pprint x)
  x)

(defn spy
  ([x]
   (tap> x)
   x)
  ([label x]
   (tap> {:label label :value x})
   x))

(defn keys+
  [m]
  (-> m keys sort vec))

(defn select-keys+
  [m ks]
  (select-keys m ks))

(defn summarize-map
  [m]
  {:type      (type m)
   :count     (when (counted? m) (count m))
   :keys      (when (map? m) (-> m keys sort vec))
   :sample    (cond
                (map? m) (into {} (take 10 m))
                (sequential? m) (take 10 m)
                :else m)})

(defn tap
  [x]
  (tap> x)
  x)

(defn tap-summary
  [x]
  (tap> (summarize-map x))
  x)

(defn preload-framework!
  []
  (doseq [ns-sym (explore/all-project-ns)]
    (when (or (str/starts-with? (str ns-sym) "resolver-sim.")
              (str/starts-with? (str ns-sym) "benchmark.")
              (str/starts-with? (str ns-sym) "validation."))
      (try
        (require ns-sym)
        (catch Throwable e
          (tap> {:type :preload/error
                 :ns ns-sym
                 :message (.getMessage e)})))))
  :preload/done)
