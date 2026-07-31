// Core plumbing for the dashboard: CLI/argv parsing, workspace resolution, and
// the strand JSON access primitives the view module builds its own fetchers on.
// Everything reaches the coordination world through the public strand JSON CLI
// (TEN-006 — the CLI is the safe consumption surface; no trusted REPL eval
// needed). The weaver transport is one JSON response per request — there is no
// streaming — so the view polls strandJson() itself.

import { existsSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { stringify as stringifyYaml } from "yaml";

// Fields the detail view reads. A view module's row type extends this so the
// shared DetailView stays strand-generic and only adds its own list columns.
export type DetailRow = {
  id: string;
  title?: string;
  state: string;
  branch: string;
  createdAt: string;
  updatedAt: string;
  attrs: Record<string, unknown>;
};

// The `strand show` / `strand list` record: attributes plus the timestamps and
// identity the views read. Fetchers narrow the JSON to this shared shape rather
// than re-typing it inline.
export type StrandRecord = {
  id: string;
  title?: string;
  state: string;
  attributes: Record<string, unknown>;
  created_at: string;
  updated_at: string;
};

// A string attribute or its fallback; strand attributes are untyped JSON.
export const str = (v: unknown, fallback = ""): string => (typeof v === "string" ? v : fallback);

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, "../..");

export const opts = { interval: 2, all: false, once: false, workspace: "" };
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  const a = argv[i];
  if (a === "--interval") opts.interval = Number(argv[++i]);
  else if (a === "--all") opts.all = true;
  else if (a === "--once") opts.once = true;
  else if (a === "--workspace") opts.workspace = argv[++i];
  else if (a === "--help" || a === "-h") {
    console.log("usage: kanban-dash [--interval secs] [--all] [--once]");
    process.exit(0);
  } else {
    console.error(`unknown flag: ${a}`);
    process.exit(2);
  }
}
if (!Number.isFinite(opts.interval) || opts.interval <= 0) {
  console.error(`--interval must be a positive number of seconds, got: ${opts.interval}`);
  process.exit(2);
}

async function run(cmd: string[], cwd: string): Promise<{ code: number; out: string; err: string }> {
  // Never hand a child the controlling pty: Bun's default inherited stdin lets
  // strand/git reset terminal modes, which silently drops Ink out of raw mode
  // and leaves keys line-buffered after the first interactive poll.
  const proc = Bun.spawn(cmd, { cwd, stdin: "ignore", stdout: "pipe", stderr: "pipe" });
  const [out, err, code] = await Promise.all([
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
    proc.exited,
  ]);
  return { code, out, err };
}

async function resolveWorkspace(): Promise<string> {
  if (opts.workspace) return resolve(opts.workspace);
  // git-common-dir points at the canonical root's .git even from a linked
  // worktree, so every checkout dashboards the same coordination world.
  const git = await run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], repoRoot);
  if (git.code !== 0) throw new Error(`cannot resolve canonical repo root: ${git.err.trim()}`);
  return resolve(dirname(git.out.trim()), ".skein");
}

const workspace = await resolveWorkspace();
export const workspaceRoot = dirname(workspace);

export async function strandJson(args: string[]): Promise<unknown> {
  const cmd = ["strand", "--workspace", workspace, ...args];
  const res = await run(cmd, repoRoot);
  if (res.code !== 0) throw new Error(`${cmd.join(" ")}\n${(res.err || res.out).trim()}`);
  return JSON.parse(res.out);
}

// $EDITOR (falling back to $VISUAL then vi), split into its command and any
// leading args so callers can append the file. A shared editor entrypoint like
// "code -w" is preserved as separate argv entries.
export function editorArgv(): string[] {
  const ed = (process.env.VISUAL || process.env.EDITOR || "vi").trim();
  return ed.split(/\s+/).filter(Boolean);
}

