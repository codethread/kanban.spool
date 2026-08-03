package board

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/google/go-cmp/cmp"
)

func view(name string, mode FilterMode, terms map[string]Term) FilterView {
	if mode == "" {
		mode = ModeAnd
	}
	if terms == nil {
		terms = map[string]Term{}
	}
	return FilterView{Name: name, Mode: mode, Terms: terms}
}

// testRow is the minimum ApplyFilter needs of a card.
type testRow struct {
	id, typ, epic string
	labels        []string
}

func (r testRow) FilterID() string       { return r.id }
func (r testRow) FilterType() string     { return r.typ }
func (r testRow) FilterEpic() string     { return r.epic }
func (r testRow) FilterLabels() []string { return r.labels }

func card(id string, labels []string, typ, epic string) testRow {
	if typ == "" {
		typ = "feature"
	}
	return testRow{id: id, typ: typ, epic: epic, labels: labels}
}

func ids(rows []testRow) []string {
	out := make([]string, 0, len(rows))
	for _, r := range rows {
		out = append(out, r.id)
	}
	return out
}

// tempStore is a filters.json under a nested directory that does not exist yet,
// so every save also exercises the mkdir path.
func tempStore(t *testing.T) string {
	t.Helper()
	return filepath.Join(t.TempDir(), "nested", "filters.json")
}

func TestAndModeRequiresEveryIncludedLabelOrModeAnyOfThem(t *testing.T) {
	terms := map[string]Term{"tests": TermInclude, "docs": TermInclude}
	cases := []struct {
		labels []string
		mode   FilterMode
		want   bool
	}{
		{[]string{"tests"}, ModeAnd, false},
		{[]string{"tests", "docs"}, ModeAnd, true},
		{[]string{"tests"}, ModeOr, true},
		{[]string{"other"}, ModeOr, false},
	}
	for _, tc := range cases {
		if got := Matches(tc.labels, view("", tc.mode, terms)); got != tc.want {
			t.Errorf("Matches(%v, %s) = %v, want %v", tc.labels, tc.mode, got, tc.want)
		}
	}
}

func TestAnExcludedLabelVetoesInBothModesEvenAgainstASatisfiedInclude(t *testing.T) {
	terms := map[string]Term{"tests": TermInclude, "wip": TermExclude}
	cases := []struct {
		labels []string
		mode   FilterMode
		want   bool
	}{
		{[]string{"tests"}, ModeAnd, true},
		{[]string{"tests", "wip"}, ModeAnd, false},
		{[]string{"tests", "wip"}, ModeOr, false},
	}
	for _, tc := range cases {
		if got := Matches(tc.labels, view("", tc.mode, terms)); got != tc.want {
			t.Errorf("Matches(%v, %s) = %v, want %v", tc.labels, tc.mode, got, tc.want)
		}
	}
}

func TestExcludesAloneAreAPureSubtraction(t *testing.T) {
	v := view("", ModeAnd, map[string]Term{"tests": TermExclude})
	for _, tc := range []struct {
		labels []string
		want   bool
	}{
		{nil, true},
		{[]string{"docs"}, true},
		{[]string{"tests"}, false},
	} {
		if got := Matches(tc.labels, v); got != tc.want {
			t.Errorf("Matches(%v) = %v, want %v", tc.labels, got, tc.want)
		}
	}
}

func TestAViewWithNoTermsMatchesEveryCard(t *testing.T) {
	if !Matches(nil, view("everything", ModeAnd, nil)) {
		t.Error("a termless named view must match everything")
	}
}

func TestAnEpicSurvivesAsScaffoldingForAMatchingFeatureButNotAlone(t *testing.T) {
	cards := []testRow{
		card("keeper", nil, "epic", ""),
		card("empty", nil, "epic", ""),
		card("feat", []string{"tests"}, "", "keeper"),
		card("other", []string{"docs"}, "", "empty"),
	}
	v := view("", ModeAnd, map[string]Term{"tests": TermInclude})
	if diff := cmp.Diff([]string{"keeper", "feat"}, ids(ApplyFilter(cards, &v))); diff != "" {
		t.Errorf("kept cards (-want +got):\n%s", diff)
	}
}

func TestAnEpicCarryingTheLabelSurvivesOnItsOwnMatch(t *testing.T) {
	cards := []testRow{
		card("epic", []string{"tests"}, "epic", ""),
		card("feat", nil, "", "epic"),
	}
	v := view("", ModeAnd, map[string]Term{"tests": TermInclude})
	if diff := cmp.Diff([]string{"epic"}, ids(ApplyFilter(cards, &v))); diff != "" {
		t.Errorf("kept cards (-want +got):\n%s", diff)
	}
}

