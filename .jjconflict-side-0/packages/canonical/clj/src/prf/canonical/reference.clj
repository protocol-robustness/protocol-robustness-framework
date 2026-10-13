(ns prf.canonical.reference
  "Canonical SHA-256 reference formatting shared by PRF packages.")

(def ^:const sha256-ref-prefix "sha256:")
(def sha256-ref-pattern #"^sha256:[0-9a-f]{64}$")
(def ^:private sha256-hex-pattern #"^[0-9a-f]{64}$")

(defn valid-sha256-ref?
  "True for the canonical lowercase `sha256:<64 hex>` reference form."
  [value]
  (boolean (and (string? value) (re-matches sha256-ref-pattern value))))

(defn sha256-ref
  "Wrap a bare lowercase 64-character digest, or validate a canonical ref.
   Rejects rather than normalizing noncanonical input."
  [hex-or-ref]
  (cond
    (valid-sha256-ref? hex-or-ref) hex-or-ref
    (and (string? hex-or-ref) (re-matches sha256-hex-pattern hex-or-ref))
    (str sha256-ref-prefix hex-or-ref)
    :else
    (throw (ex-info "Invalid canonical SHA-256 reference"
                    {:reason :invalid-sha256-reference}))))

(defn parse-sha256-ref
  "Return the bare lowercase digest, or nil for a noncanonical reference."
  [value]
  (when (valid-sha256-ref? value)
    (subs value (count sha256-ref-prefix))))
