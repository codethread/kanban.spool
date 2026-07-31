// The board: the user↔agent work as a collapsible epic → feature → task tree
// (kanban.md). Normal polling reads the spool-owned `kanban board`
// snapshot. Expanded features fetch their authoritative task views lazily through
// `kanban card`; the explicit all view asks that same board op for compact
// all-state cards with direct epic membership. Epics group their features
// (`=`/`-` collapses the group, open by
// default); a feature that bears tasks gets a marker and `=`/`-` reveals/hides
// them (collapsed by default).
// The dashboard's tabs are this board's saved filter views: ⇥/⇧⇥ walk ALL → each
// saved view → the `+` slot, which opens the editor on a new one, and `f` edits
// the tab in force. A view is named labels combined with AND/OR plus per-label
// exclusion; filtering is client-side over the `labels` each card ships (the pure
// half lives in ./kanban-filter, which also owns the on-disk store).
// The row types, fetchers, colour maps, and tree component live here so the tab
// is edited in exactly one place.

import { Box, Text } from "ink";
import { str, strandJson, type DetailRow } from "../data";
import {
  clip,
  DetailView,
  detailMaxScroll,
  detailPage,
  Failure,
  fitCol,
  hintRows,
  listPage,
  listViewport,
  ListFooter,
  oneLine,
  pad,
  TableRow,
  windowRows,
  type Cell,
} from "../ui";
import { defineDash, emptyListState, followSelection, reduceListKeys, reduceScrollKeys, type ListState, type RenderCtx } from "../app";
import {
  activeBoardCards,
  activeTasks,
  loadCardDetails,
  type BoardCard,
  type BoardSnapshot,
  type CardView,
  type TaskChild,
} from "./kanban-data";
import {
  applyFilter,
  deleteView,
  describeView,
  emptyView,
  filtersFile,
  isBlank,
  loadFilterState,
  negateTerm,
  posOf,
  saveFilterState,
  saveView,
  stepPos,
  stripLabels,
  toggleTerm,
  viewAt,
  withTerm,
  type FilterState,
  type FilterView,
} from "./kanban-filter";
import { workspaceRoot } from "../data";

// A kanban card (epic or feature) plus the two joins the client caches: the epic
// it hangs under (null for top-level cards) and its lazily loaded tasks.
export type KanbanRow = DetailRow & {
  lane: string;
  type: string;
  owner: string;
  priority: string;
  epic: string | null;
  labels: string[];
  tasks: TaskChild[];
  tasksLoaded: boolean;
};

const LANE_COLOR: Record<string, string | undefined> = { claimed: "green", in_review: "magenta", pending: "yellow", refinement: "cyan" };

// Derived task status (`kanban card`): doing is live work, ready is actionable,
// blocked waits on a dependency, closed is complete.
const TASK_STATUS_COLOR: Record<string, string | undefined> = { doing: "green", ready: "yellow", blocked: "red" };

// Priority tint mirrors the spool's p1..p4 urgency (kanban.md): p1 is an
// immediate blocker, p4 is someday. p3 is the unstamped default and stays plain.
const PRIO_COLOR: Record<string, string | undefined> = { p1: "red", p2: "yellow" };
const prioDim = (p: string): boolean => p === "p4";

// Board lane order is review-first urgency, not the spool's lifecycle order:
// claimed work in flight, then the cards under review that a coordinator should
// clear next (in_review), then the actionable queue (pending), then ideas still
// in refinement. Closed strands sink regardless of their lane column — the
// vocabulary-reset cutover leaves closed cards on historic kanban/status while
// live cards carry kanban/lane and freshly closed ones kanban/outcome — and
// show their outcome (done/abandoned/...) dimmed.
const LANE_RANK: Record<string, number> = { claimed: 0, in_review: 1, pending: 2, refinement: 3 };
const laneRank = (r: KanbanRow): number => (r.state === "closed" ? 4 : (LANE_RANK[r.lane] ?? 4));

// created_at is "YYYY-MM-DD HH:MM:SS" (UTC), so lexical order is chronological,
// and "p1".."p4" also compares lexically. Active lanes are queues and sort
// priority-first then oldest-first to agree with `kanban next` (kanban.md);
// the closed bucket lists newest-first so fresh outcomes stay in reach. Used for
// both the top level and each epic's feature group.
function byLane(a: KanbanRow, b: KanbanRow): number {
  const rank = laneRank(a) - laneRank(b);
  if (rank !== 0) return rank;
  if (a.state === "closed") return b.createdAt.localeCompare(a.createdAt);
  const prio = a.priority.localeCompare(b.priority);
  if (prio !== 0) return prio;
  return a.createdAt.localeCompare(b.createdAt);
}

