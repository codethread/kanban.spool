// The dashboard shell: the view contract, the reusable list+detail state kit,
// the tab strip, the polling loop, the single keyboard dispatch, and the
// fullscreen/raw-mode entry point. The shell hosts one view module (the board)
// and owns no concrete row type; it never runs a second useInput — every
// view-local key is routed to that module.
//
// The tab strip is the view's own, not the shell's: the module reports the
// labels and which one is current (Strip), and the shell only draws them. On the
// board those tabs are the saved filter views, so ⇥ is a view-local key like any
// other.

import { appendFileSync } from "node:fs";
import React, { useCallback, useEffect, useRef, useState } from "react";
import { Box, render, useApp, useInput, useStdin, type Key } from "ink";
import { copyToClipboard, editorArgv, editorFileFor, opts, workspaceRoot, type DetailRow } from "./data";
import { Header, TableRow, useTerminalSize, type Cell } from "./ui";

// ── reusable list+detail view state ──────────────────────────────────────────
// One scrollable list with an optional attribute detail. Selection is anchored
// to a stable per-row key (id, or a tree path) so it survives refreshes that
// reorder or drop rows.

export type ListState = {
  selected: number;
  anchor: string | null;
  view: "list" | "detail";
  detailScroll: number;
};

export const emptyListState = (): ListState => ({ selected: 0, anchor: null, view: "list", detailScroll: 0 });

// Re-anchor the selection after a fetch: follow the anchored key; if it vanished,
// hold the old index clamped into the new list.
export function followSelection<R>(s: ListState, rows: R[], keyOf: (r: R) => string): ListState {
  if (rows.length === 0) return { ...s, selected: 0, anchor: null };
  const byKey = s.anchor ? rows.findIndex((r) => keyOf(r) === s.anchor) : -1;
  const selected = byKey >= 0 ? byKey : Math.max(0, Math.min(s.selected, rows.length - 1));
  return { ...s, selected, anchor: keyOf(rows[selected]!) };
}

// List-mode movement (↑↓/jk, ⌃u/⌃d half-page, g/G). Returns the next state, or
// null when the key is not a movement command so callers can layer enter/refresh
// on top. `page` is the ⌃u/⌃d jump distance (half a viewport, see listPage).
export function reduceListKeys<R>(s: ListState, input: string, key: Key, rows: R[], keyOf: (r: R) => string, page = 1): ListState | null {
  if (rows.length === 0) return null;
  const go = (raw: number): ListState => {
    const next = Math.max(0, Math.min(rows.length - 1, raw));
    return { ...s, selected: next, anchor: keyOf(rows[next]!) };
  };
  if (key.ctrl && input === "u") return go(s.selected - page);
  if (key.ctrl && input === "d") return go(s.selected + page);
  if (key.upArrow || input === "k") return go(s.selected - 1);
  if (key.downArrow || input === "j") return go(s.selected + 1);
  if (input === "g") return go(0);
  if (input === "G") return go(rows.length - 1);
  return null;
}

// Detail-mode scroll (↑↓/jk, ⌃u/⌃d half-page, g/G) and back (esc/h/←). Returns
// the next scroll offset, "back" to leave the detail, or null when the key is not
// handled. The offset can be 0, so callers must test `!== null`. `page` is the
// ⌃u/⌃d jump distance (half a viewport, see detailPage).
export function reduceScrollKeys(scroll: number, input: string, key: Key, maxScroll: number, page = 1): number | "back" | null {
  if (key.ctrl && input === "u") return Math.max(0, scroll - page);
  if (key.ctrl && input === "d") return Math.min(maxScroll, scroll + page);
  if (key.upArrow || input === "k") return Math.max(0, scroll - 1);
  if (key.downArrow || input === "j") return Math.min(maxScroll, scroll + 1);
  if (input === "g") return 0;
  if (input === "G") return maxScroll;
  if (key.escape || key.leftArrow || input === "h") return "back";
  return null;
}

// ── the view contract ────────────────────────────────────────────────────────
// The hosted module owns its view state V (the shell persists it across polls),
// fetches into it, reduces its own keys, and renders list/detail/failure. The
// shell owns the header/tab-strip chrome, the all/active axis, quit, ⌃g/y, and
// the polling cadence. defineDash erases V at the single unsafe boundary so the
// shell can hold the module without knowing its state shape.

export type RenderCtx = { cols: number; termRows: number; interactive: boolean; all: boolean };

export type KeyCtx<V> = {
  input: string;
  key: Key;
  cols: number;
  termRows: number;
  setV: (next: V | ((v: V) => V)) => void;
  refresh: () => void;
};

