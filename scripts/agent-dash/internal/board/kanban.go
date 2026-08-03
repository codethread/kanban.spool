package board

// The board: the user↔agent work as a collapsible epic → feature → task tree
// (kanban.md). Normal polling reads the spool-owned `kanban board` snapshot.
// Expanded features fetch their authoritative task views lazily through `kanban
// card`; the explicit all view asks that same board op for compact all-state cards
// with direct epic membership. Epics group their features (`=`/`-` collapses the
// group, open by default); a feature that bears tasks gets a marker and `=`/`-`
// reveals/hides them (collapsed by default).
//
// The dashboard's tabs are this board's saved filter views: ⇥/⇧⇥ walk ALL → each
// saved view → the `+` slot, which opens the editor on a new one, and `f` edits
// the tab in force. A view is named labels combined with AND/OR plus per-label
// exclusion; filtering is client-side over the `labels` each card ships (the pure
// half lives in ./filter.go, which also owns the on-disk store).

import (
	"fmt"
	"slices"
	"sort"
	"strings"

	"github.com/charmbracelet/bubbles/textinput"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"

	"github.com/codethread/kanban.spool/scripts/agent-dash/internal/app"
	"github.com/codethread/kanban.spool/scripts/agent-dash/internal/data"
	"github.com/codethread/kanban.spool/scripts/agent-dash/internal/ui"
)

// KanbanRow is a kanban card (epic or feature) plus the two joins the client
// caches: the epic it hangs under (empty for top-level cards) and its lazily
// loaded tasks.
type KanbanRow struct {
	data.DetailRow
	Lane        string
	Type        string
	Owner       string
	Priority    string
	Epic        string
	Labels      []string
	Tasks       []TaskChild
	TasksLoaded bool
}

func (r KanbanRow) FilterID() string       { return r.ID }
func (r KanbanRow) FilterType() string     { return r.Type }
func (r KanbanRow) FilterEpic() string     { return r.Epic }
func (r KanbanRow) FilterLabels() []string { return r.Labels }

var laneColour = map[string]string{"claimed": "green", "in_review": "magenta", "pending": "yellow", "refinement": "cyan"}

// Derived task status (`kanban card`): doing is live work, ready is actionable,
// blocked waits on a dependency, closed is complete.
var taskStatusColour = map[string]string{"doing": "green", "ready": "yellow", "blocked": "red"}

// Priority tint mirrors the spool's p1..p4 urgency (kanban.md): p1 is an
// immediate blocker, p4 is someday. p3 is the unstamped default and stays plain.
var prioColour = map[string]string{"p1": "red", "p2": "yellow"}

func prioDim(p string) bool { return p == "p4" }

// Board lane order is review-first urgency, not the spool's lifecycle order:
// claimed work in flight, then the cards under review that a coordinator should
// clear next (in_review), then the actionable queue (pending), then ideas still in
// refinement. Closed strands sink regardless of their lane column — the
// vocabulary-reset cutover leaves closed cards on historic kanban/status while
// live cards carry kanban/lane and freshly closed ones kanban/outcome — and show
// their outcome (done/abandoned/...) dimmed.
var laneRankOf = map[string]int{"claimed": 0, "in_review": 1, "pending": 2, "refinement": 3}

func laneRank(r KanbanRow) int {
	if r.State == "closed" {
		return 4
	}
	if rank, ok := laneRankOf[r.Lane]; ok {
		return rank
	}
	return 4
}

// byLane orders a queue. created_at is "YYYY-MM-DD HH:MM:SS" (UTC), so lexical
// order is chronological, and "p1".."p4" also compares lexically. Active lanes are
// queues and sort priority-first then oldest-first to agree with `kanban next`
// (kanban.md); the closed bucket lists newest-first so fresh outcomes stay in
// reach. Used for both the top level and each epic's feature group.
func byLane(a, b KanbanRow) int {
	if rank := laneRank(a) - laneRank(b); rank != 0 {
		return rank
	}
	if a.State == "closed" {
		return strings.Compare(b.CreatedAt, a.CreatedAt)
	}
	if prio := strings.Compare(a.Priority, b.Priority); prio != 0 {
		return prio
	}
	return strings.Compare(a.CreatedAt, b.CreatedAt)
}

// `labels` is passed separately rather than read off `c`: when a card's tasks are
// expanded, `c` is the richer `kanban card` detail, and that view returns a null
// `labels` — folding it in would silently drop an expanded card out of its own
// filter. The board snapshot is the authority for labels.
func rowFromCard(c BoardCard, tasks []TaskChild, tasksLoaded bool, epic string, labels []string) KanbanRow {
	attrs := c.Attributes
	if attrs == nil {
		attrs = map[string]any{}
	}
	lane := ""
	if c.State == "closed" {
		lane = firstNonEmpty(c.Outcome, data.Str(attrs, "kanban/outcome"), c.Lane, data.Str(attrs, "kanban/status", "?"))
	} else {
		lane = firstNonEmpty(c.Lane, data.Str(attrs, "kanban/lane"), data.Str(attrs, "kanban/status", "?"))
	}
	return KanbanRow{
		DetailRow: data.DetailRow{
			ID:        c.ID,
			Title:     c.Title,
			State:     c.State,
			Branch:    firstNonEmpty(c.Branch, data.Str(attrs, "branch", "-")),
			CreatedAt: c.CreatedAt,
			UpdatedAt: firstNonEmpty(c.UpdatedAt, c.CreatedAt),
			Attrs:     attrs,
		},
		Lane:        lane,
		Type:        firstNonEmpty(c.Type, data.Str(attrs, "kanban/type", "feature")),
		Owner:       firstNonEmpty(c.Owner, data.Str(attrs, "owner", "-")),
		Priority:    firstNonEmpty(c.Priority, data.Str(attrs, "kanban/priority", "p3")),
		Epic:        epic,
		Labels:      labels,
		Tasks:       tasks,
		TasksLoaded: tasksLoaded,
	}
}

