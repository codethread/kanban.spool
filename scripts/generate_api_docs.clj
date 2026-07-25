(require '[quickdoc.api :as quickdoc])

;; ---------------------------------------------------------------------------
;; Per-repo configuration. Everything below this block is repo-independent, so
;; porting this generator to another spool repo means editing only these three
;; forms: the GitHub repo the source links point at, the branch those links
;; pin, and one entry per namespace that earns a published API page.
;; ---------------------------------------------------------------------------

(def github-repo "https://github.com/codethread/kanban.spool")

(def git-branch "main")

(def api-docs
  ;; Peering is a second published surface, not an implementation detail: its
  ;; entry points are named from consumers' trusted config and its docstrings
  ;; are the honest source for the three ops it registers, so it gets its own
  ;; page rather than being folded into the board page.
  [{:name "kanban" :source "src/ct/spools/kanban.clj" :outfile "kanban.api.md"}
   {:name "kanban-peering" :source "src/ct/spools/kanban/peering.clj" :outfile "kanban.peering.api.md"}])

;; ---------------------------------------------------------------------------

(doseq [{:keys [source outfile]} api-docs]
  (quickdoc/quickdoc
   {:source-paths [source]
    :outfile outfile
    :github/repo github-repo
    :git/branch git-branch
    ;; quickdoc v0.2.6 links backticked var-shaped tokens even when they name
    ;; private helpers intentionally omitted from public API docs. There is no
    ;; public-only link filter, and including private vars would publish
    ;; internals, so use the wikilink detector; these docstrings use backticks,
    ;; which remain code-styled text instead of becoming dead links.
    :var-pattern :wikilinks
    ;; Suppress quickdoc's in-body "# Table of contents". It emits a leading H1
    ;; before the namespace H1, and mkdocs-material's right-hand TOC collapses to
    ;; the first H1's child headings — which for that TOC H1 are none — leaving
    ;; API pages with an empty sidebar TOC. Dropping it makes the namespace the
    ;; sole leading H1 so the sidebar lists every var, matching the other docs.
    :toc false}))

(System/exit 0)
