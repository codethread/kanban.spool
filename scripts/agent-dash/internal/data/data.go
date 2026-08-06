// Core plumbing for the dashboard: CLI/argv parsing, workspace resolution, and
// the strand JSON access primitives the view module builds its own fetchers on.
// Everything reaches the coordination world through the public strand JSON CLI
// (TEN-006 — the CLI is the safe consumption surface; no trusted REPL eval
// needed). The weaver transport is one JSON response per request — there is no
// streaming — so the view polls StrandJSON itself.
package data

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strings"
	"time"
)

// DetailRow holds the fields the detail view reads. A view module's row type
// embeds this so the shared DetailView stays strand-generic and only adds its
// own list columns.
type DetailRow struct {
	ID        string
	Title     string
	State     string
	Branch    string
	CreatedAt string
	UpdatedAt string
	Attrs     map[string]any
}

// Options are the parsed command line. Populated once by ParseFlags and read
// through Opts.
type Options struct {
	Interval  time.Duration
	All       bool
	Once      bool
	Workspace string
}

var (
	opts          = Options{Interval: 2 * time.Second}
	workspace     string
	workspaceRoot string
	repoRoot      string
)

// Opts is the parsed command line.
func Opts() Options { return opts }

// WorkspaceRoot is the directory holding the .millstrand workspace — the repo the
// dashboard reports on, and the cwd every editor spawn inherits.
func WorkspaceRoot() string { return workspaceRoot }

// ErrHelpRequested is returned when argv asked for the usage, which the flag set
// has already printed. It is not a failure, so the caller exits 0 on it.
var ErrHelpRequested = errors.New("help requested")

// ErrFlagsReported wraps a parse error the flag set has already written to
// stderr, so the caller exits non-zero without repeating it.
var ErrFlagsReported = errors.New("bad flags")

// ParseFlags reads argv and resolves the coordination workspace. Failures here
// are fatal: without a workspace there is no board to draw. A parse error has
// already been reported by the flag set, so it comes back wrapped in
// ErrFlagsReported for the caller to exit on without printing it twice.
func ParseFlags(args []string) error {
	fs := flag.NewFlagSet("kanban-dash", flag.ContinueOnError)
	fs.Usage = func() {
		fmt.Fprintln(fs.Output(), "usage: kanban-dash [--interval secs] [--all] [--once] [--workspace dir]")
	}
	secs := fs.Float64("interval", 2, "poll interval in seconds")
	fs.BoolVar(&opts.All, "all", false, "show all cards, not just active ones")
	fs.BoolVar(&opts.Once, "once", false, "print a single frame and exit")
	fs.StringVar(&opts.Workspace, "workspace", "", "path to the .millstrand workspace")
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return ErrHelpRequested
		}
		return fmt.Errorf("%w: %w", ErrFlagsReported, err)
	}
	if *secs <= 0 {
		return fmt.Errorf("--interval must be a positive number of seconds, got: %v", *secs)
	}
	opts.Interval = time.Duration(*secs * float64(time.Second))

	var err error
	repoRoot, err = resolveRepoRoot()
	if err != nil {
		return err
	}
	workspace, err = resolveWorkspace()
	if err != nil {
		return err
	}
	workspaceRoot = filepath.Dir(workspace)
	return nil
}

// The repo the dashboard reads from is the one holding this binary, mirroring
// the Bun entrypoint's import.meta.url anchor: the built binary sits in
// scripts/agent-dash/ inside the checkout. A binary run from outside any
// checkout falls back to the cwd, which is what a bare `go run` gets.
func resolveRepoRoot() (string, error) {
	if exe, err := os.Executable(); err == nil {
		if resolved, err := filepath.EvalSymlinks(exe); err == nil {
			exe = resolved
		}
		// scripts/agent-dash/<binary> → the checkout two levels up.
		candidate := filepath.Dir(filepath.Dir(filepath.Dir(exe)))
		if _, err := os.Stat(filepath.Join(candidate, ".git")); err == nil {
			return candidate, nil
		}
	}
	return os.Getwd()
}

func resolveWorkspace() (string, error) {
	if opts.Workspace != "" {
		return filepath.Abs(opts.Workspace)
	}
	// git-common-dir points at the canonical root's .git even from a linked
	// worktree, so every checkout dashboards the same coordination world.
	out, err := run(repoRoot, "git", "rev-parse", "--path-format=absolute", "--git-common-dir")
	if err != nil {
		return "", fmt.Errorf("cannot resolve canonical repo root: %w", err)
	}
	return filepath.Join(filepath.Dir(strings.TrimSpace(out)), ".millstrand"), nil
}

// Never hand a child the controlling tty: strand and git reset terminal modes
// when they get one, which fights the Bubble Tea renderer for the same pty.
func run(cwd string, argv ...string) (string, error) {
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Dir = cwd
	cmd.Stdin = nil
	var stderr strings.Builder
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		detail := strings.TrimSpace(stderr.String())
		if detail == "" {
			detail = strings.TrimSpace(string(out))
		}
		return "", fmt.Errorf("%s\n%s", strings.Join(argv, " "), detail)
	}
	return string(out), nil
}

// StrandJSON runs one strand CLI read against the workspace and decodes its JSON
// response into `into`.
func StrandJSON(into any, args ...string) error {
	argv := append([]string{"strand", "--workspace", workspace}, args...)
	out, err := run(repoRoot, argv...)
	if err != nil {
		return err
	}
	if err := json.Unmarshal([]byte(out), into); err != nil {
		return fmt.Errorf("%s\n%w", strings.Join(argv, " "), err)
	}
	return nil
}

// Str reads a string attribute or its fallback; strand attributes are untyped
// JSON.
func Str(attrs map[string]any, key string, fallback ...string) string {
	def := ""
	if len(fallback) > 0 {
		def = fallback[0]
	}
	if v, ok := attrs[key].(string); ok {
		return v
	}
	return def
}

