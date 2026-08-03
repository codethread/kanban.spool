package app

import (
	"testing"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/google/go-cmp/cmp"
)

func keys(msgs []tea.KeyMsg) []string {
	out := make([]string, 0, len(msgs))
	for _, m := range msgs {
		out = append(out, m.String())
	}
	return out
}

// Bubble Tea batches every printable rune it finds in one read of stdin into a
// single KeyRunes. A held-down j or a fast "gg" therefore arrives as one event
// whose String() matches no binding, so without the split the repeat is silently
// dropped.
func TestABatchedRunKeyIsSplitBackIntoItsKeystrokes(t *testing.T) {
	got := keys(splitRunes(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("jjk")}))
	if diff := cmp.Diff([]string{"j", "j", "k"}, got); diff != "" {
		t.Errorf("split (-want +got):\n%s", diff)
	}
}

func TestSingleKeystrokesPassThroughUntouched(t *testing.T) {
	for _, msg := range []tea.KeyMsg{
		{Type: tea.KeyRunes, Runes: []rune("j")},
		{Type: tea.KeyUp},
		{Type: tea.KeyCtrlD},
		{Type: tea.KeyEnter},
	} {
		got := splitRunes(msg)
		if len(got) != 1 || got[0].String() != msg.String() {
			t.Errorf("splitRunes(%q) = %v, want the message unchanged", msg.String(), keys(got))
		}
	}
}

type row struct{ id string }

func rowID(r row) string { return r.id }

func rows(ids ...string) []row {
	out := make([]row, 0, len(ids))
	for _, id := range ids {
		out = append(out, row{id})
	}
	return out
}

func TestMovementClampsAtBothEndsOfTheList(t *testing.T) {
	list := rows("a", "b", "c")
	cases := []struct {
		from int
		key  string
		want int
	}{
		{0, "j", 1},
		{0, "k", 0},
		{2, "j", 2},
		{1, "g", 0},
		{0, "G", 2},
		{0, "ctrl+d", 2},
		{2, "ctrl+u", 0},
	}
	for _, tc := range cases {
		got, ok := ReduceListKeys(ListState{Selected: tc.from}, tc.key, list, rowID, 5)
		if !ok {
			t.Errorf("%q from %d was not handled", tc.key, tc.from)
			continue
		}
		if got.Selected != tc.want {
			t.Errorf("%q from %d = %d, want %d", tc.key, tc.from, got.Selected, tc.want)
		}
		if got.Anchor != list[tc.want].id {
			t.Errorf("%q from %d anchored %q, want %q", tc.key, tc.from, got.Anchor, list[tc.want].id)
		}
	}
}

func TestANonMovementKeyIsLeftForTheCaller(t *testing.T) {
	if _, ok := ReduceListKeys(ListState{}, "f", rows("a"), rowID, 1); ok {
		t.Error(`"f" must fall through to the view module`)
	}
	if _, ok := ReduceListKeys(ListState{}, "j", nil, rowID, 1); ok {
		t.Error("an empty list has nothing to move through")
	}
}

// A poll that reorders or drops rows must not move the cursor off the card the
// user was looking at.
func TestSelectionFollowsItsAnchorAcrossARefresh(t *testing.T) {
	before := ListState{Selected: 2, Anchor: "c"}

	reordered := FollowSelection(before, rows("c", "a", "b"), rowID)
	if reordered.Selected != 0 || reordered.Anchor != "c" {
		t.Errorf("reordered = %+v, want the cursor to follow c to index 0", reordered)
	}

	// The anchored row is gone, so the old index is held, clamped into what is left.
	dropped := FollowSelection(before, rows("a", "b"), rowID)
	if dropped.Selected != 1 || dropped.Anchor != "b" {
		t.Errorf("dropped = %+v, want the index held at the clamped 1", dropped)
	}

	emptied := FollowSelection(before, nil, rowID)
	if emptied.Selected != 0 || emptied.Anchor != "" {
		t.Errorf("emptied = %+v, want a cleared selection", emptied)
	}
}

func TestTheDetailModeSurvivesARefresh(t *testing.T) {
	got := FollowSelection(ListState{Selected: 0, Anchor: "a", Mode: ModeDetail}, rows("a"), rowID)
	if got.Mode != ModeDetail {
		t.Error("a poll landing under an open detail must not close it")
	}
}
