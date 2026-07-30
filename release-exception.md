# Authoring-forms release exception

This record prepares `v16`. It is not a tag or a publication instruction.

- Previous marker: annotated `v15`; immutable peeled commit `e0b48770625c9d4c6ca33bc53a7d88ec315f7a7e`.
- Proposed marker: annotated `v16`.
- Affected root: `codethread/kanban`.
- Breaking change: `ct.spools.kanban` and `ct.spools.kanban.peering` are forms-only modules. They no longer expose the legacy public `spool`, `contribute`, or `reconcile` Vars, nor the imperative `install-peering!` entry points. Peering's public macro-generated handlers are now `kanban-peers-op` and `kanban-send-op`; the old `peers-op` and `send-card-op` names are withdrawn.
- Reason: the static `skein/defop`, `skein/defquery`, and `skein/defpattern` forms make each registry partition owner-complete in source and replayable in image mode. Named lifecycle resources own vocabulary/state setup and register the guarded Guild receiver through Guild's supported seam.
- Consumer cutover: activate `ct.spools.kanban` and, when peering is wanted, `ct.spools.kanban.peering` as ordinary ordered runtime modules. Do not call an installer or resolve callback Vars.
- Lifecycle callback contract: resource `:open` and `:close` symbols are form-required implementation callables under Skein's existing lifecycle declaration contract. They are not new public consumer APIs and require no additional lifecycle spec work.
- Lifecycle parity: Kanban removal retracts its static entries without clearing stored cards or process-lifetime tracker state. Peering removal retracts its two local ops and closes its resource; it does not remove the Guild receiver. Guild retains ownership of the process-lifetime `kanban.send.v1` dispatch entry and receiver table, and the accepted receiver-removal policy is process-lifetime no-op.
- Skein baseline: validated against main `80e11a5b54808cf33c7fee2c1b09b232c060e001`; the first compatible contribution-and-lifecycle authoring commit is `24f900464d9b26e8e3d81550eef3a06230de5395`.
- Floor: unchanged. This coordinated break does not add or raise `:skein/min`.
- Authorization: the accepted PROP-Auf-001 sibling-break ruling and the explicit migration task. No callback aliases or compatibility shims are retained.
- Compatibility alarm: `bin/compat-alarm v15` stops while compiling v15's frozen `ct.spools.kanban-peering-test` at line 21 with `No such var: peering/spool`. This is the first expected public-surface withdrawal, so the frozen suite cannot proceed to a test summary. No unrelated failure is accepted.
- Current-suite evidence: `clojure -M:test` covers source/image normalized surface equality, exact owner omission, lifecycle removal, and retained Guild receiver dispatch in addition to the existing board and peering contracts.

Rollback is a consumer action: retain or restore the old `v15` pin and peeled SHA. Do not move or replace the old tag.
