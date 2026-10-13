(require '[clojure.data.json :as json]
         '[clojure.pprint :as pprint])

(let [json-str (slurp *in*)
      data (json/read-str json-str :key-fn keyword)]
  (pprint/pprint data))
