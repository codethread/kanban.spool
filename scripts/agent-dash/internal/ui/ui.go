// Shared presentation kit for the dashboard: text-fitting helpers, the
// header/failure chrome, the list windowing/footer primitives, and the
// strand-generic detail view. The view module renders over these; its columns and
// colour maps live in its own file.
package ui

import (
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/charmbracelet/lipgloss"
	"github.com/charmbracelet/x/ansi"

	"github.com/codethread/kanban.spool/scripts/agent-dash/internal/data"
)

// ── styling ──────────────────────────────────────────────────────────────────
// Every style the dashboard draws with, resolved once. Colours are the ANSI 0-15
// palette so they follow whatever the user's terminal theme sets, exactly as the
// Ink build did.

var (
	Bold    = lipgloss.NewStyle().Bold(true)
	Dim     = lipgloss.NewStyle().Faint(true)
	Red     = lipgloss.NewStyle().Foreground(lipgloss.Color("1"))
	Green   = lipgloss.NewStyle().Foreground(lipgloss.Color("2"))
	Yellow  = lipgloss.NewStyle().Foreground(lipgloss.Color("3"))
	Blue    = lipgloss.NewStyle().Foreground(lipgloss.Color("4"))
	Magenta = lipgloss.NewStyle().Foreground(lipgloss.Color("5"))
	Cyan    = lipgloss.NewStyle().Foreground(lipgloss.Color("6"))
)

// Colour looks up one of the palette names the view modules key their maps on.
// An unmapped name renders plain, so a new lane value never crashes the board.
func Colour(name string) lipgloss.Style {
	switch name {
	case "red":
		return Red
	case "green":
		return Green
	case "yellow":
		return Yellow
	case "blue":
		return Blue
	case "magenta":
		return Magenta
	case "cyan":
		return Cyan
	default:
		return lipgloss.NewStyle()
	}
}

// ── text fitting ─────────────────────────────────────────────────────────────

// Width is the terminal columns a string occupies, ANSI sequences excluded.
func Width(s string) int { return ansi.StringWidth(s) }

// Clip fits a string into w columns, marking any loss with an ellipsis. ansi
// reserves a column for that tail, so anything already fitting short-circuits.
func Clip(s string, w int) string {
	if w <= 0 {
		return ""
	}
	if ansi.StringWidth(s) <= w {
		return s
	}
	return ansi.Truncate(s, w, "…")
}

// Pad clips a string to w columns and then fills the remainder with spaces, so
// the result is exactly w columns wide.
func Pad(s string, w int) string {
	t := Clip(s, w)
	return t + strings.Repeat(" ", max(0, w-ansi.StringWidth(t)))
}

// OneLine flattens whitespace so a multi-line value can sit in a table cell.
func OneLine(s string) string { return strings.Join(strings.Fields(s), " ") }

// wrap breaks text to `w` columns at word boundaries, hard-breaking words too
// long to fit. Every layout height calculation goes through it so the rows a
// caller reserves and the rows it renders are counted the same way.
func wrap(s string, w int) []string {
	if w < 1 {
		w = 1
	}
	return strings.Split(ansi.Wrap(s, w, ""), "\n")
}

// FitCol sizes a column to its widest value, never past `cap` and never under
// its own header.
func FitCol(name string, values []string, limit int) int {
	w := len(name)
	for _, v := range values {
		w = max(w, ansi.StringWidth(v))
	}
	return min(limit, w)
}

// ── rows ─────────────────────────────────────────────────────────────────────

// Cell is one column (or one literal gap between columns) of a table row, with
// its own styling.
type Cell struct {
	Text    string
	Colour  string
	Dim     bool
	Bold    bool
	Inverse bool
}

// Row lays cells out left to right within a width budget, each clipped to
// whatever budget remains, so the joined line can never exceed `width`. This is
// the hard guarantee that no rendered row wraps at any terminal size — a wrapped
// row would add lines the frame math (WindowRows/DetailViewport) does not count.
//
// A row-level `inverse` is pushed down into every cell rather than wrapped around
// the finished line: an SGR reverse applied outside cells that already closed
// their own styling does not repaint them, so the selected row would come back
// half-highlighted.
func Row(cells []Cell, width int, inverse, bold bool) string {
	var b strings.Builder
	used := 0
	for _, c := range cells {
		if used >= width {
			break
		}
		t := Clip(c.Text, width-used)
		if t == "" {
			continue
		}
		used += ansi.StringWidth(t)
		style := lipgloss.NewStyle()
		if c.Colour != "" && !inverse {
			style = Colour(c.Colour)
		}
		if c.Dim && !inverse {
			style = style.Faint(true)
		}
		if c.Bold || bold {
			style = style.Bold(true)
		}
		if c.Inverse || inverse {
			style = style.Reverse(true)
		}
		b.WriteString(style.Render(t))
	}
	// The selection bar reads as a bar only when it spans the terminal, so an
	// inverted row is filled out to the full width rather than stopping at its
	// last glyph.
	if inverse && used < width {
		b.WriteString(lipgloss.NewStyle().Reverse(true).Render(strings.Repeat(" ", width-used)))
	}
	return b.String()
}