func TestANilViewIsAPassThrough(t *testing.T) {
	cards := []testRow{card("a", nil, "", ""), card("b", []string{"tests"}, "", "")}
	if diff := cmp.Diff([]string{"a", "b"}, ids(ApplyFilter(cards, nil))); diff != "" {
		t.Errorf("kept cards (-want +got):\n%s", diff)
	}
}

func TestSpaceCyclesALabelInAndOutAndBangSwingsIt(t *testing.T) {
	cases := []struct {
		fn    func(Term) Term
		in    Term
		want  Term
		label string
	}{
		{ToggleTerm, "", TermInclude, "toggle absent"},
		{ToggleTerm, TermInclude, "", "toggle include"},
		{ToggleTerm, TermExclude, "", "toggle exclude"},
		{NegateTerm, "", TermExclude, "negate absent"},
		{NegateTerm, TermInclude, TermExclude, "negate include"},
		{NegateTerm, TermExclude, TermInclude, "negate exclude"},
	}
	for _, tc := range cases {
		if got := tc.fn(tc.in); got != tc.want {
			t.Errorf("%s = %q, want %q", tc.label, got, tc.want)
		}
	}
}

func TestClearingATermDropsTheKeyRatherThanStoringAnOffState(t *testing.T) {
	on := WithTerm(EmptyView(), "tests", TermInclude)
	if diff := cmp.Diff(map[string]Term{"tests": TermInclude}, on.Terms); diff != "" {
		t.Errorf("terms after include (-want +got):\n%s", diff)
	}
	if diff := cmp.Diff(map[string]Term{}, WithTerm(on, "tests", "").Terms); diff != "" {
		t.Errorf("terms after clear (-want +got):\n%s", diff)
	}
}

func TestAllLeadsTheSavedViewsFollowAndTheNewSlotTrails(t *testing.T) {
	got := StripLabels([]FilterView{view("tests", ModeAnd, nil), view("  ", ModeAnd, nil)})
	if diff := cmp.Diff([]string{"ALL", "tests", "(unnamed)", "+"}, got); diff != "" {
		t.Errorf("strip (-want +got):\n%s", diff)
	}
	if diff := cmp.Diff([]string{"ALL", "+"}, StripLabels(nil)); diff != "" {
		t.Errorf("empty strip (-want +got):\n%s", diff)
	}
}

func TestTheActiveFilterAndItsStripPositionAreTheSamePlace(t *testing.T) {
	one := 1
	if got := PosOf(nil); got != 0 {
		t.Errorf("PosOf(nil) = %d, want 0", got)
	}
	if got := PosOf(&one); got != 2 {
		t.Errorf("PosOf(1) = %d, want 2", got)
	}
	if got := ViewAt(0, 2); got.New || got.Index != nil {
		t.Errorf("ViewAt(0, 2) = %+v, want the ALL tab", got)
	}
	if got := ViewAt(2, 2); got.New || got.Index == nil || *got.Index != 1 {
		t.Errorf("ViewAt(2, 2) = %+v, want view 1", got)
	}
	if got := ViewAt(3, 2); !got.New {
		t.Errorf("ViewAt(3, 2) = %+v, want the NEW slot", got)
	}
}

func TestTabRingsThroughAllEveryViewAndTheNewSlot(t *testing.T) {
	walk := func(back bool) []int {
		seen := make([]int, 0, 4)
		pos := 0
		for range 4 {
			pos = StepPos(pos, 2, back)
			seen = append(seen, pos)
		}
		return seen
	}
	if diff := cmp.Diff([]int{1, 2, 3, 0}, walk(false)); diff != "" {
		t.Errorf("forward walk (-want +got):\n%s", diff)
	}
	if diff := cmp.Diff([]int{3, 2, 1, 0}, walk(true)); diff != "" {
		t.Errorf("backward walk (-want +got):\n%s", diff)
	}
}

func TestANewViewIsAppendedAndBecomesTheActiveTab(t *testing.T) {
	views := []FilterView{view("a", ModeAnd, nil)}
	added := view("b", ModeAnd, nil)
	got := SaveView(views, nil, added)
	if diff := cmp.Diff([]FilterView{views[0], added}, got.Views); diff != "" {
		t.Errorf("views (-want +got):\n%s", diff)
	}
	if got.Active == nil || *got.Active != 1 {
		t.Errorf("active = %v, want 1", got.Active)
	}
}

