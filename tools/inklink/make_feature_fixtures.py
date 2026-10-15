#!/usr/bin/env python3
"""Simulator fixtures for Folio reader features.

Writes into <sd_root> (default: fs_):
  books/Footnotes_test.epub   - footnote links of several kinds: an <aside>
                                note, a <p id> note, an empty <a id> anchor in
                                a paragraph, a link to a heading (not a note),
                                a link that targets itself (note in notes.xhtml)
                                and a <span> wrapping block-level note content
  dictionaries/ru-test/ru-test.{ifo,idx,dict}
                              - a four-word StarDict dictionary with Russian
                                lemmas, for inflected-form lookups

Usage: python3 tools/inklink/make_feature_fixtures.py [sd_root]
The text is generated, so the fixtures are free of copyright concerns.
"""
import struct
import sys
import uuid
import zipfile
from pathlib import Path

CHAPTER = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="ru">
<head><title>Глава</title></head>
<body>
<h1>Глава первая</h1>
<p>Она читала книгами по вечерам<a epub:type="noteref" href="notes.xhtml#n1" id="r1">1</a>,
и красивого в этом было больше, чем казалось<a epub:type="noteref" href="notes.xhtml#n2" id="r2">2</a>.</p>
<p>Третья сноска спрятана в абзаце<a epub:type="noteref" href="notes.xhtml#n3" id="r3">3</a>,
а эта ссылка ведёт на заголовок<a epub:type="noteref" href="notes.xhtml#notes">4</a>.</p>
<p>Дети смеялись, а взрослые читают и сегодня.</p>
<p>Сломанная ссылка ведёт сама на себя<a class="_idFootnoteLink" href="#n5" id="n5">5</a>,
а шестая сноска завёрнута в span<a epub:type="noteref" href="notes.xhtml#n6">6</a>.</p>
</body>
</html>
"""

NOTES = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="ru">
<head><title>Примечания</title></head>
<body>
<h2 id="notes">Примечания</h2>
<aside epub:type="footnote" id="n1"><p><a href="ch1.xhtml#r1">1</a> Первая сноска: блок <i>aside</i>
с курсивом.</p></aside>
<p id="n2"><a href="ch1.xhtml#r2">2</a> Вторая сноска: абзац с идентификатором.</p>
<!-- <p id="n3">это комментарий, его не показываем</p> -->
<p><a id="n3"></a>3. Третья сноска: пустой якорь внутри абзаца.</p>
<div class="_idFootnote" id="n5"><p><a href="#n5">5.</a> Пятая сноска: найдена в файле примечаний,
хотя ссылка указывала сама на себя.</p></div>
<span id="n6"><div class="title"><p>6</p></div><p>Шестая сноска: блоки внутри span.</p></span>
</body>
</html>
"""


def write_epub(path: Path) -> None:
    book_id = f"urn:uuid:{uuid.uuid4()}"
    opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="id">{book_id}</dc:identifier><dc:title>Сноски (тест)</dc:title>
<dc:creator>Folio</dc:creator><dc:language>ru</dc:language>
<meta property="dcterms:modified">2026-10-15T00:00:00Z</meta>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
<item id="notes" href="notes.xhtml" media-type="application/xhtml+xml"/>
</manifest>
<spine><itemref idref="ch1"/><itemref idref="notes"/></spine>
</package>"""
    nav = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>nav</title></head>
<body><nav epub:type="toc"><ol><li><a href="ch1.xhtml">Глава первая</a></li>
<li><a href="notes.xhtml">Примечания</a></li></ol></nav></body></html>"""
    container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", container, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/content.opf", opf, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/nav.xhtml", nav, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/ch1.xhtml", CHAPTER, compress_type=zipfile.ZIP_DEFLATED)
        z.writestr("OEBPS/notes.xhtml", NOTES, compress_type=zipfile.ZIP_DEFLATED)


WORDS = {
    "книга": "Книга — печатное или электронное издание.",
    "красивый": "Красивый — приятный на вид.",
    "смеяться": "Смеяться — издавать смех.",
    "читать": "Читать — воспринимать написанный текст.",
}


def write_dictionary(folder: Path) -> None:
    folder.mkdir(parents=True, exist_ok=True)
    stem = folder.name
    data = b""
    idx = b""
    # StarDict orders headwords by g_ascii_strcasecmp, then strcmp: bytes for UTF-8.
    for word in sorted(WORDS, key=lambda w: w.encode("utf-8")):
        definition = WORDS[word].encode("utf-8")
        idx += word.encode("utf-8") + b"\0" + struct.pack(">II", len(data), len(definition))
        data += definition
    (folder / f"{stem}.dict").write_bytes(data)
    (folder / f"{stem}.idx").write_bytes(idx)
    (folder / f"{stem}.ifo").write_text(
        "StarDict's dict ifo file\nversion=2.4.2\nbookname=RU test\n"
        f"wordcount={len(WORDS)}\nidxfilesize={len(idx)}\nsametypesequence=m\n",
        encoding="utf-8",
    )


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "fs_")
    write_epub(root / "books" / "Footnotes_test.epub")
    write_dictionary(root / "dictionaries" / "ru-test")
    print(f"fixtures written under {root}")


if __name__ == "__main__":
    main()