func firstNonEmpty(vs ...string) string {
	for _, v := range vs {
		if v != "" {
			return v
		}
	}
	return ""
}

// ── tree flattening ──────────────────────────────────────────────────────────
// The board is a flattened pre-order walk of the epic → feature → task tree. Every
// strand appears exactly once per render — dedup keeps a feature either top-level
// or under its epic, never both — so the row key is the plain strand id. Selection
// then stays anchored to the same card when a poll regroups it (a feature hopping
// under a newly-linked epic) instead of jumping, which a position-derived key
// would do the moment the ancestry changed. Epics are open unless the user
// collapsed them; features are closed unless the user expanded them — so the
// default board shows every feature (grouped under any epic) with tasks tucked
// away, matching the old flat board plus expand markers.

type marker int

const (
	markerLeaf marker = iota
	markerOpen
	markerClosed
)

// FlatRow is one rendered line. `Guide` is the ID-column tree art (box-drawing
// connectors) for this row's place in the tree; empty for a root. Titles carry
// their own indent+marker, so the guide is a second, denser read of the same
// structure. Exactly one of Card/Task is set.
type FlatRow struct {
	Key    string
	Depth  int
	Guide  string
	Card   *KanbanRow
	Task   *TaskChild
	Marker marker
}

// guideOf is one box-drawing prefix for a child row: the ancestor continuation
// columns (`segs`, "│ " where an ancestor has siblings below, "  " where it is
// spent) followed by the node's own connector. Roots (depth 0) carry no guide.
func guideOf(depth int, segs []string, last bool) string {
	if depth == 0 {
		return ""
	}
	connector := "├─"
	if last {
		connector = "└─"
	}
	return strings.Join(segs, "") + connector
}

func flatten(cards []KanbanRow, collapsed, expanded map[string]bool) []FlatRow {
	byID := make(map[string]bool, len(cards))
	for _, c := range cards {
		byID[c.ID] = true
	}
	// Group features under an epic that is itself present in the payload; a feature
	// whose epic is closed-and-filtered (or unset) stays a top-level card.
	featuresByEpic := map[string][]KanbanRow{}
	claimed := map[string]bool{}
	for _, c := range cards {
		if c.Type == "feature" && c.Epic != "" && byID[c.Epic] {
			featuresByEpic[c.Epic] = append(featuresByEpic[c.Epic], c)
			claimed[c.ID] = true
		}
	}
	for _, feats := range featuresByEpic {
		slices.SortStableFunc(feats, byLane)
	}
	var topLevel []KanbanRow
	for _, c := range cards {
		if c.Type == "epic" || !claimed[c.ID] {
			topLevel = append(topLevel, c)
		}
	}
	slices.SortStableFunc(topLevel, byLane)

	var rows []FlatRow
	// Each card's children are its epic's features or its own tasks; `segs` carries
	// the ancestor continuation columns down so a task under a non-last feature draws
	// the "│" that keeps the epic's branch connected.
	var emitCard func(card KanbanRow, depth int, segs []string, last bool)
	emitCard = func(card KanbanRow, depth int, segs []string, last bool) {
		isEpic := card.Type == "epic"
		feats := featuresByEpic[card.ID]
		open := expanded[card.ID]
		hasChildren := !card.TasksLoaded || len(card.Tasks) > 0
		if isEpic {
			open = !collapsed[card.ID]
			hasChildren = len(feats) > 0
		}
		m := markerLeaf
		if hasChildren {
			m = markerClosed
			if open {
				m = markerOpen
			}
		}
		row := card
		rows = append(rows, FlatRow{Key: card.ID, Depth: depth, Guide: guideOf(depth, segs, last), Card: &row, Marker: m})
		if !open || !hasChildren {
			return
		}
		childSegs := []string{}
		if depth > 0 {
			seg := "│ "
			if last {
				seg = "  "
			}
			childSegs = append(append([]string{}, segs...), seg)
		}
		if isEpic {
			for i, f := range feats {
				emitCard(f, depth+1, childSegs, i == len(feats)-1)
			}
			return
		}
		for i := range card.Tasks {
			task := card.Tasks[i]
			rows = append(rows, FlatRow{
				Key:   task.ID,
				Depth: depth + 1,
				Guide: guideOf(depth+1, childSegs, i == len(card.Tasks)-1),
				Task:  &task,
			})
		}
	}
	for _, c := range topLevel {
		emitCard(c, 0, nil, true)
	}
	return rows
}