// The tab strip as the shell draws it: the labels left to right, and the index of
// the one in force. What a tab *means* is the module's business.
export type Strip = { labels: string[]; active: number };

export type Dash<V> = {
  id: string;
  // What the header counts ("active cards"): the module's own noun for its rows.
  noun: string;
  init: () => V;
  // Fetch under the current all/active axis, then return a pure updater the shell
  // applies against the *latest* V — never the pre-fetch snapshot — so a slow poll
  // landing after the user has scrolled or switched views folds in the new rows
  // without clobbering that interim navigation. Errors are caught into the updater
  // so a poll failure renders instead of throwing. `v` selects which data to fetch;
  // the updater re-checks the latest V and drops itself if that choice changed
  // mid-flight.
  refresh: (v: V, all: boolean) => Promise<(latest: V) => V>;
  // A change re-runs the poll immediately (expanding a card needs its tasks).
  fetchKey: (v: V) => string;
  // View-local keys: movement, enter, esc, scroll, tab-strip navigation, and any
  // module-private keys.
  onKey: (v: V, ctx: KeyCtx<V>) => V;
  // A detail is open: the shell leaves the all/active axis inert.
  inDetail: (v: V) => boolean;
  // The module is reading raw text (a filter name being typed) and every key
  // belongs to it — including the shell's own q/a/⌃g/y, which would otherwise quit
  // mid-word. Absent means "never captures".
  capturesInput?: (v: V) => boolean;
  // The strand under the cursor in the module's current view, or null when nothing
  // is focused. The shell opens it in $EDITOR on ⌃g.
  editTarget: (v: V) => DetailRow | null;
  // The strand id under the cursor, or null when nothing is focused. Broader than
  // editTarget — a bare tree/task row has an id even where no full DetailRow is in
  // hand — so the shell can copy it on y.
  copyId: (v: V) => string | null;
  // The all/active axis applies in the module's current view.
  allApplies: (v: V) => boolean;
  strip: (v: V) => Strip;
  render: (v: V, ctx: RenderCtx) => React.ReactElement;
};

export function defineDash<V>(dash: Dash<V>): Dash<unknown> {
  return dash as unknown as Dash<unknown>;
}

// ── the shell ────────────────────────────────────────────────────────────────

function TabBar({ strip, cols }: { strip: Strip; cols: number }) {
  const cells: Cell[] = [];
  strip.labels.forEach((label, i) => {
    if (i > 0) cells.push({ text: " | ", dimColor: true });
    cells.push({ text: ` ${label} `, bold: i === strip.active, inverse: i === strip.active });
  });
  return <TableRow cells={cells} width={cols} />;
}

// The envelope the shell owns: the all/active axis, the last refresh time, and
// the module's opaque view state.
type Env = { all: boolean; refreshedAt: Date; v: unknown };

