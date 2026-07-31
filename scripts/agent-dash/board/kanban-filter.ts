// Saved label-filter views for the board, and their on-disk store. Pure
// transforms plus two filesystem entry points, kept out of the Ink tab so Bun
// tests exercise matching, tab navigation, and persistence without a dashboard.
//
// Filtering is client-side by design: `kanban board` ships each card's `labels`
// array (spool-side `--label` is AND-only, with no OR and no negation), so the
// richer boolean surface here costs nothing and never needs a second read.

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

// A label is either required or forbidden; absent from `terms` means the view is
// indifferent to it. Excludes are hard in both modes (see `matches`), so `mode`
// only ever combines the includes.
export type FilterMode = "and" | "or";
export type Term = "include" | "exclude";
export type FilterView = { name: string; mode: FilterMode; terms: Record<string, Term> };

// `active` indexes `views`, and null is the unfiltered ALL tab — the whole board.
export type FilterState = { views: FilterView[]; active: number | null };

export const emptyView = (): FilterView => ({ name: "", mode: "and", terms: {} });
export const emptyFilterState = (): FilterState => ({ views: [], active: null });

// A view nobody named and nobody gave a term to says nothing, so committing one
// saves nothing. A *named* view with no terms is kept — it is a deliberate "show
// everything" bookmark.
export const isBlank = (v: FilterView): boolean =>
  v.name.trim() === "" && Object.keys(v.terms).length === 0;

// ── term editing ─────────────────────────────────────────────────────────────
// space toggles a label in and out of the view; ! swings it to the forbidden
// side (and back to required), so a label can be excluded without first being
// included.

export const toggleTerm = (t: Term | undefined): Term | undefined => (t === undefined ? "include" : undefined);
export const negateTerm = (t: Term | undefined): Term | undefined => (t === "exclude" ? "include" : "exclude");

export function withTerm(view: FilterView, label: string, next: Term | undefined): FilterView {
  const terms = { ...view.terms };
  if (next === undefined) delete terms[label];
  else terms[label] = next;
  return { ...view, terms };
}

// ── matching ─────────────────────────────────────────────────────────────────

// An excluded label always vetoes, in both modes: "or" widens which cards get in,
// never which ones get past a hard exclusion. With no includes at all the view is
// a pure subtraction, so everything the excludes don't veto passes.
export function matches(labels: readonly string[], view: FilterView): boolean {
  const carried = new Set(labels);
  const entries = Object.entries(view.terms);
  if (entries.some(([label, term]) => term === "exclude" && carried.has(label))) return false;
  const includes = entries.filter(([, term]) => term === "include").map(([label]) => label);
  if (includes.length === 0) return true;
  return view.mode === "and"
    ? includes.every((label) => carried.has(label))
    : includes.some((label) => carried.has(label));
}

// The board is a tree, so a filter that keeps a feature must keep the epic it
// hangs under or the row loses its grouping. Epics therefore survive on their own
// match OR as scaffolding for a surviving feature; an epic matching nothing and
// parenting nothing drops out entirely rather than showing as an empty group.
export function applyFilter<T extends { id: string; type: string; epic: string | null; labels: string[] }>(
  cards: readonly T[],
  view: FilterView | null,
): T[] {
  if (!view) return [...cards];
  const kept = cards.filter((c) => matches(c.labels, view));
  const parents = new Set(kept.filter((c) => c.type !== "epic" && c.epic).map((c) => c.epic!));
  const keptIds = new Set(kept.map((c) => c.id));
  return cards.filter((c) => keptIds.has(c.id) || (c.type === "epic" && parents.has(c.id)));
}

// ── the tab strip ────────────────────────────────────────────────────────────
// Saved views *are* the dashboard's tabs: ALL (the unfiltered board) sits first,
// each saved view follows in order, and a trailing NEW slot is where ⇥ lands to
// author one. A strip position is that flat index, so navigation is plain ring
// arithmetic and the shell can render the strip without knowing what a filter is.

export const ALL_TAB = "ALL";
export const NEW_TAB = "+";

export const stripLabels = (views: readonly FilterView[]): string[] =>
  [ALL_TAB, ...views.map((v) => v.name.trim() || "(unnamed)"), NEW_TAB];

export const posOf = (active: number | null): number => (active === null ? 0 : active + 1);

// Where a strip position points: the ALL tab, a saved view's index, or the NEW
// slot that has no view behind it yet.
export const viewAt = (pos: number, count: number): number | null | "new" =>
  pos === 0 ? null : pos > count ? "new" : pos - 1;

export const stepPos = (pos: number, count: number, back: boolean): number => {
  const size = count + 2;
  return (pos + (back ? -1 : 1) + size) % size;
};

// ── saving ───────────────────────────────────────────────────────────────────

// Land the edited view: `slot` null appends it (the NEW tab), otherwise it
// replaces that tab in place. Either way the result becomes the active tab, so
// the board the user just described is the board they land on.
export function saveView(views: readonly FilterView[], slot: number | null, view: FilterView): { views: FilterView[]; active: number } {
  if (slot === null) return { views: [...views, view], active: views.length };
  return { views: views.map((v, i) => (i === slot ? view : v)), active: slot };
}

// Drop a saved view, which drops its tab. The board falls back to ALL rather
// than to a neighbouring filter: silently landing on a *different* filtered
// board after a delete would read as the delete having hidden cards.
export function deleteView(views: readonly FilterView[], slot: number): { views: FilterView[]; active: null } {
  return { views: views.filter((_, i) => i !== slot), active: null };
}

