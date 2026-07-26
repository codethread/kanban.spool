# Explicit-runtime release exception

This record prepares `v11`. It is not a tag or a publication instruction.

- Previous marker: annotated `v10`; immutable peeled commit `14390f448cac93fb045d36bcecaf49f11f0a4de2`.
- Proposed marker: annotated `v11`.
- Affected root: `codethread/kanban`.
- Breaking change: runtime-dependent public functions now take the target runtime as their first argument. This includes board mutations and reads, tracker binding, and peering activation. CLI and registered-op invocation are unchanged.
- Reason: a shared spool must work in unpublished runtimes and in JVMs containing more than one runtime. Resolving ambient runtime state inside these functions could read or mutate the wrong world.
- Consumer cutover: pass the module or test runtime explicitly, for example `(kanban/set-tracker! runtime binding)` and `(kanban/add! runtime title flags)`.
- Floor: none. Compatibility remains documented and tested without adding a `:skein/min` requirement.
- Authorization: TEN-000@1 and the user's explicit instruction to make the required breaking changes.
- Known consumer: skein-src. Its tracker adapter moves to the explicit runtime API in the same coordinated release.
- Compatibility alarm: the frozen `v10` suite fails at changed public calls. Most
  calls fail with arity errors; overlapping old and new arities interpret an old
  argument as a runtime and fail at that boundary. `bin/compat-alarm v10`
  currently reports 2 failures and 44 errors across these two expected classes.
  No unrelated failure is accepted.
- Decision: no ambient-runtime compatibility arities. Keeping them would preserve the cross-runtime bug this release removes.

Rollback is a consumer action: retain or restore the old `v10` pin and peeled SHA. Do not move or replace the old tag.