// `labels` is passed separately rather than read off `s`: when a card's tasks are
// expanded, `s` is the richer `kanban card` detail, and that view returns a null
// `labels` — folding it in would silently drop an expanded card out of its own
// filter. The board snapshot is the authority for labels.
const rowFromCard = (
  s: BoardCard,
  tasks: TaskChild[],
  tasksLoaded: boolean,
  epic: string | null,
  labels: string[],
): KanbanRow => {
    const attrs = s.attributes ?? {};
    const lane = s.state === "closed"
      ? s.outcome || str(attrs["kanban/outcome"], "") || s.lane || str(attrs["kanban/status"], "?")
      : s.lane || str(attrs["kanban/lane"], "") || str(attrs["kanban/status"], "?");
    return {
      id: s.id,
      title: s.title,
      state: s.state,
      branch: s.branch ?? str(attrs["branch"], "-"),
      lane,
      type: s.type ?? str(attrs["kanban/type"], "feature"),
      owner: s.owner ?? str(attrs["owner"], "-"),
      priority: s.priority ?? str(attrs["kanban/priority"], "p3"),
      createdAt: s.created_at,
      updatedAt: s.updated_at ?? s.created_at,
      attrs,
      epic,
      labels,
      tasks,
      tasksLoaded,
    };
};

// The board snapshot is the shape authority for labels, and every filter decision
// keys off them, so a payload whose `labels` is not a string vector is reported
// rather than coerced (TEN-003) — a filter silently matching nothing would look
// exactly like an empty backlog. Absent is not malformed: the spool omits the key
// on cards carrying no labels.
function cardLabels(card: BoardCard): string[] {
  const raw = card.labels;
  if (raw === undefined || raw === null) return [];
  if (!Array.isArray(raw) || raw.some((label) => typeof label !== "string")) {
    throw new Error(`kanban board returned a non-string-vector labels for card ${card.id}: ${JSON.stringify(raw)}`);
  }
  return raw;
}

async function fetchTaskDetails(
  ids: string[],
  cached: Map<string, TaskChild[]>,
  cachedCards: Map<string, BoardCard>,
): Promise<{
  taskCache: Map<string, TaskChild[]>;
  cardCache: Map<string, BoardCard>;
  taskFailures: Map<string, string>;
}> {
  return loadCardDetails(
    ids,
    cached,
    cachedCards,
    async (id) => (await strandJson(["kanban", "card", id])) as CardView,
  );
}

async function fetchActiveKanban(
  taskCache: Map<string, TaskChild[]>,
  cardCache: Map<string, BoardCard>,
): Promise<KanbanRow[]> {
  const board = (await strandJson(["kanban", "board"])) as BoardSnapshot;
  const cards = activeBoardCards(board);
  return cards.map((card) => {
    const detail = cardCache.get(card.id);
    return rowFromCard(
      detail ?? card,
      activeTasks(taskCache.get(card.id) ?? []),
      taskCache.has(card.id),
      card.epic ?? null,
      cardLabels(card),
    );
  });
}

async function fetchAllKanban(
  taskCache: Map<string, TaskChild[]>,
  cardCache: Map<string, BoardCard>,
): Promise<KanbanRow[]> {
  const board = (await strandJson(["kanban", "board", "--all", "true"])) as BoardSnapshot;
  if (!Array.isArray(board.cards)) {
    throw new Error("kanban board --all true returned no cards collection");
  }
  return board.cards.map((card) => rowFromCard(
      cardCache.get(card.id) ?? card,
      taskCache.get(card.id) ?? [],
      taskCache.has(card.id),
      card.epic ?? null,
      cardLabels(card),
    ));
}

// ── tree flattening ──────────────────────────────────────────────────────────
// The board is a flattened pre-order walk of the epic → feature → task tree.
// Every strand appears exactly once per render — dedup keeps a feature either
// top-level or under its epic, never both — so the row `key` is the plain strand
// id. Selection then stays anchored to the same card when a poll regroups it (a
// feature hopping under a newly-linked epic) instead of jumping, which a
// position-derived key would do the moment the ancestry changed. Epics are open
// unless the user collapsed them; features are closed unless the user expanded
// them — so the default board shows every feature (grouped under any epic) with
// tasks tucked away, matching the old flat board plus expand markers.

type Marker = "open" | "closed" | "leaf";

