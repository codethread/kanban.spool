// The dashboard shell: the view contract, the reusable list state kit, the tab
// strip, the polling loop, the single keyboard dispatch, and the
// fullscreen/one-shot entry points. The shell hosts one view module (the board)
// and owns no concrete row type; every view-local key is routed to that module.
//
// The tab strip is the view's own, not the shell's: the module reports the labels
// and which one is current (Strip), and the shell only draws them. On the board
// those tabs are the saved filter views, so ⇥ is a view-local key like any other.
package app

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"

	tea "github.com/charmbracelet/bubbletea"

	"github.com/codethread/kanban.spool/scripts/agent-dash/internal/data"
	"github.com/codethread/kanban.spool/scripts/agent-dash/internal/ui"
)

// ── reusable list view state ─────────────────────────────────────────────────
// One scrollable list with an optional attribute detail. Selection is anchored to
// a stable per-row key (a strand id) so it survives refreshes that reorder or
// drop rows.

type Mode int

const (
	ModeList Mode = iota
	ModeDetail
)

type ListState struct {
	Selected int
	Anchor   string
	Mode     Mode
}

// FollowSelection re-anchors the selection after a fetch: it follows the anchored
// key, and if that vanished it holds the old index clamped into the new list.
func FollowSelection[R any](s ListState, rows []R, keyOf func(R) string) ListState {
	if len(rows) == 0 {
		return ListState{Mode: s.Mode}
	}
	selected := -1
	if s.Anchor != "" {
		for i, r := range rows {
			if keyOf(r) == s.Anchor {
				selected = i
				break
			}
		}
	}
	if selected < 0 {
		selected = max(0, min(s.Selected, len(rows)-1))
	}
	return ListState{Selected: selected, Anchor: keyOf(rows[selected]), Mode: s.Mode}
}

// ReduceListKeys handles list-mode movement (↑↓/jk, ⌃u/⌃d half-page, g/G). It
// reports false when the key is not a movement command, so callers can layer
// enter/refresh on top. `page` is the ⌃u/⌃d jump distance (half a viewport, see
// ui.ListPage).
func ReduceListKeys[R any](s ListState, key string, rows []R, keyOf func(R) string, page int) (ListState, bool) {
	if len(rows) == 0 {
		return s, false
	}
	go_ := func(raw int) (ListState, bool) {
		next := max(0, min(len(rows)-1, raw))
		return ListState{Selected: next, Anchor: keyOf(rows[next]), Mode: s.Mode}, true
	}
	switch key {
	case "ctrl+u":
		return go_(s.Selected - page)
	case "ctrl+d":
		return go_(s.Selected + page)
	case "up", "k":
		return go_(s.Selected - 1)
	case "down", "j":
		return go_(s.Selected + 1)
	case "g":
		return go_(0)
	case "G":
		return go_(len(rows) - 1)
	}
	return s, false
}

// ── the view contract ────────────────────────────────────────────────────────
// The hosted module owns its own state, fetches into it, reduces its own keys,
// and renders list/detail/failure. The shell owns the header/tab-strip chrome,
// the all/active axis, quit, ⌃g/y, and the polling cadence.

type RenderCtx struct {
	Cols, TermRows int
	Interactive    bool
	All            bool
}

type KeyCtx struct {
	Cols, TermRows int
	// Refresh asks the shell for an immediate out-of-band poll. Returning it as a
	// Cmd from OnKey is what runs it.
	Refresh tea.Cmd
}

// Strip is the tab strip as the shell draws it: the labels left to right, and the
// index of the one in force. What a tab *means* is the module's business.
type Strip struct {
	Labels []string
	Active int
}

type Dash interface {
	// Noun is what the header counts ("active cards"): the module's own noun for
	// its rows.
	Noun() string
	// Refresh fetches under the current all/active axis, off the update loop,
	// delivering its result as a Msg for Apply. Errors are carried in that Msg so
	// a poll failure renders instead of killing the program.
	Refresh(all bool) tea.Cmd
	// Apply folds a Msg the module's own Refresh produced into its state, against
	// whatever the state is *now* — never a pre-fetch snapshot — so a slow poll
	// landing after the user has scrolled or switched tabs folds in the new rows
	// without clobbering that interim navigation. It reports whether the Msg was
	// the module's to handle.
	Apply(msg tea.Msg) bool
	// FetchKey changing re-runs the poll immediately (expanding a card needs its
	// tasks).
	FetchKey() string
	// OnKey handles view-local keys: movement, enter, esc, scroll, tab-strip
	// navigation, and any module-private keys.
	OnKey(key tea.KeyMsg, ctx KeyCtx) tea.Cmd
	// InDetail reports a detail is open, so the shell leaves the all/active axis
	// inert.
	InDetail() bool
	// CapturesInput reports the module is reading raw text (a filter name being
	// typed) and every key belongs to it — including the shell's own q/a/⌃g/y,
	// which would otherwise quit mid-word.
	CapturesInput() bool
	// EditTarget is the strand under the cursor in the module's current view. The
	// shell opens it in $EDITOR on ⌃g.
	EditTarget() (data.DetailRow, bool)
	// CopyID is the strand id under the cursor. Broader than EditTarget — a bare
	// tree/task row has an id even where no full DetailRow is in hand — so the
	// shell can copy it on y.
	CopyID() string
	// AllApplies reports whether the all/active axis applies in the module's
	// current view.
	AllApplies() bool
	Strip() Strip
	View(ctx RenderCtx) string
}