func rowKey(r FlatRow) string { return r.Key }

func cardAt(rows []FlatRow, i int) *KanbanRow {
	if i < 0 || i >= len(rows) {
		return nil
	}
	return rows[i].Card
}

// ── list columns ─────────────────────────────────────────────────────────────

var markGlyph = map[marker]string{markerOpen: "▾ ", markerClosed: "▸ ", markerLeaf: "  "}

func rowID(r FlatRow) string {
	if r.Card != nil {
		return r.Card.ID
	}
	return r.Task.ID
}

// The ID cell doubles as the tree spine: the box-drawing guide precedes the id.
func rowIDCell(r FlatRow) string { return r.Guide + rowID(r) }

func rowLane(r FlatRow) string {
	if r.Card != nil {
		return r.Card.Lane
	}
	return r.Task.Status
}

func rowPrio(r FlatRow) string {
	if r.Card != nil {
		return r.Card.Priority
	}
	return ""
}

// PRIO renders as the bare number (colour carries the urgency); rowPrio keeps the
// "p1".."p4" form the colour/dim lookups key on.
func rowPrioNum(r FlatRow) string { return strings.TrimPrefix(rowPrio(r), "p") }

// Type column compacts feature→feat so it never widens past its 4-char header.
var typeAbbr = map[string]string{"feature": "feat", "epic": "epic", "task": "task"}

func rowType(r FlatRow) string {
	t := "task"
	if r.Card != nil {
		t = r.Card.Type
	}
	if abbr, ok := typeAbbr[t]; ok {
		return abbr
	}
	return t
}

// Under a narrow terminal (<80) the lane column costs the most width; compact
// known lanes to four-letter codes (unknowns — task statuses — fall back to a
// four-char slice).
var laneAbbr = map[string]string{"claimed": "clmd", "in_review": "revw", "pending": "pend", "refinement": "refn"}

func abbrevLane(lane string) string {
	if a, ok := laneAbbr[lane]; ok {
		return a
	}
	if len(lane) > 4 {
		return lane[:4]
	}
	return lane
}

func rowOwner(r FlatRow) string {
	if r.Card != nil {
		return r.Card.Owner
	}
	if r.Task.Owner != "" {
		return r.Task.Owner
	}
	return "-"
}

func rowBranch(r FlatRow) string {
	if r.Card != nil {
		return r.Card.Branch
	}
	return ""
}

func rowTitle(r FlatRow) string {
	indent := strings.Repeat("  ", r.Depth)
	if r.Card != nil {
		return indent + markGlyph[r.Marker] + ui.OneLine(r.Card.Title)
	}
	return indent + "  " + ui.OneLine(r.Task.Title)
}

const hint = "↑↓/jk move · ⌃d/⌃u page · = expand · - collapse · ⏎ attrs · ⇥/⇧⇥ filter tab · f edit tab · ⌃g open · y copy · a all/active · r refresh · q quit"

// ── the view module ──────────────────────────────────────────────────────────

// overlay is the editor for one tab's view: a picker over every label in play,
// working on a copy so nothing is written until ⏎ saves and ⎋ leaves the saved
// view (and the board under it) exactly as it was. `slot` is the tab being edited,
// or nil for the `+` tab's not-yet-saved view.
type overlay struct {
	view   FilterView
	slot   *int
	cursor int
	naming bool
	name   textinput.Model
}

// Kanban is the board view module. Its state is mutated in place: Bubble Tea
// funnels every input and every landed fetch through one Update goroutine, so
// there is no second writer to guard against.
type Kanban struct {
	rows         []KanbanRow
	taskCache    map[string][]TaskChild
	cardCache    map[string]BoardCard
	taskFailures map[string]string
	loaded       bool
	failure      string
	// The tree's per-card overrides against the defaults (epics open, features
	// closed); both survive polls so the tree the user shaped stays put while the
	// board refreshes underneath it.
	collapsed   map[string]bool
	expanded    map[string]bool
	filter      FilterState
	filterError string
	overlay     *overlay
	s           app.ListState

	detail   viewport.Model
	cols     int
	termRows int
}

func New() *Kanban {
	// Saved views are read once at startup; a store the user has never written is
	// simply an empty set, while a corrupt one degrades to empty plus a banner.
	state, err := LoadFilterState(FiltersFile(), data.WorkspaceRoot())
	vp := viewport.New(0, 0)
	// The detail pane's bindings are the dashboard's, not the component's defaults:
	// bare u/d/b/f/space scroll in stock viewport and mean nothing here.
	vp.KeyMap = detailKeyMap()
	return &Kanban{
		taskCache:    map[string][]TaskChild{},
		cardCache:    map[string]BoardCard{},
		taskFailures: map[string]string{},
		collapsed:    map[string]bool{},
		expanded:     map[string]bool{},
		filter:       state,
		filterError:  err,
		detail:       vp,
		cols:         120,
		termRows:     40,
	}
}

func detailKeyMap() viewport.KeyMap {
	km := viewport.DefaultKeyMap()
	km.PageUp.SetEnabled(false)
	km.PageDown.SetEnabled(false)
	km.HalfPageUp.SetKeys("ctrl+u")
	km.HalfPageDown.SetKeys("ctrl+d")
	km.Up.SetKeys("up", "k")
	km.Down.SetKeys("down", "j")
	return km
}