// The one-line summary the filter strip shows: `#tests & !docs`, or the mode-less
// "all cards" when a named view carries no terms.
export function describeView(view: FilterView): string {
  const terms = Object.entries(view.terms)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([label, term]) => (term === "exclude" ? `!${label}` : `#${label}`));
  if (terms.length === 0) return "all cards";
  return terms.join(view.mode === "and" ? " & " : " | ");
}

// ── store ────────────────────────────────────────────────────────────────────
// Saved views are a UI preference, not coordination data, so they live in the
// user's cache rather than the workspace — writing under .skein would surface as
// a dirty tree on every validation run. One file holds every workspace's views,
// keyed by workspace root, so dashboards over different worlds keep their own.

export const filtersFile = (): string =>
  join(process.env.XDG_CACHE_HOME || join(homedir(), ".cache"), "skein", "agent-dash", "filters.json");

// The store is parsed strictly (TEN-003): a value we did not expect is reported
// with where it sat and what was allowed, never coerced to a "sensible" default.
// Quietly rewriting `mode: "orr"` to AND would change what a saved view means
// without telling anyone, which is exactly the silent-semantics failure the
// tenet forbids.
const isObject = (v: unknown): v is Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v);

function parseView(raw: unknown, where: string): FilterView {
  if (!isObject(raw)) throw new Error(`${where} must be an object`);
  const extra = Object.keys(raw).filter((k) => !["name", "mode", "terms"].includes(k));
  if (extra.length > 0) throw new Error(`${where} has unexpected keys: ${extra.join(", ")}`);
  if (typeof raw.name !== "string") throw new Error(`${where}.name must be a string, got ${JSON.stringify(raw.name)}`);
  if (raw.mode !== "and" && raw.mode !== "or") {
    throw new Error(`${where}.mode must be "and" or "or", got ${JSON.stringify(raw.mode)}`);
  }
  if (!isObject(raw.terms)) throw new Error(`${where}.terms must be an object`);
  const terms: Record<string, Term> = {};
  for (const [label, term] of Object.entries(raw.terms)) {
    if (term !== "include" && term !== "exclude") {
      throw new Error(`${where}.terms[${JSON.stringify(label)}] must be "include" or "exclude", got ${JSON.stringify(term)}`);
    }
    terms[label] = term;
  }
  return { name: raw.name, mode: raw.mode, terms };
}

// `enabled` is a store written before the views became tabs, where ⇧f parked the
// active filter without forgetting it. The ALL tab is that off switch now, so the
// key is accepted and dropped: reading it back as anything would resurrect a
// setting the UI can no longer show or change.
function parseState(raw: unknown, where: string): FilterState {
  if (!isObject(raw)) throw new Error(`${where} must be an object`);
  const extra = Object.keys(raw).filter((k) => !["views", "active", "enabled"].includes(k));
  if (extra.length > 0) throw new Error(`${where} has unexpected keys: ${extra.join(", ")}`);
  if (!Array.isArray(raw.views)) throw new Error(`${where}.views must be an array`);
  const views = raw.views.map((view, i) => parseView(view, `${where}.views[${i}]`));
  if (raw.active !== null && !(typeof raw.active === "number" && Number.isInteger(raw.active) && raw.active >= 0 && raw.active < views.length)) {
    throw new Error(`${where}.active must be null or an index into views (0..${views.length - 1}), got ${JSON.stringify(raw.active)}`);
  }
  return { views, active: raw.active as number | null };
}

// A store we cannot read or cannot trust yields no views plus a message the tab
// shows in a banner. The board still renders — losing a filter bookmark must not
// blank it — but the filters stay unloaded and the reason is stated, rather than
// a guessed-at version of the user's saved views being put quietly into force.
export function loadFilterState(file: string, root: string): { state: FilterState; error: string | null } {
  let text: string;
  try {
    text = readFileSync(file, "utf8");
  } catch (e) {
    // A store that has never been written is the normal first-run case.
    if ((e as NodeJS.ErrnoException).code === "ENOENT") return { state: emptyFilterState(), error: null };
    return { state: emptyFilterState(), error: `${file}: ${e instanceof Error ? e.message : String(e)}` };
  }
  try {
    const parsed: unknown = JSON.parse(text);
    if (!isObject(parsed)) throw new Error("store must be a JSON object keyed by workspace root");
    // A workspace with no entry is not an error — that is every workspace this
    // dashboard has not saved a view in yet.
    if (!(root in parsed)) return { state: emptyFilterState(), error: null };
    return { state: parseState(parsed[root], JSON.stringify(root)), error: null };
  } catch (e) {
    return { state: emptyFilterState(), error: `${file}: ${e instanceof Error ? e.message : String(e)}` };
  }
}

// Read-modify-write so a dashboard over one workspace never drops another's
// views — which means a store we failed to *read* must never be overwritten: the
// rewrite would carry only this workspace and silently destroy every other one's
// saved views. Only a genuinely absent file is safe to create from nothing.
// Returns an error message rather than throwing; a failed preference write is
// worth a banner, not a dead board.
export function saveFilterState(file: string, root: string, state: FilterState): string | null {
  let all: Record<string, unknown> = {};
  try {
    const parsed: unknown = JSON.parse(readFileSync(file, "utf8"));
    if (!isObject(parsed)) {
      return `refusing to overwrite ${file}: store is not a JSON object keyed by workspace root`;
    }
    all = parsed;
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code !== "ENOENT") {
      return `refusing to overwrite ${file}: ${e instanceof Error ? e.message : String(e)}`;
    }
  }
  try {
    all[root] = { views: state.views, active: state.active };
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, JSON.stringify(all, null, 2) + "\n");
    return null;
  } catch (e) {
    return `saving filters failed: ${e instanceof Error ? e.message : String(e)}`;
  }
}