// `guide` is the ID-column tree art (box-drawing connectors) for this row's place
// in the epic → feature → task tree; empty for a root. Titles carry their own
// indent+marker, so the guide is a second, denser read of the same structure.
type FlatRow = { key: string; depth: number; guide: string } & (
  | { kind: "card"; card: KanbanRow; marker: Marker }
  | { kind: "task"; task: TaskChild }
);

// One box-drawing prefix for a child row: the ancestor continuation columns
// (`segs`, "│ " where an ancestor has siblings below, "  " where it is spent)
// followed by the node's own connector. Roots (depth 0) carry no guide.
const guideOf = (depth: number, segs: string[], last: boolean): string =>
  depth === 0 ? "" : segs.join("") + (last ? "└─" : "├─");

function flatten(cards: KanbanRow[], collapsed: Set<string>, expanded: Set<string>): FlatRow[] {
  const byId = new Map(cards.map((c) => [c.id, c] as const));
  // Group features under an epic that is itself present in the payload; a feature
  // whose epic is closed-and-filtered (or unset) stays a top-level card.
  const featuresByEpic = new Map<string, KanbanRow[]>();
  const claimed = new Set<string>();
  for (const c of cards) {
    if (c.type === "feature" && c.epic && byId.has(c.epic)) {
      (featuresByEpic.get(c.epic) ?? featuresByEpic.set(c.epic, []).get(c.epic)!).push(c);
      claimed.add(c.id);
    }
  }
  for (const feats of featuresByEpic.values()) feats.sort(byLane);
  const topLevel = cards.filter((c) => c.type === "epic" || !claimed.has(c.id)).sort(byLane);

  const rows: FlatRow[] = [];
  // Each card's children are its epic's features or its own tasks; `segs` carries
  // the ancestor continuation columns down so a task under a non-last feature draws
  // the "│" that keeps the epic's branch connected.
  const emitCard = (card: KanbanRow, depth: number, segs: string[], last: boolean) => {
    const isEpic = card.type === "epic";
    const feats = isEpic ? featuresByEpic.get(card.id) ?? [] : [];
    const open = isEpic ? !collapsed.has(card.id) : expanded.has(card.id);
    const hasChildren = isEpic ? feats.length > 0 : !card.tasksLoaded || card.tasks.length > 0;
    rows.push({ key: card.id, depth, guide: guideOf(depth, segs, last), kind: "card", card, marker: hasChildren ? (open ? "open" : "closed") : "leaf" });
    if (!open || !hasChildren) return;
    const childSegs = depth === 0 ? [] : [...segs, last ? "  " : "│ "];
    if (isEpic) feats.forEach((f, i) => emitCard(f, depth + 1, childSegs, i === feats.length - 1));
    else
      card.tasks.forEach((t, i) =>
        rows.push({ key: t.id, depth: depth + 1, guide: guideOf(depth + 1, childSegs, i === card.tasks.length - 1), kind: "task", task: t }),
      );
  };
  topLevel.forEach((c) => emitCard(c, 0, [], true));
  return rows;
}

const keyOf = (r: FlatRow): string => r.key;
const cardAt = (rows: FlatRow[], i: number): KanbanRow | undefined => {
  const r = rows[i];
  return r?.kind === "card" ? r.card : undefined;
};

// ── list view ──────────────────────────────────────────────────────────────

const MARK: Record<Marker, string> = { open: "▾ ", closed: "▸ ", leaf: "  " };

const rowId = (r: FlatRow): string => (r.kind === "card" ? r.card.id : r.task.id);
// The ID cell doubles as the tree spine: the box-drawing guide precedes the id.
const rowIdCell = (r: FlatRow): string => r.guide + rowId(r);
const rowLane = (r: FlatRow): string => (r.kind === "card" ? r.card.lane : r.task.status);
const rowPrio = (r: FlatRow): string => (r.kind === "card" ? r.card.priority : "");
// PRIO renders as the bare number (colour carries the urgency); rowPrio keeps the
// "p1".."p4" form the colour/dim lookups key on.
const rowPrioNum = (r: FlatRow): string => rowPrio(r).replace(/^p/, "");
// Type column compacts feature→feat so it never widens past its 4-char header.
const TYPE_ABBR: Record<string, string> = { feature: "feat", epic: "epic", task: "task" };
const rowType = (r: FlatRow): string => {
  const t = r.kind === "card" ? r.card.type : "task";
  return TYPE_ABBR[t] ?? t;
};
// Under a narrow terminal (<80) the lane column costs the most width; compact known
// lanes to four-letter codes (unknowns—task statuses—fall back to a four-char slice).
const LANE_ABBR: Record<string, string> = { claimed: "clmd", in_review: "revw", pending: "pend", refinement: "refn" };
const abbrevLane = (lane: string): string => LANE_ABBR[lane] ?? lane.slice(0, 4);
const rowOwner = (r: FlatRow): string => (r.kind === "card" ? r.card.owner : r.task.owner ?? "-");
const rowBranch = (r: FlatRow): string => (r.kind === "card" ? r.card.branch : "");
const rowTitle = (r: FlatRow): string =>
  "  ".repeat(r.depth) + (r.kind === "card" ? MARK[r.marker] : "  ") + oneLine(r.kind === "card" ? r.card.title ?? "" : r.task.title);

