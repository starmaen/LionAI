#!/usr/bin/env python3
"""يستورد كتالوج الأدوات الحقيقي من كود التطبيق (AiTools.kt) إلى بيانات التدريب.

لماذا: أسماء الأدوات ومفاتيح الوسائط تُكتب في التطبيق مرة واحدة. أي تدريب على قائمة قديمة
يعلّم النموذج استدعاءات لا يعرفها التطبيق (أو مفاتيح خاطئة) فتفشل الأدوات. هذا السكربت يقرأ
`app/src/main/java/com/lion/ai/AiTools.kt` ويولّد:

  data/tools.json          قائمة الأسماء فقط (متوافقة مع eval_lion.py والنسخ القديمة)
  data/tools_catalog.json  الاسم + المجموعة + وسائط نموذجية + الوصف + هل هي أداة كتابة

تشغيل (من جذر المستودع أو من model/):
  python model/data/import_tools.py
  python model/data/import_tools.py --check   # يفشل إذا كانت الملفات قديمة
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
AI_TOOLS = os.path.join(ROOT, "app", "src", "main", "java", "com", "lion", "ai", "AiTools.kt")

# Def("name", ToolGroup.GROUP, \"""{"k":"v"}\"""", "وصف", [true])
# الحقول الإضافية بعد الوصف قد تكون موضعية أو مسماة:
#   Def("x", ToolGroup.X, \"""{}\"""", "وصف")                     → لا شيء
#   Def("x", ToolGroup.X, \"""{}\"""", "وصف", true)                → أداة كتابة
#   Def("x", ToolGroup.X, \"""{}\"""", "وصف", true, "google_calendar")
#   Def("x", ToolGroup.X, \"""{}\"""", "وصف", connector = "notion")
DEF_RE = re.compile(
    r'Def\(\s*"([A-Za-z0-9_]+)"\s*,\s*ToolGroup\.([A-Z_]+)\s*,\s*'
    r'(?:"""\s*(.*?)\s*"""|"(\{.*?\})")\s*,\s*'
    r'"((?:[^"\\]|\\.)*)"\s*([^)]*)\)',
    re.S,
)
CONNECTOR_RE = re.compile(r'"([a-z0-9_]+)"\s*$')


def parse_catalog(path=AI_TOOLS):
    if not os.path.exists(path):
        raise SystemExit(f"❌ لم أجد {path}")
    src = open(path, encoding="utf-8").read()
    tools, bad = [], 0
    for m in DEF_RE.finditer(src):
        name, group, raw_triple, raw_single, desc, extras = m.groups()
        raw_args = raw_triple or raw_single or "{}" 
        try:
            args = json.loads(raw_args)
        except Exception:
            # بعض الأمثلة تحتوي هروباً مضاعفاً (\\" داخل سلسلة) — نحاول تخفيفه قبل الاستسلام.
            try:
                args = json.loads(raw_args.replace('\\\\', '\\'))
            except Exception:
                bad += 1
                continue
        if not isinstance(args, dict):
            bad += 1
            continue
        extras = (extras or "").strip().strip(",").strip()
        connector = None
        cm = CONNECTOR_RE.search(extras)
        if cm:
            connector = cm.group(1)
        tools.append({
            "name": name,
            "group": group.lower(),
            "args": args,
            "description": desc.replace('\\"', '"').strip(),
            "write": extras.lower().startswith("true") or "write = true" in extras.lower(),
            "connector": connector,
        })
    # لا تكرار، مع الحفاظ على ترتيب الظهور في الكود
    seen, uniq = set(), []
    for t in tools:
        if t["name"] in seen:
            continue
        seen.add(t["name"])
        uniq.append(t)
    return uniq, bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="تحقّق فقط: هل الملفات محدّثة؟")
    ap.add_argument("--src", default=AI_TOOLS)
    a = ap.parse_args()

    tools, bad = parse_catalog(a.src)
    if not tools:
        raise SystemExit("❌ لم أستخرج أي أداة — تغيّرت صيغة Def(...) في AiTools.kt؟")

    names = sorted(t["name"] for t in tools)
    names_path = os.path.join(HERE, "tools.json")
    catalog_path = os.path.join(HERE, "tools_catalog.json")

    if a.check:
        old = json.load(open(names_path, encoding="utf-8")) if os.path.exists(names_path) else []
        missing = sorted(set(names) - set(old))
        extra = sorted(set(old) - set(names))
        if missing or extra:
            print(f"⚠️ tools.json قديم: ناقص {len(missing)}، زائد {len(extra)}")
            for n in missing[:10]:
                print("  +", n)
            for n in extra[:10]:
                print("  -", n)
            sys.exit(1)
        print(f"✅ tools.json محدّث ({len(names)} أداة)")
        return

    with open(names_path, "w", encoding="utf-8") as f:
        json.dump(names, f, ensure_ascii=False, indent=1)
    with open(catalog_path, "w", encoding="utf-8") as f:
        json.dump(tools, f, ensure_ascii=False, indent=1)

    groups = {}
    for t in tools:
        groups[t["group"]] = groups.get(t["group"], 0) + 1
    print(f"✅ {len(tools)} أداة في {len(groups)} مجموعة → {names_path}")
    print("   " + " · ".join(f"{k}:{v}" for k, v in sorted(groups.items())))
    if bad:
        print(f"   (تُخطّيت {bad} أداة بسبب وسائط غير صالحة)")


if __name__ == "__main__":
    main()