// ── messages ─────────────────────────────────────────────────────────────────

type tickMsg time.Time

// pollResult wraps whatever the module's Refresh produced, so the shell can clear
// its in-flight guard and stamp the refresh time before handing the payload on.
type pollResult struct{ inner tea.Msg }

type flashMsg string

type flashExpiredMsg int

type editorDoneMsg struct{ err error }

// ── the shell ────────────────────────────────────────────────────────────────

type shell struct {
	dash        Dash
	all         bool
	refreshedAt time.Time
	cols        int
	termRows    int
	interactive bool

	flash    string
	flashSeq int

	// No overlapping fetches: a request landing mid-flight is queued, not run, then
	// replayed with the latest state once this fetch settles, so a toggle during a
	// slow poll can't leave the new view stale.
	refreshing bool
	pending    bool
	fetchKey   string
}

func (s *shell) Init() tea.Cmd {
	return tea.Batch(s.refresh(s.all), s.tick())
}

func (s *shell) tick() tea.Cmd {
	return tea.Tick(data.Opts().Interval, func(t time.Time) tea.Msg { return tickMsg(t) })
}

func (s *shell) refresh(all bool) tea.Cmd {
	if s.refreshing {
		s.pending = true
		return nil
	}
	s.refreshing = true
	s.fetchKey = s.dash.FetchKey()
	inner := s.dash.Refresh(all)
	return func() tea.Msg { return pollResult{inner: inner()} }
}

// refreshCmd defers the guard check to when the Cmd actually runs, so a view
// module can hand its own key handler a refresh without the shell having to run
// it first.
func (s *shell) refreshCmd() tea.Cmd {
	return func() tea.Msg { return tickMsg(time.Now()) }
}

func (s *shell) showFlash(msg string) tea.Cmd {
	s.flash = msg
	s.flashSeq++
	seq := s.flashSeq
	// A fresh flash resets the timer rather than leaving an older one to blank the
	// newer message, which is what the sequence number checks on arrival.
	return tea.Tick(2*time.Second, func(time.Time) tea.Msg { return flashExpiredMsg(seq) })
}

func (s *shell) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		s.cols, s.termRows = msg.Width, msg.Height
		// Forwarded on: a module holding a sized component (the detail viewport)
		// has to resize it, and this is the only place the new size is announced.
		s.dash.Apply(msg)
		return s, nil

	case tickMsg:
		return s, s.refresh(s.all)

	case pollResult:
		s.refreshing = false
		s.refreshedAt = time.Now()
		s.dash.Apply(msg.inner)
		var cmds []tea.Cmd
		if s.pending {
			s.pending = false
			cmds = append(cmds, s.refresh(s.all))
		}
		cmds = append(cmds, s.tick())
		return s, tea.Batch(cmds...)

	case flashMsg:
		return s, s.showFlash(string(msg))

	case flashExpiredMsg:
		if int(msg) == s.flashSeq {
			s.flash = ""
		}
		return s, nil

	case editorDoneMsg:
		if msg.err != nil {
			return s, s.showFlash(fmt.Sprintf("editor failed · %v", msg.err))
		}
		return s, nil

	case tea.KeyMsg:
		// Bubble Tea parses one read of stdin at a time, so printable characters
		// arriving together — a held-down j, a fast "gg", a paste — land as a single
		// KeyRunes carrying every rune. A vim-style keymap dispatches on the whole
		// batch and would match nothing, silently dropping the repeat, so the batch
		// is split back into the keystrokes it was. Text fields see the same runes in
		// the same order, one append each.
		var cmds []tea.Cmd
		for _, key := range splitRunes(msg) {
			cmds = append(cmds, s.onKey(key))
		}
		return s, tea.Batch(cmds...)
	}

	// Anything else is a module message — a lazily fetched detail landing, a
	// component's own tick — so it goes to the module untouched.
	s.dash.Apply(msg)
	return s, nil
}

// splitRunes breaks a batched KeyRunes back into one message per keystroke.
// Every other key type (arrows, ctrl chords, enter) is already a single event and
// passes through untouched.
func splitRunes(msg tea.KeyMsg) []tea.KeyMsg {
	if msg.Type != tea.KeyRunes || len(msg.Runes) <= 1 {
		return []tea.KeyMsg{msg}
	}
	out := make([]tea.KeyMsg, 0, len(msg.Runes))
	for _, r := range msg.Runes {
		out = append(out, tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune{r}, Alt: msg.Alt})
	}
	return out
}

