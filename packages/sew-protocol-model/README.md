# Sew protocol model

Status: in-tree extraction candidate. This directory contains the Sew
behavioral model source and package test suite; it is not an independently
resolvable or released package.

## Qualification in this checkout

From this directory, run the model package suite with:

```sh
clojure -Srepro -M:test -m resolver-sim.protocols.sew.package-test-runner
```

The test alias intentionally overlays the local pro-rata economics source and
adds the in-tree PRF framework and yield integration sources. This qualifies
the candidate against this checkout; it does not prove clean-consumer
resolution. The repository-level extension gate is:

```sh
cd ../..
scripts/test_extension_packages.sh
```

## Extraction status

The runtime manifest pins PRF core revision
`b94b091cfbaf46ec34ae2ade4920f9a3751e3097` and Sew pro-rata economics revision
`c6d21f852f8fbeff3d29b52c1139ad6f88ed332a`. The core revision predates the
portable pro-rata API used by the economics adapter, and the economics
revision is not independently fetchable. Do not treat the runtime dependency
graph as clean-consumer qualified until both dependencies are available at
immutable, fetchable revisions and the package passes without local source
overrides.

The intended destination is
`~/Code/prf-extensions/packages/sew-protocol-model/`. Move the package only
after the dependency and clean-consumer gates above are satisfied. The
destination also needs its incubating extension manifest, explicit package
and consumer test runners, validation registration, and reproducible package
instructions.
