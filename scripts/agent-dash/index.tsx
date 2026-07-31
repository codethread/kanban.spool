#!/usr/bin/env bun
// Interactive TUI dashboard over the kanban board of the live coordination
// world, for code owners working in this repo (not shipped, not part of the CLI
// surface). Built on Ink; the shell, polling loop, and strand access live in
// ./app, ./ui, ./data, and the board itself in ./board.
//
// The tab bar under the header is the board's saved label-filter views: ALL, one
// tab per saved view, then a `+` slot. ⇥/⇧⇥ walk them, landing on `+` opens the
// editor for a new view, and f edits the tab in force. Keys: ↑/↓ or j/k move,
// enter/l opens a full-attribute detail view of the selected strand, esc/h goes
// back, g/G jump, = expands and - collapses the selected card (epics open by
// default, a feature's tasks closed), a toggles all/active, ⌃g opens the card in
// $EDITOR, y copies its id, r forces a refresh, q quits. Non-TTY (and --once)
// prints a single board frame.
//
// Usage: bun scripts/agent-dash/index.tsx [--interval secs] [--all] [--once] [--workspace dir]

import { runApp } from "./app";
import { kanbanDash } from "./board/kanban";

await runApp(kanbanDash);
