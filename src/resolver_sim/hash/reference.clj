(ns resolver-sim.hash.reference
  "Shared hash reference format and utilities.

   ════════════════════════════════════════════════════════════════
   AUTHORITY BOUNDARY
   ════════════════════════════════════════════════════════════════

   The canonical hash reference format is \"sha256:<64-hex-chars>\".
   This is the single authoritative namespace for constructing, parsing,
   and validating canonical hash references across all PRF namespaces.

   “Single authority” applies to the canonical format, not to bare SHA-256
   calculation.  Subsystem-specific namespaces may continue to compute bare
   SHA-256 digests (e.g. chain/compute-file-sha256, lifecycle/sha256-file,
   or local MessageDigest usage) as long as the resulting hex string is
   wrapped through sha256-ref when a canonical reference is needed.

   In short:
     - Bare SHA-256 calculation may occur in subsystem-specific namespaces.
     - Construction, parsing, and validation of canonical \"sha256:\"
       references must use this namespace.

   See also resolver-sim.hash.canonical for domain-separated canonical hashing.

   ════════════════════════════════════════════════════════════════"
  (:import [java.security MessageDigest]
           [java.math BigInteger]))

(def ^:const sha256-ref-prefix "sha256:")

(def ^:const sha256-ref-pattern
  "Regex matching a canonical sha256 reference: sha256:<64 hex chars>."
  #"^sha256:[0-9a-f]{64}$")

(defn sha256-ref
  "Construct a canonical sha256 reference from a hex digest string.

   (sha256-ref \"abc...\") => \"sha256:abc...\"

   The hex string must be a 64-character lowercase hex SHA-256 digest.
   When the argument already has a sha256: prefix, returns it unchanged."
  [hex-or-ref]
  (if (and (string? hex-or-ref) (.startsWith hex-or-ref sha256-ref-prefix))
    hex-or-ref
    (str sha256-ref-prefix hex-or-ref)))

(defn parse-sha256-ref
  "Parse a canonical sha256 reference to its raw hex digest.

   Returns the 64-character lowercase hex string (without the sha256:
   prefix) when ref is a valid canonical reference, or nil otherwise.

   The return value is always a bare hex digest, suitable for use with
   sha256-ref to reconstruct the canonical form.  Callers MUST NOT add
   their own \"sha256:\" prefix to the result of parse-sha256-ref —
   use sha256-ref for construction.

   Example:
     (parse-sha256-ref \"sha256:abcd...64hex...\")
     => \"abcd...64hex...\"

     (parse-sha256-ref \"sha256:short\") => nil"
  [ref]
  (when (and (string? ref) (re-find sha256-ref-pattern ref))
    (subs ref (count sha256-ref-prefix))))

(defn valid-sha256-ref?
  "Return true if ref is a valid canonical sha256 reference string.
   Accepts strings of the form sha256:<64 lowercase hex chars>."
  [ref]
  (boolean (and (string? ref) (re-find sha256-ref-pattern ref))))

(defn sha256-ref-file
  "Compute the canonical sha256 reference for a file's content.
   Returns \"sha256:<hex>\" or nil if the file does not exist."
  [path]
  (let [f (java.io.File. path)]
    (when (.isFile f)
      (let [digest (MessageDigest/getInstance "SHA-256")]
        (.update digest (java.nio.file.Files/readAllBytes (.toPath f)))
        (str sha256-ref-prefix
             (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))))))
