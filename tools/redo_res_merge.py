# 重做资源合并：不加前缀，只处理真冲突
import os, shutil, re

BASE = r"D:\Software\NEW ERA\wb data\2026-11-05-14-11-21"
M = os.path.join(BASE, r".workbuddy\backup\TVWebPlayer-0.5.1\app\src\main\res")
C = os.path.join(BASE, r"TVWebPlayer\app\src\main\res")

# 1) 删除我之前过度的 ext_* 资源
removed = []
for sub in os.listdir(C):
    p = os.path.join(C, sub)
    if not os.path.isdir(p):
        continue
    for f in os.listdir(p):
        if f.startswith("ext_") and f.endswith(".xml"):
            os.remove(os.path.join(p, f))
            removed.append(sub + "/" + f)
print("removed over-prefixed:", len(removed))

# 2) 从备份复制我的全部资源，只改真冲突的文件名
RENAME = {
    "layout/activity_main.xml":   "ext_activity_main.xml",
    "layout/activity_player.xml": "ext_activity_player.xml",
    "values/colors.xml":   "ext_colors.xml",
    "values/dimens.xml":   "ext_dimens.xml",
    "values/ids.xml":      "ext_ids.xml",
    "values/strings.xml":  "ext_strings.xml",
    "values/themes.xml":   "ext_themes.xml",
    "values/styles_text.xml": "ext_styles_text.xml",
}

copied, skipped = [], []
for sub in os.listdir(M):
    src_dir = os.path.join(M, sub)
    if not os.path.isdir(src_dir):
        continue
    dst_dir = os.path.join(C, sub)
    os.makedirs(dst_dir, exist_ok=True)
    for f in os.listdir(src_dir):
        rel = sub + "/" + f
        dst_name = RENAME.get(rel, f)
        dst = os.path.join(dst_dir, dst_name)
        # 已存在且不在 RENAME 里的，说明是 blbl 的（或已复制）→ 冲突，跳过并报告
        if os.path.exists(dst) and rel not in RENAME:
            skipped.append(rel)
            continue
        shutil.copy2(os.path.join(src_dir, f), dst)
        copied.append(sub + "/" + dst_name)

print("copied:", len(copied))
print("skipped(existing):", skipped)
