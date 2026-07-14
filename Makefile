.PHONY: fmt fmt-check lint test kanban-export kanban-serve

fmt:
	clojure -M:format/fix

fmt-check:
	clojure -M:format

lint:
	clojure -M:lint/clj-kondo
	clojure -M:lint/splint

test:
	clojure -M:test

# Standalone HTML export of a feature/epic card subtree; polls the strand CLI.
# Pass the card id as ID and any extra flags (--out, --workspace, --open) as ARGS,
# e.g. make kanban-export ID=abc12 ARGS='--open'. Output defaults to /tmp.
kanban-export:
	@test -n "$(ID)" || { echo "make kanban-export: pass a card id, e.g. make kanban-export ID=abc12 [ARGS='--open']" >&2; exit 2; }
	bun install --cwd scripts/kanban-export --silent
	bun scripts/kanban-export/kanban-export.ts $(ID) $(ARGS)

# Export a card to /tmp and serve it over the LAN, printing ip/port/file/url up
# front. Pass the card id as ID and an optional PORT (default 8000); Ctrl-C stops.
KANBAN_EXPORT_DIR := /tmp/kanban-export
kanban-serve:
	@test -n "$(ID)" || { echo "make kanban-serve: pass a card id, e.g. make kanban-serve ID=abc12 [PORT=8000]" >&2; exit 2; }
	@bun install --cwd scripts/kanban-export --silent
	@file="$(KANBAN_EXPORT_DIR)/kanban-$(ID).html"; \
	bun scripts/kanban-export/kanban-export.ts $(ID) --out "$$file" $(ARGS); \
	ip="$$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || hostname)"; \
	port="$(or $(PORT),8000)"; \
	printf '\n  file: %s\n  port: %s\n  url:  http://%s:%s/kanban-%s.html\n\n  serving %s — Ctrl-C to stop\n\n' \
		"$$file" "$$port" "$$ip" "$$port" "$(ID)" "$(KANBAN_EXPORT_DIR)"; \
	python3 -m http.server "$$port" --bind 0.0.0.0 --directory "$(KANBAN_EXPORT_DIR)"
