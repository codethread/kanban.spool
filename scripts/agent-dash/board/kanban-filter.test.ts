import { afterEach, describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import {
  applyFilter,
  deleteView,
  describeView,
  emptyView,
  isBlank,
  loadFilterState,
  matches,
  negateTerm,
  posOf,
  saveFilterState,
  saveView,
  stepPos,
  stripLabels,
  toggleTerm,
  viewAt,
  withTerm,
  type FilterView,
} from "./kanban-filter";

const view = (over: Partial<FilterView> = {}): FilterView => ({ ...emptyView(), ...over });

const card = (id: string, labels: string[], over: Partial<{ type: string; epic: string | null }> = {}) => ({
  id,
  type: "feature",
  epic: null as string | null,
  labels,
  ...over,
});

const dirs: string[] = [];
const tempStore = (): string => {
  const dir = mkdtempSync(join(tmpdir(), "agent-dash-filters-"));
  dirs.push(dir);
  return join(dir, "nested", "filters.json");
};
afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

describe("label filter matching", () => {
  test("and mode requires every included label, or mode any of them", () => {
    const terms = { tests: "include" as const, docs: "include" as const };
    expect(matches(["tests"], view({ mode: "and", terms }))).toBe(false);
    expect(matches(["tests", "docs"], view({ mode: "and", terms }))).toBe(true);
    expect(matches(["tests"], view({ mode: "or", terms }))).toBe(true);
    expect(matches(["other"], view({ mode: "or", terms }))).toBe(false);
  });

  test("an excluded label vetoes in both modes, even against a satisfied include", () => {
    const terms = { tests: "include" as const, wip: "exclude" as const };
    expect(matches(["tests"], view({ mode: "and", terms }))).toBe(true);
    expect(matches(["tests", "wip"], view({ mode: "and", terms }))).toBe(false);
    expect(matches(["tests", "wip"], view({ mode: "or", terms }))).toBe(false);
  });

  test("excludes alone are a pure subtraction: everything else passes", () => {
    const v = view({ terms: { tests: "exclude" } });
    expect(matches([], v)).toBe(true);
    expect(matches(["docs"], v)).toBe(true);
    expect(matches(["tests"], v)).toBe(false);
  });

  test("a view with no terms matches every card", () => {
    expect(matches([], view({ name: "everything" }))).toBe(true);
  });
});

describe("filtering the board tree", () => {
  test("an epic survives as scaffolding for a matching feature, but not alone", () => {
    const cards = [
      card("keeper", [], { type: "epic" }),
      card("empty", [], { type: "epic" }),
      card("feat", ["tests"], { epic: "keeper" }),
      card("other", ["docs"], { epic: "empty" }),
    ];

    expect(applyFilter(cards, view({ terms: { tests: "include" } })).map((c) => c.id)).toEqual(["keeper", "feat"]);
  });

  test("an epic carrying the label survives on its own match", () => {
    const cards = [card("epic", ["tests"], { type: "epic" }), card("feat", [], { epic: "epic" })];

    expect(applyFilter(cards, view({ terms: { tests: "include" } })).map((c) => c.id)).toEqual(["epic"]);
  });

  test("a null view is a pass-through", () => {
    const cards = [card("a", []), card("b", ["tests"])];
    expect(applyFilter(cards, null).map((c) => c.id)).toEqual(["a", "b"]);
  });
});

describe("editing terms", () => {
  test("space cycles a label in and out; ! swings it between required and forbidden", () => {
    expect(toggleTerm(undefined)).toBe("include");
    expect(toggleTerm("include")).toBeUndefined();
    expect(toggleTerm("exclude")).toBeUndefined();
    expect(negateTerm(undefined)).toBe("exclude");
    expect(negateTerm("include")).toBe("exclude");
    expect(negateTerm("exclude")).toBe("include");
  });

  test("clearing a term drops the key rather than storing an off state", () => {
    const on = withTerm(view(), "tests", "include");
    expect(on.terms).toEqual({ tests: "include" });
    expect(withTerm(on, "tests", undefined).terms).toEqual({});
  });
});

describe("the tab strip", () => {
  test("ALL leads, the saved views follow in order, and the new-view slot trails", () => {
    expect(stripLabels([view({ name: "tests" }), view({ name: "  " })])).toEqual(["ALL", "tests", "(unnamed)", "+"]);
    expect(stripLabels([])).toEqual(["ALL", "+"]);
  });

  test("the active filter and its strip position are the same place", () => {
    expect(posOf(null)).toBe(0);
    expect(posOf(1)).toBe(2);
    expect(viewAt(0, 2)).toBeNull();
    expect(viewAt(2, 2)).toBe(1);
    expect(viewAt(3, 2)).toBe("new");
  });

  test("⇥ and ⇧⇥ ring through ALL, every view, and the new-view slot", () => {
    const walk = (back: boolean) => {
      const seen: number[] = [];
      let pos = 0;
      for (let i = 0; i < 4; i++) seen.push((pos = stepPos(pos, 2, back)));
      return seen;
    };
    expect(walk(false)).toEqual([1, 2, 3, 0]);
    expect(walk(true)).toEqual([3, 2, 1, 0]);
  });
});

describe("saving a view", () => {
  test("a new view is appended and becomes the active tab", () => {
    const views = [view({ name: "a" })];
    const added = view({ name: "b" });
    expect(saveView(views, null, added)).toEqual({ views: [views[0]!, added], active: 1 });
  });

  test("editing a tab replaces it in place and stays on it", () => {
    const views = [view({ name: "a" }), view({ name: "b" })];
    const edited = view({ name: "a", terms: { tests: "include" } });
    expect(saveView(views, 0, edited)).toEqual({ views: [edited, views[1]!], active: 0 });
  });

  test("a named view with no terms is a keepable bookmark, an unnamed termless one is not", () => {
    expect(isBlank(view({ name: "everything" }))).toBe(false);
    expect(isBlank(view({ terms: { tests: "include" } }))).toBe(false);
    expect(isBlank(emptyView())).toBe(true);
  });
});

describe("deleting a view", () => {
  test("the tab is dropped and the board falls back to ALL, not a neighbouring filter", () => {
    const views = [view({ name: "a" }), view({ name: "b" })];
    expect(deleteView(views, 0)).toEqual({ views: [views[1]!], active: null });
    expect(deleteView(views, 1)).toEqual({ views: [views[0]!], active: null });
  });
});

describe("describing a view", () => {
  test("terms render with their mode's joiner and a ! for exclusions", () => {
    expect(describeView(view({ mode: "and", terms: { tests: "include", wip: "exclude" } }))).toBe("#tests & !wip");
    expect(describeView(view({ mode: "or", terms: { tests: "include", docs: "include" } }))).toBe("#docs | #tests");
    expect(describeView(view({ name: "all" }))).toBe("all cards");
  });
});

describe("the saved-view store", () => {
  test("a round trip preserves the views and which tab was active", () => {
    const file = tempStore();
    const state = { views: [view({ name: "test only", terms: { tests: "include" } })], active: 0 };

    expect(saveFilterState(file, "/repo", state)).toBeNull();
    expect(loadFilterState(file, "/repo")).toEqual({ state, error: null });
  });

  test("workspaces keep separate views in one store", () => {
    const file = tempStore();
    saveFilterState(file, "/one", { views: [view({ name: "one" })], active: 0 });
    saveFilterState(file, "/two", { views: [view({ name: "two" })], active: 0 });

    expect(loadFilterState(file, "/one").state.views[0]?.name).toBe("one");
    expect(loadFilterState(file, "/two").state.views[0]?.name).toBe("two");
  });

  test("a never-written store is empty without an error", () => {
    expect(loadFilterState(tempStore(), "/repo")).toEqual({
      state: { views: [], active: null },
      error: null,
    });
  });

  test("a corrupt store degrades to no views but names the file", () => {
    const file = tempStore();
    saveFilterState(file, "/repo", { views: [], active: null });
    writeFileSync(file, "{not json");

    const { state, error } = loadFilterState(file, "/repo");
    expect(state.views).toEqual([]);
    expect(error).toContain(file);
  });

  test("a workspace with no saved entry is not an error", () => {
    const file = tempStore();
    saveFilterState(file, "/one", { views: [view({ name: "one" })], active: 0 });

    expect(loadFilterState(file, "/other")).toEqual({ state: { views: [], active: null }, error: null });
  });

  // The tabs replaced the ⇧f park switch, so a store written before them still
  // loads — its views and active tab are honoured and the dead flag is dropped.
  test("a legacy enabled flag is accepted and dropped", () => {
    const file = tempStore();
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, JSON.stringify({ "/repo": { views: [{ name: "x", mode: "and", terms: {} }], active: 0, enabled: false } }));

    expect(loadFilterState(file, "/repo")).toEqual({ state: { views: [view({ name: "x" })], active: 0 }, error: null });
  });

  // TEN-003: a value we did not expect is reported, never coerced into a
  // "sensible" default that would silently change what a saved view means.
  const rejects = (entry: unknown, expected: string) => {
    const file = tempStore();
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, JSON.stringify({ "/repo": entry }));

    const { state, error } = loadFilterState(file, "/repo");
    expect(state).toEqual({ views: [], active: null });
    expect(error).toContain(expected);
  };

  test("an unknown mode is rejected rather than defaulted to AND", () => {
    rejects({ active: null, views: [{ name: "x", mode: "nonsense", terms: {} }] }, 'mode must be "and" or "or"');
  });

  test("an unknown term value is rejected rather than dropped", () => {
    rejects({ active: null, views: [{ name: "x", mode: "and", terms: { a: "maybe" } }] }, '"include" or "exclude"');
  });

  test("an out-of-range active tab is rejected rather than nulled", () => {
    rejects({ active: 7, views: [] }, "must be null or an index into views");
  });

  test("a missing active tab is rejected rather than assumed to be ALL", () => {
    rejects({ views: [] }, "must be null or an index into views");
  });

  test("a non-string name and unexpected keys are both rejected", () => {
    rejects({ active: null, views: [{ name: 7, mode: "and", terms: {} }] }, "name must be a string");
    rejects({ active: null, views: [{ name: "x", mode: "and", terms: {}, colour: "red" }] }, "unexpected keys: colour");
  });

  test("a store that failed to read is never overwritten — other workspaces survive", () => {
    const file = tempStore();
    saveFilterState(file, "/one", { views: [view({ name: "one" })], active: 0 });
    const intact = readFileSync(file, "utf8");
    writeFileSync(file, "{not json");

    const error = saveFilterState(file, "/two", { views: [view({ name: "two" })], active: 0 });
    expect(error).toContain("refusing to overwrite");
    // The unreadable bytes are left exactly as found rather than replaced by a
    // store carrying only /two — that rewrite would destroy /one's saved views.
    expect(readFileSync(file, "utf8")).toBe("{not json");
    expect(intact).toContain("one");
  });
});
