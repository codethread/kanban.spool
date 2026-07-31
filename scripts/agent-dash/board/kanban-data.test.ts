import { describe, expect, test } from "bun:test";
import {
  activeBoardCards,
  activeTasks,
  loadCardDetails,
  type BoardCard,
  type BoardSnapshot,
} from "./kanban-data";

const card = (id: string, updated: string): BoardCard => ({
  id,
  state: "active",
  created_at: "2026-01-01 00:00:00",
  updated_at: updated,
});

describe("kanban dashboard data", () => {
  test("active polling flattens the board snapshot without detail reads", () => {
    const board: BoardSnapshot = {
      epics: [card("epic", "1")],
      claimed: [card("claimed", "2")],
      in_review: [card("review", "3")],
      pending: [card("pending", "4")],
      refinement: [card("refinement", "5")],
      "unknown-lane": [card("unknown", "6")],
    };

    expect(activeBoardCards(board).map(({ id }) => id)).toEqual([
      "epic",
      "claimed",
      "review",
      "pending",
      "refinement",
      "unknown",
    ]);
  });

  test("active mode hides closed tasks while keeping authoritative statuses", () => {
    expect(activeTasks([
      { id: "ready", title: "Ready", state: "active", status: "ready" },
      { id: "done", title: "Done", state: "closed", status: "closed" },
    ])).toEqual([
      { id: "ready", title: "Ready", state: "active", status: "ready" },
    ]);
  });

  test("all-mode cards retain direct epic membership and closed outcomes", () => {
    const board: BoardSnapshot = {
      epics: [],
      claimed: [],
      in_review: [],
      pending: [],
      refinement: [],
      cards: [
        { ...card("epic", "1"), type: "epic" },
        {
          ...card("feature", "2"),
          state: "closed",
          type: "feature",
          epic: "epic",
          outcome: "done",
        },
      ],
    };

    expect(board.cards?.[1]).toMatchObject({
      id: "feature",
      state: "closed",
      epic: "epic",
      outcome: "done",
    });
  });

  test("one failed detail read does not discard successful card data", async () => {
    const result = await loadCardDetails(
      ["good", "bad"],
      new Map(),
      new Map(),
      async (id) => {
        if (id === "bad") throw new Error("tracker projection failed");
        return {
          card: { ...card(id, "2"), attributes: { body: "Full detail" } },
          tasks: [{ id: "task", title: "Task", state: "active", status: "ready" }],
        };
      },
    );

    expect(result.cardCache.get("good")?.attributes).toEqual({ body: "Full detail" });
    expect(result.taskCache.get("good")?.[0]?.status).toBe("ready");
    expect(result.taskFailures.get("bad")).toBe("tracker projection failed");
  });
});
