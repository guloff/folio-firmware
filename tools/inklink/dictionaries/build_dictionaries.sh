#!/usr/bin/env bash
# Rebuild Folio's starter StarDict dictionaries into releases/dictionaries-1/.
#
#   tools/inklink/dictionaries/build_dictionaries.sh [--cache DIR] [--only ID ...]
#
# Needs only python3 (stdlib) and network access to kaikki.org. Downloads the
# pinned Wiktextract extracts (~950 MB, cached in ~/.cache/folio-dictionaries),
# builds every dictionary, validates it, and writes manifest.json + LICENSES.md.
# Inputs are pinned by sha256 in build_dictionaries.py (INPUTS); see the header
# there for what to do when kaikki.org has moved on to a newer extract.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$(cd "$here/../../../.." && pwd)/releases/dictionaries-1"
mkdir -p "$out"
# A fixed date keeps the .ifo (and so its sha256) reproducible.
export SOURCE_DATE="${SOURCE_DATE:-2026-10-23}"
# -I: isolated mode, so nothing from the working directory or environment is imported.
exec python3 -I "$here/build_dictionaries.py" --out "$out" "$@"
