# Kanban v25 release exception

This record prepares the next Kanban marker, `v25`. It is not a tag or publication instruction.

- Previous marker: annotated `v24`; immutable peeled commit `87f61bc2750e7026f3650235907db25f19b1536e`.
- Proposed marker: annotated `v25`.
- Affected root: `codethread/kanban`.
- Breaking change: v25 removes Guild-backed cross-weaver peering, the peering and Guild activation surface, and the `kanban/from` provenance attribute. The removed peering/Guild/`kanban/from` surface has no compatibility alias or shim.
- Core surface retained: local Kanban cards, lanes, graph relationships, status derivation, queries, the `kanban-batch` pattern, and the `kanban-dash` bin remain in `ct.spools.kanban`.
- Consumer cutover: approve the published Kanban spool from `spools.edn`, then activate only `ct.spools.kanban` as a runtime module. Do not activate the removed peering or Guild module.
- Millstrand baseline: the core release input is the SHA-only map from MSR-04 (`bxwq0`), with coordinate `io.millstrand/millstrand`; no tag or peeled-SHA field is used for that core entry.
- Floor: unchanged. Removing the experimental peering surface does not add or raise `:millstrand/min`.
- Identity gate: `make identity-check` scans active source, tests, docs, CI, dependency/build files, and workspace templates. The empty allowlist records that no removed peering identity exception is retained.
- Release proof: run `bin/verify-release --mode pre-tag --source-root "$PWD" --core-release <MSR-04-release.json>` before landing. After landing and tagging, run `bin/verify-release --mode published --repository https://github.com/codethread/kanban.spool.git --tag v25 --sha <peeled-40-hex> --core-release <MSR-04-release.json>`. The verifier loads `ct.spools.kanban` from a clean consumer and proves `.millstrand`/`.ms` database identity.
- Authorization: the explicit 2026-08-13 decision under Epic `z2yhh` authorizes this Guild-backed peering removal, v25 release publication, and creation of the annotated `v25` marker. No compatibility shim is retained.

Rollback is a consumer action: retain or restore the old `v24` pin and peeled SHA `87f61bc2750e7026f3650235907db25f19b1536e`. Do not move or replace the old tag.
