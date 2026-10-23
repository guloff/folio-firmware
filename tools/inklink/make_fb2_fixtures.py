#!/usr/bin/env python3
"""FB2 fixtures for the simulator.

Writes into <sd_root>/books (default: fs_):
  FB2_test.fb2            - UTF-8 FictionBook: cover (<coverpage> + base64
                            JPEG <binary>), parts with nested chapters, an
                            epigraph, a poem, inline emphasis, note links into
                            <body name="notes">, an inline image
  FB2_test_cp1251.fb2.zip - the same book in windows-1251, zipped

Usage: python3 tools/inklink/make_fb2_fixtures.py [sd_root]
Needs Pillow for the cover (the project's .venv has it); without it the
books are written without one. The text is generated, so the fixtures are
free of copyright concerns.
"""
import base64
import io
import sys
import zipfile
from pathlib import Path

SENTENCES = [
    "Утро выдалось тихим, и над рекой ещё долго держался туман.",
    "Анна открыла книгу на заложенной странице и забыла про остывший чай.",
    "Дорога петляла между холмами, словно не хотела приводить путника к цели.",
    "В доме пахло яблоками, старой бумагой и чем-то неуловимо знакомым.",
    "Он говорил медленно, взвешивая каждое слово, как монету на ладони.",
    "За окном шумел дождь, а в печи потрескивали берёзовые поленья.",
    "Никто не помнил, откуда в библиотеке взялся этот потрёпанный том.",
    "Вечером они вышли к пристани и долго смотрели на огни другого берега.",
]


def paragraph(seed, length=5):
    return " ".join(SENTENCES[(seed + i) % len(SENTENCES)] for i in range(length))


def cover_jpeg():
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        return None
    img = Image.new("L", (300, 450), 235)
    draw = ImageDraw.Draw(img)
    draw.rectangle([20, 20, 279, 429], outline=0, width=6)
    draw.rectangle([50, 140, 249, 300], fill=60)
    draw.ellipse([110, 170, 190, 250], fill=235)
    out = io.BytesIO()
    img.save(out, "JPEG", quality=80)
    return out.getvalue()


def chapter(title, seed, notes=(), paragraphs=6):
    body = [f"<section><title><p>{title}</p></title>"]
    for i in range(paragraphs):
        text = paragraph(seed + i)
        if i == 1:
            text = text.replace("книгу", "<emphasis>книгу</emphasis>", 1).replace(
                "держался", "<strong>держался</strong>", 1)
        if i < len(notes):
            text += f'<a l:href="#{notes[i]}" type="note">[{notes[i][1:]}]</a>'
        body.append(f"<p>{text}</p>")
    body.append("</section>")
    return "".join(body)


def book(encoding, cover):
    cover_page = '<coverpage><image l:href="#cover.jpg"/></coverpage>' if cover else ""
    parts = [
        f'<?xml version="1.0" encoding="{encoding}"?>\n',
        '<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" '
        'xmlns:l="http://www.w3.org/1999/xlink">',
        "<description><title-info><genre>prose_contemporary</genre>",
        "<author><first-name>Иван</first-name><last-name>Тестов</last-name></author>",
        "<book-title>Тихая пристань</book-title>",
        "<annotation><p>Небольшая повесть для проверки FB2.</p></annotation>",
        f"{cover_page}<lang>ru</lang></title-info>",
        "<document-info><author><nickname>folio</nickname></author><id>folio-fb2-test</id>"
        "<version>1.0</version></document-info></description>",
        "<body><title><p>Иван Тестов</p><p>Тихая пристань</p></title>",
        "<epigraph><p>Всякая книга — это пристань для того, кто читает.</p>"
        "<text-author>Неизвестный автор</text-author></epigraph>",
        '<section><title><p>Часть первая</p><p>Туман</p></title>',
        chapter("Глава 1. Утро", 0, notes=("n1", "n2")),
        chapter("Глава 2. Дорога", 3, notes=("n3",), paragraphs=8),
        "</section>",
        '<section><title><p>Часть вторая</p><p>Огни</p></title>',
        "<section><title><p>Глава 3. Стихи у реки</p></title>",
        f"<p>{paragraph(5, 3)}</p>",
        "<poem><title><p>Песня перевозчика</p></title><stanza>",
        "<v>Над водой туман ложится,</v><v>Лодка тихо в нём плывёт,</v>",
        "<v>Тот, кому сегодня снится,</v><v>Утром к берегу придёт.</v>",
        "</stanza><text-author>Перевозчик</text-author></poem>",
        "<empty-line/>",
        f'<p>{paragraph(1, 4)}</p><image l:href="#cover.jpg"/>' if cover else f"<p>{paragraph(1, 4)}</p>",
        f'<p>{paragraph(6)}<a l:href="#n4" type="note">[4]</a></p>',
        "</section>",
        chapter("Глава 4. Пристань", 7, paragraphs=10),
        "</section></body>",
        '<body name="notes"><title><p>Примечания</p></title>',
        '<section id="n1"><title><p>1</p></title><p>Туман над рекой бывает по утрам в конце лета.</p></section>',
        '<section id="n2"><title><p>2</p></title><p>Строчка из старой песни, которую пела бабушка.</p></section>',
        '<section id="n3"><title><p>3</p></title><p>Дорога через холмы короче, но труднее.</p></section>',
        '<section id="n4"><title><p>4</p></title><p>Огни другого берега видно только в ясную ночь.</p></section>',
        "</body>",
    ]
    if cover:
        b64 = base64.b64encode(cover).decode("ascii")
        wrapped = "\n".join(b64[i:i + 76] for i in range(0, len(b64), 76))
        parts.append(f'<binary id="cover.jpg" content-type="image/jpeg">{wrapped}</binary>')
    parts.append("</FictionBook>\n")
    return "".join(parts).encode(encoding)


def main():
    sd_root = Path(sys.argv[1] if len(sys.argv) > 1 else "fs_")
    books = sd_root / "books"
    books.mkdir(parents=True, exist_ok=True)
    cover = cover_jpeg()
    if cover is None:
        print("Pillow not found: writing books without a cover")

    (books / "FB2_test.fb2").write_bytes(book("utf-8", cover))
    with zipfile.ZipFile(books / "FB2_test_cp1251.fb2.zip", "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("FB2_test_cp1251.fb2", book("windows-1251", cover))
    print(f"Wrote FB2 fixtures to {books}")


if __name__ == "__main__":
    main()
