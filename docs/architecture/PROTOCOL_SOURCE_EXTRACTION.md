### Protocol source extraction

Current state:
Sew protocol sources remain available through `protocols_src` path composition.

Target state:
Protocol implementations can be consumed as explicit local/root or published
dependencies rather than being injected into the framework classpath.

Why deferred:
The current path boundary is working and classpath equivalence has been
verified. Extracting it changes dependency resolution, packaging, tests and
runner assumptions simultaneously, so it is intentionally outside the
path-group normalization change.

Exit criteria:
- framework tests run without `protocols_src` on the base/project classpath;
- Sew can be added through an explicit dependency;
- runner and trace-equivalence behaviour remains identical;
- build/package commands remain deterministic;
- framework code has no reverse dependency on Sew implementation code.
