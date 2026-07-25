.PHONY: api-docs docs-site docs-serve docs-check

QUICKDOC_DEPS := '{:deps {io.github.borkdude/quickdoc {:git/tag "v0.2.6" :git/sha "ce86780"}}}'
QUICKDOC_SCRIPT := scripts/generate_api_docs.clj
# Pin the proven toolchain so an upstream MkDocs 2.0 release cannot break every
# propagated docs pipeline unexpectedly.
MKDOCS := uvx --from mkdocs==1.6.1 --with mkdocs-material==9.7.7 --with markdown-gfm-admonition==0.3.0 mkdocs

# Regenerate the *.api.md pages from source docstrings. bb starts far faster
# than a JVM, so use it when present; the clojure fallback keeps the target
# working on machines (and CI images) that only have the JVM toolchain.
api-docs:
	@if command -v bb >/dev/null 2>&1; then \
		bb -Sdeps $(QUICKDOC_DEPS) $(QUICKDOC_SCRIPT); \
	else \
		PATH="/opt/homebrew/opt/openjdk/bin:$$PATH" clojure -Sdeps $(QUICKDOC_DEPS) -M $(QUICKDOC_SCRIPT); \
	fi

docs-site:
	$(MKDOCS) build --strict

docs-serve:
	$(MKDOCS) serve --dev-addr 127.0.0.1:8000

# Anti-drift gate: generated API pages are committed so they render on GitHub,
# which means they can silently fall behind the docstrings they came from.
# Regenerating and diffing makes that divergence a build failure rather than a
# thing a reader discovers.
docs-check:
	$(MAKE) api-docs
	git diff --exit-code -- '*.api.md'
	$(MAKE) docs-site
