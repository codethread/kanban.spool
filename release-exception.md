# Owner-scoped live refresh release exception

This record prepares `v6`. It is not a tag or a publication instruction.

- Previous marker: annotated `v5`; immutable peeled commit `e4bb7b64d600052cd32c3e3728231f7bba9ad67e`.
- Proposed marker: annotated `v6`.
- Affected root and names: `codethread/kanban`; trusted configuration now declares the `ct.spools.kanban` and `ct.spools.kanban.peering` modules with their `contribute` and `reconcile` entries rather than the retired lifecycle sequence.
- Required Skein range: the owner-scoped live-refresh candidate from `b8be0c8` through `91bec8ac0caf1cb21bf1119d4b253d4601159ecb` (the latter is the release-preparation baseline).
- Known consumer: this Skein repository only. Its current immutable old pin remains `v5` at the peeled commit above until human approval changes it.
- Compatibility alarm: `bin/compat-alarm v5` is green (73 tests, 423 assertions). There are no expected failures and no unrelated failure is accepted.
- Decision: no compatibility shim. The release documents the required module declaration directly instead of keeping old lifecycle guidance alive.

Rollback is a consumer action: retain or restore the old `v5` pin and peeled SHA. Do not move or replace the old tag.
