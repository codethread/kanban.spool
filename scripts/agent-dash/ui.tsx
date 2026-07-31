// Shared presentation kit for the dashboard: text-fitting helpers, the
// header/failure chrome, the list windowing/footer primitives, and the
// strand-generic DetailView. The view module renders over these; its columns and
// colour maps live in its own file.

import React from "react";
import { Box, Text } from "ink";
import cliTruncate from "cli-truncate";
import stringWidth from "string-width";
import wrapAnsi from "wrap-ansi";
import { opts, workspaceRoot, type DetailRow } from "./data";

// cliTruncate reserves a column for its ellipsis, so at w=1 it drops even a
// single fitting glyph to "…"; short-circuit anything that already fits.
export const clip = (s: string, w: number): string =>
  w <= 0 ? "" : stringWidth(s) <= w ? s : cliTruncate(s, w, { truncationCharacter: "…" });
export const pad = (s: string, w: number): string => {
  const t = clip(s, w);
  return t + " ".repeat(Math.max(0, w - stringWidth(t)));
};
export const oneLine = (s: string): string => s.replace(/\s+/g, " ").trim();

// A table row assembled within a width budget. Cells (columns and the literal
// gaps between them) are laid out left to right, each clipped to whatever budget
// remains, so the joined line can never exceed `width`. This is the hard
// guarantee that no rendered row wraps at any terminal size — a wrapped row would
// add lines the pinned-height frame math (windowRows/detailViewport) does not
// count, clipping or corrupting the frame. Per-cell styling survives the clip;
// row-level `inverse`/`bold` wrap the whole line.
export type Cell = { text: string; color?: string; dimColor?: boolean; bold?: boolean; inverse?: boolean };

export function TableRow({ cells, width, inverse, bold }: { cells: Cell[]; width: number; inverse?: boolean; bold?: boolean }) {
  const out: React.ReactNode[] = [];
  let used = 0;
  for (let i = 0; i < cells.length && used < width; i++) {
    const c = cells[i]!;
    const t = clip(c.text, width - used);
    if (t === "") continue;
    used += stringWidth(t);
    out.push(
      <Text key={i} color={c.color} dimColor={c.dimColor} bold={c.bold} inverse={c.inverse}>
        {t}
      </Text>,
    );
  }
  return (
    <Text inverse={inverse} bold={bold}>
      {out}
    </Text>
  );
}

