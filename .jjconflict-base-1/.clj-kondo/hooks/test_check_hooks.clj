(ns hooks.test-check-hooks
  "clj-kondo :macroexpand hook for clojure.test.check.clojure-test/defspec.
   The :lint aliases do not pass the dependency classpath to clj-kondo, so
   `defspec` (a var-defining macro) is invisible to the analyzer. Expanding
   `(defspec name N prop)` to `(def name)` teaches kondo that the defined
   property var exists.")

(defmacro defspec
  [name & _]
  (list 'clojure.core/def name))