// ── chrome ───────────────────────────────────────────────────────────────────

const title = "kanban"

// Header is clipped to a single line: a wrapped header would add rows the layout
// math (which pins the frame to the terminal height) does not account for. A
// transient `flash` (a y-copy result) takes over the info half in green so the
// confirmation lands where the eye already is, without stealing a layout row.
func Header(all bool, noun string, refreshedAt time.Time, cols int, flash string) string {
	axis := "active"
	if all {
		axis = "all"
	}
	info := fmt.Sprintf(" %s · %s %s · every %s · %s",
		data.WorkspaceRoot(), axis, noun, data.Opts().Interval, refreshedAt.Format("15:04:05"))
	tail := max(0, cols-len(title))
	if flash != "" {
		return Bold.Render(title) + Green.Render(Clip(" "+flash, tail))
	}
	return Bold.Render(title) + Dim.Render(Clip(info, tail))
}

// Failure lines are clipped to cols so a long strand error cannot wrap past the
// pinned frame height and corrupt it.
func Failure(failure string, cols int) string {
	lines := []string{
		"",
		Red.Bold(true).Render(Clip(fmt.Sprintf("strand poll failed — retrying every %s", data.Opts().Interval), cols)),
	}
	for i, l := range strings.Split(failure, "\n") {
		if i >= 6 {
			break
		}
		lines = append(lines, Red.Render(Clip("  "+l, cols)))
	}
	return strings.Join(lines, "\n")
}

// ── layout ───────────────────────────────────────────────────────────────────
// The interactive frame is pinned to the full terminal height, so a view may only
// spend as many scrolling rows as remain after its fixed chrome. Overshooting
// overflows the frame and scrolls the alt screen, so both viewports derive from
// one accounting rather than scattered magic numbers.
//
//	shell   header + tab strip + the content block's top margin
//	list    the column header + the footer's top margin; the footer's own text
//	        rows vary with how far its hint wraps, so callers pass that count in
//	        (HintRows) rather than it being baked in here.
//	detail  the id/title line, the meta line, the attribute block's top margin,
//	        and the footer (its own top margin + text)
//	slack   one row left unwritten so a full frame never lands on the terminal's
//	        last cell and nudges an autoscroll.
const (
	chromeShell  = 3
	chromeList   = 2
	chromeDetail = 5
	chromeSlack  = 1
)

func ListViewport(termRows, footerRows int) int {
	return max(3, termRows-chromeShell-chromeList-footerRows-chromeSlack)
}

func DetailViewport(termRows int) int {
	return max(3, termRows-chromeShell-chromeDetail-chromeSlack)
}

// Space held for the scroll counter ListFooter appends, so the footer's height is
// a function of the hint and width alone. Reserving it up front is what keeps the
// footer from growing a line mid-scroll — which would resize the viewport under
// the selection every time the counter appeared or wrapped.
const counterReserve = " · 0000↑ 0000↓ of 0000"

// HintRows is how many rows a hint occupies at this width — the number every
// caller must agree on: the paging math to size its viewport, ListFooter to fill
// exactly that many lines. One row minimum, however narrow the terminal.
func HintRows(hint string, cols int) int {
	if cols <= 0 {
		return 1
	}
	return len(wrap(hint+counterReserve, cols))
}

// ListPage and DetailPage are the ⌃u/⌃d jump: half a viewport, vim's half-page
// scroll, floored to at least one row so a tiny terminal still moves.
func ListPage(termRows, footerRows int) int { return max(1, ListViewport(termRows, footerRows)/2) }
func DetailPage(termRows int) int           { return max(1, DetailViewport(termRows)/2) }

// WindowRows is the visible slice centred on the selection, plus the off-screen
// counts for the scroll hint. A non-interactive frame prints every row.
func WindowRows[T any](rows []T, selected int, interactive bool, termRows, footerRows int) (start int, visible []T, below int) {
	viewport := len(rows)
	if interactive {
		viewport = ListViewport(termRows, footerRows)
	}
	start = max(0, min(selected-viewport/2, len(rows)-viewport))
	end := min(len(rows), start+viewport)
	visible = rows[start:end]
	return start, visible, len(rows) - end
}

