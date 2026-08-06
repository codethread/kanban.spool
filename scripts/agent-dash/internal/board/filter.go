// Saved label-filter views for the board, and their on-disk store. Pure
// transforms plus two filesystem entry points, kept out of the render tab so
// tests exercise matching, tab navigation, and persistence without a dashboard.
//
// Filtering is client-side by design: `kanban board` ships each card's `labels`
// array (spool-side `--label` is AND-only, with no OR and no negation), so the
// richer boolean surface here costs nothing and never needs a second read.
package board

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strings"
)

// A label is either required or forbidden; absent from Terms means the view is
// indifferent to it. Excludes are hard in both modes (see Matches), so Mode only
// ever combines the includes.
type (
	FilterMode string
	Term       string
)

const (
	ModeAnd FilterMode = "and"
	ModeOr  FilterMode = "or"

	TermInclude Term = "include"
	TermExclude Term = "exclude"
)

type FilterView struct {
	Name  string
	Mode  FilterMode
	Terms map[string]Term
}

// FilterState is the saved strip. Active indexes Views, and nil is the
// unfiltered ALL tab — the whole board.
type FilterState struct {
	Views  []FilterView
	Active *int
}

func EmptyView() FilterView         { return FilterView{Mode: ModeAnd, Terms: map[string]Term{}} }
func EmptyFilterState() FilterState { return FilterState{Views: []FilterView{}} }

// Clone is a deep copy, so the overlay can edit a working copy without the saved
// view seeing it until ⏎ commits.
func (v FilterView) Clone() FilterView {
	terms := make(map[string]Term, len(v.Terms))
	for k, t := range v.Terms {
		terms[k] = t
	}
	return FilterView{Name: v.Name, Mode: v.Mode, Terms: terms}
}

// IsBlank reports a view nobody named and nobody gave a term to, which says
// nothing, so committing one saves nothing. A *named* view with no terms is kept
// — it is a deliberate "show everything" bookmark.
func (v FilterView) IsBlank() bool {
	return strings.TrimSpace(v.Name) == "" && len(v.Terms) == 0
}

// ── term editing ─────────────────────────────────────────────────────────────
// Space toggles a label in and out of the view; ! swings it to the forbidden side
// (and back to required), so a label can be excluded without first being
// included. The empty Term is "absent".

func ToggleTerm(t Term) Term {
	if t == "" {
		return TermInclude
	}
	return ""
}

func NegateTerm(t Term) Term {
	if t == TermExclude {
		return TermInclude
	}
	return TermExclude
}

func WithTerm(view FilterView, label string, next Term) FilterView {
	out := view.Clone()
	if next == "" {
		delete(out.Terms, label)
	} else {
		out.Terms[label] = next
	}
	return out
}

// ── matching ─────────────────────────────────────────────────────────────────

// Matches reports whether a card's labels satisfy a view. An excluded label
// always vetoes, in both modes: "or" widens which cards get in, never which ones
// get past a hard exclusion. With no includes at all the view is a pure
// subtraction, so everything the excludes don't veto passes.
func Matches(labels []string, view FilterView) bool {
	carried := make(map[string]bool, len(labels))
	for _, l := range labels {
		carried[l] = true
	}
	var includes []string
	for label, term := range view.Terms {
		switch term {
		case TermExclude:
			if carried[label] {
				return false
			}
		case TermInclude:
			includes = append(includes, label)
		}
	}
	if len(includes) == 0 {
		return true
	}
	if view.Mode == ModeAnd {
		for _, label := range includes {
			if !carried[label] {
				return false
			}
		}
		return true
	}
	for _, label := range includes {
		if carried[label] {
			return true
		}
	}
	return false
}

// Filterable is the shape ApplyFilter needs of a card: enough to match it and to
// know whether it is an epic somebody hangs under.
type Filterable interface {
	FilterID() string
	FilterType() string
	FilterEpic() string
	FilterLabels() []string
}

