"""MkDocs hook: rewrite repo-relative links that leave the docs collection.

The site is a symlinked projection of repository markdown, so docs legitimately
carry relative links to source files (test suites, config, RFC/archive
material) that are correct in the repo but unresolvable in the site. Rather
than let those accumulate as accepted warnings — which forces the docs gate to
build non-strict and stop failing loudly — this hook rewrites any relative
link whose target exists in the repository but not in the docs collection to a
GitHub blob URL. The build then runs `--strict`, so every remaining warning is
a genuinely broken link.

The rewrite relies on the projection mirroring repo layout (each `.mkdocs/`
entry symlinks its same-named repo path), which makes a collection-relative
path also the repo-relative path.
"""

import os
import posixpath
import re

# [text](target#anchor) with a non-anchor, non-empty target; angle-bracket and
# titled forms are rare in this repo and intentionally left alone (they would
# fail --strict and get fixed at the source).
_LINK = re.compile(r"(\[[^\]]*\]\()([^)\s#]+)(#[^)\s]*)?(\))")


def on_page_markdown(markdown, page, config, files):
    repo_url = (config.get("repo_url") or "").rstrip("/")
    if not repo_url:
        raise ValueError("mkdocs_hooks requires repo_url to rewrite out-of-collection links")
    repo_root = os.path.dirname(os.path.abspath(config["config_file_path"]))
    src_dir = posixpath.dirname(page.file.src_uri)

    def rewrite(match):
        prefix, target, anchor, suffix = (
            match.group(1),
            match.group(2),
            match.group(3) or "",
            match.group(4),
        )
        if "://" in target or target.startswith(("mailto:", "/")):
            return match.group(0)
        resolved = posixpath.normpath(posixpath.join(src_dir, target))
        if resolved.startswith(".."):
            return match.group(0)
        if files.get_file_from_path(resolved) is not None:
            # in-collection: leave it for mkdocs to validate and rewrite
            return match.group(0)
        if os.path.exists(os.path.join(repo_root, resolved)):
            return f"{prefix}{repo_url}/blob/main/{resolved}{anchor}{suffix}"
        return match.group(0)

    return _LINK.sub(rewrite, markdown)
