#!/usr/bin/env python3
"""التعلّم من مزوّديك: يولّد أمثلة تدريب من نماذج قوية عبر بوابتك (OpenAI-compatible) مع ترشيح صارم.

  export LION_API_KEY=<RELAY_TOKEN>
  URL=https://lionai-gateway.starsyria2500.workers.dev/v1
  python distill.py --url $URL --list-models                        # يعرض النماذج المتاحة
  python distill.py --url $URL --models m1,m2,m3 --auto 300         # يدوّر النماذج على 300 سؤال متنوع
  python distill.py --url $URL --models m1,m2 --auto 300 --agree    # يقبل الجواب فقط إن وافقه نموذج ثانٍ
  python distill.py --url $URL --models m1 prompts.txt              # أسئلتك أنت (سطر لكل سؤال)

- نفس فلاتر distill_local.py (تسريب أسماء، كتل أدوات، ردود مقطوعة/مكرّرة، لغة).
- --agree: فحص اتفاق تقريبي (تشابه كلمات) بين نموذجين؛ يقلّل الهلوسة لكنه ليس ضماناً.
- يسجّل مصدر كل مثال في data/distill_log.jsonl (النموذج + السؤال) لتحذف لاحقاً ما يخالف شروط مزوّد.
- يستأنف تلقائياً: لا يكرر سؤالاً موجوداً في seed_extra.jsonl.

⚠️ راجع شروط استخدام كل مزوّد قبل تدريب نموذج على مخرجاته (بعضها يمنع ذلك)، وراجع عيّنة بنفسك.
"""
import argparse
import hashlib
import json
import os
import random
import re
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from data.generate_dataset import SYSTEM          # noqa: E402
from distill_local import accept, build_prompts   # noqa: E402

OUT = os.path.join(HERE, "data", "seed_extra.jsonl")
LOG = os.path.join(HERE, "data", "distill_log.jsonl")
WORD = re.compile(r"\w+", re.U)


def http_json(url, key, payload=None, timeout=120):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = "Bearer " + key
        headers["X-Api-Key"] = key
    req = urllib.request.Request(url, data=json.dumps(payload).encode() if payload is not None else None, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def list_models(base, key):
    d = http_json(base.rstrip("/") + "/models", key)
    return [m.get("id", "") for m in d.get("data", [])]


def ask(base, key, model, question, retries=3, temperature=0.4):
    body = {"model": model, "temperature": temperature, "max_tokens": 600,
            "messages": [{"role": "system", "content": SYSTEM + "\n- هذه محادثة عادية: لا تستدعِ أدوات."},
                         {"role": "user", "content": question}]}
    last = None
    for i in range(retries):
        try:
            d = http_json(base.rstrip("/") + "/chat/completions", key, body)
            ch = d["choices"][0]
            return ch["message"]["content"].strip(), ch.get("finish_reason") == "length"
        except Exception as e:                       # شبكة/429/خطأ مزوّد → تراجع أسّي
            last = e
            time.sleep(1.5 * (2 ** i))
    raise RuntimeError(str(last)[:120])


def similar(a, b):
    """تشابه كلمات (Jaccard) — إشارة اتفاق تقريبية بين جوابين."""
    x, y = set(WORD.findall(a)), set(WORD.findall(b))
    return len(x & y) / len(x | y) if x and y else 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("prompts", nargs="?", help="ملف أسئلة (سطر لكل سؤال)")
    ap.add_argument("--url", required=True)
    ap.add_argument("--models", default="", help="نماذج مفصولة بفواصل (تُدوَّر)")
    ap.add_argument("--model", default="", help="نموذج واحد (توافق مع الإصدار القديم)")
    ap.add_argument("--key", default=os.environ.get("LION_API_KEY"))
    ap.add_argument("--auto", type=int, default=0, help="ولّد N سؤالاً متنوعاً تلقائياً")
    ap.add_argument("--agree", action="store_true", help="اشترط اتفاق نموذج ثانٍ")
    ap.add_argument("--agree_min", type=float, default=0.22)
    ap.add_argument("--sleep", type=float, default=0.3)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--list-models", action="store_true")
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--log", default=LOG)
    a = ap.parse_args()

    if a.list_models:
        for m in list_models(a.url, a.key):
            print(m)
        return
    models = [m.strip() for m in (a.models or a.model).split(",") if m.strip()]
    if not models:
        sys.exit("حدّد --models (أو استعمل --list-models لمعرفة المتاح)")
    if a.agree and len(models) < 2:
        sys.exit("--agree يحتاج نموذجين على الأقل")

    prompts = []
    if a.prompts:
        prompts += [(l.strip(), "ar") for l in open(a.prompts, encoding="utf-8") if l.strip()]
    if a.auto:
        prompts += build_prompts(a.auto, a.seed)
    if not prompts:
        sys.exit("لا أسئلة: مرّر ملفاً أو --auto N")

    have = set()
    if os.path.exists(a.out):
        for line in open(a.out, encoding="utf-8"):
            if line.strip():
                try:
                    have.add(json.loads(line)["messages"][-2]["content"])
                except Exception:
                    pass
    todo = [(q, k) for q, k in prompts if q not in have]
    print(f"📋 أسئلة جديدة: {len(todo)} · نماذج: {', '.join(models)}")

    kept, rejected, per_model = 0, {}, {}
    rng = random.Random(a.seed)
    with open(a.out, "a", encoding="utf-8") as f, open(a.log, "a", encoding="utf-8") as lg:
        for i, (q, kind) in enumerate(todo):
            order = models[i % len(models):] + models[:i % len(models)]
            m1 = order[0]
            try:
                ans, trunc = ask(a.url, a.key, m1, q)
            except Exception as e:
                rejected["خطأ مزوّد"] = rejected.get("خطأ مزوّد", 0) + 1
                print(f"تخطي [{m1}]: {str(e)[:60]}")
                continue
            why = accept(ans, kind, trunc)
            if not why and a.agree:
                m2 = rng.choice(order[1:])
                try:
                    ans2, trunc2 = ask(a.url, a.key, m2, q)
                    if trunc2 or similar(ans, ans2) < a.agree_min:
                        why = "لا اتفاق"
                except Exception:
                    why = "تعذّر التحقق"
            if why:
                rejected[why] = rejected.get(why, 0) + 1
            else:
                f.write(json.dumps({"messages": [{"role": "system", "content": SYSTEM},
                                                 {"role": "user", "content": q},
                                                 {"role": "assistant", "content": ans}]}, ensure_ascii=False) + "\n")
                lg.write(json.dumps({"model": m1, "prompt_sha": hashlib.sha1(q.encode()).hexdigest()[:12],
                                     "prompt": q, "ts": int(time.time())}, ensure_ascii=False) + "\n")
                f.flush(); lg.flush()
                kept += 1
                per_model[m1] = per_model.get(m1, 0) + 1
            if (i + 1) % 20 == 0:
                print(f"  {i + 1}/{len(todo)} · مقبول {kept}")
            time.sleep(a.sleep)
    print(f"\n✅ أُضيف {kept} · لكل نموذج: {per_model or '-'} · مرفوض: {rejected or 'لا شيء'}")
    print("راجع عيّنة: shuf -n 5 data/seed_extra.jsonl ثم python data/generate_dataset.py")


if __name__ == "__main__":
    main()
