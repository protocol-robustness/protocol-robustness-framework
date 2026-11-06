#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
probe_dir=$(mktemp -d "${TMPDIR:-/tmp}/prf-extension-api-v1.XXXXXX")
trap 'rm -rf "$probe_dir"' EXIT
cd "$repo_root"

manifest="packages/prf-extension-api-v1/DEPENDENCIES.edn"
actual_closure=$(clojure -M:check -e '
  (require (quote clojure.java.io)
           (quote clojure.string)
           (quote clojure.tools.namespace.find)
           (quote clojure.tools.namespace.parse))
  (let [dirs ["src"
              "extensions/force-authorisation/src"
              "extensions/held-custody/src"
              "extensions/force-authorisation/test"
              "extensions/held-custody/test"]
        forms (mapcat #(clojure.tools.namespace.find/find-ns-decls-in-dir
                        (clojure.java.io/file %)) dirs)
        deps (into {} (map (fn [form]
                             [(clojure.tools.namespace.parse/name-from-ns-decl form)
                              (clojure.tools.namespace.parse/deps-from-ns-decl form)])
                           forms))
        roots (concat [(symbol "resolver-sim.extensions.api.v1")]
                      (clojure.tools.namespace.find/find-namespaces-in-dir
                       (clojure.java.io/file "extensions/force-authorisation/test"))
                      (clojure.tools.namespace.find/find-namespaces-in-dir
                       (clojure.java.io/file "extensions/held-custody/test")))
        closure (loop [todo roots seen #{}]
                  (if-let [n (first todo)]
                    (if (contains? seen n)
                      (recur (rest todo) seen)
                      (recur (concat (get deps n []) (rest todo)) (conj seen n)))
                    seen))]
    (doseq [n (sort (filter #(and (symbol? %)
                                  (clojure.string/starts-with? (str %) "resolver-sim."))
                            closure))]
      (println n)))')
expected_closure=$(clojure -M -e "(require 'clojure.edn) (doseq [n (sort (:source-closure (clojure.edn/read-string (slurp \"$manifest\"))))] (println n))")
if [[ "$actual_closure" != "$expected_closure" ]]; then
  echo "Extension API source closure drifted from $manifest" >&2
  diff -u <(printf '%s\n' "$expected_closure") <(printf '%s\n' "$actual_closure") || true
  exit 1
fi

while IFS= read -r namespace; do
  relative_path=$(printf '%s' "$namespace" | tr '.' '/' | tr '-' '_').clj
  source_path="src/$relative_path"
  if [[ ! -f "$source_path" ]]; then
    printf 'missing extension API source dependency: %s (%s)\n' \
      "$namespace" "$source_path" >&2
    exit 1
  fi
  mkdir -p "$probe_dir/src/$(dirname "$relative_path")"
  cp "$source_path" "$probe_dir/src/$relative_path"
done <<< "$actual_closure"

mkdir -p "$probe_dir/resources/config" \
         "$probe_dir/extensions/force-authorisation" \
         "$probe_dir/extensions/held-custody"
cp config/defaults.edn "$probe_dir/resources/config/defaults.edn"
cp -R extensions/force-authorisation/src "$probe_dir/extensions/force-authorisation/"
cp -R extensions/force-authorisation/test "$probe_dir/extensions/force-authorisation/"
cp -R extensions/held-custody/src "$probe_dir/extensions/held-custody/"
cp -R extensions/held-custody/test "$probe_dir/extensions/held-custody/"

source_root=$(cd "$probe_dir" && find src resources extensions -type f -print0 \
  | sort -z | xargs -0 sha256sum | sha256sum | awk '{print $1}')
printf 'isolated-source-closure=sha256:%s\n' "$source_root"

cd "$probe_dir"
clojure -Srepro -Sdeps '{:paths ["src" "resources"
                                   "extensions/force-authorisation/src"
                                   "extensions/force-authorisation/test"
                                   "extensions/held-custody/src"
                                   "extensions/held-custody/test"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}
        org.clojure/test.check {:mvn/version "1.1.1"}
        prf/canonical {:git/url "https://github.com/protocol-robustness/prf-canonical.git"
                       :git/sha "5e2b520feca812b049ba397b7b3681963c66a48c"
                       :deps/root "clj"}}}' -M -e '
  (require (quote clojure.test)
           (quote prf.extensions.force-authorisation.manifest-test)
           (quote prf.extensions.force-authorisation.scope-verification-test)
           (quote prf.extensions.held-custody.authoritative-gate-test)
           (quote prf.extensions.held-custody.authorisation-classification-test)
           (quote prf.extensions.held-custody.core-seam-test)
           (quote prf.extensions.held-custody.golden-vectors-test)
           (quote prf.extensions.held-custody.historical-read-conformance-test)
           (quote prf.extensions.held-custody.legacy-add-held-test)
           (quote prf.extensions.held-custody.manifest-test)
           (quote prf.extensions.held-custody.mutation-test)
           (quote prf.extensions.held-custody.semantic-admission-test)
           (quote prf.extensions.held-custody.aggregate-test))
  (let [result (clojure.test/run-tests
                (quote prf.extensions.force-authorisation.manifest-test)
                (quote prf.extensions.force-authorisation.scope-verification-test)
                (quote prf.extensions.held-custody.authoritative-gate-test)
                (quote prf.extensions.held-custody.authorisation-classification-test)
                (quote prf.extensions.held-custody.core-seam-test)
                (quote prf.extensions.held-custody.golden-vectors-test)
                (quote prf.extensions.held-custody.historical-read-conformance-test)
                (quote prf.extensions.held-custody.legacy-add-held-test)
                (quote prf.extensions.held-custody.manifest-test)
                (quote prf.extensions.held-custody.mutation-test)
                (quote prf.extensions.held-custody.semantic-admission-test)
                (quote prf.extensions.held-custody.aggregate-test))]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1)))'