const HINT = "↑↓/jk move · ⌃d/⌃u page · = expand · - collapse · ⏎ attrs · ⇥/⇧⇥ filter tab · f edit tab · ⌃g open · y copy · a all/active · r refresh · q quit";

function KanbanTree({
  rows,
  selected,
  interactive,
  cols,
  termRows,
  all,
  loaded,
}: {
  rows: FlatRow[];
  selected: number;
  interactive: boolean;
  cols: number;
  termRows: number;
  all: boolean;
  loaded: boolean;
}) {
  if (rows.length === 0) {
    return <Text dimColor>{clip(loaded ? `no ${all ? "" : "active "}cards` : "loading board…", cols)}</Text>;
  }
  const narrow = cols < 80;
  const laneText = (r: FlatRow): string => (narrow ? abbrevLane(rowLane(r)) : rowLane(r));
  // Narrow terminals collapse a present branch to a tick; rows without one keep
  // their bare value (a "-"/"" placeholder) so the column still reads.
  const hasBranch = (v: string): boolean => v !== "" && v !== "-";
  const branchHeader = narrow ? "B" : "BRANCH";
  const branchText = (r: FlatRow): string => {
    const b = rowBranch(r);
    return narrow && hasBranch(b) ? "✓" : b;
  };
  const w = {
    id: fitCol("ID", rows.map(rowIdCell), 16),
    lane: fitCol("LANE", rows.map(laneText), 12),
    prio: fitCol("P", rows.map(rowPrioNum), 4),
    type: fitCol("TYPE", rows.map(rowType), 8),
    owner: fitCol("OWNER", rows.map(rowOwner), 14),
    branch: fitCol(branchHeader, rows.map(branchText), 24),
  };
  const titleWidth = Math.max(0, cols - 12 - w.id - w.lane - w.prio - w.type - w.owner - w.branch);
  const { start, visible, below } = windowRows(rows, selected, interactive, termRows, hintRows(HINT, cols));

  return (
    <Box flexDirection="column">
      <TableRow
        width={cols}
        bold
        cells={[
          { text: pad("ID", w.id) }, { text: "  " },
          { text: pad("LANE", w.lane) }, { text: "  " },
          { text: pad("P", w.prio) }, { text: "  " },
          { text: pad("TYPE", w.type) }, { text: "  " },
          { text: pad("TITLE", titleWidth) }, { text: "  " },
          { text: pad("OWNER", w.owner) }, { text: "  " },
          { text: branchHeader },
        ]}
      />
      {visible.map((r, i) => {
        const isSelected = interactive && start + i === selected;
        const isTask = r.kind === "task";
        // Cards colour the lane/priority; tasks colour the derived-status cell in
        // the lane column and read dimmer overall (they hang under their feature).
        const laneColor = isSelected ? undefined : isTask ? TASK_STATUS_COLOR[r.task.status] : LANE_COLOR[rowLane(r)];
        const closed = r.kind === "card" && r.card.state === "closed";
        const laneDim = !isSelected && (isTask ? r.task.state === "closed" : closed);
        const cells: Cell[] = [
          { text: pad(rowIdCell(r), w.id), dimColor: !isSelected && isTask },
          { text: "  " },
          { text: pad(laneText(r), w.lane), color: laneColor, dimColor: laneDim },
          { text: "  " },
          { text: pad(rowPrioNum(r), w.prio), color: isSelected || closed ? undefined : PRIO_COLOR[rowPrio(r)], dimColor: !isSelected && (closed || prioDim(rowPrio(r))) },
          { text: "  " },
          { text: pad(rowType(r), w.type), dimColor: !isSelected && rowType(r) !== "epic" },
          { text: "  " },
          { text: pad(rowTitle(r), titleWidth), dimColor: !isSelected && (isTask ? r.task.state === "closed" : closed) },
          { text: "  " },
          { text: pad(rowOwner(r), w.owner), dimColor: !isSelected },
          { text: "  " },
          { text: pad(branchText(r), w.branch) },
        ];
        return <TableRow key={r.key} cells={cells} width={cols} inverse={isSelected} />;
      })}
      {interactive && <ListFooter hint={HINT} cols={cols} start={start} below={below} total={rows.length} />}
    </Box>
  );
}