// ApplyFilter keeps the cards a view admits. The board is a tree, so a filter
// that keeps a feature must keep the epic it hangs under or the row loses its
// grouping. Epics therefore survive on their own match OR as scaffolding for a
// surviving feature; an epic matching nothing and parenting nothing drops out
// entirely rather than showing as an empty group. A nil view is a pass-through.
func ApplyFilter[T Filterable](cards []T, view *FilterView) []T {
	if view == nil {
		return slices.Clone(cards)
	}
	keptIDs := map[string]bool{}
	parents := map[string]bool{}
	for _, c := range cards {
		if !Matches(c.FilterLabels(), *view) {
			continue
		}
		keptIDs[c.FilterID()] = true
		if c.FilterType() != "epic" && c.FilterEpic() != "" {
			parents[c.FilterEpic()] = true
		}
	}
	out := make([]T, 0, len(cards))
	for _, c := range cards {
		if keptIDs[c.FilterID()] || (c.FilterType() == "epic" && parents[c.FilterID()]) {
			out = append(out, c)
		}
	}
	return out
}

// ── the tab strip ────────────────────────────────────────────────────────────
// Saved views *are* the dashboard's tabs: ALL (the unfiltered board) sits first,
// each saved view follows in order, and a trailing NEW slot is where ⇥ lands to
// author one. A strip position is that flat index, so navigation is plain ring
// arithmetic and the shell can render the strip without knowing what a filter is.

const (
	AllTab = "ALL"
	NewTab = "+"
)

func StripLabels(views []FilterView) []string {
	out := make([]string, 0, len(views)+2)
	out = append(out, AllTab)
	for _, v := range views {
		name := strings.TrimSpace(v.Name)
		if name == "" {
			name = "(unnamed)"
		}
		out = append(out, name)
	}
	return append(out, NewTab)
}

// PosOf is the strip position of the active tab; the two are the same place.
func PosOf(active *int) int {
	if active == nil {
		return 0
	}
	return *active + 1
}

// TabTarget is where a strip position points: the ALL tab, a saved view's index,
// or the NEW slot that has no view behind it yet.
type TabTarget struct {
	New   bool
	Index *int // nil with New false means the ALL tab
}

func ViewAt(pos, count int) TabTarget {
	switch {
	case pos == 0:
		return TabTarget{}
	case pos > count:
		return TabTarget{New: true}
	default:
		i := pos - 1
		return TabTarget{Index: &i}
	}
}

// StepPos rings ⇥/⇧⇥ through ALL, every saved view, and the NEW slot.
func StepPos(pos, count int, back bool) int {
	size := count + 2
	step := 1
	if back {
		step = -1
	}
	return ((pos+step)%size + size) % size
}

// ── saving ───────────────────────────────────────────────────────────────────

// SaveView lands the edited view: a nil `slot` appends it (the NEW tab),
// otherwise it replaces that tab in place. Either way the result becomes the
// active tab, so the board the user just described is the board they land on.
func SaveView(views []FilterView, slot *int, view FilterView) FilterState {
	if slot == nil {
		active := len(views)
		return FilterState{Views: append(slices.Clone(views), view), Active: &active}
	}
	out := slices.Clone(views)
	out[*slot] = view
	active := *slot
	return FilterState{Views: out, Active: &active}
}

// DeleteView drops a saved view, which drops its tab. The board falls back to ALL
// rather than to a neighbouring filter: silently landing on a *different*
// filtered board after a delete would read as the delete having hidden cards.
func DeleteView(views []FilterView, slot int) FilterState {
	out := slices.Clone(views)
	return FilterState{Views: slices.Delete(out, slot, slot+1)}
}

// DescribeView is the one-line summary the filter strip shows: `#tests & !docs`,
// or the mode-less "all cards" when a named view carries no terms.
func DescribeView(view FilterView) string {
	labels := make([]string, 0, len(view.Terms))
	for label := range view.Terms {
		labels = append(labels, label)
	}
	slices.Sort(labels)
	if len(labels) == 0 {
		return "all cards"
	}
	terms := make([]string, 0, len(labels))
	for _, label := range labels {
		mark := "#"
		if view.Terms[label] == TermExclude {
			mark = "!"
		}
		terms = append(terms, mark+label)
	}
	joiner := " & "
	if view.Mode == ModeOr {
		joiner = " | "
	}
	return strings.Join(terms, joiner)
}

