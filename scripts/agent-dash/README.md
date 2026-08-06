# agent-dash

`kanban-dash` is the Go/Bubble Tea interactive terminal dashboard for the
Kanban spool.

Build and run it through the spool's published bin:

```sh
mill bin build kanban-dash
mill bin run kanban-dash
make kanban-dash-check
```

It presents the epic → feature → task tree, saved label-filter views,
attribute details, editor and clipboard actions, active/all filtering, polling,
and a single-frame non-TTY mode. Saved filters live at
`~/.cache/millstrand/agent-dash/filters.json`, keyed by workspace.

The implementation is split into `internal/app` (lifecycle and input),
`internal/board` (board model), `internal/data` (strand access and filters), and
`internal/ui` (rendering).