function TaskFailureBanner(failures: Map<string, string>, ctx: RenderCtx) {
  if (failures.size === 0) return null;
  const [[id, message]] = failures;
  const suffix = failures.size === 1 ? "" : ` · ${failures.size - 1} more`;
  return (
    <Text bold color="red">
      {clip(`TASK DETAIL ${id} failed · ${oneLine(message)}${suffix}`, ctx.cols)}
    </Text>
  );
}

// ── label filter ─────────────────────────────────────────────────────────────
// The editor for one tab's view: a picker over every label in play, working on a
// copy so nothing is written until ⏎ saves and ⎋ leaves the saved view (and the
// board under it) exactly as it was. `slot` is the tab being edited, or null for
// the `+` tab's not-yet-saved view.

type Overlay = { view: FilterView; slot: number | null; cursor: number; naming: boolean };

// Every label the picker offers: the ones cards actually carry, plus any the view
// being edited still names. A view outliving the last card with its label must
// stay editable — otherwise its term is invisible and impossible to clear.
const labelUniverse = (rows: KanbanRow[], view: FilterView): string[] =>
  [...new Set([...rows.flatMap((r) => r.labels), ...Object.keys(view.terms)])].sort();

const labelCount = (rows: KanbanRow[], label: string): number =>
  rows.filter((r) => r.labels.includes(label)).length;

const openOverlay = (v: KanbanView, slot: number | null): Overlay => {
  const saved = slot === null ? null : v.filter.views[slot];
  return { view: saved ? { ...saved, terms: { ...saved.terms } } : emptyView(), slot, cursor: 0, naming: false };
};

const TERM_MARK: Record<string, string> = { include: "✓", exclude: "✗" };
const TERM_COLOR: Record<string, string> = { include: "green", exclude: "red" };

function FilterOverlay({ o, rows, cols, termRows }: { o: Overlay; rows: KanbanRow[]; cols: number; termRows: number }) {
  const view = o.view;
  const labels = labelUniverse(rows, view);
  const hint = o.naming
    ? "type a name · ⏎/⎋ done"
    : `↑↓/jk move · ␣ toggle · ! exclude · m and/or · i name${o.slot === null ? "" : " · x delete"} · ⏎ save · ⎋ cancel · ⇥ next tab`;
  // The pane spends the list viewport less its three header rows (title, name,
  // mode), against a footer that wraps to however many rows the hint needs, so
  // the whole overlay stays inside the frame the shell pins to the terminal.
  const footer = hintRows(hint, cols);
  const viewport = Math.max(3, listViewport(termRows, footer) - 3);
  const start = Math.max(0, Math.min(o.cursor - Math.floor(viewport / 2), labels.length - viewport));
  const visible = labels.slice(start, start + viewport);

  return (
    <Box flexDirection="column">
      <Text bold>{clip(o.slot === null ? "NEW FILTER TAB" : "EDIT FILTER TAB", cols)}</Text>
      <Text>
        <Text dimColor>{pad("name", 6)}</Text>
        <Text color={o.naming ? "green" : undefined}>{clip(view.name || (o.naming ? "" : "(unnamed)"), Math.max(0, cols - 8))}</Text>
        {o.naming ? <Text inverse> </Text> : null}
      </Text>
      <Text>
        <Text dimColor>{pad("mode", 6)}</Text>
        <Text color="cyan">{view.mode.toUpperCase()}</Text>
        <Text dimColor>{clip(view.mode === "and" ? "  (all of)" : "  (any of)", Math.max(0, cols - 12))}</Text>
      </Text>
      <Box marginTop={1} flexDirection="column">
        {labels.length === 0 ? (
          <Text dimColor>{clip("no labels on the board — `strand kanban label add <id> <slug>`", cols)}</Text>
        ) : (
          visible.map((label, i) => {
            const term = view.terms[label];
            const selected = start + i === o.cursor && !o.naming;
            return (
              <TableRow
                key={label}
                width={cols}
                inverse={selected}
                cells={[
                  { text: "  " },
                  { text: pad(term ? TERM_MARK[term]! : " ", 2), color: selected || !term ? undefined : TERM_COLOR[term] },
                  { text: pad(label, Math.max(8, Math.min(32, cols - 16))) },
                  { text: `${labelCount(rows, label)}`, dimColor: !selected },
                ]}
              />
            );
          })
        )}
      </Box>
      <ListFooter hint={hint} cols={cols} start={0} below={0} total={labels.length} />
    </Box>
  );
}

