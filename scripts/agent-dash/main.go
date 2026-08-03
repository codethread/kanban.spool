// Interactive TUI dashboard over the kanban board of the live coordination
// world, for code owners working in this repo (not shipped, not part of the CLI
// surface). Built on Bubble Tea; the shell, polling loop, and strand access live
// in internal/app, internal/ui, internal/data, and the board itself in
// internal/board.
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
// Usage: kanban-dash [--interval secs] [--all] [--once] [--workspace dir]
package main

import (
	"errors"
	"fmt"
	"os"

	"github.com/codethread/kanban.spool/scripts/agent-dash/internal/app"
	"github.com/codethread/kanban.spool/scripts/agent-dash/internal/board"
	"github.com/codethread/kanban.spool/scripts/agent-dash/internal/data"
)

func main() {
	if err := data.ParseFlags(os.Args[1:]); err != nil {
		switch {
		case errors.Is(err, data.ErrHelpRequested):
			os.Exit(0)
		case errors.Is(err, data.ErrFlagsReported):
			// The flag set already wrote the reason and the usage.
		default:
			fmt.Fprintln(os.Stderr, err)
		}
		os.Exit(2)
	}
	if err := app.Run(board.New()); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