function App({
  dash,
  fullscreen,
  preloaded,
  frame,
}: {
  dash: Dash<unknown>;
  fullscreen: boolean;
  preloaded: unknown | undefined;
  frame: { clear?: () => void };
}) {
  const { exit } = useApp();
  const { isRawModeSupported, setRawMode } = useStdin();
  const { cols, rows: termRows } = useTerminalSize();
  // Bumped after an $EDITOR round-trip to force Ink to repaint the clobbered frame.
  const [, setRedraw] = useState(0);
  // A transient one-line status (a y-copy result), shown in the header and cleared
  // after a moment. The timer is held in a ref so a fresh flash resets it rather
  // than leaving an older timer to blank the newer message.
  const [flash, setFlash] = useState<string | null>(null);
  const flashTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const showFlash = useCallback((msg: string) => {
    setFlash(msg);
    if (flashTimer.current) clearTimeout(flashTimer.current);
    flashTimer.current = setTimeout(() => setFlash(null), 2000);
  }, []);
  useEffect(() => () => void (flashTimer.current && clearTimeout(flashTimer.current)), []);
  const [env, setEnv] = useState<Env>(() => ({
    all: opts.all,
    refreshedAt: new Date(),
    v: preloaded !== undefined ? preloaded : dash.init(),
  }));
  // Mirror for callbacks/timers that must read the latest env without
  // re-subscribing. refreshing guards against overlapping fetches.
  const envRef = useRef(env);
  envRef.current = env;
  const refreshing = useRef(false);
  // A refresh asked for while one is already in flight (an all toggle during a
  // slow poll) is coalesced here and re-run against the then-current v/all when
  // the in-flight fetch settles, so the new view can't be left stale.
  const pendingRefresh = useRef(false);

  const setV = useCallback((next: unknown | ((v: unknown) => unknown)) => {
    setEnv((e) => ({ ...e, v: typeof next === "function" ? (next as (v: unknown) => unknown)(e.v) : next }));
  }, []);

  const refresh = useCallback(
    async (allOverride?: boolean) => {
      // No overlapping fetches: a request landing mid-flight is queued, not run,
      // then replayed below with the latest v/all once this fetch settles.
      if (refreshing.current) {
        pendingRefresh.current = true;
        return;
      }
      refreshing.current = true;
      const all = allOverride ?? envRef.current.all;
      try {
        const apply = await dash.refresh(envRef.current.v, all);
        setEnv((e) => ({ ...e, refreshedAt: new Date(), v: apply(e.v) }));
      } finally {
        refreshing.current = false;
        if (pendingRefresh.current) {
          pendingRefresh.current = false;
          void refresh();
        }
        if (opts.once) exit();
      }
    },
    [dash, exit],
  );

  // Suspend the dashboard, hand the editor the controlling tty, then restore.
  // spawnSync blocks the input loop so no poll runs meanwhile; on return we drop
  // Ink's cached frame (frame.clear) and bump redraw so the alt screen repaints
  // from scratch rather than diffing against a frame the editor overwrote.
  const openInEditor = useCallback(
    (row: DetailRow) => {
      let file: string;
      try {
        file = editorFileFor(row);
      } catch (error) {
        showFlash(`editor file failed · ${error instanceof Error ? error.message : String(error)}`);
        return;
      }
      setRawMode?.(false);
      if (fullscreen) process.stdout.write("\x1b[?1049l");
      let failure: string | null = null;
      try {
        const argv = [...editorArgv(), file];
        const result = Bun.spawnSync(argv, { cwd: workspaceRoot, stdin: "inherit", stdout: "inherit", stderr: "inherit" });
        if (result.exitCode !== 0) failure = `editor exited ${result.exitCode} · ${argv.join(" ")}`;
      } catch (error) {
        failure = `editor failed · ${error instanceof Error ? error.message : String(error)}`;
      }
      if (fullscreen) process.stdout.write("\x1b[?1049h\x1b[2J\x1b[H");
      setRawMode?.(true);
      frame.clear?.();
      setRedraw((n) => n + 1);
      if (failure) showFlash(failure);
    },
    [fullscreen, frame, setRawMode, showFlash],
  );

  // Copy the id under the cursor to a clipboard, flashing the result. The copy is
  // best-effort across tmux/OS tools (copyToClipboard); a world with none reachable
  // flashes the failure with the id still shown so it can be read off the screen.
  const copyCursorId = useCallback(
    async (id: string) => {
      const how = await copyToClipboard(id);
      showFlash(how ? `copied ${id} · ${how}` : `no clipboard — ${id}`);
    },
    [showFlash],
  );

  // Re-poll on start and whenever the fetch key changes (an expanded card needs
  // its tasks fetched). Toggling all/active refetches directly from its key
  // handler, so it is intentionally not a dependency here.
  const fetchKey = dash.fetchKey(env.v);
  useEffect(() => {
    if (opts.once) {
      exit();
      return;
    }
    void refresh();
    const timer = setInterval(() => void refresh(), opts.interval * 1000);
    return () => clearInterval(timer);
  }, [fetchKey, refresh, exit]);

  useInput(
    (input, key) => {
      const v = envRef.current.v;
      if (process.env.SHUTTLE_DASH_DEBUG) {
        appendFileSync(
          process.env.SHUTTLE_DASH_DEBUG,
          `${JSON.stringify({ input, ret: key.return, esc: key.escape, view: dash.id, inDetail: dash.inDetail(v) })}\n`,
        );
      }
      // A capturing view owns the whole keyboard: every global binding is skipped
      // so no keystroke meant for a text field can quit mid-word.
      if (dash.capturesInput?.(v) !== true) {
        if (input === "q") {
          exit();
          return;
        }
        if (input === "a" && !dash.inDetail(v) && dash.allApplies(v)) {
          const newAll = !envRef.current.all;
          setEnv((e) => ({ ...e, all: newAll }));
          void refresh(newAll);
          return;
        }
        if (key.ctrl && input === "g") {
          const target = dash.editTarget(v);
          if (target) openInEditor(target);
          return;
        }
        if (input === "y") {
          const id = dash.copyId(v);
          if (id) void copyCursorId(id);
          return;
        }
      }
      const ctx: KeyCtx<unknown> = {
        input,
        key,
        cols,
        termRows,
        setV,
        refresh: () => void refresh(),
      };
      // Reduce against the latest V so keys arriving faster than React commits
      // don't drop through a stale snapshot. onKey's only side effects (refresh,
      // async detail fetch) suspend on an await before any setState, so running
      // them inside the updater is safe.
      setV((prev: unknown) => dash.onKey(prev, ctx));
    },
    { isActive: isRawModeSupported === true && !opts.once },
  );

  const interactive = isRawModeSupported === true && !opts.once;
  const rctx: RenderCtx = { cols, termRows, interactive, all: env.all };
  // Pin the frame to the full terminal in the alt screen: a constant-height root
  // makes every frame terminal-tall so a shorter frame overwrites a taller one
  // (list ⇄ detail, tab switches) instead of leaving stale lines. overflow hidden
  // keeps a miscounted view from scrolling the alt screen.
  return (
    <Box flexDirection="column" height={fullscreen ? termRows : undefined} overflow={fullscreen ? "hidden" : undefined}>
      <Header all={env.all} noun={dash.noun} refreshedAt={env.refreshedAt} cols={cols} flash={flash} />
      <TabBar strip={dash.strip(env.v)} cols={cols} />
      <Box marginTop={1} flexDirection="column">
        {dash.render(env.v, rctx)}
      </Box>
    </Box>
  );
}