// One line spelling out what the tab in force actually filters on and what it
// left on screen, so a board that is hiding cards always says so — a filtered
// board and an empty backlog must never look the same. The ALL tab hides nothing
// and gets no strip.
function FilterStrip(v: KanbanView, shown: number, total: number, ctx: RenderCtx) {
  const view = activeView(v);
  if (!view) return null;
  return (
    <Text bold color="blue">
      {clip(`FILTER · ${describeView(view)} · ${shown}/${total} cards · f edit · ⇥ next tab`, ctx.cols)}
    </Text>
  );
}

function FilterErrorBanner(error: string | null, ctx: RenderCtx) {
  if (!error) return null;
  return (
    <Text bold color="red">
      {clip(`SAVED FILTERS · ${oneLine(error)}`, ctx.cols)}
    </Text>
  );
}

// ── the view module ────────────────────────────────────────────────────────
// The board is a list+detail view with status strips, composing the shared
// movement/scroll reducers directly rather than a list helper. Owning the view
// `collapsed`/`expanded` are the tree's per-card overrides against the defaults
// (epics open, features closed); both survive polls so the tree the user shaped
// stays put while the board refreshes underneath it.
type KanbanView = {
  rows: KanbanRow[];
  taskCache: Map<string, TaskChild[]>;
  cardCache: Map<string, BoardCard>;
  taskFailures: Map<string, string>;
  loaded: boolean;
  failure: string | null;
  collapsed: Set<string>;
  expanded: Set<string>;
  filter: FilterState;
  filterError: string | null;
  overlay: Overlay | null;
  s: ListState;
};

// The view the current tab filters by, or null on the ALL tab.
const activeView = (v: KanbanView): FilterView | null =>
  v.filter.active !== null ? v.filter.views[v.filter.active] ?? null : null;

// Every read of the board goes through here, so the filter applies once, before
// grouping — and selection, paging, and the detail target all agree on which rows
// exist. `rows` is explicit so a poll can flatten its fresh cards against the
// latest view state.
const treeOf = (rows: KanbanRow[], v: KanbanView): FlatRow[] =>
  flatten(applyFilter(rows, activeView(v)), v.collapsed, v.expanded);
const tree = (v: KanbanView): FlatRow[] => treeOf(v.rows, v);

// Total rows the status strips above the board consume. Every banner that can
// draw is counted here once, so the list windows against the height it actually
// gets and the stacked heights still sum to the pinned frame.
const stripRows = (v: KanbanView): number =>
  (v.taskFailures.size > 0 ? 1 : 0) +
  (v.filter.active !== null ? 1 : 0) +
  (v.filterError ? 1 : 0);

// Move to another tab: the board underneath changes, so the store is written
// through and the selection re-anchored against the rows the new tab shows.
// Closing any open overlay is part of it — every path here settles the edit.
function withFilter(v: KanbanView, filter: FilterState): KanbanView {
  const next = { ...v, filter, filterError: saveFilterState(filtersFile(), workspaceRoot, filter), overlay: null };
  return { ...next, s: followSelection(next.s, tree(next), keyOf) };
}

// Save the overlay's working copy as its tab and switch to it. A view nobody
// named and gave no terms describes nothing, so it saves nothing and the board
// stays on the tab it was already showing.
function commitOverlay(v: KanbanView, o: Overlay): KanbanView {
  if (isBlank(o.view)) return { ...v, overlay: null };
  return withFilter(v, saveView(v.filter.views, o.slot, o.view));
}

// ⇥/⇧⇥ step the strip, from the editor as much as from the board: the working
// copy is dropped (⏎ is the only thing that saves) and the walk carries on, so
// cycling the tabs passes over the `+` slot instead of being trapped in the
// editor that landing on it opens.
function stepTab(v: KanbanView, back: boolean): KanbanView {
  const count = v.filter.views.length;
  const from = v.overlay ? (v.overlay.slot === null ? count + 1 : v.overlay.slot + 1) : posOf(v.filter.active);
  const at = viewAt(stepPos(from, count, back), count);
  if (at === "new") return { ...v, overlay: openOverlay(v, null) };
  return withFilter(v, { ...v.filter, active: at });
}

