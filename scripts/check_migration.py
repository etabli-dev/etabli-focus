#!/usr/bin/env python3
"""Exercise the production v1 -> v2 SQL against SQLite, preserving historical rows.
This complements, but does not replace, an on-device Room upgrade check.
"""
import re
import sqlite3
from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = (root / "android/app/src/main/java/com/raban/etabli/focus/data/AppDatabase.kt").read_text()
statements = re.findall(r'db.execSQL\("([^"\n]+)"\)', source)
assert statements and '.addMigrations(MIGRATION_1_2)' in source
with sqlite3.connect(":memory:") as db:
    db.execute("CREATE TABLE session (id TEXT PRIMARY KEY NOT NULL, categoryID TEXT NOT NULL, startedAtEpoch INTEGER NOT NULL, plannedEndEpoch INTEGER NOT NULL, actualEndEpoch INTEGER, completed INTEGER NOT NULL, kind TEXT NOT NULL, note TEXT)")
    row = ("preserved", "category", 1000, 61000, 62000, 1, "focus", "original note")
    db.execute("INSERT INTO session VALUES (?,?,?,?,?,?,?,?)", row)
    for sql in statements: db.execute(sql)
    assert db.execute("SELECT * FROM session").fetchone() == row + (None,)
    db.execute("UPDATE session SET activeMillis = 60000 WHERE id = 'preserved'")
    assert db.execute("SELECT activeMillis FROM session").fetchone() == (60000,)
print("Production SQLite migration preserves v1 history and accepts v2 active duration")