func TestEditingATabReplacesItInPlaceAndStaysOnIt(t *testing.T) {
	views := []FilterView{view("a", ModeAnd, nil), view("b", ModeAnd, nil)}
	edited := view("a", ModeAnd, map[string]Term{"tests": TermInclude})
	slot := 0
	got := SaveView(views, &slot, edited)
	if diff := cmp.Diff([]FilterView{edited, view("b", ModeAnd, nil)}, got.Views); diff != "" {
		t.Errorf("views (-want +got):\n%s", diff)
	}
	if got.Active == nil || *got.Active != 0 {
		t.Errorf("active = %v, want 0", got.Active)
	}
}

func TestANamedTermlessViewIsKeepableAnUnnamedOneIsNot(t *testing.T) {
	if view("everything", ModeAnd, nil).IsBlank() {
		t.Error("a named termless view is a keepable bookmark")
	}
	if view("", ModeAnd, map[string]Term{"tests": TermInclude}).IsBlank() {
		t.Error("an unnamed view with terms is not blank")
	}
	if !EmptyView().IsBlank() {
		t.Error("an unnamed termless view is blank")
	}
}

func TestDeletingATabFallsBackToAllNotANeighbouringFilter(t *testing.T) {
	views := []FilterView{view("a", ModeAnd, nil), view("b", ModeAnd, nil)}
	for slot, want := range map[int]string{0: "b", 1: "a"} {
		got := DeleteView(views, slot)
		if len(got.Views) != 1 || got.Views[0].Name != want {
			t.Errorf("DeleteView(slot %d) kept %v, want only %q", slot, got.Views, want)
		}
		if got.Active != nil {
			t.Errorf("DeleteView(slot %d) active = %v, want the ALL tab", slot, got.Active)
		}
	}
}

func TestTermsRenderWithTheirModesJoinerAndABangForExclusions(t *testing.T) {
	cases := []struct {
		view FilterView
		want string
	}{
		{view("", ModeAnd, map[string]Term{"tests": TermInclude, "wip": TermExclude}), "#tests & !wip"},
		{view("", ModeOr, map[string]Term{"tests": TermInclude, "docs": TermInclude}), "#docs | #tests"},
		{view("all", ModeAnd, nil), "all cards"},
	}
	for _, tc := range cases {
		if got := DescribeView(tc.view); got != tc.want {
			t.Errorf("DescribeView = %q, want %q", got, tc.want)
		}
	}
}

func TestARoundTripPreservesTheViewsAndWhichTabWasActive(t *testing.T) {
	file := tempStore(t)
	active := 0
	state := FilterState{
		Views:  []FilterView{view("test only", ModeAnd, map[string]Term{"tests": TermInclude})},
		Active: &active,
	}

	if err := SaveFilterState(file, "/repo", state); err != "" {
		t.Fatalf("save: %s", err)
	}
	got, err := LoadFilterState(file, "/repo")
	if err != "" {
		t.Fatalf("load: %s", err)
	}
	if diff := cmp.Diff(state, got); diff != "" {
		t.Errorf("round trip (-want +got):\n%s", diff)
	}
}

func TestWorkspacesKeepSeparateViewsInOneStore(t *testing.T) {
	file := tempStore(t)
	zero := 0
	SaveFilterState(file, "/one", FilterState{Views: []FilterView{view("one", ModeAnd, nil)}, Active: &zero})
	SaveFilterState(file, "/two", FilterState{Views: []FilterView{view("two", ModeAnd, nil)}, Active: &zero})

	for root, want := range map[string]string{"/one": "one", "/two": "two"} {
		state, _ := LoadFilterState(file, root)
		if len(state.Views) != 1 || state.Views[0].Name != want {
			t.Errorf("%s loaded %v, want the view %q", root, state.Views, want)
		}
	}
}

func TestANeverWrittenStoreIsEmptyWithoutAnError(t *testing.T) {
	state, err := LoadFilterState(tempStore(t), "/repo")
	if err != "" {
		t.Errorf("error = %q, want none", err)
	}
	if diff := cmp.Diff(EmptyFilterState(), state); diff != "" {
		t.Errorf("state (-want +got):\n%s", diff)
	}
}

