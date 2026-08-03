package board

import (
	"testing"

	tea "github.com/charmbracelet/bubbletea"

	"github.com/codethread/kanban.spool/scripts/agent-dash/internal/app"
	"github.com/codethread/kanban.spool/scripts/agent-dash/internal/data"
)

func searchDetail(id, title string) data.DetailRow { return data.DetailRow{ID: id, Title: title} }

var searchCtx = app.KeyCtx{Cols: 120, TermRows: 40}

func TestSearchRowsMatchesExactCaseSensitiveSubstringsInIDsAndTitles(t *testing.T) {
	alpha := KanbanRow{DetailRow: searchDetail("e7s42", "Wire dashboard search")}
	beta := KanbanRow{DetailRow: searchDetail("other", "Use E7S search")}
	rows := []FlatRow{{Key: alpha.ID, Card: &alpha}, {Key: beta.ID, Card: &beta}}

	if got := searchRows(rows, "e7s"); len(got) != 1 || got[0].Key != "e7s42" {
		t.Errorf("ID search = %v, want only e7s42", got)
	}
	if got := searchRows(rows, "dashboard"); len(got) != 1 || got[0].Key != "e7s42" {
		t.Errorf("title search = %v, want only e7s42", got)
	}
	if got := searchRows(rows, "E7S"); len(got) != 1 || got[0].Key != "other" {
		t.Errorf("search must retain exact case; got %v", got)
	}
}

func TestSearchIsLivePerTabAndEscapeClearsIt(t *testing.T) {
	t.Setenv("XDG_CACHE_HOME", t.TempDir())
	k := New()
	k.rows = []KanbanRow{
		{DetailRow: searchDetail("other", "Unrelated card")},
		{DetailRow: searchDetail("e7s42", "Wire dashboard search")},
		{DetailRow: searchDetail("e7s99", "Search navigation")},
	}
	active := 0
	k.filter = FilterState{Views: []FilterView{EmptyView()}, Active: &active}
	k.ensureSearches()

	press := func(key string) {
		k.OnKey(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune(key)}, searchCtx)
	}
	press("/")
	press("e")
	press("7")
	press("s")
	if got := k.visibleRows(); len(got) != 2 || got[0].Key != "e7s42" || got[1].Key != "e7s99" {
		t.Fatalf("live search rows = %v, want e7s42 and e7s99", got)
	}
	k.OnKey(tea.KeyMsg{Type: tea.KeyEnter}, searchCtx)
	if k.searchInput {
		t.Error("enter must keep the search and leave input mode")
	}
	k.OnKey(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("j")}, searchCtx)
	if got := k.visibleRows()[k.s.Selected].Key; got != "e7s99" {
		t.Errorf("j after enter selected %q, want e7s99", got)
	}
	k.s.Mode = app.ModeDetail
	if got := k.FetchKey(); got != "|e7s99" {
		t.Errorf("detail fetch key = %q, want |e7s99", got)
	}
	k.s.Mode = app.ModeList

	k.listKey("shift+tab", searchCtx) // ALL, whose independent search is blank.
	if got := k.searchQuery(); got != "" {
		t.Fatalf("ALL query = %q, want empty", got)
	}
	k.listKey("tab", searchCtx) // return to the saved view.
	if got := k.searchQuery(); got != "e7s" {
		t.Fatalf("saved-tab query = %q, want e7s", got)
	}
	k.listKey("esc", searchCtx)
	if got := k.searchQuery(); got != "" {
		t.Errorf("escape query = %q, want empty", got)
	}
}
