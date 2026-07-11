#!/usr/bin/env bun
// Render a kanban feature/epic card and its whole parent-of subtree to a single
// self-contained HTML file: an overall progress rollup plus a per-child
// breakdown of what work is involved and how far along it is. Data comes from
// the `strand kanban-export <id>` op (registered by the kanban spool's install!),
// which bundles the card subtree, its parent-of hierarchy, and the depends-on
// edges internal to that subtree in one JSON call. Everything else here is
// pure presentation, so the tool stays a single dependency-free file.
//
// Usage: bun scripts/kanban-export/kanban-export.ts <card-id> [--workspace dir] [--out file.html] [--open]
// Default output is /tmp/kanban-export/kanban-<id>.html.

import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";

type Strand = {
  id: string;
  title?: string;
  state: string;
  attributes: Record<string, unknown>;
  created_at: string;
  updated_at: string;
};
type Edge = { from_strand_id: string; to_strand_id: string; edge_type: string };
type Export = {
  "root-id": string;
  strands: Strand[];
  "parent-of-edges": Edge[];
  "depends-on-edges": Edge[];
};

// ── CLI ──────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const opts = { id: "", workspace: "", out: "", open: false };
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === "--workspace") opts.workspace = argv[++i]!;
  else if (a === "--out") opts.out = argv[++i]!;
  else if (a === "--open") opts.open = true;
  else if (a === "--help" || a === "-h") {
    console.log("usage: bun scripts/kanban-export/kanban-export.ts <card-id> [--workspace dir] [--out file.html] [--open]");
    process.exit(0);
  } else if (a.startsWith("-")) {
    console.error(`unknown flag: ${a}`);
    process.exit(2);
  } else if (!opts.id) opts.id = a;
  else {
    console.error(`unexpected argument: ${a}`);
    process.exit(2);
  }
}
if (!opts.id) {
  console.error("a feature or epic card id is required");
  process.exit(2);
}

async function run(cmd: string[], cwd: string): Promise<{ code: number; out: string; err: string }> {
  const proc = Bun.spawn(cmd, { cwd, stdin: "ignore", stdout: "pipe", stderr: "pipe" });
  const [out, err, code] = await Promise.all([new Response(proc.stdout).text(), new Response(proc.stderr).text(), proc.exited]);
  return { code, out, err };
}

// Default to the canonical coordination world: git-common-dir points at the real
// .git even from a linked worktree, so every checkout exports the same board.
async function resolveWorkspace(): Promise<string> {
  if (opts.workspace) return resolve(opts.workspace);
  const git = await run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], process.cwd());
  if (git.code !== 0) throw new Error(`cannot resolve canonical repo root: ${git.err.trim()}`);
  return resolve(dirname(git.out.trim()), ".skein");
}

const workspace = await resolveWorkspace();
const res = await run(["strand", "--workspace", workspace, "kanban-export", opts.id], process.cwd());
if (res.code !== 0) {
  console.error((res.err || res.out).trim());
  process.exit(1);
}
const data = JSON.parse(res.out) as Export;

// ── Model ────────────────────────────────────────────────────────────────────
const str = (v: unknown, fallback = ""): string => (typeof v === "string" ? v : fallback);
const byId = new Map(data.strands.map((s) => [s.id, s]));
const childrenOf = new Map<string, string[]>();
for (const e of data["parent-of-edges"]) {
  const list = childrenOf.get(e.from_strand_id) ?? [];
  list.push(e.to_strand_id);
  childrenOf.set(e.from_strand_id, list);
}
// depends-on points dependent -> dependency: index both directions for annotations.
const blockedBy = new Map<string, string[]>();
const blocks = new Map<string, string[]>();
const push = (m: Map<string, string[]>, k: string, v: string) => m.set(k, [...(m.get(k) ?? []), v]);
for (const e of data["depends-on-edges"]) {
  push(blockedBy, e.from_strand_id, e.to_strand_id);
  push(blocks, e.to_strand_id, e.from_strand_id);
}

const kids = (id: string): Strand[] =>
  (childrenOf.get(id) ?? [])
    .map((cid) => byId.get(cid))
    .filter((s): s is Strand => !!s)
    .sort((a, b) => a.created_at.localeCompare(b.created_at));

const isCard = (s: Strand): boolean => str(s.attributes["kanban/card"]) === "true";
const isClosed = (s: Strand): boolean => s.state === "closed";
const kindOf = (s: Strand): string =>
  isCard(s) ? str(s.attributes["kanban/type"], "feature") : str(s.attributes["kind"], "item");

