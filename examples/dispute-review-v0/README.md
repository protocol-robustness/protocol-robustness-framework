# PRF dispute review V0 pilot

This bounded pilot examines a frozen, synthetic local partial-fill allocation.
The expected amounts total 70. A deliberate contradictory projection totals
71, so it falsifies conservation. A referenced external chain receipt is
missing; therefore the fixture cannot establish public-chain execution.

The reconstruction reads the frozen fixture from the review package and emits
the result EDN. The documented command redirects that exact output into the
package's pinned result file; it writes review evidence, not protocol state.

```sh
bb -f examples/dispute-review-v0/prf-dispute-review.d30b15dbd6094112801acf1065a70826/reconstruct.clj examples/dispute-review-v0/prf-dispute-review.d30b15dbd6094112801acf1065a70826/frozen-current-world.edn > examples/dispute-review-v0/prf-dispute-review.d30b15dbd6094112801acf1065a70826/reconstruction-result.edn
```

The pinned pilot package is
`prf-dispute-review.d30b15dbd6094112801acf1065a70826/`; it contains a
manifest, the exact reconstruction script and its pinned local inputs, report,
and machine-readable verification result. The command above runs the script
from that package, reads its frozen fixture, and writes the result file that
the manifest pins and verification checks. File references are confined to
the review package; the package stores copies of the frozen fixture,
reconstruction result, and specification so their identities can be checked
without reaching outside the review directory. A first
unpinned draft package is retained alongside it to show that resolvable files
without identities are reported as absent. Both packages have different IDs
and independent manifests. The pilot's `current-world` entry is a timestamped
local fixture snapshot, not a live world lookup. The commands do not connect
to or mutate protocol state. Re-running the command overwrites only the
package-local reproduction result; verification then fails if its bytes no
longer match the manifest's pinned result identity.