export const kanbanDash = defineDash<KanbanView>({
  id: "kanban",
  noun: "cards",
  init: () => {
    // Saved views are read once at startup; a store the user has never written is
    // simply an empty set, while a corrupt one degrades to empty plus a banner.
    const { state, error } = loadFilterState(filtersFile(), workspaceRoot);
    return {
      rows: [],
      taskCache: new Map(),
      cardCache: new Map(),
      taskFailures: new Map(),
      loaded: false,
      failure: null,
      collapsed: new Set(),
      expanded: new Set(),
      filter: state,
      filterError: error,
      overlay: null,
      s: emptyListState(),
    };
  },
  fetchKey: (v) => {
    const selected = cardAt(tree(v), v.s.selected);
    const detail = v.s.view === "detail" && selected ? selected.id : "";
    return `${[...v.expanded].sort().join(",")}|${detail}`;
  },
  allApplies: () => true,
  // The strip is the saved views: ALL, each of them, then the `+` slot. An open
  // overlay highlights the tab it is editing — the `+` while a new one is being
  // authored — so the strip always says which tab the pane belongs to.
  strip: (v) => ({
    labels: stripLabels(v.filter.views),
    active: v.overlay ? (v.overlay.slot === null ? v.filter.views.length + 1 : v.overlay.slot + 1) : posOf(v.filter.active),
  }),
  inDetail: (v) => v.s.view === "detail" || v.overlay !== null,
  // The overlay owns every key while it is open, including the shell's q — a
  // filter name with a "q" in it must not quit the dashboard.
  capturesInput: (v) => v.overlay !== null,
  editTarget: (v) => (v.overlay ? null : cardAt(tree(v), v.s.selected) ?? null),
  // Unlike editTarget (cards only — a task has no openable source), y copies the id
  // of whatever row is under the cursor, task rows included: they are strands too.
  copyId: (v) => {
    if (v.overlay) return null;
    const row = tree(v)[v.s.selected];
    return row ? rowId(row) : null;
  },
  refresh: async (v, all) => {
    // The board is the tab's primary data: a kanban read failure surfaces as the
    // full-pane <Failure>.
    try {
      const selected = cardAt(tree(v), v.s.selected);
      const detailIds = new Set(v.expanded);
      if (v.s.view === "detail" && selected) detailIds.add(selected.id);
      const details = await fetchTaskDetails([...detailIds], v.taskCache, v.cardCache);
      const { taskCache, cardCache, taskFailures } = details;
      const rows = all
        ? await fetchAllKanban(taskCache, cardCache)
        : await fetchActiveKanban(taskCache, cardCache);
      return (latest) => ({
        ...latest,
        rows,
        taskCache,
        cardCache,
        taskFailures: new Map([...taskFailures].filter(([id]) => rows.some((row) => row.id === id))),
        expanded: new Set([...latest.expanded].filter((id) => rows.some((row) => row.id === id))),
        loaded: true,
        failure: null,
        s: followSelection(latest.s, treeOf(rows, latest), keyOf),
      });
    } catch (e) {
      const failure = e instanceof Error ? e.message : String(e);
      return (latest) => ({ ...latest, loaded: true, failure });
    }
  },
  onKey: (v, ctx) => {
    const { input, key } = ctx;
    // The overlay is modal: it precedes every other binding and swallows keys it
    // does not use, so a stray press can never leak through to the board beneath.
    if (v.overlay) {
      const o = v.overlay;
      const view = o.view;
      const labels = labelUniverse(v.rows, view);
      const setView = (next: FilterView): KanbanView => ({ ...v, overlay: { ...o, view: next } });
      if (o.naming) {
        // Name editing is a sub-mode because the picker's own keys (jk/hl/space)
        // are letters: there is no way to type "test only" and navigate at once.
        if (key.return || key.escape) return { ...v, overlay: { ...o, naming: false } };
        if (key.backspace || key.delete) return setView({ ...view, name: view.name.slice(0, -1) });
        // Printable ASCII only: a tab, arrow, or unparsed escape sequence must not
        // land in the name as literal control characters.
        if (!key.ctrl && !key.meta && /^[\x20-\x7e]+$/.test(input)) return setView({ ...view, name: view.name + input });
        return v;
      }
      if (key.tab) return stepTab(v, key.shift);
      if (key.escape) return { ...v, overlay: null };
      if (key.return) return commitOverlay(v, o);
      if (input === "i") return { ...v, overlay: { ...o, naming: true } };
      // ⇥ is tab navigation everywhere else, so the mode toggle is `m` rather than
      // the same key meaning two things one keystroke apart.
      if (input === "m") return setView({ ...view, mode: view.mode === "and" ? "or" : "and" });
      if (labels.length > 0 && (input === " " || input === "!")) {
        const label = labels[Math.min(o.cursor, labels.length - 1)]!;
        const current = view.terms[label];
        return setView(withTerm(view, label, input === " " ? toggleTerm(current) : negateTerm(current)));
      }
      if (key.upArrow || input === "k") return { ...v, overlay: { ...o, cursor: Math.max(0, o.cursor - 1) } };
      if (key.downArrow || input === "j") return { ...v, overlay: { ...o, cursor: Math.min(labels.length - 1, o.cursor + 1) } };
      // Deleting the tab is the one overlay edit that lands without ⏎: there is no
      // working copy left to commit once the view it edits is gone. The `+` tab has
      // no saved view behind it, so there is nothing there to delete.
      if (input === "x" && o.slot !== null) return withFilter(v, deleteView(v.filter.views, o.slot));
      return v;
    }
    const rows = tree(v);
    if (v.s.view === "detail") {
      // ⇥ means "show me that tab's board" wherever it is pressed, so it drops the
      // detail on the way: the card under it may not be among the rows the tab
      // being switched to keeps, and a detail pane left open over a card the board
      // no longer lists is a dead end reachable by one keystroke.
      if (key.tab) {
        const next = stepTab(v, key.shift);
        return { ...next, s: { ...next.s, view: "list", detailScroll: 0 } };
      }
      const r = reduceScrollKeys(v.s.detailScroll, input, key, detailMaxScroll(cardAt(rows, v.s.selected), ctx.cols, ctx.termRows), detailPage(ctx.termRows));
      if (r === "back") return { ...v, s: { ...v.s, view: "list" } };
      if (r !== null) return { ...v, s: { ...v.s, detailScroll: r } };
      if (input === "r") ctx.refresh();
      return v;
    }
    // The banners steal list rows when they draw, so page jumps size against the
    // same reduced viewport the list renders into, not the raw terminal.
    const listRows = ctx.termRows - stripRows(v);
    const moved = reduceListKeys(v.s, input, key, rows, keyOf, listPage(listRows, hintRows(HINT, ctx.cols)));
    if (moved) return { ...v, s: moved };
    // ⇥/⇧⇥ walk the strip. The `+` tab has no board of its own — it is where a new
    // filter is authored — so landing on it opens the editor instead of switching.
    if (key.tab) return stepTab(v, key.shift);
    // Edit the tab in force; on ALL — which has no view to edit — author a new one.
    if (input === "f") return { ...v, overlay: openOverlay(v, v.filter.active) };
    // Expand/collapse the selected card. Epics default open (toggle via collapsed);
    // features default closed (toggle via expanded), and only when they bear tasks.
    if (input === "=" || input === "-") {
      const card = cardAt(rows, v.s.selected);
      if (!card) return v;
      const open = input === "=";
      if (card.type === "epic") {
        const collapsed = new Set(v.collapsed);
        open ? collapsed.delete(card.id) : collapsed.add(card.id);
        return { ...v, collapsed };
      }
      if (card.tasksLoaded && card.tasks.length === 0) return v;
      const expanded = new Set(v.expanded);
      open ? expanded.add(card.id) : expanded.delete(card.id);
      return { ...v, expanded };
    }
    if (key.return || key.rightArrow || input === "l") {
      if (!cardAt(rows, v.s.selected)) return v;
      return { ...v, s: { ...v.s, view: "detail", detailScroll: 0 } };
    }
    if (input === "r") ctx.refresh();
    return v;
  },
  render: (v, ctx) => {
    if (v.failure) return <Failure failure={v.failure} cols={ctx.cols} />;
    // The picker takes the whole pane, like the detail view: its own name/mode
    // header would not survive sharing the frame with the board's status strips.
    if (v.overlay && ctx.interactive)
      return <FilterOverlay o={v.overlay} rows={v.rows} cols={ctx.cols} termRows={ctx.termRows} />;
    const rows = tree(v);
    if (v.s.view === "detail" && ctx.interactive)
      return <DetailView row={cardAt(rows, v.s.selected)} scroll={v.s.detailScroll} cols={ctx.cols} termRows={ctx.termRows} />;
    return (
      <Box flexDirection="column">
        {TaskFailureBanner(v.taskFailures, ctx)}
        {FilterErrorBanner(v.filterError, ctx)}
        {FilterStrip(v, applyFilter(v.rows, activeView(v)).length, v.rows.length, ctx)}
        <KanbanTree
          rows={rows}
          selected={v.s.selected}
          interactive={ctx.interactive}
          cols={ctx.cols}
          termRows={ctx.termRows - stripRows(v)}
          all={ctx.all}
          loaded={v.loaded}
        />
      </Box>
    );
  },
});