// Progress over every descendant of a node (excludes the node itself); a
// descendant counts as done when it is closed. Rolls the whole subtree up so an
// epic's bar reflects its features' tasks, not just the feature cards.
function rollup(id: string): { done: number; total: number } {
  let done = 0;
  let total = 0;
  for (const c of kids(id)) {
    total++;
    if (isClosed(c)) done++;
    const sub = rollup(c.id);
    done += sub.done;
    total += sub.total;
  }
  return { done, total };
}

// ── Render ───────────────────────────────────────────────────────────────────
const esc = (s: string): string => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);
const titleOf = (s: Strand): string => s.title ?? "(untitled)";

function bar(done: number, total: number): string {
  const pct = total === 0 ? 0 : Math.round((done / total) * 100);
  return `<div class="bar" title="${done}/${total} done"><div class="fill" style="width:${pct}%"></div><span class="pct">${pct}% · ${done}/${total}</span></div>`;
}

function badges(s: Strand): string {
  const out: string[] = [];
  const state = isClosed(s) ? str(s.attributes["kanban/status"], "closed") : s.state;
  out.push(`<span class="badge state-${esc(isClosed(s) ? "closed" : s.state)}">${esc(state)}</span>`);
  out.push(`<span class="badge kind">${esc(kindOf(s))}</span>`);
  const status = str(s.attributes["kanban/status"]);
  if (status && !isClosed(s)) out.push(`<span class="badge lane lane-${esc(status)}">${esc(status)}</span>`);
  const prio = str(s.attributes["kanban/priority"]);
  if (prio) out.push(`<span class="badge prio prio-${esc(prio)}">${esc(prio)}</span>`);
  const owner = str(s.attributes["owner"]);
  if (owner) out.push(`<span class="badge owner">@${esc(owner)}</span>`);
  if (str(s.attributes["workflow/hitl"]) === "true" || str(s.attributes["hitl"]) === "true")
    out.push(`<span class="badge review">human review</span>`);
  return out.join(" ");
}

function deps(id: string): string {
  const parts: string[] = [];
  const bb = (blockedBy.get(id) ?? []).map((d) => byId.get(d)).filter((s): s is Strand => !!s);
  const bl = (blocks.get(id) ?? []).map((d) => byId.get(d)).filter((s): s is Strand => !!s);
  if (bb.length) parts.push(`<div class="dep blocked">⛔ depends on ${bb.map((s) => esc(titleOf(s))).join(", ")}</div>`);
  if (bl.length) parts.push(`<div class="dep blocks">↳ blocks ${bl.map((s) => esc(titleOf(s))).join(", ")}</div>`);
  return parts.join("");
}

// A leaf work item (task/review) as a checklist row.
function leaf(s: Strand): string {
  const body = str(s.attributes["body"]);
  return `<li class="leaf ${isClosed(s) ? "done" : ""}">
    <span class="check">${isClosed(s) ? "✔" : "○"}</span>
    <div class="leaf-main">
      <div class="leaf-head"><span class="leaf-title">${esc(titleOf(s))}</span> ${badges(s)} <span class="sid">${esc(s.id)}</span></div>
      ${body ? `<div class="leaf-body">${esc(body)}</div>` : ""}
      ${deps(s.id)}
    </div>
  </li>`;
}

// A card (epic/feature) as a section: header + progress + children, recursing
// into sub-cards and grouping leaf work into a checklist.
function card(s: Strand, depth: number): string {
  const children = kids(s.id);
  const subCards = children.filter(isCard);
  const leaves = children.filter((c) => !isCard(c));
  const { done, total } = rollup(s.id);
  const body = str(s.attributes["body"]);
  return `<section class="card d${Math.min(depth, 3)}">
    <div class="card-head">
      <h${Math.min(depth + 2, 5)}>${esc(titleOf(s))} <span class="sid">${esc(s.id)}</span></h${Math.min(depth + 2, 5)}>
      <div class="meta">${badges(s)}</div>
    </div>
    ${body ? `<p class="card-body">${esc(body)}</p>` : ""}
    ${deps(s.id)}
    ${total ? bar(done, total) : `<p class="empty">no child work yet</p>`}
    ${subCards.map((c) => card(c, depth + 1)).join("")}
    ${leaves.length ? `<ul class="leaves">${leaves.map(leaf).join("")}</ul>` : ""}
  </section>`;
}

const root = byId.get(data["root-id"]);
if (!root) {
  console.error(`root strand ${data["root-id"]} missing from export payload`);
  process.exit(1);
}
const overall = rollup(root.id);
const now = new Date().toISOString().replace("T", " ").slice(0, 16);

