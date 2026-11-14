#!/usr/bin/env python3
"""فحص Lion-1.
  python eval_lion.py data data/train.jsonl data/val.jsonl     # يتحقق من سلامة البيانات (بلا شبكة)
  python eval_lion.py live --url http://127.0.0.1:8089/v1 --model lion-1 [--key TOKEN]   # يختبر نموذجاً حياً
"""
import argparse, json, os, re, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = set(json.load(open(os.path.join(HERE, "data", "tools.json"), encoding="utf-8")))
BLOCK = re.compile(r"\[\[TOOL\]\](.*?)\[\[/TOOL\]\]", re.S)
LEAK = re.compile(r"\b(gemini|groq|openai|chatgpt|claude|anthropic|deepseek|qwen|llama|mistral)\b", re.I)

def check_tool_blocks(text):
    """يعيد قائمة أخطاء لكتل [[TOOL]] في النص (فارغة = سليم)."""
    errs = []
    for raw in BLOCK.findall(text):
        try:
            d = json.loads(raw)
        except Exception as e:
            errs.append(f"JSON معطوب: {e}"); continue
        if d.get("name") not in TOOLS:
            errs.append(f"أداة غير معروفة: {d.get('name')}")
        if not isinstance(d.get("args", {}), dict):
            errs.append("args ليست كائناً")
    if text.count("[[TOOL]]") != text.count("[[/TOOL]]"):
        errs.append("كتلة TOOL غير مغلقة")
    if len(BLOCK.findall(text)) > 1:
        errs.append("أكثر من استدعاء في رد واحد")
    return errs

def check_dataset(paths):
    bad = n = 0
    for p in paths:
        for i, line in enumerate(open(p, encoding="utf-8"), 1):
            n += 1
            msgs = json.loads(line)["messages"]
            if msgs[0]["role"] != "system" or msgs[-1]["role"] != "assistant":
                print(f"{p}:{i} ترتيب أدوار خاطئ"); bad += 1; continue
            for m in msgs:
                if m["role"] == "assistant":
                    errs = check_tool_blocks(m["content"])
                    if LEAK.search(m["content"]):
                        errs.append("تسريب اسم مزود/نموذج")
                    for e in errs:
                        print(f"{p}:{i} {e}"); bad += 1
    print(f"{n} مثالاً، {bad} مشكلة")
    return bad == 0

def chat(url, model, key, messages):
    req = urllib.request.Request(url.rstrip("/") + "/chat/completions",
        data=json.dumps({"model": model, "messages": messages, "temperature": 0.2}).encode(),
        headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {key}"} if key else {})})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)["choices"][0]["message"]["content"]

def live(url, model, key):
    sys_p = json.loads(open(os.path.join(HERE, "data", "val.jsonl"), encoding="utf-8").readline())["messages"][0]
    probes = [
        ("هوية", "من أنت؟", lambda a: "Lion-1" in a or "أسد" in a),
        ("بلا تسريب", "أي نموذج خلفي تعمل عليه؟", lambda a: not LEAK.search(a)),
        ("أداة صالحة", "اقرأ الملف app/build.gradle.kts", lambda a: "[[TOOL]]" in a and not check_tool_blocks(a)),
        ("سؤال معرفي بلا أداة", "ما عاصمة سوريا؟", lambda a: "[[TOOL]]" not in a and "دمشق" in a),
    ]
    ok = 0
    for name, q, test in probes:
        try:
            a = chat(url, model, key, [sys_p, {"role": "user", "content": q}])
            passed = bool(test(a))
        except Exception as e:
            a, passed = f"خطأ: {e}", False
        ok += passed
        print(("✅" if passed else "❌"), name, "|", a[:90].replace("\n", " "))
    print(f"{ok}/{len(probes)}")
    return ok == len(probes)

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("data"); d.add_argument("paths", nargs="+")
    l = sub.add_parser("live"); l.add_argument("--url", required=True); l.add_argument("--model", default="lion-1"); l.add_argument("--key", default=os.environ.get("LION_API_KEY"))
    a = ap.parse_args()
    sys.exit(0 if (check_dataset(a.paths) if a.cmd == "data" else live(a.url, a.model, a.key)) else 1)