func (k *Kanban) Noun() string { return "cards" }

// activeView is the view the current tab filters by, or nil on the ALL tab.
func (k *Kanban) activeView() *FilterView {
	if k.filter.Active == nil || *k.filter.Active >= len(k.filter.Views) {
		return nil
	}
	return &k.filter.Views[*k.filter.Active]
}

// Every read of the board goes through treeOf, so the filter applies once, before
// grouping — and selection, paging, and the detail target all agree on which rows
// exist. `rows` is explicit so a poll can flatten its fresh cards against the
// latest view state.
func (k *Kanban) treeOf(rows []KanbanRow) []FlatRow {
	return flatten(ApplyFilter(rows, k.activeView()), k.collapsed, k.expanded)
}

func (k *Kanban) tree() []FlatRow { return k.treeOf(k.rows) }

// stripRows is the total rows the status strips above the board consume. Every
// banner that can draw is counted here once, so the list windows against the
// height it actually gets and the stacked heights still sum to the pinned frame.
func (k *Kanban) stripRows() int {
	n := 0
	if len(k.taskFailures) > 0 {
		n++
	}
	if k.filter.Active != nil {
		n++
	}
	if k.filterError != "" {
		n++
	}
	return n
}

func (k *Kanban) FetchKey() string {
	expanded := make([]string, 0, len(k.expanded))
	for id := range k.expanded {
		expanded = append(expanded, id)
	}
	slices.Sort(expanded)
	detail := ""
	if k.s.Mode == app.ModeDetail {
		if c := cardAt(k.tree(), k.s.Selected); c != nil {
			detail = c.ID
		}
	}
	return strings.Join(expanded, ",") + "|" + detail
}

func (k *Kanban) AllApplies() bool { return true }

// The strip is the saved views: ALL, each of them, then the `+` slot. An open
// overlay highlights the tab it is editing — the `+` while a new one is being
// authored — so the strip always says which tab the pane belongs to.
func (k *Kanban) Strip() app.Strip {
	active := PosOf(k.filter.Active)
	if k.overlay != nil {
		active = len(k.filter.Views) + 1
		if k.overlay.slot != nil {
			active = *k.overlay.slot + 1
		}
	}
	return app.Strip{Labels: StripLabels(k.filter.Views), Active: active}
}

func (k *Kanban) InDetail() bool { return k.s.Mode == app.ModeDetail || k.overlay != nil }

// The overlay owns every key while it is open, including the shell's q — a filter
// name with a "q" in it must not quit the dashboard.
func (k *Kanban) CapturesInput() bool { return k.overlay != nil }

func (k *Kanban) EditTarget() (data.DetailRow, bool) {
	if k.overlay != nil {
		return data.DetailRow{}, false
	}
	if c := cardAt(k.tree(), k.s.Selected); c != nil {
		return c.DetailRow, true
	}
	return data.DetailRow{}, false
}

// Unlike EditTarget (cards only — a task has no openable source), y copies the id
// of whatever row is under the cursor, task rows included: they are strands too.
func (k *Kanban) CopyID() string {
	if k.overlay != nil {
		return ""
	}
	rows := k.tree()
	if k.s.Selected < 0 || k.s.Selected >= len(rows) {
		return ""
	}
	return rowID(rows[k.s.Selected])
}

// ── fetching ─────────────────────────────────────────────────────────────────

// boardMsg is one settled poll. A failure rides in the message rather than being
// thrown, so a poll that cannot reach the workspace renders as the full-pane
// failure instead of tearing the program down.
type boardMsg struct {
	rows    []KanbanRow
	details CardDetails
	failure string
}

func (k *Kanban) Refresh(all bool) tea.Cmd {
	// The fetch inputs are read here, on the update loop, and captured — the closure
	// runs on its own goroutine and must not touch live view state.
	detailIDs := make([]string, 0, len(k.expanded)+1)
	for id := range k.expanded {
		detailIDs = append(detailIDs, id)
	}
	if k.s.Mode == app.ModeDetail {
		if c := cardAt(k.tree(), k.s.Selected); c != nil && !k.expanded[c.ID] {
			detailIDs = append(detailIDs, c.ID)
		}
	}
	slices.Sort(detailIDs)
	taskCache, cardCache := k.taskCache, k.cardCache

	return func() tea.Msg {
		details := LoadCardDetails(detailIDs, taskCache, cardCache, func(id string) (CardView, error) {
			var view CardView
			err := data.StrandJSON(&view, "kanban", "card", id)
			return view, err
		})
		rows, err := fetchKanban(all, details)
		if err != nil {
			return boardMsg{failure: err.Error()}
		}
		return boardMsg{rows: rows, details: details}
	}
}