const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(titleOf(root))} · kanban export</title>
<style>
:root { color-scheme: light dark; --bg:#fff; --fg:#1a1a1a; --muted:#666; --line:#e2e2e2; --card:#fafafa; --accent:#2563eb; --done:#16a34a; }
@media (prefers-color-scheme: dark) { :root { --bg:#15171a; --fg:#e6e6e6; --muted:#9aa0a6; --line:#2c2f33; --card:#1c1f23; --accent:#5b8dff; --done:#4ade80; } }
* { box-sizing: border-box; }
body { margin:0; font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; background:var(--bg); color:var(--fg); }
.wrap { max-width:900px; margin:0 auto; padding:2rem 1.25rem 4rem; }
header.top { border-bottom:1px solid var(--line); padding-bottom:1.25rem; margin-bottom:1.5rem; }
header.top h1 { margin:0 0 .5rem; font-size:1.6rem; }
.sub { color:var(--muted); font-size:.85rem; }
.meta { display:flex; flex-wrap:wrap; gap:.35rem; margin:.5rem 0; }
.badge { font-size:.72rem; padding:.1rem .5rem; border-radius:999px; border:1px solid var(--line); color:var(--muted); white-space:nowrap; }
.badge.state-active { color:var(--accent); border-color:var(--accent); }
.badge.state-closed { color:var(--done); border-color:var(--done); }
.badge.review { color:#a855f7; border-color:#a855f7; }
.badge.prio-p1 { color:#dc2626; border-color:#dc2626; }
.badge.prio-p2 { color:#d97706; border-color:#d97706; }
.bar { position:relative; height:1.4rem; background:var(--line); border-radius:6px; overflow:hidden; margin:.6rem 0; }
.bar .fill { position:absolute; inset:0 auto 0 0; background:var(--done); opacity:.85; }
.bar .pct { position:relative; font-size:.72rem; line-height:1.4rem; padding-left:.6rem; color:var(--fg); mix-blend-mode:difference; }
.card { border:1px solid var(--line); border-radius:10px; padding:1rem 1.1rem; margin:1rem 0; background:var(--card); }
.card.d1 { margin-left:0; } .card.d2 { background:transparent; } .card.d3 { background:transparent; border-style:dashed; }
.card-head { display:flex; flex-wrap:wrap; align-items:baseline; justify-content:space-between; gap:.5rem; }
.card-head h2,.card-head h3,.card-head h4,.card-head h5 { margin:0; font-size:1.05rem; }
.sid { font:.72rem monospace; color:var(--muted); }
.card-body { color:var(--muted); font-size:.9rem; margin:.4rem 0; }
.empty { color:var(--muted); font-size:.82rem; font-style:italic; }
ul.leaves { list-style:none; margin:.6rem 0 0; padding:0; }
li.leaf { display:flex; gap:.6rem; padding:.5rem 0; border-top:1px solid var(--line); }
li.leaf .check { color:var(--muted); }
li.leaf.done .check { color:var(--done); }
li.leaf.done .leaf-title { text-decoration:line-through; color:var(--muted); }
.leaf-head { display:flex; flex-wrap:wrap; align-items:baseline; gap:.4rem; }
.leaf-title { font-weight:600; }
.leaf-body { color:var(--muted); font-size:.85rem; margin-top:.15rem; }
.dep { font-size:.78rem; margin-top:.25rem; }
.dep.blocked { color:#dc2626; }
.dep.blocks { color:var(--muted); }
</style>
</head>
<body>
<div class="wrap">
<header class="top">
  <h1>${esc(titleOf(root))}</h1>
  <div class="meta">${badges(root)} <span class="sid">${esc(root.id)}</span></div>
  ${str(root.attributes["body"]) ? `<p class="card-body">${esc(str(root.attributes["body"]))}</p>` : ""}
  ${bar(overall.done, overall.total)}
  <div class="sub">${overall.done} of ${overall.total} descendant items done · exported ${esc(now)}</div>
</header>
${kids(root.id).filter(isCard).map((c) => card(c, 1)).join("")}
${kids(root.id).filter((c) => !isCard(c)).length ? `<ul class="leaves">${kids(root.id).filter((c) => !isCard(c)).map(leaf).join("")}</ul>` : ""}
</div>
</body>
</html>
`;

// Default output lands in a dedicated /tmp dir (never the working tree) so
// exports stay throwaway; `make kanban-serve` serves that same directory.
const outPath = resolve(opts.out || `/tmp/kanban-export/kanban-${root.id}.html`);
mkdirSync(dirname(outPath), { recursive: true });
await Bun.write(outPath, html);
console.error(`wrote ${outPath} (${overall.done}/${overall.total} items done)`);
if (opts.open) await run(["open", outPath], process.cwd());