export async function runApp(dash: Dash<unknown>) {
  const fullscreen = process.stdout.isTTY === true && process.stdin.isTTY === true && !opts.once;

  // Non-interactive frames pre-fetch so the printed frame is real data. The
  // interactive path must NOT await spawns before render: under Bun, subprocess
  // activity before Ink attaches to stdin leaves the pty in canonical mode and
  // setRawMode never takes effect — keys then arrive line-buffered and dead.
  let preloaded: unknown | undefined;
  if (!fullscreen) {
    const apply = await dash.refresh(dash.init(), opts.all);
    preloaded = apply(dash.init());
  }

  // Enter the alt screen and clear+home before Ink renders, so the frame starts
  // at row 0 rather than the shell's old cursor row and no scrollback shows
  // through. Leaving the alt screen on exit restores the shell buffer verbatim.
  if (fullscreen) process.stdout.write("\x1b[?1049h\x1b[2J\x1b[H");
  // Leaving runs on every exit path. Writing to a terminal that has already been
  // destroyed fails with EIO or EPIPE and there is nothing left to restore, so
  // only that failure is tolerated — any other write error is a real one.
  const leaveAltScreen = () => {
    if (!fullscreen) return;
    try {
      process.stdout.write("\x1b[?1049l");
    } catch (err) {
      const code = (err as NodeJS.ErrnoException).code;
      if (code !== "EIO" && code !== "EPIPE") throw err;
    }
  };
  // Handed to App so an $EDITOR round-trip can drop Ink's cached frame and force a
  // full repaint of the alt screen the editor clobbered.
  const frame: { clear?: () => void } = {};
  try {
    const app = render(<App dash={dash} fullscreen={fullscreen} preloaded={preloaded} frame={frame} />);
    frame.clear = app.clear;

    // Losing the terminal under a live dash — tmux kill-session, a closed window,
    // a dropped ssh — revokes stdin and stdout. Ink's signal handling unmounts but
    // does not terminate the process under Bun, and Bun's event loop then spins on
    // the revoked descriptor at 100% CPU forever, orphaned to init. So own the
    // teardown and exit on whichever the terminal's death delivers: the hangup, or
    // stdin ending. Interactive only — a piped stdin ends normally, and exiting on
    // that would cut the single printed frame short.
    //
    // The status is non-zero because losing the terminal is not a clean quit, and
    // 128+SIGHUP is what a shell reports for a hangup, so a supervisor can tell the
    // two apart. Teardown that fails for any reason other than the already-dead
    // terminal says so on the way out; exiting is unconditional, since a throw here
    // would leave behind exactly the spinning process this handler exists to end.
    if (fullscreen) {
      const abandon = (status: number) => () => {
        try {
          app.unmount();
          leaveAltScreen();
        } catch (err) {
          try {
            process.stderr.write(`dash: teardown after terminal loss failed: ${err}\n`);
          } catch {
            // stderr died with the terminal; the exit status is the only signal left.
          }
        }
        process.exit(status);
      };
      process.once("SIGHUP", abandon(129));
      process.stdin.once("end", abandon(1));
      process.stdin.once("close", abandon(1));
    }

    await app.waitUntilExit();
  } finally {
    leaveAltScreen();
  }
}