// ── store ────────────────────────────────────────────────────────────────────
// Saved views are a UI preference, not coordination data, so they live in the
// user's cache rather than the workspace — writing under .millstrand would surface as
// a dirty tree on every validation run. One file holds every workspace's views,
// keyed by workspace root, so dashboards over different worlds keep their own.

func FiltersFile() string {
	cache := os.Getenv("XDG_CACHE_HOME")
	if cache == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			home = "."
		}
		cache = filepath.Join(home, ".cache")
	}
	return filepath.Join(cache, "millstrand", "agent-dash", "filters.json")
}

// The store is parsed strictly (TEN-003): a value we did not expect is reported
// with where it sat and what was allowed, never coerced to a "sensible" default.
// Quietly rewriting mode "orr" to AND would change what a saved view means
// without telling anyone, which is exactly the silent-semantics failure the tenet
// forbids.

func parseView(raw json.RawMessage, where string) (FilterView, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil {
		return FilterView{}, fmt.Errorf("%s must be an object", where)
	}
	var extra []string
	for k := range fields {
		if k != "name" && k != "mode" && k != "terms" {
			extra = append(extra, k)
		}
	}
	if len(extra) > 0 {
		slices.Sort(extra)
		return FilterView{}, fmt.Errorf("%s has unexpected keys: %s", where, strings.Join(extra, ", "))
	}

	var name string
	if err := json.Unmarshal(fields["name"], &name); err != nil {
		return FilterView{}, fmt.Errorf("%s.name must be a string, got %s", where, jsonOrNull(fields["name"]))
	}
	var mode FilterMode
	if err := json.Unmarshal(fields["mode"], &mode); err != nil || (mode != ModeAnd && mode != ModeOr) {
		return FilterView{}, fmt.Errorf("%s.mode must be \"and\" or \"or\", got %s", where, jsonOrNull(fields["mode"]))
	}
	var rawTerms map[string]json.RawMessage
	if err := json.Unmarshal(fields["terms"], &rawTerms); err != nil {
		return FilterView{}, fmt.Errorf("%s.terms must be an object", where)
	}
	terms := make(map[string]Term, len(rawTerms))
	for label, rawTerm := range rawTerms {
		var term Term
		if err := json.Unmarshal(rawTerm, &term); err != nil || (term != TermInclude && term != TermExclude) {
			key, _ := json.Marshal(label)
			return FilterView{}, fmt.Errorf("%s.terms[%s] must be \"include\" or \"exclude\", got %s",
				where, key, jsonOrNull(rawTerm))
		}
		terms[label] = term
	}
	return FilterView{Name: name, Mode: mode, Terms: terms}, nil
}

// `enabled` is a store written before the views became tabs, where ⇧f parked the
// active filter without forgetting it. The ALL tab is that off switch now, so the
// key is accepted and dropped: reading it back as anything would resurrect a
// setting the UI can no longer show or change.
func parseState(raw json.RawMessage, where string) (FilterState, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil {
		return FilterState{}, fmt.Errorf("%s must be an object", where)
	}
	var extra []string
	for k := range fields {
		if k != "views" && k != "active" && k != "enabled" {
			extra = append(extra, k)
		}
	}
	if len(extra) > 0 {
		slices.Sort(extra)
		return FilterState{}, fmt.Errorf("%s has unexpected keys: %s", where, strings.Join(extra, ", "))
	}

	var rawViews []json.RawMessage
	if err := json.Unmarshal(fields["views"], &rawViews); err != nil {
		return FilterState{}, fmt.Errorf("%s.views must be an array", where)
	}
	views := make([]FilterView, 0, len(rawViews))
	for i, rv := range rawViews {
		view, err := parseView(rv, fmt.Sprintf("%s.views[%d]", where, i))
		if err != nil {
			return FilterState{}, err
		}
		views = append(views, view)
	}

	badActive := fmt.Errorf("%s.active must be null or an index into views (0..%d), got %s",
		where, len(views)-1, jsonOrNull(fields["active"]))
	rawActive, present := fields["active"]
	if !present {
		return FilterState{}, badActive
	}
	if string(rawActive) == "null" {
		return FilterState{Views: views}, nil
	}
	var active int
	if err := json.Unmarshal(rawActive, &active); err != nil || active < 0 || active >= len(views) {
		return FilterState{}, badActive
	}
	return FilterState{Views: views, Active: &active}, nil
}

