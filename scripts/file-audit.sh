#!/usr/bin/env bash
set -u

ROOTS=(src test scripts notebooks)
BASE="${1:-@-}"
failed=0

echo
echo "== changed files vs $BASE =="
jj diff --from "$BASE" --to @ --name-only

echo
echo "== conflict-marker residue =="
if rg -n \
  -g '*.clj' -g '*.cljc' -g '*.edn' \
  '^(<<<<<<<|%%%%%%%|\|\|\|\|\|\|\||=======|>>>>>>>|\+\+\+\+\+\+\+)' \
  "${ROOTS[@]}" 2>/dev/null
then
  failed=1
else
  echo "OK"
fi

echo
echo "== suspicious editor / merge leftovers =="
leftovers="$(
  find "${ROOTS[@]}" -type f \
    \( -name '*.modified' -o \
       -name '*.orig' -o \
       -name '*.rej' -o \
       -name '*.bak' \) \
    -print 2>/dev/null
)"

if [[ -n "$leftovers" ]]; then
  printf '%s\n' "$leftovers"
else
  echo "OK"
fi

echo
echo "== read every Clojure source file =="
checker="$(mktemp)"

cat >"$checker" <<'CLJ'
(require '[clojure.java.io :as io])

(def failed? (atom false))

(defn read-file! [path]
  (let [eof (Object.)]
    (with-open [r (java.io.PushbackReader. (io/reader path))]
      (binding [*read-eval* false]
        (loop []
          (let [form (read {:eof eof
                            :read-cond :allow
                            :features #{:clj}}
                           r)]
            (when-not (identical? eof form)
              (recur))))))))

(doseq [path (line-seq
              (java.io.BufferedReader.
               (java.io.InputStreamReader. System/in)))]
  (try
    (read-file! path)
    (catch Throwable t
      (reset! failed? true)
      (binding [*out* *err*]
        (println "READ-FAIL" path ":" (.getMessage t))))))

(when @failed?
  (System/exit 1))
CLJ

if find "${ROOTS[@]}" -type f \
     \( -name '*.clj' -o -name '*.cljc' \) \
     -print 2>/dev/null |
   sort |
   clojure -M:test:with-sew "$checker"
then
  echo "OK"
else
  failed=1
fi

rm -f "$checker"

echo
echo "== unusually large size changes vs $BASE (warning only) =="

while IFS= read -r f; do
  [[ -f "$f" ]] || continue

  old="$(jj file show -r "$BASE" "$f" 2>/dev/null | wc -c)"
  new="$(wc -c <"$f")"

  if (( old >= 1000 )); then
    pct="$(awk -v old="$old" -v new="$new" \
      'BEGIN { printf "%.0f", ((new-old) * 100) / old }')"

    abs="${pct#-}"

    if (( abs >= 30 )); then
      printf '%-6s %8d -> %8d  %s\n' "${pct}%" "$old" "$new" "$f"
    fi
  fi
done < <(jj diff --from "$BASE" --to @ --name-only)

echo
if (( failed )); then
  echo "AUDIT: FAIL"
  exit 1
else
  echo "AUDIT: PASS"
fi
