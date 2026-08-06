# Millstrand identity release exception

This record prepares the next Kanban marker, `v24`. It is not a tag or a publication instruction.

- Previous marker: annotated `v23`; immutable peeled commit `2947590e7965feb95a239189af3bd55f008d1209`.
- Proposed marker: annotated `v24`.
- Affected root: `codethread/kanban`.
- Breaking change: the consuming core is now `io.millstrand/millstrand`, the workspace marker is `.millstrand` (with `.ms` accepted by the core), and Kanban source/config/tests/docs use `millstrand.api.*`, `millstrand.spools.*`, and `:millstrand/*` identity. Legacy core names are not compatibility aliases.
- Domain identity retained: `ct.spools.kanban`, `ct.spools.kanban.peering`, `codethread/kanban`, and the `kanban.*` operation and attribute namespaces are unchanged.
- Consumer cutover: approve the published Kanban family from `spools.edn`, then activate `ct.spools.kanban` and, when needed, `ct.spools.kanban.peering` as ordered runtime modules. Do not call legacy installers or resolve old core namespaces.
- Millstrand baseline: the core release input is the SHA-only map from MSR-04 (`bxwq0`), with coordinate `io.millstrand/millstrand`; no tag or peeled-SHA field is used for that core entry.
- Floor: unchanged. This coordinated identity break does not add or raise `:millstrand/min`.
- Identity gate: `make identity-check` scans active source, tests, docs, CI, dependency/build files, and workspace templates. The empty allowlist records that no active legacy identity exception is retained.
- Release proof: run `bin/verify-release --mode pre-tag --source-root "$PWD" --core-release <MSR-04-release.json>` before landing, then run its published mode against the annotated `v24` tag and peeled SHA after landing. The verifier loads `ct.spools.kanban` from a clean consumer and proves `.millstrand`/`.ms` database identity.
- Authorization: the accepted Millstrand rename plan and Epic `ke3rd` standing authorization cover the coordinated pre-v1 break, release publication, and marker creation. No callback or namespace compatibility shim is retained.

Rollback is a consumer action: retain or restore the old `v23` pin and peeled SHA. Do not move or replace the old tag.
