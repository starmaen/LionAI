#!/usr/bin/env python3
"""يحوّل معارف Lion-1 المتعلَّمة (lion_learn.json من التطبيق) إلى بيانات تدريب JSONL لـ train_lora.py.

الاستخدام:
    python model/learned_to_dataset.py lion_learn.json model/data/learned.jsonl [--min-len 120]

الملف في الجهاز: <files>/lion_learn.json (انسخه إلى Termux أو مساحة العمل).
يُسقط أي زوج فيه سر أو بريد/هاتف أو اسم مزود احتياطاً (تُرشَّح في التطبيق أيضاً).
"""
import argparse
import json
import re
import sys

SECRET = re.compile(r"(ghp_|gho_|github_pat_|sk-[A-Za-z0-9]|AIza[0-9A-Za-z_-]{10}|xox[bp]-|AKIA[0-9A-Z]{8})")
CONTACT = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")
PROVIDERS = re.compile(r"(chatgpt|openai|gemini|claude|anthropic|deepseek|qwen|mistral|copilot|llama|grok|perplexity)", re.I)
SYSTEM = "أنت «أسد» (Lion-1)، مساعد ذكاء اصطناعي عربي متعدد المهام من Star Syria."


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--min-len", type=int, default=120)
    args = ap.parse_args()
    facts = json.load(open(args.src, encoding="utf-8"))
    seen, out = set(), 0
    with open(args.dst, "w", encoding="utf-8") as f:
        for it in facts:
            q, a = it.get("q", "").strip(), it.get("a", "").strip()
            if len(a) < args.min_len or q in seen:
                continue
            if any(p.search(q + " " + a) for p in (SECRET, CONTACT, PROVIDERS)):
                continue
            seen.add(q)
            rec = {"messages": [
                {"role": "system", "content": SYSTEM},
                {"role": "user", "content": q},
                {"role": "assistant", "content": a},
            ]}
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            out += 1
    print(f"wrote {out} examples to {args.dst}", file=sys.stderr)


if __name__ == "__main__":
    main()