func (s *shell) onKey(msg tea.KeyMsg) tea.Cmd {
	key := msg.String()
	// A capturing view owns the whole keyboard: every global binding is skipped so
	// no keystroke meant for a text field can quit mid-word.
	if !s.dash.CapturesInput() {
		switch key {
		case "q", "ctrl+c":
			return tea.Quit
		case "a":
			if !s.dash.InDetail() && s.dash.AllApplies() {
				s.all = !s.all
				return s.refresh(s.all)
			}
			return nil
		case "ctrl+g":
			return s.openInEditor()
		case "y":
			if id := s.dash.CopyID(); id != "" {
				return copyCmd(id)
			}
			return nil
		}
	}

	before := s.dash.FetchKey()
	cmd := s.dash.OnKey(msg, KeyCtx{Cols: s.cols, TermRows: s.termRows, Refresh: s.refreshCmd()})
	// Re-poll when the module's data needs changed (a card was expanded and now
	// wants its tasks), rather than making every view remember to ask.
	if s.dash.FetchKey() != before {
		return tea.Batch(cmd, s.refresh(s.all))
	}
	return cmd
}

// Suspend the dashboard, hand the editor the controlling tty, then restore.
// tea.ExecProcess owns that whole handover — leaving the alt screen, restoring
// cooked mode, and repainting on return — so the dashboard only has to name the
// file and the argv.
func (s *shell) openInEditor() tea.Cmd {
	row, ok := s.dash.EditTarget()
	if !ok {
		return nil
	}
	file, err := data.EditorFileFor(row)
	if err != nil {
		return s.showFlash(fmt.Sprintf("editor file failed · %v", err))
	}
	argv := append(data.EditorArgv(), file)
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Dir = data.WorkspaceRoot()
	return tea.ExecProcess(cmd, func(err error) tea.Msg { return editorDoneMsg{err: err} })
}

// Copy the id under the cursor to a clipboard, flashing the result. The copy is
// best-effort across tmux/OS tools; a world with none reachable flashes the
// failure with the id still shown so it can be read off the screen.
func copyCmd(id string) tea.Cmd {
	return func() tea.Msg {
		if how := data.CopyToClipboard(id); how != "" {
			return flashMsg(fmt.Sprintf("copied %s · %s", id, how))
		}
		return flashMsg(fmt.Sprintf("no clipboard — %s", id))
	}
}

func (s *shell) tabBar() string {
	strip := s.dash.Strip()
	cells := make([]ui.Cell, 0, len(strip.Labels)*2)
	for i, label := range strip.Labels {
		if i > 0 {
			cells = append(cells, ui.Cell{Text: " | ", Dim: true})
		}
		active := i == strip.Active
		cells = append(cells, ui.Cell{Text: " " + label + " ", Bold: active, Inverse: active})
	}
	return ui.Row(cells, s.cols, false, false)
}

func (s *shell) View() string {
	body := s.dash.View(RenderCtx{Cols: s.cols, TermRows: s.termRows, Interactive: s.interactive, All: s.all})
	frame := strings.Join([]string{
		ui.Header(s.all, s.dash.Noun(), s.refreshedAt, s.cols, s.flash),
		s.tabBar(),
		"",
		body,
	}, "\n")
	if !s.interactive {
		return frame + "\n"
	}
	// Pin the frame to the terminal: a constant-height render means a shorter frame
	// overwrites a taller one (list ⇄ detail, tab switches) instead of leaving stale
	// lines, and a frame that overshoots would scroll the alt screen.
	lines := strings.Split(frame, "\n")
	if len(lines) > s.termRows {
		lines = lines[:s.termRows]
	}
	for len(lines) < s.termRows {
		lines = append(lines, "")
	}
	return strings.Join(lines, "\n")
}

// ── entry points ─────────────────────────────────────────────────────────────

// Run drives the dashboard. A TTY on both ends gets the interactive alt-screen
// program; anything else (a pipe, --once) fetches once and prints a single frame,
// which needs no terminal program at all.
func Run(dash Dash) error {
	interactive := isTTY(os.Stdout) && isTTY(os.Stdin) && !data.Opts().Once
	s := &shell{
		dash:        dash,
		all:         data.Opts().All,
		refreshedAt: time.Now(),
		cols:        120,
		termRows:    40,
		interactive: interactive,
	}

	if !interactive {
		// The printed frame must be real data, so the fetch is awaited here rather
		// than run as a Cmd nothing would pump.
		dash.Apply(dash.Refresh(s.all)())
		s.refreshedAt = time.Now()
		fmt.Print(s.View())
		return nil
	}

	p := tea.NewProgram(s, tea.WithAltScreen())
	_, err := p.Run()
	return err
}

func isTTY(f *os.File) bool {
	info, err := f.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}