// EditorArgv is $VISUAL (falling back to $EDITOR then vi), split into its
// command and any leading args so callers can append the file. A shared editor
// entrypoint like "code -w" is preserved as separate argv entries.
func EditorArgv() []string {
	ed := strings.TrimSpace(os.Getenv("VISUAL"))
	if ed == "" {
		ed = strings.TrimSpace(os.Getenv("EDITOR"))
	}
	if ed == "" {
		ed = "vi"
	}
	return strings.Fields(ed)
}

var instantLike = regexp.MustCompile(`^\d{4}-\d{2}-\d{2} `)

// ParseInstant reads a strand timestamp, which is "YYYY-MM-DD HH:MM:SS" in UTC
// without a zone marker.
func ParseInstant(s string) (time.Time, bool) {
	if s == "" {
		return time.Time{}, false
	}
	if instantLike.MatchString(s) {
		t, err := time.Parse("2006-01-02 15:04:05", s)
		return t.UTC(), err == nil
	}
	t, err := time.Parse(time.RFC3339, s)
	return t, err == nil
}

// EditorFileFor returns the file to open for a strand row: its `source`
// attribute when that resolves to a real file under the repo (kanban cards stamp
// the RFC/spec path there), otherwise a throwaway markdown rendering so any
// strand is inspectable in a full editor. The convention is that `body` holds the
// meaty prose, so it becomes the document's markdown body and every other
// attribute (plus identity/timestamps) rides in YAML frontmatter. Temp renderings
// live in the OS temp dir and are left for it to reap; real source files are
// never written.
func EditorFileFor(row DetailRow) (string, error) {
	if source := Str(row.Attrs, "source"); source != "" {
		p := source
		if !filepath.IsAbs(p) {
			p = filepath.Join(workspaceRoot, p)
		}
		if _, err := os.Stat(p); err == nil {
			return p, nil
		}
	}

	front := map[string]any{
		"id":      row.ID,
		"state":   row.State,
		"branch":  row.Branch,
		"created": row.CreatedAt,
		"updated": row.UpdatedAt,
	}
	if row.Title != "" {
		front["title"] = row.Title
	}
	body := ""
	for k, v := range row.Attrs {
		if k == "body" {
			// A non-string body (rare) is pretty-printed rather than dropped; an
			// absent body leaves the document body empty under the frontmatter.
			if s, ok := v.(string); ok {
				body = s
			} else {
				pretty, err := json.MarshalIndent(v, "", "  ")
				if err != nil {
					return "", err
				}
				body = string(pretty)
			}
			continue
		}
		front[k] = v
	}

	file := filepath.Join(os.TempDir(), fmt.Sprintf("agent-run-%s.md", row.ID))
	content := fmt.Sprintf("---\n%s---\n\n%s\n", toYAML(front), body)
	if err := os.WriteFile(file, []byte(content), 0o644); err != nil {
		return "", err
	}
	return file, nil
}

// A deliberately small YAML emitter for the frontmatter block: the values are
// strand attributes, so scalars and the odd nested structure. Anything that is
// not a plain scalar is emitted as its JSON form, which YAML is a superset of.
func toYAML(m map[string]any) string {
	keys := sortedKeys(m)
	var b strings.Builder
	for _, k := range keys {
		fmt.Fprintf(&b, "%s: %s\n", k, yamlScalar(m[k]))
	}
	return b.String()
}

func yamlScalar(v any) string {
	switch t := v.(type) {
	case string:
		if t == "" || strings.ContainsAny(t, ":#{}[]&*!|>'\"%@`\n") || strings.TrimSpace(t) != t {
			encoded, _ := json.Marshal(t)
			return string(encoded)
		}
		return t
	case bool, float64, int:
		return fmt.Sprint(t)
	case nil:
		return "null"
	default:
		encoded, _ := json.Marshal(t)
		return string(encoded)
	}
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	slices.Sort(keys)
	return keys
}

// CopyToClipboard copies `text` to a clipboard reachable from wherever the dash
// runs, returning the method(s) that took it for the status flash (empty when
// nothing is available). tmux is tried first: it is the path that works when
// ssh'd into a remote host — `set-buffer -w` also forwards to the outer
// terminal's clipboard via OSC 52, and on a tmux too old for `-w` the plain
// buffer set still lands so prefix-] paste works. The local OS clipboard is
// layered on when its tool is on PATH, so a mac running the dash inside tmux gets
// both the tmux buffer and the system pasteboard. Every child runs with no
// controlling pty so it cannot reset terminal modes under the renderer.
func CopyToClipboard(text string) string {
	var ok []string
	spawn := func(stdin bool, argv ...string) bool {
		cmd := exec.Command(argv[0], argv[1:]...)
		if stdin {
			cmd.Stdin = strings.NewReader(text)
		}
		return cmd.Run() == nil
	}

	if os.Getenv("TMUX") != "" {
		if spawn(false, "tmux", "set-buffer", "-w", "--", text) || spawn(false, "tmux", "set-buffer", "--", text) {
			ok = append(ok, "tmux")
		}
	}
	switch {
	case runtime.GOOS == "darwin":
		if spawn(true, "pbcopy") {
			ok = append(ok, "pbcopy")
		}
	case os.Getenv("WAYLAND_DISPLAY") != "":
		if spawn(true, "wl-copy") {
			ok = append(ok, "wl-copy")
		}
	case spawn(true, "xclip", "-selection", "clipboard"):
		ok = append(ok, "xclip")
	case spawn(true, "xsel", "--clipboard", "--input"):
		ok = append(ok, "xsel")
	}
	return strings.Join(ok, "+")
}
