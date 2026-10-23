#!/usr/bin/env python3
"""Build Folio's starter set of StarDict dictionaries from Wiktionary extracts.

Stdlib only (Python >= 3.9). Run through build_dictionaries.sh, or directly:

    python3 -I build_dictionaries.py [--cache DIR] [--out DIR] [--only ID ...]

Inputs are the Wiktextract (kaikki.org) JSONL extracts of Wiktionary, pinned by
sha256 in INPUTS below. kaikki.org only serves the *latest* weekly extract at a
stable URL, so the pin is the hash: a later run whose download no longer matches
stops with an error (pass --accept-new-inputs to rebuild from whatever is
current and print the new pins). Keep the cache directory (default
~/.cache/folio-dictionaries) to rebuild byte-identically from the pinned files.

Output (one folder per dictionary, only StarDict files inside):
    <out>/<id>/<id>.ifo  <id>.idx  <id>.dict.dz  [<id>.syn]
plus <out>/manifest.json and <out>/LICENSES.md.

The output is shaped for the Folio/CrossPoint firmware reader
(src/util/Dictionary.cpp): uncompressed .idx with 32-bit offsets, dictzip
.dict.dz with <= 8192 chunks, sametypesequence=h with simple HTML (b, i, br,
ol/li), headwords <= 255 bytes, definitions capped well under the 64 KB
MAX_DEFINITION_BYTES, entries that differ only by letter case merged (the
reader lowercases the tapped word and compares ASCII-case-insensitively), and
.syn entries for inflected forms, lowercase spellings of capitalised Cyrillic
headwords, and ё -> е spellings.

Downloaded data is treated as untrusted: it is only parsed as JSON, never
executed, and every download lives in its own directory under the cache.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime
import gzip
import hashlib
import html
import io
import json
import os
import re
import shutil
import struct
import sys
import time
import unicodedata
import urllib.request
import zlib

# --------------------------------------------------------------------------
# Pinned inputs
# --------------------------------------------------------------------------

# Wiktextract extracts as published by kaikki.org on 2026-10-21 (English
# edition from the enwiktionary dump dated 2026-10-01; Russian edition
# ru-extract from the same week's ruwiktionary dump).
INPUTS = {
    "kaikki-English": {
        "url": "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz",
        "size": 534135938,
        "sha256": "cabb3a13b7179d5f8c5909e06b1dcd2e2ae6465d05f35de56509a5b4e2bf0a49",
    },
    "kaikki-Russian": {
        "url": "https://kaikki.org/dictionary/Russian/kaikki.org-dictionary-Russian.jsonl.gz",
        "size": 90794673,
        "sha256": "9dc6b247752a59b1be12e891656504d80be3045c4218fd28ec16154246aec251",
    },
    "kaikki-Tajik": {
        "url": "https://kaikki.org/dictionary/Tajik/kaikki.org-dictionary-Tajik.jsonl",
        "size": 13521296,
        "sha256": "b7f85b03312461f70d92246d00d71b3c8895e930ae461b7c08ad6a85a994dd93",
    },
    "ruwiktionary-extract": {
        "url": "https://kaikki.org/dictionary/downloads/ru/ru-extract.jsonl.gz",
        "size": 308932290,
        "sha256": "613d46349636406fb1e341d20d9fde5c65644a09e6db377a4d32c188966d8caf",
    },
}

WIKT_LICENSE = "CC BY-SA 4.0"
WIKT_LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"

# Per-dictionary definition cap (bytes of HTML). The reader caps at 64 KB and
# lays HTML out in RAM, so keep every entry far below that.
MAX_ENTRY_BYTES = 12 * 1024
MAX_HEADWORD_BYTES = 255
DICTZIP_CHUNK = 58315
DICTZIP_MAX_CHUNKS = 8192  # firmware DictZip MAX_CHUNK_COUNT

# --------------------------------------------------------------------------
# Download / pinning
# --------------------------------------------------------------------------


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def _http(url: str, method: str = "GET", headers: dict | None = None):
    req = urllib.request.Request(url, method=method, headers={"User-Agent": "folio-dict-build/1", **(headers or {})})
    return urllib.request.urlopen(req, timeout=120)


def download(url: str, dest: str, parts: int = 16) -> None:
    """Parallel ranged download (kaikki.org throttles each connection).

    Every part is requested with If-Range on the ETag seen by the initial HEAD,
    so a file replaced upstream mid-download fails instead of mixing versions.
    """
    with _http(url, "HEAD") as r:
        size = int(r.headers["Content-Length"])
        etag = r.headers.get("ETag")
        ranges_ok = r.headers.get("Accept-Ranges", "") == "bytes" and etag
    tmp = dest + ".part"
    if not ranges_ok or size < 8 << 20:
        parts = 1
    step = -(-size // parts)

    def fetch(i: int) -> None:
        lo, hi = i * step, min(size, (i + 1) * step) - 1
        if lo > hi:
            return
        pf = f"{tmp}.{i}"
        have = os.path.getsize(pf) if os.path.exists(pf) else 0
        if have == hi - lo + 1:
            return
        for attempt in range(8):
            try:
                have = os.path.getsize(pf) if os.path.exists(pf) else 0
                hdr = {}
                if parts > 1:
                    hdr = {"Range": f"bytes={lo + have}-{hi}", "If-Range": etag}
                with _http(url, headers=hdr) as r, open(pf, "ab" if have else "wb") as out:
                    if parts > 1 and r.status != 206:
                        raise RuntimeError(f"{url} changed upstream during download (HTTP {r.status})")
                    shutil.copyfileobj(r, out, 1 << 20)
                if os.path.getsize(pf) == hi - lo + 1:
                    return
            except RuntimeError:
                raise
            except Exception as e:  # network hiccup: resume this part
                print(f"  part {i}: {e!r}, retrying", file=sys.stderr)
                time.sleep(2 + attempt * 3)
        raise RuntimeError(f"part {i} of {url} failed")

    with concurrent.futures.ThreadPoolExecutor(parts) as ex:
        list(ex.map(fetch, range(parts)))
    with open(tmp, "wb") as out:
        for i in range(parts):
            pf = f"{tmp}.{i}"
            if os.path.exists(pf):
                with open(pf, "rb") as f:
                    shutil.copyfileobj(f, out, 1 << 20)
                os.remove(pf)
    if os.path.getsize(tmp) != size:
        raise RuntimeError(f"{url}: got {os.path.getsize(tmp)} bytes, expected {size}")
    os.replace(tmp, dest)


def fetch_inputs(cache: str, names: set[str], accept_new: bool) -> dict[str, str]:
    paths = {}
    for name in sorted(names):
        spec = INPUTS[name]
        d = os.path.join(cache, name)  # one directory per download
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, spec["url"].rsplit("/", 1)[1])
        if not os.path.exists(path):
            print(f"downloading {spec['url']}", file=sys.stderr)
            download(spec["url"], path)
        digest = sha256_file(path)
        if digest != spec["sha256"]:
            msg = f"{name}: sha256 {digest} (size {os.path.getsize(path)}) does not match pin {spec['sha256']}"
            if not accept_new:
                sys.exit(msg + "\n  delete the cached file to re-download, or pass --accept-new-inputs")
            print("WARNING " + msg, file=sys.stderr)
            spec["sha256"] = digest
            spec["size"] = os.path.getsize(path)
        paths[name] = path
    return paths


def iter_jsonl(path: str):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rb") as f:
        for line in f:
            if line.strip():
                yield json.loads(line)


# --------------------------------------------------------------------------
# Text helpers
# --------------------------------------------------------------------------

STRESS_MARKS = dict.fromkeys(map(ord, "́̀"), None)
# Combining marks the reader's fonts may not carry; strip from Cyrillic text.


def strip_stress(s: str) -> str:
    s = unicodedata.normalize("NFD", s)
    s = s.translate(STRESS_MARKS)
    return unicodedata.normalize("NFC", s)


# Code points the reader's built-in fonts carry (lib/EpdFont/scripts/fontconvert.py
# intervals). Text outside them would render as missing glyphs, so it is
# dropped: parenthesised asides in other scripts (e.g. "(Persian spelling
# ...)") go entirely, any remaining run becomes "…". IPA (U+0250-02AF) is not
# in the built-in fonts, so pronunciations are not included at all.
RENDERABLE = [
    (0x0020, 0x007E), (0x00A0, 0x017F), (0x01A0, 0x01A1), (0x01AF, 0x01B0), (0x01C4, 0x021F),
    (0x0300, 0x036F), (0x0400, 0x04FF), (0x1EA0, 0x1EF9), (0x2000, 0x206F), (0x2070, 0x209F),
    (0x20A0, 0x20CF), (0x2190, 0x21FF), (0x2200, 0x22FF), (0xFB00, 0xFB06),
]
_bad_class = "".join(
    f"\\U{lo:08x}-\\U{hi:08x}" for lo, hi in
    [(a[1] + 1, b[0] - 1) for a, b in zip(RENDERABLE, RENDERABLE[1:]) if b[0] - a[1] > 1]
    + [(0, 0x1F), (0x7F, 0x9F), (0xFB07, 0x10FFFF)]
)
BAD_RE = re.compile(f"[{_bad_class}]")
BAD_PAREN_RE = re.compile(f"\\s*[(\\[][^()\\[\\]]*[{_bad_class}][^()\\[\\]]*[)\\]]")
BAD_RUN_RE = re.compile(f"[{_bad_class}]+")


def renderable(s: str) -> bool:
    return BAD_RE.search(s) is None


def sanitize(s: str) -> str:
    if renderable(s):
        return s
    s = BAD_PAREN_RE.sub("", s)
    s = BAD_RUN_RE.sub("…", s)
    return " ".join(s.split())


def esc(s: str) -> str:
    return html.escape(sanitize(s), quote=False)


WORD_RE = re.compile(r"^[^\s\x00-\x1f]+$")


def usable_headword(w: str) -> bool:
    # The reader looks up a single tapped word: multi-word phrases can never be
    # hit, so they would only cost space.
    return (bool(w) and WORD_RE.match(w) is not None and len(w.encode()) <= MAX_HEADWORD_BYTES
            and renderable(w))


CYRILLIC_RE = re.compile(r"[Ѐ-ԯ]")


def lookup_key(w: str) -> str:
    """What the firmware turns a tapped word into (lowercase ASCII+Cyrillic)."""
    return w.lower()


# --------------------------------------------------------------------------
# Generic dictionary accumulator
# --------------------------------------------------------------------------


class Dict:
    def __init__(self, ident: str):
        self.id = ident
        # key -> {spelling: [html fragments]}
        self.groups: dict[str, dict[str, list[str]]] = {}
        # synonym (lowercased) -> (target key, relation), first writer wins.
        # relation: "infl" (regular inflection) or "spell" (spelling variant).
        self.syn: dict[str, tuple[str, str]] = {}
        self.dropped_minor = 0      # archaic/obsolete/dialectal/... forms not made synonyms
        self.dropped_collision = 0  # non-inflection synonyms that are headwords elsewhere
        # inflected forms / alternative spellings: (form, lemma, fragment)
        self.forms: list[tuple[str, str, str]] = []
        # key -> lemma key whose definition is appended to this entry
        self.tail: dict[str, str] = {}

    def add(self, word: str, fragment: str, front: bool = False) -> None:
        if not fragment or not usable_headword(word):
            return
        lst = self.groups.setdefault(lookup_key(word), {}).setdefault(word, [])
        if front:
            lst.insert(0, fragment)
        else:
            lst.append(fragment)

    def add_syn(self, form: str, target_word: str, rel: str = "infl") -> None:
        form = strip_stress(form).strip()
        if not usable_headword(form):
            return
        k = lookup_key(form)
        t = lookup_key(strip_stress(target_word))
        if k != t and k not in self.syn:
            self.syn[k] = (t, rel)

    def add_form(self, form: str, lemma: str, fragment: str, alt: bool = False, minor: bool = False) -> None:
        """A word that only exists as a form of another ("went" -> "go").

        alt: only an alternative spelling ("bettor" spelt "better").
        minor: every sense is obsolete/dialectal/rare/nonstandard.
        """
        self.forms.append((form, strip_stress(lemma), fragment, alt, minor))

    def pending_form_keys(self) -> set[str]:
        return {lookup_key(f) for f, lemma, _, _, _ in self.forms if lookup_key(lemma) in self.groups}

    def resolve_forms(self, standalone: bool) -> None:
        """Turn recorded forms into synonyms, or into entries when needed.

        Plain forms become .syn entries pointing at the lemma. A form that is
        also a headword of its own ("saw" the noun, "went" the obsolete noun)
        cannot be a synonym — the reader finds the headword first — so the
        "past of see" line is put in front of that entry and the lemma's
        definition appended. Minor forms ("book", dialectal past of "bake")
        are dropped on such a collision, and alternative spellings only get
        their one line appended. Forms of lemmas missing from this dictionary
        become small entries of their own when `standalone`.
        """
        for form, lemma, fragment, alt, minor in self.forms:
            if not usable_headword(form):
                continue
            fk, lk = lookup_key(form), lookup_key(lemma)
            if fk == lk:
                continue
            if minor and fk not in self.groups:
                # Never redirect to another headword through an archaic or
                # obsolete relation ("the" -> "thee"): keep it as a small entry
                # of its own in monolingual dictionaries, drop it elsewhere.
                if lk in self.groups:
                    self.dropped_minor += 1
                if standalone:
                    self.add(form, fragment)
                continue
            if lk not in self.groups:
                if standalone and fk not in self.groups:
                    self.add(form, fragment)
            elif fk in self.groups:
                if minor:
                    continue
                if alt:
                    self.add(form, fragment)
                else:
                    self.add(form, fragment, front=True)
                    self.tail.setdefault(fk, lk)
            else:
                self.add_syn(form, lemma, "spell" if alt else "infl")
        self.forms = []

    def _body(self, key: str) -> tuple[str, str]:
        spellings = self.groups[key]
        # Prefer the all-lowercase spelling as the displayed headword.
        names = sorted(spellings, key=lambda s: (s != key, s))
        if len(names) == 1:
            return names[0], "".join(spellings[names[0]])
        return names[0], "".join(f"<p><b>{esc(n)}</b></p>" + "".join(spellings[n]) for n in names)

    def finalize(self, mono: set[str] | None = None):
        """Returns (entries [(headword, html)], syns [(syn, headword)]).

        mono: headword keys of the monolingual dictionary of the source
        language. A synonym that is such a headword but is not a regular
        inflection of its target is dropped, so a common word is never sent to
        a different, rarer headword.
        """
        entries = []
        head_of = {}
        for key in self.groups:
            head, body = self._body(key)
            lemma = self.tail.get(key)
            if lemma in self.groups and lemma != key:
                lhead, lbody = self._body(lemma)
                body += f"<p>→ <b>{esc(lhead)}</b></p>" + lbody
            entries.append((head, cap_html(body)))
            head_of[key] = head
        syns = {}
        for key, head in head_of.items():
            # The reader lowercases Cyrillic before comparing, but compares
            # non-ASCII bytes exactly: "Москва" needs a "москва" synonym.
            if head != key and head.lower() != head.encode().lower().decode():
                syns.setdefault(key, head)
            # ё-less spelling (books and the reader's stemmer use е).
            if "ё" in key:
                alt = key.replace("ё", "е")
                if alt in mono_or_empty(mono) and alt not in syns:
                    self.dropped_collision += 1  # "все" is its own word, not "всё"
                elif alt not in head_of:
                    syns.setdefault(alt, head)
        for s, (t, rel) in self.syn.items():
            if s in head_of or t not in head_of:
                continue
            if rel != "infl" and s in mono_or_empty(mono):
                self.dropped_collision += 1
                continue
            syns.setdefault(s, head_of[t])
        return entries, sorted(syns.items())


def mono_or_empty(mono):
    return mono if mono is not None else ()


def cap_html(body: str) -> str:
    if len(body.encode()) <= MAX_ENTRY_BYTES:
        return body
    # Cut at a list-item boundary and close what is open.
    b = body.encode()[:MAX_ENTRY_BYTES].decode("utf-8", "ignore")
    cut = b.rfind("</li>")
    if cut > 0:
        b = b[: cut + 5]
    else:
        b = re.sub(r"<[^>]*$", "", b)
    # Close dangling tags (only ol/p/b/i are used).
    for tag in ("i", "b", "p", "ol"):
        opens = len(re.findall(f"<{tag}>", b))
        closes = len(re.findall(f"</{tag}>", b))
        if tag == "ol" and opens > closes:
            b += "<li>…</li>"
        b += f"</{tag}>" * max(0, opens - closes)
    return b


# --------------------------------------------------------------------------
# Wiktextract helpers
# --------------------------------------------------------------------------

POS_EN = {
    "adj": "adjective", "adv": "adverb", "noun": "noun", "verb": "verb", "name": "proper noun",
    "pron": "pronoun", "prep": "preposition", "conj": "conjunction", "intj": "interjection",
    "num": "numeral", "particle": "particle", "det": "determiner", "article": "article",
    "abbrev": "abbreviation", "prefix": "prefix", "suffix": "suffix", "phrase": "phrase",
    "prep_phrase": "prepositional phrase", "contraction": "contraction", "symbol": "symbol",
    "character": "letter", "affix": "affix", "postp": "postposition", "adv_phrase": "adverbial phrase",
    "proverb": "proverb", "interfix": "interfix", "infix": "infix", "circumfix": "circumfix",
}
POS_RU = {
    "adj": "прилагательное", "adv": "наречие", "noun": "существительное", "verb": "глагол",
    "name": "имя собственное", "pron": "местоимение", "prep": "предлог", "conj": "союз",
    "intj": "междометие", "num": "числительное", "particle": "частица", "det": "определитель",
    "article": "артикль", "abbrev": "аббревиатура", "prefix": "приставка", "suffix": "суффикс",
    "phrase": "фразеологизм", "contraction": "сокращение", "symbol": "символ", "character": "буква",
    "affix": "аффикс", "postp": "послелог", "predicative": "предикатив", "participle": "причастие",
    "adv_phrase": "наречное выражение", "prep_phrase": "предложное выражение",
}

RU_GENDER = {"masculine": "м. р.", "feminine": "ж. р.", "neuter": "ср. р.", "perfective": "сов. в.",
             "imperfective": "несов. в."}

SKIP_POS = {"romanization", "soft-redirect", "hard-redirect"}


def is_form_sense(s: dict) -> bool:
    tags = s.get("tags") or []
    return bool(s.get("form_of") or s.get("alt_of")) or "form-of" in tags or "alt-of" in tags


def sense_targets(s: dict) -> list[str]:
    out = []
    for key in ("form_of", "alt_of"):
        for t in s.get(key) or []:
            w = t.get("word") if isinstance(t, dict) else None
            if w:
                out.append(w)
    return out


def gloss_text(s: dict) -> str:
    g = s.get("raw_glosses") or s.get("glosses") or []
    if not g:
        return ""
    return " ".join(str(g[-1]).split())


def head_line(rec: dict, strip: bool) -> str:
    for t in rec.get("head_templates") or []:
        e = " ".join(str(t.get("expansion", "")).split())
        if e and len(e) <= 240 and "<" not in e and "{" not in e:
            return strip_stress(e) if strip else e
    return ""


def first_ipa(rec: dict) -> str:
    for s in rec.get("sounds") or []:
        ipa = s.get("ipa")
        if ipa and len(ipa) < 60:
            return ipa
    return ""


def senses_html(senses: list[str]) -> str:
    seen = []
    for g in senses:
        if g and g not in seen:
            seen.append(g)
    if not seen:
        return ""
    return "<ol>" + "".join(f"<li>{esc(g)}</li>" for g in seen) + "</ol>"


ENGLISH_FORM_TAGS = {
    "plural", "past", "participle", "present", "third-person", "singular", "comparative",
    "superlative", "gerund",
}
BAD_FORM_TAGS = {
    "romanization", "table-tags", "inflection-template", "class", "canonical", "obsolete",
    "archaic", "dialectal", "nonstandard", "misspelling", "rare", "error-unrecognized-form",
}


def english_forms(rec: dict) -> list[str]:
    out = []
    for f in rec.get("forms") or []:
        form = f.get("form", "")
        tags = set(f.get("tags") or [])
        if not form or tags & (BAD_FORM_TAGS | MINOR_TAGS) or not tags & ENGLISH_FORM_TAGS:
            continue
        if " " in form or form == rec.get("word"):
            continue
        out.append(form)
    return out


def generic_forms(rec: dict) -> list[str]:
    out = []
    for f in rec.get("forms") or []:
        form = f.get("form", "")
        tags = set(f.get("tags") or [])
        if not form or tags & (BAD_FORM_TAGS | MINOR_TAGS) or " " in form or form == rec.get("word"):
            continue
        out.append(form)
    return out


# --------------------------------------------------------------------------
# Builders
# --------------------------------------------------------------------------


def form_fragment(rec: dict, pos_names: dict, strip: bool) -> str:
    glosses = [gloss_text(s) for s in rec.get("senses") or []]
    if strip:
        glosses = [strip_stress(g) for g in glosses]
    pos = pos_names.get(rec.get("pos", ""), rec.get("pos", ""))
    return f"<p><i>{esc(pos)}</i></p>" + senses_html(glosses)


def form_target(rec: dict) -> str | None:
    """Lemma of an entry whose every sense is an inflection/alt-spelling."""
    senses = rec.get("senses") or []
    if not senses or any(not is_form_sense(s) for s in senses):
        return None
    targets = [t for s in senses for t in sense_targets(s)]
    return targets[0] if targets else None


MINOR_TAGS = {
    "obsolete", "archaic", "dialectal", "rare", "nonstandard", "misspelling", "pronunciation-spelling",
    "eye-dialect", "uncommon", "nonce-word", "humorous", "Internet", "slang", "proscribed", "dated",
}
MINOR_LABEL_RE = re.compile(r"^\((?:[^)]*\b(?:obsolete|archaic|dialect|dialectal|rare|nonstandard|"
                            r"Scotland|Northern England|humorous|nonce|eye dialect|dated|slang)\b)")


def form_kind(rec: dict) -> tuple[bool, bool]:
    """(alt, minor) for a form-only entry — see Dict.add_form."""
    senses = rec.get("senses") or []
    alt = all(not s.get("form_of") and (s.get("alt_of") or "alt-of" in (s.get("tags") or [])) for s in senses)
    minor = all(set(s.get("tags") or []) & MINOR_TAGS or MINOR_LABEL_RE.match(gloss_text(s)) for s in senses)
    return alt, bool(minor)


RU_TR_TAGS = {"masculine": "м", "feminine": "ж", "neuter": "ср", "perfective": "сов.",
              "imperfective": "несов.", "plural": "мн."}


def build_from_english(path: str):
    """One pass over English-Wiktionary English entries -> en-en and en-ru."""
    en = Dict("en-en-wiktionary")
    enru = Dict("en-ru-wiktionary")
    n = 0
    for rec in iter_jsonl(path):
        n += 1
        if n % 200000 == 0:
            print(f"  en: {n} records", file=sys.stderr)
        if rec.get("lang_code") != "en" or rec.get("pos") in SKIP_POS:
            continue
        word = rec.get("word", "")
        if not usable_headword(word):
            continue
        senses = rec.get("senses") or []
        pos = POS_EN.get(rec.get("pos", ""), rec.get("pos", ""))
        lemma = form_target(rec)
        if lemma:
            frag = form_fragment(rec, POS_EN, strip=False)
            alt, minor = form_kind(rec)
            en.add_form(word, lemma, frag, alt, minor)
            enru.add_form(word, lemma, frag, alt, minor)
            continue
        glosses = [gloss_text(s) for s in senses]
        if any(glosses):
            head = head_line(rec, strip=False)
            top = f"<i>{esc(pos)}</i>"
            if head and head != word:
                top = f"<b>{esc(head)}</b> " + top
            en.add(word, f"<p>{top}</p>" + senses_html(glosses))
        # Russian translations, grouped by sense.
        trs = list(rec.get("translations") or [])
        for s in senses:
            trs.extend(s.get("translations") or [])
        by_sense: dict[str, list[str]] = {}
        for t in trs:
            if (t.get("lang_code") or t.get("code")) != "ru":
                continue
            w = strip_stress(" ".join(str(t.get("word", "")).split()))
            if not w or w.startswith("please add"):
                continue
            tags = [x for x in (t.get("tags") or []) if x in RU_TR_TAGS]
            item = esc(w) + "".join(f" <i>{RU_TR_TAGS[x]}</i>" for x in tags)
            lst = by_sense.setdefault(" ".join(str(t.get("sense") or "").split()), [])
            if item not in lst:
                lst.append(item)
        if by_sense:
            li = []
            for sense, items in by_sense.items():
                label = f"<i>({esc(sense)})</i> " if sense else ""
                li.append(f"<li>{label}{', '.join(items)}</li>")
            enru.add(word, f"<p><i>{esc(pos)}</i></p><ol>{''.join(li)}</ol>")
        for f in english_forms(rec):
            en.add_syn(f, word)
            enru.add_syn(f, word)
    return en, enru


SIMPLE_GLOSS_RE = re.compile(r"^(?:to |a |an |the )?([A-Za-z][A-Za-z'-]*)$")


def reverse_ru_en(ru_en_path: str, ruwikt_path: str) -> dict[str, list[str]]:
    """English word -> Russian words, from the Russian side of both Wiktionaries.

    Fills en-ru for English words that English Wiktionary gives no Russian
    translation table: (a) Russian Wiktionary's English translations of
    Russian entries, (b) one-word English glosses of Russian entries in
    English Wiktionary ("книга: book").
    """
    rev: dict[str, dict[str, int]] = {}

    def put(en_word: str, ru_word: str, weight: int) -> None:
        if not usable_headword(en_word) or not ru_word or " " in ru_word.strip() and len(ru_word) > 40:
            return
        slot = rev.setdefault(lookup_key(en_word), {})
        slot[ru_word] = slot.get(ru_word, 0) + weight

    for rec in iter_jsonl(ruwikt_path):
        if rec.get("lang_code") != "ru":
            continue
        ru = strip_stress(rec.get("word", ""))
        if not ru or " " in ru:
            continue
        for t in rec.get("translations") or []:
            if t.get("lang_code") == "en":
                w = " ".join(str(t.get("word", "")).split())
                if w and " " not in w:
                    put(w, ru, 2)
    for rec in iter_jsonl(ru_en_path):
        if rec.get("lang_code") != "ru" or form_target(rec):
            continue
        ru = strip_stress(rec.get("word", ""))
        if not ru or " " in ru:
            continue
        for s in rec.get("senses") or []:
            for g in s.get("glosses") or []:
                for part in str(g).split(";"):
                    m = SIMPLE_GLOSS_RE.match(part.strip())
                    if m:
                        put(m.group(1), ru, 1)
    return {k: [w for w, _ in sorted(v.items(), key=lambda x: (-x[1], x[0]))][:12] for k, v in rev.items()}


def build_glossed(path: str, ident: str, lang_code: str, pos_names: dict, strip: bool):
    """Entries of one language from English Wiktionary (English glosses)."""
    d = Dict(ident)
    for rec in iter_jsonl(path):
        if rec.get("lang_code") != lang_code or rec.get("pos") in SKIP_POS:
            continue
        word = rec.get("word", "")
        if not usable_headword(word):
            continue
        lemma = form_target(rec)
        if lemma:
            d.add_form(word, lemma, form_fragment(rec, pos_names, strip), *form_kind(rec))
            continue
        glosses = [gloss_text(s) for s in rec.get("senses") or []]
        if not any(glosses):
            continue
        pos = pos_names.get(rec.get("pos", ""), rec.get("pos", ""))
        top = f"<i>{esc(pos)}</i>"
        head = head_line(rec, strip)
        if head and head != word:
            top = f"<b>{esc(head)}</b> " + top
        d.add(word, f"<p>{top}</p>" + senses_html([strip_stress(g) if strip else g for g in glosses]))
    d.resolve_forms(standalone=True)
    return d


def build_ruwikt(path: str, lang_code: str, ident: str):
    """Russian Wiktionary (glosses in Russian) for one language section."""
    d = Dict(ident)
    for rec in iter_jsonl(path):
        if rec.get("lang_code") != lang_code or rec.get("pos") in SKIP_POS:
            continue
        word = rec.get("word", "")
        if not usable_headword(word):
            continue
        glosses = []
        for s in rec.get("senses") or []:
            g = s.get("glosses") or []
            text = " ".join(str(g[-1]).split()) if g else ""
            if not text:
                continue
            labels = [x for x in (s.get("raw_tags") or []) if isinstance(x, str) and len(x) < 30][:3]
            if labels:
                text = "(" + ", ".join(labels) + ") " + text
            glosses.append(strip_stress(text))
        lemma = form_target(rec)
        if lemma:
            d.add_form(word, lemma, "<p><i>форма слова</i></p>" + senses_html(glosses), *form_kind(rec))
            continue
        if not any(glosses):
            continue
        pos = POS_RU.get(rec.get("pos", ""), rec.get("pos_title") or rec.get("pos", ""))
        gender = [RU_GENDER[t] for t in rec.get("tags") or [] if t in RU_GENDER]
        if gender:
            pos += ", " + " ".join(gender)
        d.add(word, f"<p><i>{esc(pos)}</i></p>" + senses_html(glosses))
    d.resolve_forms(standalone=True)
    return d


# --------------------------------------------------------------------------
# StarDict writer
# --------------------------------------------------------------------------


def sd_sort_key(b: bytes):
    # stardict_strcmp: g_ascii_strcasecmp, then strcmp. bytes.lower() is ASCII-only.
    return (b.lower(), b)


def write_dictzip(path: str, data: bytes) -> None:
    chunks = []
    co = zlib.compressobj(9, zlib.DEFLATED, -15, 9)
    count = -(-len(data) // DICTZIP_CHUNK) or 1
    if count > DICTZIP_MAX_CHUNKS:
        raise RuntimeError(f"{path}: {count} dictzip chunks exceeds the reader's {DICTZIP_MAX_CHUNKS}")
    for i in range(count):
        piece = data[i * DICTZIP_CHUNK:(i + 1) * DICTZIP_CHUNK]
        c = co.compress(piece) + co.flush(zlib.Z_FINISH if i == count - 1 else zlib.Z_FULL_FLUSH)
        if len(c) > 0xFFFF:
            raise RuntimeError("dictzip chunk too large")
        chunks.append(c)
    ra = struct.pack("<HHH", 1, DICTZIP_CHUNK, count) + b"".join(struct.pack("<H", len(c)) for c in chunks)
    extra = b"RA" + struct.pack("<H", len(ra)) + ra
    header = b"\x1f\x8b\x08\x04" + struct.pack("<I", 0) + b"\x02\x03" + struct.pack("<H", len(extra)) + extra
    with open(path, "wb") as f:
        f.write(header)
        for c in chunks:
            f.write(c)
        f.write(struct.pack("<II", zlib.crc32(data) & 0xFFFFFFFF, len(data) & 0xFFFFFFFF))


def write_stardict(outdir: str, stem: str, entries, syns, ifo: dict) -> dict:
    os.makedirs(outdir, exist_ok=True)
    for f in os.listdir(outdir):
        os.remove(os.path.join(outdir, f))
    enc = sorted(((h.encode(), d.encode()) for h, d in entries), key=lambda x: sd_sort_key(x[0]))
    for a, b in zip(enc, enc[1:]):
        if a[0] == b[0]:
            raise RuntimeError(f"duplicate headword {a[0]!r}")
    idx = io.BytesIO()
    data = io.BytesIO()
    ordinal = {}
    for i, (h, d) in enumerate(enc):
        off = data.tell()
        data.write(d)
        idx.write(h + b"\0" + struct.pack(">II", off, len(d)))
        ordinal[h] = i
    if data.tell() >= 1 << 32:
        raise RuntimeError("dict exceeds 32-bit offsets")
    syn_items = sorted(((s.encode(), ordinal[t.encode()]) for s, t in syns), key=lambda x: sd_sort_key(x[0]))
    with open(os.path.join(outdir, stem + ".idx"), "wb") as f:
        f.write(idx.getvalue())
    write_dictzip(os.path.join(outdir, stem + ".dict.dz"), data.getvalue())
    if syn_items:
        with open(os.path.join(outdir, stem + ".syn"), "wb") as f:
            for s, o in syn_items:
                f.write(s + b"\0" + struct.pack(">I", o))
    lines = [
        "StarDict's dict ifo file",
        "version=3.0.0",
        f"bookname={ifo['bookname']}",
        f"wordcount={len(enc)}",
    ]
    if syn_items:
        lines.append(f"synwordcount={len(syn_items)}")
    lines += [
        f"idxfilesize={len(idx.getvalue())}",
        "sametypesequence=h",
        f"lang={ifo['lang']}",
        f"website={ifo['website']}",
        f"date={ifo['date']}",
        f"description={ifo['description']}",
    ]
    assert ifo["bookname"].isascii(), "bookname is drawn by the reader's UI font: keep it ASCII"
    for line in lines:
        assert "\n" not in line
    with open(os.path.join(outdir, stem + ".ifo"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    return {"headwords": len(enc), "synonyms": len(syn_items)}


# --------------------------------------------------------------------------
# Validation (mirrors the firmware's lookup path)
# --------------------------------------------------------------------------


def read_ifo(path: str) -> dict:
    out = {}
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    if lines[0] != "StarDict's dict ifo file":
        raise ValueError("bad ifo magic")
    for line in lines[1:]:
        if "=" in line:
            k, v = line.split("=", 1)
            out[k] = v
    return out


def parse_entries(buf: bytes, tail: int):
    pos = 0
    out = []
    while pos < len(buf):
        z = buf.index(b"\0", pos)
        word = buf[pos:z]
        out.append((word, buf[z + 1:z + 1 + tail]))
        pos = z + 1 + tail
    return out


class DictZipReader:
    def __init__(self, path: str):
        self.f = open(path, "rb")
        h = self.f.read(10)
        assert h[:3] == b"\x1f\x8b\x08" and h[3] & 4, "not dictzip"
        xlen = struct.unpack("<H", self.f.read(2))[0]
        extra = self.f.read(xlen)
        p = 0
        self.sizes = None
        while p + 4 <= xlen:
            sid, ln = extra[p:p + 2], struct.unpack("<H", extra[p + 2:p + 4])[0]
            if sid == b"RA":
                ver, self.chlen, cnt = struct.unpack("<HHH", extra[p + 4:p + 10])
                assert ver == 1 and cnt <= DICTZIP_MAX_CHUNKS
                self.sizes = struct.unpack(f"<{cnt}H", extra[p + 10:p + 10 + 2 * cnt])
            p += 4 + ln
        assert self.sizes, "no RA field"
        flags = h[3]
        if flags & 8:
            while self.f.read(1) not in (b"\0", b""):
                pass
        self.data_off = self.f.tell()
        self.offsets = [0]
        for s in self.sizes:
            self.offsets.append(self.offsets[-1] + s)

    def read(self, off: int, size: int) -> bytes:
        out = b""
        c = off // self.chlen
        local = off % self.chlen
        while len(out) < size:
            self.f.seek(self.data_off + self.offsets[c])
            raw = self.f.read(self.sizes[c])
            plain = zlib.decompressobj(-15).decompress(raw)  # independent chunk, like the firmware
            out += plain[local:]
            local = 0
            c += 1
        return out[:size]


def ascii_case_cmp(a: bytes, b: bytes) -> int:
    a, b = a.lower(), b.lower()
    return (a > b) - (a < b)


def firmware_clean(word: str) -> str:
    return word.strip(" .,;:!?\"'()«»“”‘’—–-").lower()


def stem_variants(w: str) -> list[str]:
    out = []
    def add(v):
        if v and v not in out:
            out.append(v)
    n = len(w)
    if w.endswith("'s") and n > 2: add(w[:-2])
    if w.endswith("ies") and n > 3: add(w[:-3] + "y")
    if w.endswith("es") and n > 2: add(w[:-2])
    if w.endswith("s") and n > 1: add(w[:-1])
    if w.endswith("ed") and n > 2:
        add(w[:-2]); add(w[:-1])
        if n >= 4 and w[-3] == w[-4]: add(w[:-3])
    if w.endswith("ing") and n > 3:
        add(w[:-3]); add(w[:-3] + "e")
        if n >= 5 and w[-4] == w[-5]: add(w[:-4])
    return out


ALLOWED_TAGS = {"p", "b", "i", "ol", "li", "br"}
TAG_RE = re.compile(r"<(/?)([a-z]+)>")


def check_html(word: bytes, text: str) -> None:
    assert len(text.encode()) <= MAX_ENTRY_BYTES + 64, f"{word!r}: entry too large"
    stack = []
    for m in TAG_RE.finditer(text):
        closing, tag = m.group(1), m.group(2)
        assert tag in ALLOWED_TAGS, f"{word!r}: tag {tag}"
        if tag == "br":
            continue
        if closing:
            assert stack and stack[-1] == tag, f"{word!r}: unbalanced </{tag}> in {text[:200]!r}"
            stack.pop()
        else:
            stack.append(tag)
    assert not stack, f"{word!r}: unclosed {stack}"
    assert "<" not in TAG_RE.sub("", text), f"{word!r}: stray '<'"


class Validator:
    def __init__(self, folder: str):
        stem = os.path.basename(folder.rstrip("/"))
        self.base = os.path.join(folder, stem)
        self.ifo = read_ifo(self.base + ".ifo")
        assert self.ifo.get("idxoffsetbits", "32") == "32"
        assert self.ifo.get("bookname", "").isascii() and self.ifo["bookname"], "bookname must be ASCII"
        assert re.fullmatch(r"[a-z]{2,3}-[a-z]{2,3}", self.ifo.get("lang", "")), "lang=<from>-<to> missing"
        assert not os.path.exists(self.base + ".idx.gz")
        with open(self.base + ".idx", "rb") as f:
            idx = f.read()
        assert int(self.ifo["idxfilesize"]) == len(idx), "idxfilesize mismatch"
        self.idx = parse_entries(idx, 8)
        assert len(self.idx) == int(self.ifo["wordcount"]), "wordcount mismatch"
        self.syn = []
        if os.path.exists(self.base + ".syn"):
            with open(self.base + ".syn", "rb") as f:
                self.syn = parse_entries(f.read(), 4)
            assert len(self.syn) == int(self.ifo["synwordcount"]), "synwordcount mismatch"
        for name, lst in (("idx", self.idx), ("syn", self.syn)):
            for a, b in zip(lst, lst[1:]):
                if sd_sort_key(a[0]) > sd_sort_key(b[0]):
                    raise AssertionError(f"{name} not sorted at {a[0]!r} > {b[0]!r}")
        for w, _ in self.idx:
            assert 0 < len(w) <= MAX_HEADWORD_BYTES
            w.decode("utf-8")
        total = 0
        for _, tail in self.idx:
            off, size = struct.unpack(">II", tail)
            total = max(total, off + size)
        self.dz = DictZipReader(self.base + ".dict.dz")
        with gzip.open(self.base + ".dict.dz", "rb") as g:
            plain_data = g.read()  # also verifies the CRC32 trailer
        assert total <= len(plain_data)
        self.max_entry = 0
        for w, tail in self.idx:
            off, size = struct.unpack(">II", tail)
            self.max_entry = max(self.max_entry, size)
            check_html(w, plain_data[off:off + size].decode("utf-8"))
        del plain_data
        for _, tail in self.syn:
            assert struct.unpack(">I", tail)[0] < len(self.idx)
        self.words = [w for w, _ in self.idx]

    def _locate(self, target: bytes):
        import bisect
        keys = [w.lower() for w in self.words] if not hasattr(self, "_keys") else self._keys
        self._keys = keys
        i = bisect.bisect_left(keys, target.lower())
        while i < len(keys) and keys[i] == target.lower():
            if ascii_case_cmp(self.words[i], target) == 0:
                return i
            i += 1
        return None

    def _locate_syn(self, target: bytes):
        for w, tail in self.syn:  # linear is fine for a handful of probes
            if ascii_case_cmp(w, target) == 0:
                return struct.unpack(">I", tail)[0]
        return None

    def lookup(self, word: str):
        t = firmware_clean(word).encode()
        i = self._locate(t)
        how = "exact"
        if i is None and self.syn:
            i, how = self._locate_syn(t), "syn"
        if i is None:
            for v in stem_variants(t.decode()):
                i = self._locate(v.encode())
                if i is not None:
                    how = f"stem:{v}"
                    break
        if i is None:
            return None
        off, size = struct.unpack(">II", self.idx[i][1])
        return self.words[i].decode(), how, self.dz.read(off, size).decode()


def plain(htmltext: str, limit: int = 160) -> str:
    t = re.sub(r"<li>", " • ", htmltext)
    t = re.sub(r"<[^>]+>", " ", t)
    t = html.unescape(" ".join(t.split()))
    return t[:limit] + ("…" if len(t) > limit else "")


# --------------------------------------------------------------------------
# Dictionary catalogue
# --------------------------------------------------------------------------

EDITION = {
    "kaikki-English": "English Wiktionary (https://en.wiktionary.org/)",
    "kaikki-Russian": "English Wiktionary (https://en.wiktionary.org/)",
    "kaikki-Tajik": "English Wiktionary (https://en.wiktionary.org/)",
    "ruwiktionary-extract": "Russian Wiktionary (https://ru.wiktionary.org/)",
}


def wikt_attribution(inputs: list[str]) -> str:
    editions = sorted({EDITION[i] for i in inputs})
    files = ", ".join(INPUTS[i]["url"] for i in inputs)
    return (f"Contains data from {' and '.join(editions)} by Wiktionary contributors, licensed CC BY-SA 4.0. "
            f"Extracted with Wiktextract by Tatu Ylonen (kaikki.org: {files}). "
            f"Adapted (filtered, reformatted, truncated) into StarDict by the Folio project; "
            f"the result is licensed CC BY-SA 4.0.")


CATALOG = [
    {
        "id": "en-ru-wiktionary", "mono": "en-en-wiktionary", "bookname": "English-Russian (Wiktionary)", "from": "en", "to": "ru", "inputs": ["kaikki-English", "kaikki-Russian", "ruwiktionary-extract"],
        "title": {"en": "English → Russian (Wiktionary)", "ru": "Английский → русский (Викисловарь)"},
    },
    {
        "id": "ru-en-wiktionary", "mono": "ru-ru-wiktionary", "bookname": "Russian-English (Wiktionary)", "from": "ru", "to": "en", "inputs": ["kaikki-Russian"],
        "title": {"en": "Russian → English (Wiktionary)", "ru": "Русский → английский (Викисловарь)"},
    },
    {
        "id": "en-en-wiktionary", "mono": "en-en-wiktionary", "bookname": "English (Wiktionary)", "from": "en", "to": "en", "inputs": ["kaikki-English"],
        "title": {"en": "English explanatory (Wiktionary)", "ru": "Толковый английский (Викисловарь)"},
    },
    {
        "id": "ru-ru-wiktionary", "mono": "ru-ru-wiktionary", "bookname": "Russian (Wiktionary)", "from": "ru", "to": "ru", "inputs": ["ruwiktionary-extract", "kaikki-Russian"],
        "title": {"en": "Russian explanatory (Wiktionary)", "ru": "Толковый русский (Викисловарь)"},
    },
    {
        "id": "tg-en-wiktionary", "mono": None, "bookname": "Tajik-English (Wiktionary)", "from": "tg", "to": "en", "inputs": ["kaikki-Tajik"],
        "title": {"en": "Tajik → English (Wiktionary)", "ru": "Таджикский → английский (Викисловарь)"},
    },
    {
        "id": "tg-ru-wiktionary", "mono": None, "bookname": "Tajik-Russian (Wiktionary)", "from": "tg", "to": "ru", "inputs": ["ruwiktionary-extract"],
        "title": {"en": "Tajik → Russian (Wiktionary)", "ru": "Таджикский → русский (Викисловарь)"},
    },
]


def build(ident: str, paths: dict, cache: dict) -> Dict:
    if ident in ("en-en-wiktionary", "en-ru-wiktionary"):
        if "english" not in cache:
            en, enru = build_from_english(paths["kaikki-English"])
            if ident == "en-ru-wiktionary" or "kaikki-Russian" in paths:
                rev = reverse_ru_en(paths["kaikki-Russian"], paths["ruwiktionary-extract"])
                forms_of_translated = enru.pending_form_keys()
                added = 0
                for key, ru_words in rev.items():
                    if key in enru.groups or key not in en.groups or key in forms_of_translated:
                        continue  # keep English Wiktionary's own table; skip non-English keys
                    head = sorted(en.groups[key], key=lambda s: (s != key, s))[0]
                    enru.add(head, "<p>" + ", ".join(esc(w) for w in ru_words) + "</p>")
                    added += 1
                print(f"  en-ru: {added} headwords from reversed Russian entries", file=sys.stderr)
            en.resolve_forms(standalone=True)
            enru.resolve_forms(standalone=False)
            cache["english"] = (en, enru)
        en, enru = cache["english"]
        return en if ident == "en-en-wiktionary" else enru
    if ident == "ru-en-wiktionary":
        return build_glossed(paths["kaikki-Russian"], ident, "ru", POS_EN, strip=True)
    if ident == "tg-en-wiktionary":
        return build_glossed(paths["kaikki-Tajik"], ident, "tg", POS_EN, strip=False)
    if ident == "ru-ru-wiktionary":
        if "ru-ru" in cache:
            return cache["ru-ru"]
        d = build_ruwikt(paths["ruwiktionary-extract"], "ru", ident)
        # Russian Wiktionary has few pages for inflected forms; English
        # Wiktionary has hundreds of thousands ("шёл" -> "идти"). Use them as
        # synonyms only (no text from them is shown).
        for rec in iter_jsonl(paths["kaikki-Russian"]):
            lemma = form_target(rec) if rec.get("lang_code") == "ru" else None
            if lemma:
                alt, minor = form_kind(rec)
                if minor:
                    d.dropped_minor += 1
                else:
                    d.add_syn(rec.get("word", ""), lemma, "spell" if alt else "infl")
        cache["ru-ru"] = d
        return d
    if ident == "tg-ru-wiktionary":
        return build_ruwikt(paths["ruwiktionary-extract"], "tg", ident)
    raise KeyError(ident)


# (dictionary, word, expected headword or None for "must not be found",
#  forbidden headword) — the build fails when one does not hold.
REQUIRED = [
    ("en-ru-wiktionary", "the", "", "thee"),
    ("en-ru-wiktionary", "went", "go", None),
    ("en-ru-wiktionary", "children", "child", None),
    ("en-en-wiktionary", "the", "the", None),
    ("en-en-wiktionary", "art", "art", None),
    ("ru-en-wiktionary", "шёл", "идти", None),
    ("ru-ru-wiktionary", "шёл", "идти", None),
    ("ru-en-wiktionary", "людей", "*", None),
    ("ru-ru-wiktionary", "людей", "*", None),
]

SAMPLES = {
    "en": ["book", "running", "children", "went", "better", "house", "colour", "mice", "beautiful", "London", "Polish"],
    "ru": ["книга", "дом", "Москва", "ёж", "еж", "шёл", "людей", "красивый", "читать", "мир", "быстро"],
    "tg": ["китоб", "хона", "об", "модар", "забон", "калон", "ман", "рафтан", "дил", "нон"],
}


def file_entry(path: str) -> dict:
    return {"name": os.path.basename(path), "size": os.path.getsize(path), "sha256": sha256_file(path)}


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    default_out = os.path.normpath(os.path.join(here, "..", "..", "..", "..", "releases", "dictionaries-1"))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cache", default=os.path.expanduser("~/.cache/folio-dictionaries"))
    ap.add_argument("--out", default=default_out)
    ap.add_argument("--only", nargs="*", help="dictionary ids to (re)build; others are kept and re-validated")
    ap.add_argument("--accept-new-inputs", action="store_true")
    ap.add_argument("--validate-only", action="store_true")
    ap.add_argument("--fetch-only", action="store_true", help="download and verify inputs, then stop")
    args = ap.parse_args()

    ids = [c["id"] for c in CATALOG]
    todo = ids if args.only is None else args.only
    if not args.validate_only:
        by_id = {c["id"]: c for c in CATALOG}
        needed = {i for c in CATALOG if c["id"] in todo
                  for i in c["inputs"] + (by_id[c["mono"]]["inputs"] if c["mono"] else [])}
        paths = fetch_inputs(args.cache, needed, args.accept_new_inputs)
        if args.fetch_only:
            for name in sorted(needed):
                print(f'{name}: size {INPUTS[name]["size"]} sha256 {INPUTS[name]["sha256"]}')
            return
        built = {}
        today = os.environ.get("SOURCE_DATE", datetime.date.today().isoformat())
        for c in CATALOG:
            if c["id"] not in todo:
                continue
            t0 = time.time()
            print(f"building {c['id']}", file=sys.stderr)
            mono = None
            if c["mono"] and c["mono"] != c["id"]:
                mono = set(build(c["mono"], paths, built).groups)
            d = build(c["id"], paths, built)
            d.dropped_collision = 0
            entries, syns = d.finalize(mono)
            print(f"  {c['id']}: .syn dropped: {d.dropped_minor} archaic/obsolete/etc. relations, "
                  f"{d.dropped_collision} non-inflection collisions with {c['mono'] or 'own'} headwords",
                  file=sys.stderr)
            desc = esc(wikt_attribution(c["inputs"])) + f" License text: {WIKT_LICENSE_URL}"
            stats = write_stardict(os.path.join(args.out, c["id"]), c["id"], entries, syns, {
                "bookname": c["bookname"], "lang": f"{c['from']}-{c['to']}", "website": "https://kaikki.org/", "date": today, "description": desc,
            })
            print(f"  {c['id']}: {stats} in {time.time() - t0:.0f}s", file=sys.stderr)

    manifest = {"version": 1, "dictionaries": []}
    report = []
    for c in CATALOG:
        folder = os.path.join(args.out, c["id"])
        if not os.path.isdir(folder):
            print(f"missing {c['id']}, skipped from manifest", file=sys.stderr)
            continue
        v = Validator(folder)
        files = [file_entry(os.path.join(folder, f"{c['id']}{ext}"))
                 for ext in (".ifo", ".idx", ".dict.dz", ".syn") if os.path.exists(os.path.join(folder, c["id"] + ext))]
        manifest["dictionaries"].append({
            "id": c["id"], "title": c["title"], "from": c["from"], "to": c["to"],
            "headwords": len(v.idx), "license": WIKT_LICENSE,
            "attribution": wikt_attribution(c["inputs"]),
            "source": INPUTS[c["inputs"][0]]["url"], "files": files,
            "inputs": [{"url": INPUTS[i]["url"], "sha256": INPUTS[i]["sha256"]} for i in c["inputs"]],
        })
        total = sum(f["size"] for f in files)
        report.append(f"== {c['id']}: {len(v.idx)} headwords, {len(v.syn)} synonyms, {total / 1e6:.1f} MB")
        words = SAMPLES[c["from"]] + [w for i, w, _, _ in REQUIRED if i == c["id"] and w not in SAMPLES[c["from"]]]
        for w in words:
            r = v.lookup(w)
            report.append(f"  {w!r}: " + ("NOT FOUND" if not r else f"[{r[1]}] {r[0]} — {plain(r[2])}"))
        for i, w, expect, forbidden in REQUIRED:
            if i != c["id"]:
                continue
            r = v.lookup(w)
            got = r[0] if r else None
            ok = (got != forbidden) and (expect == "" or (expect == "*" and got) or got == expect)
            if not ok:
                sys.exit(f"CHECK FAILED: {i} {w!r} -> {got!r} (expected {expect or 'anything but ' + str(forbidden)})")
            report.append(f"  check {w!r} -> {got!r}: ok")
    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    write_licenses(args.out, manifest)
    print("\n".join(report))


def write_licenses(out: str, manifest: dict) -> None:
    lines = [
        "# Dictionary licenses",
        "",
        "All dictionaries in this folder are derived from Wiktionary and are distributed under the",
        f"Creative Commons Attribution-ShareAlike 4.0 International license ({WIKT_LICENSE_URL}).",
        "They are adaptations: entries were filtered (single words only), reformatted to simple HTML,",
        "stress marks were removed from Russian text, and long entries were truncated.",
        "You may share and adapt them under the same license, with attribution.",
        "",
        "Build script: firmware/tools/inklink/dictionaries/build_dictionaries.py (inputs pinned by sha256).",
        "",
    ]
    for d in manifest["dictionaries"]:
        lines += [
            f"## {d['id']} — {d['title']['en']}",
            "",
            f"- License: {d['license']} ({WIKT_LICENSE_URL})",
            f"- Attribution: {d['attribution']}",
            *[f"- Source file: {i['url']} (sha256 `{i['sha256']}`)" for i in d["inputs"]],
            f"- Headwords: {d['headwords']}",
            "",
        ]
    lines += [
        "Wiktionary text is available under CC BY-SA 4.0 (and, for older revisions, GFDL);",
        "see https://en.wiktionary.org/wiki/Wiktionary:Copyrights and",
        "https://ru.wiktionary.org/wiki/Викисловарь:Авторское_право. Wiktextract and the kaikki.org",
        "extracts: https://github.com/tatuylonen/wiktextract (Tatu Ylonen, \"Wiktextract: Wiktionary as",
        "Machine-Readable Structured Data\", LREC 2022).",
        "",
    ]
    with open(os.path.join(out, "LICENSES.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


if __name__ == "__main__":
    main()
