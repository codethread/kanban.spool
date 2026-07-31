// Pure boundary-shape transforms for the kanban dashboard. Kept separate from
// the Ink tab so Bun tests exercise board/task/graph behavior without starting
// the dashboard shell or touching the live coordination workspace. BoardCard
// and BoardSnapshot mirror `kanban board` from kanban v14
// (603fa7b88808f6bbdf6a5d70aa170b3c7e48e9a3); needs-review is deliberately
// omitted because it is an aggregate, not a card collection.

export type TaskChild = {
  id: string;
  title: string;
  state: string;
  status: string;
  owner?: string;
};

export type BoardCard = {
  id: string;
  title?: string;
  state: string;
  attributes?: Record<string, unknown>;
  created_at: string;
  updated_at?: string;
  type?: string;
  epic?: string;
  lane?: string;
  owner?: string;
  priority?: string;
  branch?: string;
  outcome?: string;
  // Sorted label slugs, present only on cards that carry any (the spool omits the
  // key rather than emitting an empty vector). Shipped on every card the board
  // returns, active and closed alike, so label filtering needs no second read.
  labels?: string[];
};

export type BoardSnapshot = {
  epics: BoardCard[];
  refinement: BoardCard[];
  pending: BoardCard[];
  claimed: BoardCard[];
  in_review: BoardCard[];
  "unknown-lane"?: BoardCard[];
  cards?: BoardCard[];
};

export type CardView = { card: BoardCard; tasks: TaskChild[] };

export const activeBoardCards = (board: BoardSnapshot): BoardCard[] => [
  ...board.epics,
  ...board.claimed,
  ...board.in_review,
  ...board.pending,
  ...board.refinement,
  ...(board["unknown-lane"] ?? []),
];

export const activeTasks = (tasks: TaskChild[]): TaskChild[] =>
  tasks.filter((task) => task.state !== "closed");

export async function loadCardDetails(
  ids: string[],
  cachedTasks: Map<string, TaskChild[]>,
  cachedCards: Map<string, BoardCard>,
  load: (id: string) => Promise<CardView>,
): Promise<{
  taskCache: Map<string, TaskChild[]>;
  cardCache: Map<string, BoardCard>;
  taskFailures: Map<string, string>;
}> {
  const taskCache = new Map(cachedTasks);
  const cardCache = new Map(cachedCards);
  const taskFailures = new Map<string, string>();
  const views = await Promise.allSettled(ids.map(async (id) => [id, await load(id)] as const));
  views.forEach((result, index) => {
    const id = ids[index]!;
    if (result.status === "fulfilled") {
      taskCache.set(id, result.value[1].tasks ?? []);
      cardCache.set(id, result.value[1].card);
    } else {
      taskFailures.set(id, result.reason instanceof Error ? result.reason.message : String(result.reason));
    }
  });
  return { taskCache, cardCache, taskFailures };
}
