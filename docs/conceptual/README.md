# Concepts Layer

PRF's stakeholder-facing concept catalogue is maintained in the
[`prf-extensions` repository](https://github.com/protocol-robustness/prf-extensions/tree/main/concepts).
PRF consumes it through the immutable `org.protocol-robustness/prf-concepts`
Git dependency declared in `deps.edn`; `resources/prf/concept-catalogue-lock.edn`
binds the accepted source commit, catalogue root, and concept count.

The dependency exposes `resource:concepts/registry.edn`, the bundle registry,
plus bundle manifests, definitions, and mappings under `concepts/`. Runtime
loading checks the package catalogue lock and PRF source lock before exposing
the compatibility registry. PRF does not keep a second editable copy of these
definitions or mappings.

## Ownership and applicability

Bundle ownership and protocol applicability are separate. A concept may be
owned by `:protocol/prf` and still apply to another protocol. Definitions hold
stakeholder meaning; shared and protocol-specific mapping bundles hold
`:maps-to` labels and mapping qualifications. Consumers combine the shared
mapping set with the mapping set for their protocol.

All concepts remain draft concepts. The package's catalogue root is a content
identity, not a package release or qualification claim. Package publication,
release authority, and consumer migration are governed separately from this
source dependency.

## Validation

Run `make concepts-check` to validate the exact pinned bundle inventory,
definitions, mappings, references, and known-gap links. The PRF source lock and
package catalogue lock must agree on the root and count. Keep the package
revision immutable when changing that lock.

For authoring guidance and package-level validation, see the concept package
README in `prf-extensions`.