// The file to open for a strand row: its `source` attribute when that resolves to
// a real file under the repo (kanban cards stamp the RFC/spec path there),
// otherwise a throwaway markdown rendering so any strand is inspectable in a full
// editor. The convention is that `body` holds the meaty prose, so it becomes the
// document's markdown body and every other attribute (plus identity/timestamps)
// rides in YAML frontmatter. Temp renderings live in tmpdir and are left for the
// OS to reap; real source files are never written.
export function editorFileFor(row: DetailRow): string {
  const source = str(row.attrs["source"]);
  if (source) {
    const p = resolve(workspaceRoot, source);
    if (existsSync(p)) return p;
  }
  const { body, ...rest } = row.attrs;
  const front: Record<string, unknown> = {
    id: row.id,
    ...(row.title !== undefined ? { title: row.title } : {}),
    state: row.state,
    branch: row.branch,
    created: row.createdAt,
    updated: row.updatedAt,
    ...rest,
  };
  // A non-string body (rare) is pretty-printed rather than dropped; an absent body
  // leaves the document body empty under the frontmatter.
  const md = typeof body === "string" ? body : body === undefined ? "" : JSON.stringify(body, null, 2);
  const content = `---\n${stringifyYaml(front)}---\n\n${md}\n`;
  const file = join(tmpdir(), `agent-run-${row.id}.md`);
  writeFileSync(file, content);
  return file;
}

// Copy `text` to a clipboard reachable from wherever the dash runs, returning the
// method(s) that took it for the status flash (or null when nothing is available).
// tmux is tried first: it is the path that works when ssh'd into a remote host —
// `set-buffer -w` also forwards to the outer terminal's clipboard via OSC 52, and
// on a tmux too old for `-w` the plain buffer set still lands so prefix-] paste
// works. The local OS clipboard is layered on when its tool is on PATH, so a mac
// running the dash inside tmux gets both the tmux buffer and the system pasteboard.
// Every child runs with no controlling pty (stdin is a pipe or ignored, never
// inherited) so it can't reset terminal modes and drop Ink out of raw mode.
export async function copyToClipboard(text: string): Promise<string | null> {
  const spawnOk = async (cmd: string[], feedStdin: boolean): Promise<boolean> => {
    try {
      const proc = Bun.spawn(cmd, { stdin: feedStdin ? "pipe" : "ignore", stdout: "ignore", stderr: "ignore" });
      if (feedStdin) {
        proc.stdin!.write(text);
        await proc.stdin!.end();
      }
      return (await proc.exited) === 0;
    } catch {
      // binary missing or spawn failed — treat as unavailable
      return false;
    }
  };

  const ok: string[] = [];
  if (process.env.TMUX) {
    // tmux takes the text as an argv, so its stdin stays ignored.
    if ((await spawnOk(["tmux", "set-buffer", "-w", "--", text], false)) || (await spawnOk(["tmux", "set-buffer", "--", text], false))) {
      ok.push("tmux");
    }
  }
  if (process.platform === "darwin") {
    if (await spawnOk(["pbcopy"], true)) ok.push("pbcopy");
  } else if (process.env.WAYLAND_DISPLAY) {
    if (await spawnOk(["wl-copy"], true)) ok.push("wl-copy");
  } else if (await spawnOk(["xclip", "-selection", "clipboard"], true)) {
    ok.push("xclip");
  } else if (await spawnOk(["xsel", "--clipboard", "--input"], true)) {
    ok.push("xsel");
  }
  return ok.length ? ok.join("+") : null;
}

export function parseInstant(s: string | undefined): Date | undefined {
  if (!s) return undefined;
  // strand created_at is "YYYY-MM-DD HH:MM:SS" in UTC without a zone marker
  const d = new Date(/^\d{4}-\d{2}-\d{2} /.test(s) ? s.replace(" ", "T") + "Z" : s);
  return Number.isNaN(d.getTime()) ? undefined : d;
}
