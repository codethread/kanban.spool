# Spool installer retirement release exception

This record prepares `v9`. It is not a tag or a publication instruction.

- Previous marker: annotated `v8`; immutable peeled commit `af95849a2109e619e398b7d4a6dd3af4dfb041c8`.
- Proposed marker: annotated `v9`.
- Affected root and names: `codethread/kanban`; the removed name is `ct.spools.kanban/install!`, the legacy imperative activation entry point. Its whole surface was already duplicated by `contribute`/`reconcile` (ops, pattern, queries in contribute; vocab and spool-state in reconcile); the newly exported `ct.spools.kanban/module` datum is the authored declaration source. `install-peering!` stays: a recorded imperative opt-in (commit 5e7cb5c), not an activation path. Prereq remedies and docstrings now prescribe module activation.
- Authorization: TEN-000@1 removal recorded by skein-src ADR-003.P5 (epic waq0l, feature 9snqu) — retiring `install!` everywhere so the module lifecycle is the one activation path.
- Known consumer: the skein-src repository only. Its current immutable old pin remains `v8` at the peeled commit above until the epic's consumer-cutover feature bumps it; its `config_ops_test` already activates kanban via a literal module declaration valid at v8 and v9 alike.
- Compatibility alarm: `bin/compat-alarm v8` is expected to fail compiling archived `ct.spools.kanban-peering-test` (`No such var: kanban/install!` at its line 51) because the archived suites call the removed entry point. This is the approved lifecycle break; no unrelated failure is accepted.
- Decision: no compatibility shim. Keeping the installer would preserve the retired activation path this release exists to delete.

Rollback is a consumer action: retain or restore the old `v8` pin and peeled SHA. Do not move or replace the old tag.