func fetchKanban(all bool, details CardDetails) ([]KanbanRow, error) {
	var snapshot BoardSnapshot
	args := []string{"kanban", "board"}
	if all {
		args = append(args, "--all", "true")
	}
	if err := data.StrandJSON(&snapshot, args...); err != nil {
		return nil, err
	}
	cards := ActiveBoardCards(snapshot)
	if all {
		if snapshot.Cards == nil {
			return nil, fmt.Errorf("kanban board --all true returned no cards collection")
		}
		cards = snapshot.Cards
	}

	rows := make([]KanbanRow, 0, len(cards))
	for _, card := range cards {
		source := card
		if detail, ok := details.CardCache[card.ID]; ok {
			source = detail
		}
		tasks := details.TaskCache[card.ID]
		_, loaded := details.TaskCache[card.ID]
		if !all {
			tasks = ActiveTasks(tasks)
		}
		labels := card.Labels
		if labels == nil {
			labels = []string{}
		}
		rows = append(rows, rowFromCard(source, tasks, loaded, card.Epic, labels))
	}
	return rows, nil
}

func (k *Kanban) Apply(msg tea.Msg) bool {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		k.cols, k.termRows = msg.Width, msg.Height
		k.syncDetail()
		return true

	case boardMsg:
		k.loaded = true
		if msg.failure != "" {
			k.failure = msg.failure
			return true
		}
		k.failure = ""
		k.rows = msg.rows
		k.taskCache = msg.details.TaskCache
		k.cardCache = msg.details.CardCache
		// A failure banner outlives its card no longer than the card does, and an
		// expansion is dropped once the board stops listing what was expanded.
		live := make(map[string]bool, len(msg.rows))
		for _, r := range msg.rows {
			live[r.ID] = true
		}
		k.taskFailures = map[string]string{}
		for id, failure := range msg.details.TaskFailures {
			if live[id] {
				k.taskFailures[id] = failure
			}
		}
		for id := range k.expanded {
			if !live[id] {
				delete(k.expanded, id)
			}
		}
		k.s = app.FollowSelection(k.s, k.treeOf(msg.rows), rowKey)
		k.syncDetail()
		return true
	}
	return false
}

// ── key handling ─────────────────────────────────────────────────────────────

func (k *Kanban) OnKey(msg tea.KeyMsg, ctx app.KeyCtx) tea.Cmd {
	key := msg.String()
	// The overlay is modal: it precedes every other binding and swallows keys it
	// does not use, so a stray press can never leak through to the board beneath.
	if k.overlay != nil {
		return k.overlayKey(msg, key)
	}
	if k.s.Mode == app.ModeDetail {
		return k.detailKey(msg, key, ctx)
	}
	return k.listKey(key, ctx)
}

func (k *Kanban) overlayKey(msg tea.KeyMsg, key string) tea.Cmd {
	o := k.overlay
	labels := k.labelUniverse(o.view)

	if o.naming {
		// Name editing is a sub-mode because the picker's own keys (jk/space) are
		// letters: there is no way to type "test only" and navigate at once. The
		// text field owns every other key, so no printable-character filtering is
		// needed here.
		switch key {
		case "enter", "esc":
			o.naming = false
			o.view.Name = o.name.Value()
			o.name.Blur()
			return nil
		}
		var cmd tea.Cmd
		o.name, cmd = o.name.Update(msg)
		o.view.Name = o.name.Value()
		return cmd
	}

	switch key {
	case "tab":
		k.stepTab(false)
		return nil
	case "shift+tab":
		k.stepTab(true)
		return nil
	case "esc":
		k.overlay = nil
		return nil
	case "enter":
		k.commitOverlay(o)
		return nil
	case "i":
		o.naming = true
		o.name.SetValue(o.view.Name)
		o.name.CursorEnd()
		return o.name.Focus()
	case "m":
		// ⇥ is tab navigation everywhere else, so the mode toggle is `m` rather than
		// the same key meaning two things one keystroke apart.
		if o.view.Mode == ModeAnd {
			o.view.Mode = ModeOr
		} else {
			o.view.Mode = ModeAnd
		}
		return nil
	case " ", "!":
		if len(labels) == 0 {
			return nil
		}
		label := labels[min(o.cursor, len(labels)-1)]
		current := o.view.Terms[label]
		next := ToggleTerm(current)
		if key == "!" {
			next = NegateTerm(current)
		}
		o.view = WithTerm(o.view, label, next)
		return nil
	case "up", "k":
		o.cursor = max(0, o.cursor-1)
		return nil
	case "down", "j":
		o.cursor = min(len(labels)-1, o.cursor+1)
		return nil
	case "x":
		// Deleting the tab is the one overlay edit that lands without ⏎: there is no
		// working copy left to commit once the view it edits is gone. The `+` tab has
		// no saved view behind it, so there is nothing there to delete.
		if o.slot != nil {
			k.withFilter(DeleteView(k.filter.Views, *o.slot))
		}
		return nil
	}
	return nil
}

func (k *Kanban) detailKey(msg tea.KeyMsg, key string, ctx app.KeyCtx) tea.Cmd {
	switch key {
	case "tab", "shift+tab":
		// ⇥ means "show me that tab's board" wherever it is pressed, so it drops the
		// detail on the way: the card under it may not be among the rows the tab
		// being switched to keeps, and a detail pane left open over a card the board
		// no longer lists is a dead end reachable by one keystroke.
		k.stepTab(key == "shift+tab")
		k.s.Mode = app.ModeList
		return nil
	case "esc", "h", "left":
		k.s.Mode = app.ModeList
		return nil
	case "g":
		k.detail.GotoTop()
		return nil
	case "G":
		k.detail.GotoBottom()
		return nil
	case "r":
		return ctx.Refresh
	}
	var cmd tea.Cmd
	k.detail, cmd = k.detail.Update(msg)
	return cmd
}

