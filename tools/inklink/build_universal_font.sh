#!/usr/bin/env bash
# Builds "FolioUniversal", the wide-coverage SD-card reading font: Noto Serif
# (regular, bold, italic, bold italic: Latin, Cyrillic, Greek, IPA) with
# missing code points taken, in order, from Noto Sans Symbols, Symbols 2,
# Math and Noto Sans CJK SC (Chinese, Japanese kana, Korean Hangul). Sizes
# 8 and 10 serve the UI fallback for CJK titles; 12-18 are reading sizes.
#
#   tools/inklink/build_universal_font.sh [output dir] [sizes]
#
# Copy the output folder to /.fonts/ on the SD card, then pick FolioUniversal
# in Settings → Reader → Font Family. Needs the converter's Python deps:
#   ../.venv/bin/pip install -r lib/EpdFont/scripts/requirements.txt
set -euo pipefail

HERE="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$HERE/../releases/fonts/FolioUniversal}"
SIZES="${2:-8,10,12,14,16,18}"
PY="${PYTHON:-$HERE/../.venv/bin/python}"
SRC="$HERE/lib/EpdFont/builtinFonts/source/NotoSerif"
FB="${FOLIO_FALLBACK_FONTS:-$HERE/../upstream/crosspoint-tools/scripts/font-builder/default-fallback-fonts}"

FALLBACKS=()
for style in regular bold italic bolditalic; do
  for f in NotoSansSymbols-Regular.ttf NotoSansSymbols2-Regular.ttf NotoSansMath-Regular.ttf NotoSansCJKsc-Regular.otf; do
    [[ -r "$FB/$f" ]] || { echo "build_universal_font: missing $FB/$f" >&2; exit 1; }
    FALLBACKS+=(--fallback-"$style" "$FB/$f")
  done
done

# Presets plus Letterlike Symbols and Number Forms (№ ℃ ™ Ⅻ) and Enclosed
# Alphanumerics (① ⓐ), which no preset includes.
INTERVALS="reading,latin-ext,cyrillic,greek,ipa-chars,punctuation,symbols,cjk,hangul,(0x2100-0x218F),(0x2460-0x24FF)"

mkdir -p "$OUT"
"$PY" "$HERE/lib/EpdFont/scripts/fontconvert_sdcard.py" \
  --regular "$SRC/NotoSerif-Regular.ttf" --bold "$SRC/NotoSerif-Bold.ttf" \
  --italic "$SRC/NotoSerif-Italic.ttf" --bolditalic "$SRC/NotoSerif-BoldItalic.ttf" \
  "${FALLBACKS[@]}" --intervals "$INTERVALS" --sizes "$SIZES" \
  --name FolioUniversal --output-dir "$OUT"