export function age(from: Date | undefined, now: Date): string {
  if (!from) return "-";
  const s = Math.max(0, Math.floor((now.getTime() - from.getTime()) / 1000));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h${m % 60}m`;
  return `${Math.floor(h / 24)}d${h % 24}h`;
}

export function useTerminalSize() {
  const get = () => ({ cols: process.stdout.columns || 120, rows: process.stdout.rows || 40 });
  const [size, setSize] = React.useState(get);
  React.useEffect(() => {
    const onResize = () => setSize(get());
    process.stdout.on("resize", onResize);
    return () => {
      process.stdout.off("resize", onResize);
    };
  }, []);
  return size;
}

// Clipped to a single line: a wrapped header would add rows the layout math
// (which pins the frame to the terminal height) does not account for. A transient
// `flash` (a y-copy result) takes over the info half in green so the confirmation
// lands where the eye already is, without stealing a layout row.
const TITLE = "kanban";

export function Header({ all, noun, refreshedAt, cols, flash }: { all: boolean; noun: string; refreshedAt: Date; cols: number; flash?: string | null }) {
  const info = ` ${workspaceRoot} · ${all ? "all" : "active"} ${noun} · every ${opts.interval}s · ${refreshedAt.toLocaleTimeString()}`;
  const tail = Math.max(0, cols - TITLE.length);
  return (
    <Text>
      <Text bold>{TITLE}</Text>
      {flash ? <Text color="green">{clip(` ${flash}`, tail)}</Text> : <Text dimColor>{clip(info, tail)}</Text>}
    </Text>
  );
}

// Lines are clipped to cols so a long strand error can't wrap past the pinned
// frame height and corrupt it. cols defaults wide for non-fullscreen frames.
export function Failure({ failure, cols = 120 }: { failure: string; cols?: number }) {
  return (
    <Box flexDirection="column" marginTop={1}>
      <Text color="red" bold>
        {clip(`strand poll failed — retrying every ${opts.interval}s`, cols)}
      </Text>
      {failure.split("\n").slice(0, 6).map((l, i) => (
        <Text key={i} color="red">
          {clip("  " + l, cols)}
        </Text>
      ))}
    </Box>
  );
}

export const fitCol = (name: string, values: string[], cap: number) =>
  Math.min(cap, Math.max(name.length, ...values.map((v) => stringWidth(v))));

// ── layout ───────────────────────────────────────────────────────────────────
// The interactive frame is pinned to the full terminal height (app.tsx), so a
// view may only spend as many scrolling rows as remain after its fixed chrome.
// Overshooting overflows the pinned root and corrupts the frame, so both
// viewports derive from one accounting rather than scattered magic numbers.
//
//   SHELL   header + tab strip + the content wrapper's top margin (app.tsx)
//   LIST    the column header + the footer's top margin; the footer's own text
//           rows vary with how far its hint wraps, so callers pass that count in
//           (hintRows) rather than it being baked in here.
//   DETAIL  the id/title line, the meta line, the attribute block's top margin,
//           and the footer (its own top margin + text)
//   SLACK   one row left unwritten so a full frame never lands on the terminal's
//           last cell and nudges an autoscroll.
const CHROME = { shell: 3, list: 2, detail: 5, slack: 1 };
export const listViewport = (termRows: number, footerRows = 1) =>
  Math.max(3, termRows - CHROME.shell - CHROME.list - footerRows - CHROME.slack);
export const detailViewport = (termRows: number) => Math.max(3, termRows - CHROME.shell - CHROME.detail - CHROME.slack);

// Space held for the scroll counter ListFooter appends, so the footer's height is
// a function of the hint and width alone. Reserving it up front is what keeps the
// footer from growing a line mid-scroll — which would resize the viewport under
// the selection every time the counter appeared or wrapped.
const COUNTER_RESERVE = " · 0000↑ 0000↓ of 0000";

// How many rows a hint occupies at this width — the number every caller must
// agree on: the paging math to size its viewport, ListFooter to fill exactly that
// many lines. One row minimum, however narrow the terminal.
export const hintRows = (hint: string, cols: number): number =>
  cols <= 0 ? 1 : wrapAnsi(hint + COUNTER_RESERVE, cols, { hard: true }).split("\n").length;

// The ⌃u/⌃d jump: half a viewport, vim's half-page scroll, floored to at least one
// row so a tiny terminal still moves.
export const listPage = (termRows: number, footerRows = 1) => Math.max(1, Math.floor(listViewport(termRows, footerRows) / 2));
export const detailPage = (termRows: number) => Math.max(1, Math.floor(detailViewport(termRows) / 2));

// The visible slice centred on the selection, plus off-screen counts for the
// scroll hint.
export function windowRows<T>(rows: T[], selected: number, interactive: boolean, termRows: number, footerRows = 1) {
  const viewport = interactive ? listViewport(termRows, footerRows) : rows.length;
  const start = Math.max(0, Math.min(selected - Math.floor(viewport / 2), rows.length - viewport));
  const visible = rows.slice(start, start + viewport);
  return { start, visible, below: rows.length - start - visible.length };
}

// The hint wraps rather than truncating, so every binding stays readable on a
// narrow terminal. Height is always exactly hintRows(hint, cols) — short renders
// are padded with blank lines — because the paging math sized the viewport
// against that same number, and a footer even one line taller would push the
// frame past its pinned height.
export function ListFooter({ hint, start, below, total, cols }: { hint: string; start: number; below: number; total: number; cols: number }) {
  const text = hint + (start || below ? ` · ${start}↑ ${below}↓ of ${total}` : "");
  const height = hintRows(hint, cols);
  const lines = wrapAnsi(text, Math.max(1, cols), { hard: true }).split("\n").slice(0, height);
  while (lines.length < height) lines.push("");
  return (
    <Box marginTop={1} flexDirection="column">
      {lines.map((line, i) => (
        <Text key={i} dimColor>
          {clip(line, cols)}
        </Text>
      ))}
    </Box>
  );
}

// The wrapped attribute lines a detail view scrolls through: one entry per
// display row, key set only on the first line of each attribute. keyw/valw are
// returned so the renderer lays out the same columns the wrap was measured
// against.
function detailLines(row: DetailRow, cols: number) {
  const keys = Object.keys(row.attrs).sort();
  // keyw is capped by the terminal so the key column plus its 2-space gap always
  // leaves at least one cell for the value; valw then takes whatever remains,
  // however small, rather than a hard floor that could push the line past cols.
  const keyw = Math.max(0, Math.min(28, cols - 3, Math.max(4, ...keys.map((k) => k.length))));
  const valw = Math.max(1, cols - keyw - 2);
  const lines: { key: string | null; text: string }[] = [];
  for (const k of keys) {
    const v = row.attrs[k];
    const text = typeof v === "string" ? v : JSON.stringify(v);
    const wrapped = wrapAnsi(text, valw, { hard: true }).split("\n");
    lines.push({ key: k, text: wrapped[0] ?? "" });
    for (const cont of wrapped.slice(1)) lines.push({ key: null, text: cont });
  }
  return { keyw, lines };
}

// The furthest a detail can scroll, derived the same way DetailView renders so
// input handlers can clamp j/G without a render-time ref shared between views.
export function detailMaxScroll(row: DetailRow | undefined, cols: number, termRows: number): number {
  if (!row) return 0;
  return Math.max(0, detailLines(row, cols).lines.length - detailViewport(termRows));
}

export function DetailView({
  row,
  scroll,
  cols,
  termRows,
}: {
  row: DetailRow | undefined;
  scroll: number;
  cols: number;
  termRows: number;
}) {
  if (!row) {
    return <Text dimColor>{clip("no longer listed — esc to go back", cols)}</Text>;
  }
  const { keyw, lines } = detailLines(row, cols);

  const viewport = detailViewport(termRows);
  const maxScroll = Math.max(0, lines.length - viewport);
  const from = Math.min(scroll, maxScroll);
  const visible = lines.slice(from, from + viewport);

  const meta = [
    `state ${row.state}`,
    `branch ${row.branch}`,
    `created ${row.createdAt}`,
    `updated ${row.updatedAt}`,
  ].join(" · ");

  return (
    <Box flexDirection="column">
      <Text>
        <Text bold>{clip(row.id, cols)}</Text>  {clip(row.title ?? "", Math.max(0, cols - stringWidth(clip(row.id, cols)) - 3))}
      </Text>
      <Text dimColor>{clip(meta, cols)}</Text>
      <Box flexDirection="column" marginTop={1}>
        {visible.map((l, i) => (
          <Text key={from + i}>
            {l.key !== null ? <Text color="cyan">{pad(l.key, keyw)}</Text> : " ".repeat(keyw)}
            {"  "}
            {l.text}
          </Text>
        ))}
      </Box>
      <Box marginTop={1}>
        <Text dimColor>{clip(`↑↓/jk scroll · ⌃d/⌃u page · ⌃g open · y copy · ⇥/⇧⇥ filter tab · esc back · q quit${maxScroll > 0 ? ` · ${from}↑ ${maxScroll - from}↓` : ""}`, cols)}</Text>
      </Box>
    </Box>
  );
}