func (k *Kanban) listKey(key string, ctx app.KeyCtx) tea.Cmd {
	rows := k.tree()
	// The banners steal list rows when they draw, so page jumps size against the
	// same reduced viewport the list renders into, not the raw terminal.
	listRows := ctx.TermRows - k.stripRows()
	page := ui.ListPage(listRows, ui.HintRows(hint, ctx.Cols))
	if moved, ok := app.ReduceListKeys(k.s, key, rows, rowKey, page); ok {
		k.s = moved
		return nil
	}

	switch key {
	case "tab", "shift+tab":
		// ⇥/⇧⇥ walk the strip. The `+` tab has no board of its own — it is where a
		// new filter is authored — so landing on it opens the editor instead of
		// switching.
		k.stepTab(key == "shift+tab")
		return nil
	case "f":
		// Edit the tab in force; on ALL — which has no view to edit — author a new one.
		k.openOverlay(k.filter.Active)
		return nil
	case "=", "-":
		k.toggleExpand(cardAt(rows, k.s.Selected), key == "=")
		return nil
	case "enter", "l", "right":
		if cardAt(rows, k.s.Selected) == nil {
			return nil
		}
		k.s.Mode = app.ModeDetail
		k.detail.GotoTop()
		k.syncDetail()
		return nil
	case "r":
		return ctx.Refresh
	}
	return nil
}

// Expand/collapse the selected card. Epics default open (toggled via collapsed);
// features default closed (toggled via expanded), and only when they bear tasks.
func (k *Kanban) toggleExpand(card *KanbanRow, open bool) {
	if card == nil {
		return
	}
	if card.Type == "epic" {
		if open {
			delete(k.collapsed, card.ID)
		} else {
			k.collapsed[card.ID] = true
		}
		return
	}
	if card.TasksLoaded && len(card.Tasks) == 0 {
		return
	}
	if open {
		k.expanded[card.ID] = true
	} else {
		delete(k.expanded, card.ID)
	}
}

// ── tabs and the overlay ─────────────────────────────────────────────────────

func (k *Kanban) openOverlay(slot *int) {
	view := EmptyView()
	if slot != nil && *slot < len(k.filter.Views) {
		view = k.filter.Views[*slot].Clone()
	} else {
		slot = nil
	}
	name := textinput.New()
	name.Prompt = ""
	name.CharLimit = 64
	k.overlay = &overlay{view: view, slot: slot, name: name}
}

// withFilter moves to another tab: the board underneath changes, so the store is
// written through and the selection re-anchored against the rows the new tab
// shows. Closing any open overlay is part of it — every path here settles the edit.
func (k *Kanban) withFilter(filter FilterState) {
	k.filter = filter
	k.filterError = SaveFilterState(FiltersFile(), data.WorkspaceRoot(), filter)
	k.overlay = nil
	k.s = app.FollowSelection(k.s, k.tree(), rowKey)
}

// commitOverlay saves the overlay's working copy as its tab and switches to it. A
// view nobody named and gave no terms describes nothing, so it saves nothing and
// the board stays on the tab it was already showing.
func (k *Kanban) commitOverlay(o *overlay) {
	if o.view.IsBlank() {
		k.overlay = nil
		return
	}
	k.withFilter(SaveView(k.filter.Views, o.slot, o.view))
}

// stepTab steps the strip with ⇥/⇧⇥, from the editor as much as from the board:
// the working copy is dropped (⏎ is the only thing that saves) and the walk
// carries on, so cycling the tabs passes over the `+` slot instead of being
// trapped in the editor that landing on it opens.
func (k *Kanban) stepTab(back bool) {
	count := len(k.filter.Views)
	from := PosOf(k.filter.Active)
	if k.overlay != nil {
		from = count + 1
		if k.overlay.slot != nil {
			from = *k.overlay.slot + 1
		}
	}
	at := ViewAt(StepPos(from, count, back), count)
	if at.New {
		k.openOverlay(nil)
		return
	}
	k.withFilter(FilterState{Views: k.filter.Views, Active: at.Index})
}

// Every label the picker offers: the ones cards actually carry, plus any the view
// being edited still names. A view outliving the last card with its label must
// stay editable — otherwise its term is invisible and impossible to clear.
func (k *Kanban) labelUniverse(view FilterView) []string {
	seen := map[string]bool{}
	for _, r := range k.rows {
		for _, l := range r.Labels {
			seen[l] = true
		}
	}
	for l := range view.Terms {
		seen[l] = true
	}
	out := make([]string, 0, len(seen))
	for l := range seen {
		out = append(out, l)
	}
	sort.Strings(out)
	return out
}

func (k *Kanban) labelCount(label string) int {
	n := 0
	for _, r := range k.rows {
		if slices.Contains(r.Labels, label) {
			n++
		}
	}
	return n
}

// ── rendering ────────────────────────────────────────────────────────────────

