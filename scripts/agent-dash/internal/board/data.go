// Pure boundary-shape transforms for the kanban dashboard. Kept separate from the
// render tab so tests exercise board/task/graph behavior without starting the
// dashboard shell or touching the live coordination workspace. BoardCard and
// BoardSnapshot mirror `kanban board` from kanban v14
// (603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3); needs-review is deliberately
// omitted because it is an aggregate, not a card collection.
package board

import (
	"sync"
)

type TaskChild struct {
	ID     string `json:"id"`
	Title  string `json:"title"`
	State  string `json:"state"`
	Status string `json:"status"`
	Owner  string `json:"owner,omitempty"`
}

type BoardCard struct {
	ID         string         `json:"id"`
	Title      string         `json:"title,omitempty"`
	State      string         `json:"state"`
	Attributes map[string]any `json:"attributes,omitempty"`
	CreatedAt  string         `json:"created_at"`
	UpdatedAt  string         `json:"updated_at,omitempty"`
	Type       string         `json:"type,omitempty"`
	Epic       string         `json:"epic,omitempty"`
	Lane       string         `json:"lane,omitempty"`
	Owner      string         `json:"owner,omitempty"`
	Priority   string         `json:"priority,omitempty"`
	Branch     string         `json:"branch,omitempty"`
	Outcome    string         `json:"outcome,omitempty"`
	// Sorted label slugs, present only on cards that carry any (the spool omits the
	// key rather than emitting an empty vector). Shipped on every card the board
	// returns, active and closed alike, so label filtering needs no second read.
	//
	// The board snapshot is the shape authority for labels, and every filter
	// decision keys off them, so a payload whose `labels` is not a string vector
	// fails the decode rather than being coerced (TEN-003) — a filter silently
	// matching nothing would look exactly like an empty backlog.
	Labels []string `json:"labels,omitempty"`
}

type BoardSnapshot struct {
	Epics       []BoardCard `json:"epics"`
	Refinement  []BoardCard `json:"refinement"`
	Pending     []BoardCard `json:"pending"`
	Claimed     []BoardCard `json:"claimed"`
	InReview    []BoardCard `json:"in_review"`
	UnknownLane []BoardCard `json:"unknown-lane,omitempty"`
	Cards       []BoardCard `json:"cards,omitempty"`
}

type CardView struct {
	Card  BoardCard   `json:"card"`
	Tasks []TaskChild `json:"tasks"`
}

// ActiveBoardCards flattens the lane collections in the order the board reads
// them.
func ActiveBoardCards(b BoardSnapshot) []BoardCard {
	out := make([]BoardCard, 0, len(b.Epics)+len(b.Claimed)+len(b.InReview)+len(b.Pending)+len(b.Refinement)+len(b.UnknownLane))
	out = append(out, b.Epics...)
	out = append(out, b.Claimed...)
	out = append(out, b.InReview...)
	out = append(out, b.Pending...)
	out = append(out, b.Refinement...)
	return append(out, b.UnknownLane...)
}

// ActiveTasks hides closed tasks while keeping the authoritative statuses of the
// rest.
func ActiveTasks(tasks []TaskChild) []TaskChild {
	out := make([]TaskChild, 0, len(tasks))
	for _, t := range tasks {
		if t.State != "closed" {
			out = append(out, t)
		}
	}
	return out
}

// CardDetails is one round of lazy detail reads: the caches carried forward and
// the per-card failures the banner reports.
type CardDetails struct {
	TaskCache    map[string][]TaskChild
	CardCache    map[string]BoardCard
	TaskFailures map[string]string
}

// LoadCardDetails fetches the authoritative view of every expanded card
// concurrently, folding the results into copies of the caches. One failed read
// does not discard the others' data: it lands in TaskFailures for the banner and
// the rest of the board carries on.
func LoadCardDetails(
	ids []string,
	cachedTasks map[string][]TaskChild,
	cachedCards map[string]BoardCard,
	load func(id string) (CardView, error),
) CardDetails {
	out := CardDetails{
		TaskCache:    maps(cachedTasks),
		CardCache:    maps(cachedCards),
		TaskFailures: map[string]string{},
	}

	type result struct {
		view CardView
		err  error
	}
	results := make([]result, len(ids))
	var wg sync.WaitGroup
	for i, id := range ids {
		wg.Add(1)
		go func() {
			defer wg.Done()
			view, err := load(id)
			results[i] = result{view: view, err: err}
		}()
	}
	wg.Wait()

	for i, id := range ids {
		if results[i].err != nil {
			out.TaskFailures[id] = results[i].err.Error()
			continue
		}
		tasks := results[i].view.Tasks
		if tasks == nil {
			tasks = []TaskChild{}
		}
		out.TaskCache[id] = tasks
		out.CardCache[id] = results[i].view.Card
	}
	return out
}

func maps[V any](in map[string]V) map[string]V {
	out := make(map[string]V, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}
