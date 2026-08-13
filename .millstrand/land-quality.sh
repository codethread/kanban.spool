#!/bin/sh
set -eu

make test
make fmt-check
make lint
make docs-check
make kanban-dash-check
make identity-check