// syncDetail re-sizes and re-fills the detail viewport from whatever the cursor
// now points at. It runs on every event that can change either — a resize, a
// landed poll, entering the pane — because the component holds its own copy of
// both and cannot ask for them.
func (k *Kanban) syncDetail() {
	k.detail.Width = k.cols
	k.detail.Height = ui.DetailViewport(k.termRows)
	if k.s.Mode != app.ModeDetail {
		return
	}
	if card := cardAt(k.tree(), k.s.Selected); card != nil {
		k.detail.SetContent(ui.DetailBody(card.DetailRow, k.cols))
	} else {
		k.detail.SetContent("")
	}
}

func (k *Kanban) View(ctx app.RenderCtx) string {
	if k.failure != "" {
		return ui.Failure(k.failure, ctx.Cols)
	}
	// The picker takes the whole pane, like the detail view: its own name/mode
	// header would not survive sharing the frame with the board's status strips.
	if k.overlay != nil && ctx.Interactive {
		return k.viewOverlay(ctx)
	}
	rows := k.tree()
	if k.s.Mode == app.ModeDetail && ctx.Interactive {
		return k.viewDetail(rows, ctx)
	}

	var out []string
	if banner := k.taskFailureBanner(ctx); banner != "" {
		out = append(out, banner)
	}
	if k.filterError != "" {
		out = append(out, ui.Red.Bold(true).Render(ui.Clip("SAVED FILTERS · "+ui.OneLine(k.filterError), ctx.Cols)))
	}
	if strip := k.filterStrip(ctx); strip != "" {
		out = append(out, strip)
	}
	out = append(out, k.viewTree(rows, ctx))
	return strings.Join(out, "\n")
}

func (k *Kanban) viewDetail(rows []FlatRow, ctx app.RenderCtx) string {
	card := cardAt(rows, k.s.Selected)
	if card == nil {
		return ui.Dim.Render(ui.Clip("no longer listed — esc to go back", ctx.Cols))
	}
	from := k.detail.YOffset
	maxScroll := max(0, k.detail.TotalLineCount()-k.detail.Height)
	return strings.Join([]string{
		ui.DetailHead(card.DetailRow, ctx.Cols),
		"",
		k.detail.View(),
		ui.DetailFooter(from, maxScroll, ctx.Cols),
	}, "\n")
}

func (k *Kanban) taskFailureBanner(ctx app.RenderCtx) string {
	if len(k.taskFailures) == 0 {
		return ""
	}
	ids := make([]string, 0, len(k.taskFailures))
	for id := range k.taskFailures {
		ids = append(ids, id)
	}
	slices.Sort(ids)
	suffix := ""
	if len(ids) > 1 {
		suffix = fmt.Sprintf(" · %d more", len(ids)-1)
	}
	text := fmt.Sprintf("TASK DETAIL %s failed · %s%s", ids[0], ui.OneLine(k.taskFailures[ids[0]]), suffix)
	return ui.Red.Bold(true).Render(ui.Clip(text, ctx.Cols))
}

// One line spelling out what the tab in force actually filters on and what it left
// on screen, so a board that is hiding cards always says so — a filtered board and
// an empty backlog must never look the same. The ALL tab hides nothing and gets no
// strip.
func (k *Kanban) filterStrip(ctx app.RenderCtx) string {
	view := k.activeView()
	if view == nil {
		return ""
	}
	shown := len(ApplyFilter(k.rows, view))
	text := fmt.Sprintf("FILTER · %s · %d/%d cards · f edit · ⇥ next tab", DescribeView(*view), shown, len(k.rows))
	return ui.Blue.Bold(true).Render(ui.Clip(text, ctx.Cols))
}

