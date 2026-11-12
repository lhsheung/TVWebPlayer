import os, re, sys, collections

ROOT = r"D:\Software\NEW ERA\wb data\2026-11-05-14-11-21\TVWebPlayer\app\src\main"
RES = os.path.join(ROOT, "res")
JAVA = os.path.join(ROOT, "java")

# 1) index all defined resources
defined = collections.defaultdict(set)   # type -> names
for d in os.listdir(RES):
    p = os.path.join(RES, d)
    if not os.path.isdir(p):
        continue
    rtype = d.split("-")[0]
    for f in os.listdir(p):
        if f == "values" or d.startswith("values"):
            pass
    if rtype == "values":
        for f in os.listdir(p):
            if not f.endswith(".xml"):
                continue
            txt = open(os.path.join(p, f), encoding="utf-8", errors="ignore").read()
            for m in re.finditer(r"<(color|dimen|string|style|bool|integer|array|attr|item)\b[^>]*?\bname=\"([^\"]+)\"", txt):
                defined[m.group(1)].add(m.group(2))
    else:
        for f in os.listdir(p):
            name = f.rsplit(".", 1)[0]
            defined[rtype].add(name)

# 2) scan ext layouts + ext java for references
refs = collections.Counter()
missing = collections.Counter()
files_scanned = []

ext_layouts = [f for f in os.listdir(os.path.join(RES, "layout")) if f.startswith("ext_")]
# also my non-colliding layouts (from backup list, no blbl counterpart)
MINE_EXTRA = ["activity_browse.xml","activity_favorites.xml","activity_lan_import.xml","activity_native_player.xml",
              "activity_playlist.xml","activity_web.xml","item_bili_card.xml","item_card_chip.xml","item_card_poster.xml",
              "item_hero.xml","item_more_button.xml","item_playlist_chip.xml","item_playlist_header.xml",
              "item_playlist_row.xml","item_row.xml","item_section_title.xml","item_shortcut.xml","item_site.xml",
              "item_site_tile.xml"]
targets = ext_layouts + MINE_EXTRA

pat = re.compile(r'@(?:\+)?(color|dimen|style|drawable|id|string|layout|mipmap|bool|integer|attr)/([A-Za-z0-9_.]+)')
for f in targets:
    p = os.path.join(RES, "layout", f)
    if not os.path.exists(p):
        print("MISSING FILE:", f); continue
    files_scanned.append(f)
    txt = open(p, encoding="utf-8", errors="ignore").read()
    for m in pat.finditer(txt):
        t, n = m.group(1), m.group(2)
        refs[(t, n)] += 1
        if n not in defined[t]:
            missing[(t, n)] += 1

print("=== scanned %d layouts ===" % len(files_scanned))
print("\n=== MISSING REFS in my layouts ===")
for (t, n), c in sorted(missing.items()):
    print("  @%s/%s  (x%d)" % (t, n, c))
if not missing:
    print("  none")

# 3) for missing ones, check whether an ext_-prefixed version exists
print("\n=== suggested fixes (ext_ variant exists?) ===")
for (t, n), c in sorted(missing.items()):
    cand = "ext_" + n
    ok = cand in defined[t]
    print("  @%s/%s -> @%s/%s  %s" % (t, n, t, cand, "OK" if ok else "STILL MISSING"))
