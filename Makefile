# One gate, and CI runs exactly this target.
#
# A local check set that differs from the CI one turns "green here, red there" into the normal
# state of affairs, and people stop reading either. So: whatever is not in `make check` is not a
# gate, and whatever is in it runs the same way in both places.
#
# There is no `backlog_index.py` here on purpose: the backlog of this project is a single
# `BACKLOG.md` with milestones, not a file per item, so there is no index to generate.

DOCS ?= docs
BACKLOG ?= BACKLOG.md
REPOS ?= ..
PY ?= python3

.PHONY: check gate report fix help

help:
	@echo "make check   - the gate: blocking checks, exactly what CI runs"
	@echo "make report  - non-blocking reports: BDD coverage, code anchors"
	@echo "make fix     - fill in missing coverage-map lines"

check: gate report

gate:
	$(PY) scripts/docs_check.py --docs $(DOCS) --backlog $(BACKLOG)
	$(PY) scripts/coverage_map.py --check --docs $(DOCS)

# Non-blocking, on purpose. `code_anchors` in this repository points at *other* people's trees
# (compose-multiplatform, kobweb, kilua), so a "missing" line here usually means the checkout is
# not next to this repository rather than that the path is wrong.
report:
	$(PY) scripts/bdd_report.py --docs $(DOCS) --repos $(REPOS)
	$(PY) scripts/code_anchors.py --docs $(DOCS) --repos $(REPOS)

fix:
	$(PY) scripts/coverage_map.py --fix --docs $(DOCS)
