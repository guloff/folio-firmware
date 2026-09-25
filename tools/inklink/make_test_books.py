#!/usr/bin/env python3
"""Generate small EPUB 3 test books (Russian + English) for the simulator SD card.

Usage: python3 tools/inklink/make_test_books.py [out_dir]   (default: fs_/books)
The text is generated filler, so the books are free of copyright concerns.
"""
import sys
import uuid
import zipfile
from pathlib import Path

BOOKS = [
    ("Тихий берег", "Анна Северная", "ru", [
        "Утро начиналось медленно. Над рекой стоял туман, и лодки у причала казались нарисованными углём на сером листе.",
        "Мария открыла окно и долго слушала, как просыпается город: звон трамвая, крик чаек, шаги первых прохожих.",
        "Она думала о письме, которое так и не отправила. Слова в нём были правильные, но в них не было главного.",
        "К полудню туман рассеялся, и берег оказался совсем близко, ближе, чем она помнила с детства.",
    ]),
    ("The Lighthouse Keeper", "Thomas Grey", "en", [
        "The lamp had burned for forty years without a single night of darkness, and Elias intended to keep it that way.",
        "Every evening he climbed the one hundred and twelve steps, counting them aloud as his father had taught him.",
        "Ships passed like slow thoughts on the horizon. Some signalled, most did not, and he wished them well all the same.",
        "When the storm came in October, it came sideways, carrying salt and the ephemeral glow of distant towns.",
    ]),
    ("Заметки о чтении", "Иван Книжников", "ru", [
        "Хорошая книга похожа на разговор с умным собеседником, который никуда не торопится.",
        "Читать каждый день понемногу полезнее, чем много, но редко: привычка сильнее вдохновения.",
        "Выделяйте то, что задело. Через год эти строки расскажут о вас больше, чем дневник.",
        "Незнакомые слова стоит записывать вместе с предложением, в котором они встретились.",
    ]),
]

CHAPTERS = 6
PARAS_PER_CHAPTER = 14


def chapter_xhtml(lang, title, n, paras):
    body = []
    for i in range(PARAS_PER_CHAPTER):
        body.append(f"<p>{paras[(i + n) % len(paras)]}</p>")
    head = "Глава" if lang == "ru" else "Chapter"
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="{lang}" lang="{lang}">
<head><title>{title}</title></head>
<body><h2>{head} {n + 1}</h2>
{chr(10).join(body)}
</body></html>"""


def make_book(path, title, author, lang, paras):
    uid = str(uuid.uuid5(uuid.NAMESPACE_URL, title))
    manifest = []
    spine = []
    nav_items = []
    files = {}
    for n in range(CHAPTERS):
        name = f"ch{n + 1}.xhtml"
        files[f"OEBPS/{name}"] = chapter_xhtml(lang, title, n, paras)
        manifest.append(f'<item id="ch{n + 1}" href="{name}" media-type="application/xhtml+xml"/>')
        spine.append(f'<itemref idref="ch{n + 1}"/>')
        label = ("Глава" if lang == "ru" else "Chapter") + f" {n + 1}"
        nav_items.append(f'<li><a href="{name}">{label}</a></li>')
    files["OEBPS/nav.xhtml"] = f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>{title}</title></head>
<body><nav epub:type="toc"><ol>{''.join(nav_items)}</ol></nav></body></html>"""
    files["OEBPS/content.opf"] = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="uid">urn:uuid:{uid}</dc:identifier>
<dc:title>{title}</dc:title><dc:creator>{author}</dc:creator><dc:language>{lang}</dc:language>
<meta property="dcterms:modified">2026-09-26T00:00:00Z</meta>
</metadata>
<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
{''.join(manifest)}</manifest>
<spine>{''.join(spine)}</spine></package>"""
    container = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/container.xml", container, compress_type=zipfile.ZIP_DEFLATED)
        for name, data in files.items():
            z.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "fs_/books")
    out.mkdir(parents=True, exist_ok=True)
    for title, author, lang, paras in BOOKS:
        safe = "".join(c if c.isalnum() or c in " -_" else "_" for c in title).strip().replace(" ", "_")
        make_book(out / f"{safe}.epub", title, author, lang, paras)
        print("wrote", out / f"{safe}.epub")


if __name__ == "__main__":
    main()