func TestACorruptStoreDegradesToNoViewsButNamesTheFile(t *testing.T) {
	file := tempStore(t)
	SaveFilterState(file, "/repo", EmptyFilterState())
	if err := os.WriteFile(file, []byte("{not json"), 0o644); err != nil {
		t.Fatal(err)
	}

	state, err := LoadFilterState(file, "/repo")
	if len(state.Views) != 0 {
		t.Errorf("views = %v, want none", state.Views)
	}
	if !strings.Contains(err, file) {
		t.Errorf("error %q does not name %q", err, file)
	}
}

func TestAWorkspaceWithNoSavedEntryIsNotAnError(t *testing.T) {
	file := tempStore(t)
	zero := 0
	SaveFilterState(file, "/one", FilterState{Views: []FilterView{view("one", ModeAnd, nil)}, Active: &zero})

	state, err := LoadFilterState(file, "/other")
	if err != "" {
		t.Errorf("error = %q, want none", err)
	}
	if diff := cmp.Diff(EmptyFilterState(), state); diff != "" {
		t.Errorf("state (-want +got):\n%s", diff)
	}
}

// The tabs replaced the ⇧f park switch, so a store written before them still
// loads — its views and active tab are honoured and the dead flag is dropped.
func TestALegacyEnabledFlagIsAcceptedAndDropped(t *testing.T) {
	file := tempStore(t)
	writeStore(t, file, `{"/repo": {"views": [{"name": "x", "mode": "and", "terms": {}}], "active": 0, "enabled": false}}`)

	state, err := LoadFilterState(file, "/repo")
	if err != "" {
		t.Fatalf("load: %s", err)
	}
	zero := 0
	want := FilterState{Views: []FilterView{view("x", ModeAnd, nil)}, Active: &zero}
	if diff := cmp.Diff(want, state); diff != "" {
		t.Errorf("state (-want +got):\n%s", diff)
	}
}

// TEN-003: a value we did not expect is reported, never coerced into a "sensible"
// default that would silently change what a saved view means.
func TestUnexpectedStoreValuesAreRejectedRatherThanCoerced(t *testing.T) {
	cases := []struct {
		name  string
		entry string
		want  string
	}{
		{"unknown mode", `{"active": null, "views": [{"name": "x", "mode": "nonsense", "terms": {}}]}`, `mode must be "and" or "or"`},
		{"unknown term", `{"active": null, "views": [{"name": "x", "mode": "and", "terms": {"a": "maybe"}}]}`, `"include" or "exclude"`},
		{"out-of-range active", `{"active": 7, "views": []}`, "must be null or an index into views"},
		{"missing active", `{"views": []}`, "must be null or an index into views"},
		{"non-string name", `{"active": null, "views": [{"name": 7, "mode": "and", "terms": {}}]}`, "name must be a string"},
		{"unexpected key", `{"active": null, "views": [{"name": "x", "mode": "and", "terms": {}, "colour": "red"}]}`, "unexpected keys: colour"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			file := tempStore(t)
			writeStore(t, file, `{"/repo": `+tc.entry+`}`)

			state, err := LoadFilterState(file, "/repo")
			if diff := cmp.Diff(EmptyFilterState(), state); diff != "" {
				t.Errorf("state (-want +got):\n%s", diff)
			}
			if !strings.Contains(err, tc.want) {
				t.Errorf("error %q does not contain %q", err, tc.want)
			}
		})
	}
}

func TestAStoreThatFailedToReadIsNeverOverwritten(t *testing.T) {
	file := tempStore(t)
	zero := 0
	SaveFilterState(file, "/one", FilterState{Views: []FilterView{view("one", ModeAnd, nil)}, Active: &zero})
	intact, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(intact), "one") {
		t.Fatalf("the store under test never held /one's view: %s", intact)
	}
	if err := os.WriteFile(file, []byte("{not json"), 0o644); err != nil {
		t.Fatal(err)
	}

	msg := SaveFilterState(file, "/two", FilterState{Views: []FilterView{view("two", ModeAnd, nil)}, Active: &zero})
	if !strings.Contains(msg, "refusing to overwrite") {
		t.Errorf("error = %q, want a refusal", msg)
	}
	// The unreadable bytes are left exactly as found rather than replaced by a store
	// carrying only /two — that rewrite would destroy /one's saved views.
	after, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != "{not json" {
		t.Errorf("store was rewritten to %q", after)
	}
}

func writeStore(t *testing.T, file, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(file, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}
