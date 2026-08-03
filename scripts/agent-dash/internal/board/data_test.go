package board

import (
	"errors"
	"testing"

	"github.com/google/go-cmp/cmp"
)

func testCard(id, updated string) BoardCard {
	return BoardCard{ID: id, State: "active", CreatedAt: "2026-01-01 00:00:00", UpdatedAt: updated}
}

func TestActivePollingFlattensTheSnapshotWithoutDetailReads(t *testing.T) {
	snapshot := BoardSnapshot{
		Epics:       []BoardCard{testCard("epic", "1")},
		Claimed:     []BoardCard{testCard("claimed", "2")},
		InReview:    []BoardCard{testCard("review", "3")},
		Pending:     []BoardCard{testCard("pending", "4")},
		Refinement:  []BoardCard{testCard("refinement", "5")},
		UnknownLane: []BoardCard{testCard("unknown", "6")},
	}

	var got []string
	for _, c := range ActiveBoardCards(snapshot) {
		got = append(got, c.ID)
	}
	want := []string{"epic", "claimed", "review", "pending", "refinement", "unknown"}
	if diff := cmp.Diff(want, got); diff != "" {
		t.Errorf("lane order (-want +got):\n%s", diff)
	}
}

func TestActiveModeHidesClosedTasksKeepingAuthoritativeStatuses(t *testing.T) {
	got := ActiveTasks([]TaskChild{
		{ID: "ready", Title: "Ready", State: "active", Status: "ready"},
		{ID: "done", Title: "Done", State: "closed", Status: "closed"},
	})
	want := []TaskChild{{ID: "ready", Title: "Ready", State: "active", Status: "ready"}}
	if diff := cmp.Diff(want, got); diff != "" {
		t.Errorf("active tasks (-want +got):\n%s", diff)
	}
}

func TestOneFailedDetailReadDoesNotDiscardSuccessfulCardData(t *testing.T) {
	got := LoadCardDetails(
		[]string{"good", "bad"},
		map[string][]TaskChild{},
		map[string]BoardCard{},
		func(id string) (CardView, error) {
			if id == "bad" {
				return CardView{}, errors.New("tracker projection failed")
			}
			card := testCard(id, "2")
			card.Attributes = map[string]any{"body": "Full detail"}
			return CardView{
				Card:  card,
				Tasks: []TaskChild{{ID: "task", Title: "Task", State: "active", Status: "ready"}},
			}, nil
		},
	)

	if diff := cmp.Diff(map[string]any{"body": "Full detail"}, got.CardCache["good"].Attributes); diff != "" {
		t.Errorf("surviving card attributes (-want +got):\n%s", diff)
	}
	if status := got.TaskCache["good"][0].Status; status != "ready" {
		t.Errorf("surviving task status = %q, want %q", status, "ready")
	}
	if msg := got.TaskFailures["bad"]; msg != "tracker projection failed" {
		t.Errorf("failure message = %q, want %q", msg, "tracker projection failed")
	}
}

// All-mode cards keep the fields the tree groups and colours by, so a closed
// feature still reports the epic it hangs under and the outcome it closed with.
func TestAllModeCardsRetainDirectEpicMembershipAndClosedOutcomes(t *testing.T) {
	card := testCard("feature", "2")
	card.State = "closed"
	card.Type = "feature"
	card.Epic = "epic"
	card.Outcome = "done"

	row := rowFromCard(card, nil, false, card.Epic, nil)
	if row.Epic != "epic" {
		t.Errorf("epic = %q, want %q", row.Epic, "epic")
	}
	if row.Lane != "done" {
		t.Errorf("closed card lane = %q, want the outcome %q", row.Lane, "done")
	}
	if row.State != "closed" {
		t.Errorf("state = %q, want %q", row.State, "closed")
	}
}
