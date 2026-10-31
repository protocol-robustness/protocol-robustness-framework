# Concept Protocol Mappings

Mapping sets connect stakeholder concepts to the vocabulary of each protocol.
They do not define the concept's meaning or assert that a protocol implements
the mapped capability.

`data/concepts/registry.edn` registers these sets. `shared.edn` contains common
mappings for concepts owned by `:shared`. `prf.edn` and `sew-v1.edn` contain
protocol-owned mappings and protocol-specific overrides. When resolving a
protocol view, the loader applies the shared mapping first, then that
protocol's mapping.

Each record identifies a target by `:concept/id`, `:mapping/category` (one of
`:concept/roles`, `:concept/entities`, `:concept/actions`, or
`:concept/outcomes`), and `:mapping/id`. `:mapping/values` may contain
`:maps-to`, `:mapping/confidence`, and `:mapping/note`.

Run `make concepts-check` after changing a mapping set. It checks protocol
registration, target references, duplicate mapping keys, and mapping-label
shape. Changes under this directory are included in source hashing and the
Sew release corpus.
