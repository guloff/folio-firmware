#!/usr/bin/env python3
"""Seed the simulator SD card with synthetic InkLink data (sessions, highlights,
vocabulary, shelves) so the stats screen, heatmap and sleep screens have
something to draw.

Usage: python3 tools/inklink/seed_stats.py [sd_root]   (default: fs_)
Uses the same on-card formats as src/inklink (JSON Lines, compact keys).
"""
import json
import random
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path

BOOKS = [
    ("/books/Тихий_берег.epub", "Тихий берег"),
    ("/books/The_Lighthouse_Keeper.epub", "The Lighthouse Keeper"),
    ("/books/Заметки_о_чтении.epub", "Заметки о чтении"),
]


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "fs_")
    d = root / ".crosspoint" / "inklink"
    d.mkdir(parents=True, exist_ok=True)
    rnd = random.Random(42)

    today = date.today()
    lines = []
    for back in range(140, 0, -1):  # up to yesterday: today stays for live testing
        day = today - timedelta(days=back)
        if rnd.random() < 0.28:
            continue
        for _ in range(rnd.choice([1, 1, 2])):
            path, title = rnd.choice(BOOKS)
            start = datetime(day.year, day.month, day.day, rnd.choice([7, 8, 13, 21, 22]), rnd.randint(0, 59))
            secs = rnd.randint(5, 70) * 60
            lines.append({"b": path, "t": title, "s": int(time.mktime(start.timetuple())), "d": secs,
                          "p": secs // 75, "c": min(100, back % 97), "day": int(day.strftime("%Y%m%d"))})
    lines.sort(key=lambda x: x["s"])
    (d / "sessions.jsonl").write_text("".join(json.dumps(l, ensure_ascii=False) + "\n" for l in lines))

    hl = [
        ("/books/Заметки_о_чтении.epub", "Заметки о чтении",
         "Читать каждый день понемногу полезнее, чем много, но редко: привычка сильнее вдохновения."),
        ("/books/Тихий_берег.epub", "Тихий берег",
         "Слова в нём были правильные, но в них не было главного."),
        ("/books/The_Lighthouse_Keeper.epub", "The Lighthouse Keeper",
         "Ships passed like slow thoughts on the horizon."),
    ]
    now = int(time.time())
    (d / "highlights.jsonl").write_text("".join(
        json.dumps({"id": f"hseed{i}", "b": b, "t": t, "x": x, "n": "", "ch": "", "c": 20 + i * 10,
                    "sp": 1, "pg": 2, "ts": now - 86400 * (5 - i)}, ensure_ascii=False) + "\n"
        for i, (b, t, x) in enumerate(hl)))

    vocab = [("ephemeral", "the ephemeral glow of distant towns", "/books/The_Lighthouse_Keeper.epub"),
             ("horizon", "Ships passed like slow thoughts on the horizon", "/books/The_Lighthouse_Keeper.epub")]
    (d / "vocab.jsonl").write_text("".join(
        json.dumps({"w": w, "cx": cx, "b": b, "ts": now - 3600}, ensure_ascii=False) + "\n" for w, cx, b in vocab))

    lib = {"version": 1,
           "shelves": [{"id": "ru", "name": "На русском", "books": [BOOKS[0][0], BOOKS[2][0]]},
                       {"id": "en", "name": "English", "books": [BOOKS[1][0]]}],
           "status": {BOOKS[0][0]: "reading", BOOKS[1][0]: "want"},
           "goals": {"dailyMinutes": 30}}
    (d / "library.json").write_text(json.dumps(lib, ensure_ascii=False))
    print(f"seeded {len(lines)} sessions, {len(hl)} highlights, {len(vocab)} words into {d}")


if __name__ == "__main__":
    main()
