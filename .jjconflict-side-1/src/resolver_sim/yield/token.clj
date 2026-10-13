(ns resolver-sim.yield.token
  "Shared token normalization utilities, extracted from 7 private copies
   across the yield namespaces.")

(defn normalize
  "Coerce a token value to keyword for consistent key lookup.
   Accepts keyword or string; returns keyword."
  [t]
  (if (keyword? t) t (keyword (name t))))

(defn eq?
  "Case-insensitive token equality across keyword/string types."
  [a b]
  (= (normalize a) (normalize b)))
