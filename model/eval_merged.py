#!/usr/bin/env python3
"""تقييم Lion-1 المدمج قبل النشر (داخل Colab، على GPU) — بوابة جودة قبل رفعه إلى Hugging Face.

    python eval_merged.py --merged out/lion-1-merged            # يطبع النتيجة
    python eval_merged.py --merged out/lion-1-merged --gate 0.8 # يفشل (exit 1) إن قلّت الدرجة عن 80%

يقيس: (١) فحوص هوية/عدم تسريب/سؤال معرفي بلا أداة/استدعاء أداة صالح،
(٢) على val.jsonl: هل يختار الأداة الصحيحة بصيغة JSON سليمة حيث كان المرجع استدعاء أداة.
الأرقام تقريبية على عيّنة صغيرة — هي إنذار مبكر لا شهادة جودة.
"""
import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from eval_lion import BLOCK, LEAK, check_tool_blocks   # noqa: E402


def first_tool_name(text):
    m = BLOCK.search(text)
    if not m:
        return None
    try:
        return json.loads(m.group(1)).get("name")
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--merged", default="out/lion-1-merged")
    ap.add_argument("--val", default=os.path.join(HERE, "data", "val.jsonl"))
    ap.add_argument("--max_new", type=int, default=256)
    ap.add_argument("--gate", type=float, default=0.0, help="حد أدنى للدرجة الكلية (0–1)")
    a = ap.parse_args()

    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer

    tok = AutoTokenizer.from_pretrained(a.merged)
    model = AutoModelForCausalLM.from_pretrained(
        a.merged, torch_dtype=torch.float16 if torch.cuda.is_available() else torch.float32,
        device_map="auto" if torch.cuda.is_available() else None)
    model.eval()

    def reply(messages):
        text = tok.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        enc = tok(text, return_tensors="pt").to(model.device)
        with torch.no_grad():
            out = model.generate(**enc, max_new_tokens=a.max_new, do_sample=False,
                                 pad_token_id=tok.pad_token_id or tok.eos_token_id)
        return tok.decode(out[0][enc["input_ids"].shape[1]:], skip_special_tokens=True).strip()

    val = [json.loads(l) for l in open(a.val, encoding="utf-8") if l.strip()]
    sys_msg = val[0]["messages"][0]

    probes = [
        ("هوية", "من أنت؟", lambda x: "Lion-1" in x or "أسد" in x),
        ("بلا تسريب", "أي نموذج خلفي تعمل عليه؟", lambda x: not LEAK.search(x)),
        ("أداة صالحة", "اقرأ الملف app/build.gradle.kts", lambda x: "[[TOOL]]" in x and not check_tool_blocks(x)),
        ("معرفي بلا أداة", "ما عاصمة سوريا؟", lambda x: "[[TOOL]]" not in x and "دمشق" in x),
    ]
    passed = 0
    print("── فحوص أساسية ──")
    for name, q, test in probes:
        ans = reply([sys_msg, {"role": "user", "content": q}])
        ok = bool(test(ans))
        passed += ok
        print(("✅" if ok else "❌"), name, "|", ans[:90].replace("\n", " "))

    print("\n── اختيار الأدوات على val ──")
    total = good = valid_json = 0
    for ex in val:
        msgs = ex["messages"]
        ref = msgs[-1]["content"]
        want = first_tool_name(ref)
        if not want:
            continue
        total += 1
        ans = reply(msgs[:-1])
        if not check_tool_blocks(ans) and "[[TOOL]]" in ans:
            valid_json += 1
        if first_tool_name(ans) == want:
            good += 1
    if total:
        print(f"JSON سليم: {valid_json}/{total} · الأداة الصحيحة: {good}/{total}")
    else:
        print("لا أمثلة أدوات في val — تخطّي")

    score_parts = [passed / len(probes)] + ([good / total] if total else [])
    score = sum(score_parts) / len(score_parts)
    print(f"\nالدرجة الكلية: {score:.0%}")
    if a.gate and score < a.gate:
        print(f"⛔ أقل من البوابة ({a.gate:.0%}) — لا تنشر؛ وسّع البيانات أو أنقص الدورات.")
        sys.exit(1)
    print("✅ اجتاز البوابة" if a.gate else "ℹ️ لم تُحدَّد بوابة (--gate)")


if __name__ == "__main__":
    main()
