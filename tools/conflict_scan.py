import os, re, collections

BASE = r"D:\Software\NEW ERA\wb data\2026-11-05-14-11-21"
B = os.path.join(BASE, r".workbuddy\tmp\blbl-full\blbl-main\app\src\main\res")
M = os.path.join(BASE, r".workbuddy\backup\TVWebPlayer-0.5.1\app\src\main\res")

def index(res_dir):
    d = collections.defaultdict(set)
    for sub in os.listdir(res_dir):
        p = os.path.join(res_dir, sub)
        if not os.path.isdir(p):
            continue
        rtype = sub.split("-")[0]
        for f in os.listdir(p):
            if rtype == "values" and f.endswith(".xml"):
                txt = open(os.path.join(p, f), encoding="utf-8", errors="ignore").read()
                for m in re.finditer(r'<(color|dimen|string|style|bool|integer|attr)\b[^>]*?\bname="([^"]+)"', txt):
                    d[m.group(1)].add(m.group(2))
            elif rtype != "values":
                d[rtype].add(f.rsplit(".", 1)[0])
    return d

db, dm = index(B), index(M)

print("=== same-type same-name CONFLICTS ===")
total = 0
for t in sorted(set(db) | set(dm)):
    both = db[t] & dm[t]
    if both:
        total += len(both)
        print("  [%s] %s" % (t, ", ".join(sorted(both))))
print("TOTAL conflicting resource keys:", total)

print()
print("=== values-file overlap (whole file names) ===")
vb = {f for f in os.listdir(os.path.join(B, "values")) if f.endswith(".xml")}
vm = {f for f in os.listdir(os.path.join(M, "values")) if f.endswith(".xml")}
print("  both:", sorted(vb & vm))

print()
print("=== blbl value-file list ===")
print(" ", sorted(vb))
print("=== mine value-file list ===")
print(" ", sorted(vm))
