(ns resolver-sim.commands.ref-file
  "Compute canonical sha256:<hex> references for files.

   Canonical references are produced by resolver-sim.hash.reference/sha256-ref-file,
   the single authority for constructing canonical file references. Output uses
   the sha256sum-style two-space separator, so entries can be emitted directly
   into a packet SHA256SUMS file and later re-verified with --check.

   Examples:
     java -jar prf.jar ref-file path/to/a.edn path/to/b.md
     java -jar prf.jar ref-file --check SHA256SUMS"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:private ref-line-pattern #"^(sha256:[0-9a-f]{64})[ \t]+(.+)$")

(defn- print-to-stderr [lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println line))))

(defn- compute-refs
  "Print one canonical ref line per existing file; fail on missing paths."
  [paths]
  (let [entries (mapv (fn [path]
                        {:path path
                         :ref  (hash-ref/sha256-ref-file path)})
                      paths)
        missing (mapv :path (filter (comp nil? :ref) entries))]
    (if (seq missing)
      (do (print-to-stderr (map #(str "ref-file: file not found: " %) missing))
          {:exit-code 1 :message (str (count missing) " missing file(s)")})
      (do (doseq [{:keys [path ref]} entries]
            (println (str ref "  " path)))
          {:exit-code 0 :message (str (count entries) " refs computed")}))))

(defn- compute-refs-json
  "Print the canonical refs as a JSON document; fail on missing paths."
  [paths]
  (let [entries (mapv (fn [path]
                        {:path path
                         :ref  (hash-ref/sha256-ref-file path)})
                      paths)
        missing (mapv :path (filter (comp nil? :ref) entries))]
    (if (seq missing)
      (do (print-to-stderr (map #(str "ref-file: file not found: " %) missing))
          {:exit-code 1 :message (str (count missing) " missing file(s)")})
      (do (require 'clojure.data.json)
          (println ((resolve 'clojure.data.json/write-str) {:files entries} :indent true))
          {:exit-code 0 :message (str (count entries) " refs computed")}))))

(defn- check-refs-file
  "Verify every canonical ref entry in refs-file against the referenced file.
   Each line must be <sha256:<hex>> <path>; a missing or mismatched file fails."
  [refs-path]
  (let [refs-file (io/file refs-path)]
    (if-not (.isFile refs-file)
      {:exit-code 2 :message (str "ref-file: refs file not found: " refs-path)}
      (let [lines   (str/split-lines (slurp refs-file))
            errors  (volatile! [])
            checked (volatile! 0)]
        (doseq [line lines]
          (cond
            (str/blank? line)
            nil

            (re-find ref-line-pattern line)
            (let [[_ ref path] (re-find ref-line-pattern line)
                  actual (hash-ref/sha256-ref-file path)]
              (if (= actual ref)
                (vswap! checked inc)
                (vswap! errors conj
                        (str "ref-file: " path ": expected " ref ", got "
                             (or actual "missing file")))))

            :else
            (vswap! errors conj (str "ref-file: malformed ref entry: " line))))
        (if (empty? @errors)
          (do (println (str "PASS: " @checked " canonical file refs verified"))
              {:exit-code 0 :message (str @checked " refs verified")})
          (do (print-to-stderr @errors)
              {:exit-code 1 :message (str (count @errors) " ref verification error(s)")}))))))

(defn run
  "ref-file [--check REFS_FILE] <path>...
   Computes canonical sha256:<hex> references for the given files, or verifies
   every entry in a refs file when --check is given."
  [opts]
  (let [check (:check opts)
        args  (:cmd/args opts)
        json? (:json? opts)]
    (cond
      check  (check-refs-file check)
      (seq args)
      (if json? (compute-refs-json args) (compute-refs args))

      :else
      (do (print-to-stderr
           ["Usage: ref-file [--check REFS_FILE] <path>..."
            "Computes canonical sha256:<hex> references via sha256-ref-file."])
          {:exit-code 2 :message "no file paths given"}))))