func jsonOrNull(raw json.RawMessage) string {
	if len(raw) == 0 {
		return "undefined"
	}
	return string(raw)
}

// LoadFilterState reads the saved strip for one workspace. A store we cannot read
// or cannot trust yields no views plus a message the tab shows in a banner. The
// board still renders — losing a filter bookmark must not blank it — but the
// filters stay unloaded and the reason is stated, rather than a guessed-at version
// of the user's saved views being put quietly into force.
func LoadFilterState(file, root string) (FilterState, string) {
	text, err := os.ReadFile(file)
	if err != nil {
		// A store that has never been written is the normal first-run case.
		if errors.Is(err, fs.ErrNotExist) {
			return EmptyFilterState(), ""
		}
		return EmptyFilterState(), fmt.Sprintf("%s: %v", file, err)
	}
	var all map[string]json.RawMessage
	if err := json.Unmarshal(text, &all); err != nil {
		return EmptyFilterState(), fmt.Sprintf("%s: %v", file, err)
	}
	// A workspace with no entry is not an error — that is every workspace this
	// dashboard has not saved a view in yet.
	entry, ok := all[root]
	if !ok {
		return EmptyFilterState(), ""
	}
	key, _ := json.Marshal(root)
	state, err := parseState(entry, string(key))
	if err != nil {
		return EmptyFilterState(), fmt.Sprintf("%s: %v", file, err)
	}
	return state, ""
}

// SaveFilterState is a read-modify-write so a dashboard over one workspace never
// drops another's views — which means a store we failed to *read* must never be
// overwritten: the rewrite would carry only this workspace and silently destroy
// every other one's saved views. Only a genuinely absent file is safe to create
// from nothing. Returns an error message rather than an error value; a failed
// preference write is worth a banner, not a dead board.
func SaveFilterState(file, root string, state FilterState) string {
	all := map[string]json.RawMessage{}
	text, err := os.ReadFile(file)
	switch {
	case err == nil:
		if err := json.Unmarshal(text, &all); err != nil {
			return fmt.Sprintf("refusing to overwrite %s: %v", file, err)
		}
	case !errors.Is(err, fs.ErrNotExist):
		return fmt.Sprintf("refusing to overwrite %s: %v", file, err)
	}

	encoded, err := json.Marshal(storedState(state))
	if err != nil {
		return fmt.Sprintf("saving filters failed: %v", err)
	}
	all[root] = encoded
	out, err := json.MarshalIndent(all, "", "  ")
	if err != nil {
		return fmt.Sprintf("saving filters failed: %v", err)
	}
	if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
		return fmt.Sprintf("saving filters failed: %v", err)
	}
	if err := os.WriteFile(file, append(out, '\n'), 0o644); err != nil {
		return fmt.Sprintf("saving filters failed: %v", err)
	}
	return ""
}

// The wire shape of a saved strip. Terms is never omitted and never null: the
// strict reader demands an object, so a view with no terms must still round-trip
// as `{}`.
type storedView struct {
	Name  string          `json:"name"`
	Mode  FilterMode      `json:"mode"`
	Terms map[string]Term `json:"terms"`
}

type storedFilterState struct {
	Views  []storedView `json:"views"`
	Active *int         `json:"active"`
}

func storedState(state FilterState) storedFilterState {
	views := make([]storedView, 0, len(state.Views))
	for _, v := range state.Views {
		terms := v.Terms
		if terms == nil {
			terms = map[string]Term{}
		}
		views = append(views, storedView{Name: v.Name, Mode: v.Mode, Terms: terms})
	}
	return storedFilterState{Views: views, Active: state.Active}
}