// ListFooter wraps its hint rather than truncating it, so every binding stays
// readable on a narrow terminal. Height is always exactly HintRows(hint, cols) —
// short renders are padded with blank lines — because the paging math sized the
// viewport against that same number, and a footer even one line taller would push
// the frame past its pinned height.
func ListFooter(hint string, start, below, total, cols int) string {
	text := hint
	if start != 0 || below != 0 {
		text += fmt.Sprintf(" · %d↑ %d↓ of %d", start, below, total)
	}
	height := HintRows(hint, cols)
	lines := wrap(text, max(1, cols))
	if len(lines) > height {
		lines = lines[:height]
	}
	out := []string{""}
	for _, l := range lines {
		out = append(out, Dim.Render(Clip(l, cols)))
	}
	for len(out) < height+1 {
		out = append(out, "")
	}
	return strings.Join(out, "\n")
}

// ── the detail view ──────────────────────────────────────────────────────────

// DetailLine is one display row of the attribute block; `Key` is set only on the
// first line of each attribute, so a wrapped value continues under a blank key
// column.
type DetailLine struct {
	Key  string
	Text string
}

// DetailLines is the wrapped attribute block a detail view scrolls through. keyw
// is returned so the renderer lays out the same columns the wrap was measured
// against.
func DetailLines(row data.DetailRow, cols int) (keyw int, lines []DetailLine) {
	keys := make([]string, 0, len(row.Attrs))
	for k := range row.Attrs {
		keys = append(keys, k)
	}
	slices.Sort(keys)

	// keyw is capped by the terminal so the key column plus its 2-space gap always
	// leaves at least one cell for the value; valw then takes whatever remains,
	// however small, rather than a hard floor that could push the line past cols.
	widest := 4
	for _, k := range keys {
		widest = max(widest, len(k))
	}
	keyw = max(0, min(28, cols-3, widest))
	valw := max(1, cols-keyw-2)

	for _, k := range keys {
		text, ok := row.Attrs[k].(string)
		if !ok {
			encoded, err := json.Marshal(row.Attrs[k])
			if err != nil {
				encoded = []byte(fmt.Sprint(row.Attrs[k]))
			}
			text = string(encoded)
		}
		wrapped := wrap(text, valw)
		lines = append(lines, DetailLine{Key: k, Text: wrapped[0]})
		for _, cont := range wrapped[1:] {
			lines = append(lines, DetailLine{Text: cont})
		}
	}
	return keyw, lines
}

// DetailBody renders the attribute block a viewport scrolls over: the same lines
// DetailLines measured, styled. The viewport owns the scroll offset and its
// clamping, which is why nothing here takes one.
func DetailBody(row data.DetailRow, cols int) string {
	keyw, lines := DetailLines(row, cols)
	out := make([]string, 0, len(lines))
	for _, l := range lines {
		key := strings.Repeat(" ", keyw)
		if l.Key != "" {
			key = Cyan.Render(Pad(l.Key, keyw))
		}
		out = append(out, key+"  "+l.Text)
	}
	return strings.Join(out, "\n")
}

// DetailHead is the two fixed rows above the scrolling attribute block: identity
// and the strand's own metadata.
func DetailHead(row data.DetailRow, cols int) string {
	id := Clip(row.ID, cols)
	meta := fmt.Sprintf("state %s · branch %s · created %s · updated %s",
		row.State, row.Branch, row.CreatedAt, row.UpdatedAt)
	return Bold.Render(id) + "  " + Clip(row.Title, max(0, cols-ansi.StringWidth(id)-3)) +
		"\n" + Dim.Render(Clip(meta, cols))
}

// DetailFooter is the detail view's hint line, with the scroll counter appended
// once there is anything off screen.
func DetailFooter(from, maxScroll, cols int) string {
	hint := "↑↓/jk scroll · ⌃d/⌃u page · ⌃g open · y copy · ⇥/⇧⇥ filter tab · esc back · q quit"
	if maxScroll > 0 {
		hint += fmt.Sprintf(" · %d↑ %d↓", from, maxScroll-from)
	}
	return "\n" + Dim.Render(Clip(hint, cols))
}

// Age renders how long ago an instant was, in the coarsest unit that still says
// something.
func Age(from time.Time, ok bool, now time.Time) string {
	if !ok {
		return "-"
	}
	s := int(max(0, now.Sub(from).Seconds()))
	if s < 60 {
		return fmt.Sprintf("%ds", s)
	}
	m := s / 60
	if m < 60 {
		return fmt.Sprintf("%dm", m)
	}
	h := m / 60
	if h < 24 {
		return fmt.Sprintf("%dh%dm", h, m%60)
	}
	return fmt.Sprintf("%dd%dh", h/24, h%24)
}