func (k *Kanban) viewTree(rows []FlatRow, ctx app.RenderCtx) string {
	cols := ctx.Cols
	termRows := ctx.TermRows - k.stripRows()
	if len(rows) == 0 {
		text := "loading board…"
		if k.loaded {
			axis := "active "
			if ctx.All {
				axis = ""
			}
			text = "no " + axis + "cards"
		}
		return ui.Dim.Render(ui.Clip(text, cols))
	}

	narrow := cols < 80
	laneText := func(r FlatRow) string {
		if narrow {
			return abbrevLane(rowLane(r))
		}
		return rowLane(r)
	}
	// Narrow terminals collapse a present branch to a tick; rows without one keep
	// their bare value (a "-"/"" placeholder) so the column still reads.
	branchHeader := "BRANCH"
	if narrow {
		branchHeader = "B"
	}
	branchText := func(r FlatRow) string {
		b := rowBranch(r)
		if narrow && b != "" && b != "-" {
			return "✓"
		}
		return b
	}

	col := func(name string, of func(FlatRow) string, limit int) int {
		values := make([]string, len(rows))
		for i, r := range rows {
			values[i] = of(r)
		}
		return ui.FitCol(name, values, limit)
	}
	wID := col("ID", rowIDCell, 16)
	wLane := col("LANE", laneText, 12)
	wPrio := col("P", rowPrioNum, 4)
	wType := col("TYPE", rowType, 8)
	wOwner := col("OWNER", rowOwner, 14)
	wBranch := col(branchHeader, branchText, 24)
	wTitle := max(0, cols-12-wID-wLane-wPrio-wType-wOwner-wBranch)

	gap := ui.Cell{Text: "  "}
	out := []string{ui.Row([]ui.Cell{
		{Text: ui.Pad("ID", wID)}, gap,
		{Text: ui.Pad("LANE", wLane)}, gap,
		{Text: ui.Pad("P", wPrio)}, gap,
		{Text: ui.Pad("TYPE", wType)}, gap,
		{Text: ui.Pad("TITLE", wTitle)}, gap,
		{Text: ui.Pad("OWNER", wOwner)}, gap,
		{Text: branchHeader},
	}, cols, false, true)}

	start, visible, below := ui.WindowRows(rows, k.s.Selected, ctx.Interactive, termRows, ui.HintRows(hint, cols))
	for i, r := range visible {
		selected := ctx.Interactive && start+i == k.s.Selected
		isTask := r.Task != nil
		// Cards colour the lane/priority; tasks colour the derived-status cell in the
		// lane column and read dimmer overall (they hang under their feature).
		laneColourName := laneColour[rowLane(r)]
		if isTask {
			laneColourName = taskStatusColour[r.Task.Status]
		}
		closed := r.Card != nil && r.Card.State == "closed"
		rowClosed := closed
		if isTask {
			rowClosed = r.Task.State == "closed"
		}
		prioColourName := prioColour[rowPrio(r)]
		if closed {
			prioColourName = ""
		}
		out = append(out, ui.Row([]ui.Cell{
			{Text: ui.Pad(rowIDCell(r), wID), Dim: isTask}, gap,
			{Text: ui.Pad(laneText(r), wLane), Colour: laneColourName, Dim: rowClosed}, gap,
			{Text: ui.Pad(rowPrioNum(r), wPrio), Colour: prioColourName, Dim: closed || prioDim(rowPrio(r))}, gap,
			{Text: ui.Pad(rowType(r), wType), Dim: rowType(r) != "epic"}, gap,
			{Text: ui.Pad(rowTitle(r), wTitle), Dim: rowClosed}, gap,
			{Text: ui.Pad(rowOwner(r), wOwner), Dim: true}, gap,
			{Text: ui.Pad(branchText(r), wBranch)},
		}, cols, selected, false))
	}
	if ctx.Interactive {
		out = append(out, ui.ListFooter(hint, start, below, len(rows), cols))
	}
	return strings.Join(out, "\n")
}

var termMark = map[Term]string{TermInclude: "✓", TermExclude: "✗"}
var termColour = map[Term]string{TermInclude: "green", TermExclude: "red"}

func (k *Kanban) viewOverlay(ctx app.RenderCtx) string {
	o := k.overlay
	cols := ctx.Cols
	labels := k.labelUniverse(o.view)

	head := "EDIT FILTER TAB"
	overlayHint := "↑↓/jk move · ␣ toggle · ! exclude · m and/or · i name · x delete · ⏎ save · ⎋ cancel · ⇥ next tab"
	if o.slot == nil {
		head = "NEW FILTER TAB"
		overlayHint = "↑↓/jk move · ␣ toggle · ! exclude · m and/or · i name · ⏎ save · ⎋ cancel · ⇥ next tab"
	}
	if o.naming {
		overlayHint = "type a name · ⏎/⎋ done"
	}

	name := o.view.Name
	if o.naming {
		name = o.name.View()
	} else if name == "" {
		name = "(unnamed)"
	}
	modeSuffix := "  (all of)"
	if o.view.Mode == ModeOr {
		modeSuffix = "  (any of)"
	}

	out := []string{
		ui.Bold.Render(ui.Clip(head, cols)),
		ui.Dim.Render(ui.Pad("name", 6)) + ui.Clip(name, max(0, cols-8)),
		ui.Dim.Render(ui.Pad("mode", 6)) + ui.Cyan.Render(strings.ToUpper(string(o.view.Mode))) +
			ui.Dim.Render(ui.Clip(modeSuffix, max(0, cols-12))),
		"",
	}

	if len(labels) == 0 {
		out = append(out, ui.Dim.Render(ui.Clip("no labels on the board — `strand kanban label add <id> <slug>`", cols)))
	} else {
		// The pane spends the list viewport less its four header rows (title, name,
		// mode, spacer), against a footer that wraps to however many rows the hint
		// needs, so the whole overlay stays inside the frame the shell pins.
		viewport := max(3, ui.ListViewport(ctx.TermRows, ui.HintRows(overlayHint, cols))-4)
		start := max(0, min(o.cursor-viewport/2, len(labels)-viewport))
		end := min(len(labels), start+viewport)
		for i, label := range labels[start:end] {
			selected := start+i == o.cursor && !o.naming
			term := o.view.Terms[label]
			mark := " "
			if m, ok := termMark[term]; ok {
				mark = m
			}
			out = append(out, ui.Row([]ui.Cell{
				{Text: "  "},
				{Text: ui.Pad(mark, 2), Colour: termColour[term]},
				{Text: ui.Pad(label, max(8, min(32, cols-16)))},
				{Text: fmt.Sprint(k.labelCount(label)), Dim: true},
			}, cols, selected, false))
		}
	}
	out = append(out, ui.ListFooter(overlayHint, 0, 0, len(labels), cols))
	return strings.Join(out, "\n")
}
