import os, re, collections

ROOT = r"D:\Software\NEW ERA\wb data\2026-11-05-14-11-21\TVWebPlayer\app\src\main"
RES = os.path.join(ROOT, "res")

defined = collections.defaultdict(set)

# values
for d in os.listdir(RES):
    p = os.path.join(RES, d)
    if not os.path.isdir(p):
        continue
    rtype = d.split("-")[0]
    if rtype == "values":
        for f in os.listdir(p):
            if not f.endswith(".xml"):
                continue
            txt = open(os.path.join(p, f), encoding="utf-8", errors="ignore").read()
            for m in re.finditer(r'<(color|dimen|string|style|bool|integer|array|attr|item)\b[^>]*?\bname="([^"]+)"', txt):
                defined[m.group(1)].add(m.group(2))
    else:
        for f in os.listdir(p):
            defined[rtype].add(f.rsplit(".", 1)[0])

# @+id declared in ALL layouts
plus = re.compile(r'@\+id/([A-Za-z0-9_]+)')
for f in os.listdir(os.path.join(RES, "layout")):
    txt = open(os.path.join(RES, "layout", f), encoding="utf-8", errors="ignore").read()
    for m in plus.finditer(txt):
        defined["id"].add(m.group(1))

MINE = [
 "ext_activity_main.xml","ext_activity_player.xml",
 "activity_browse.xml","activity_favorites.xml","activity_lan_import.xml","activity_native_player.xml",
 "activity_playlist.xml","activity_web.xml","item_bili_card.xml","item_card_chip.xml","item_card_poster.xml",
 "item_hero.xml","item_more_button.xml","item_playlist_chip.xml","item_playlist_header.xml",
 "item_playlist_row.xml","item_row.xml","item_section_title.xml","item_shortcut.xml","item_site.xml",
 "item_site_tile.xml",
]

pat = re.compile(r'@(?:\+)?(color|dimen|style|drawable|id|string|layout|mipmap|bool|integer|attr)/([A-Za-z0-9_.]+)')
missing = collections.Counter()
for f in MINE:
    p = os.path.join(RES, "layout", f)
    txt = open(p, encoding="utf-8", errors="ignore").read()
    for m in pat.finditer(txt):
        t, n = m.group(1), m.group(2)
        if n not in defined[t]:
            missing[(t, n)] += 1

print("=== MISSING refs in my %d layouts ===" % len(MINE))
for (t, n), c in sorted(missing.items()):
    print("  @%s/%s (x%d)" % (t, n, c))
print("  none" if not missing else "  total %d" % len(missing))

# also: Java R.* refs in ext package
print("\n=== R.* refs in ext java not found in res ===")
jp = os.path.join(ROOT, "java", "blbl", "cat3399", "ext")
rpat = re.compile(r'\bR\.(color|dimen|style|drawable|id|string|layout|mipmap)\.([A-Za-z0-9_]+)')
jmiss = collections.Counter()
for dp, _, fns in os.walk(jp):
    for fn in fns:
        if not fn.endswith(".kt"):
            continue
        txt = open(os.path.join(dp, fn), encoding="utf-8", errors="ignore").read()
        for m in rpat.finditer(txt):
            t, n = m.group(1), m.group(2)
            if n not in defined[t]:
                jmiss[(t, n)] += 1
for (t, n), c in sorted(jmiss.items()):
    print("  R.%s.%s (x%d)" % (t, n, c))
print("  none" if not jmiss else "  total %d" % len(jmiss))
