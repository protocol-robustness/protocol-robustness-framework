(ns resolver-sim.io.edn
  "Pretty-printed EDN serialization.

   Single authority for writing human-readable EDN files. Prefer ppr-str over
   pr-str anywhere an EDN file is persisted so artifact formatting is consistent
   (indented, wrapped lines) across the codebase. Round-trips through
   clojure.edn/read-string identically to pr-str."
  (:require [clojure.pprint :as pp]))

(defn ppr-str
  "Serialize value to a pretty-printed EDN string (multi-line, indented)."
  [value]
  (with-out-str (pp/pprint value)))
