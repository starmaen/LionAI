#!/usr/bin/env python3
"""توليد أمثلة تدريب إضافية من مزودات قوية عبر بوابتك (OpenAI-compatible)، مع ترشيح صارم.
  export LION_API_KEY=<RELAY_TOKEN>
  python distill.py --url https://lionai-gateway.starsyria2500.workers.dev/v1 --model <provider/model> prompts.txt
يضيف الأمثلة المقبولة إلى data/seed_extra.jsonl، ثم أعد تشغيل data/generate_dataset.py.
راجع الأمثلة بنفسك قبل التدريب، وراعِ شروط استخدام المزود قبل تدريب نموذج على مخرجاته.
"""
import argparse, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from data.generate_dataset import SYSTEM
from eval_lion import LEAK, check_tool_blocks, chat

ap = argparse.ArgumentParser()
ap.add_argument("prompts"); ap.add_argument("--url", required=True); ap.add_argument("--model", required=True)
ap.add_argument("--key", default=os.environ.get("LION_API_KEY"))
a = ap.parse_args()
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "seed_extra.jsonl")
kept = 0
with open(out, "a", encoding="utf-8") as f:
    for q in (l.strip() for l in open(a.prompts, encoding="utf-8")):
        if not q:
            continue
        try:
            ans = chat(a.url, a.model, a.key, [{"role": "system", "content": SYSTEM}, {"role": "user", "content": q}])
        except Exception as e:
            print("تخطي:", q[:40], e); continue
        if LEAK.search(ans) or check_tool_blocks(ans) or len(ans) < 5:
            print("رُفض:", q[:40]); continue
        f.write(json.dumps({"messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": q},
                                         {"role": "assistant", "content": ans}]}, ensure_ascii=False) + "\n")
        kept += 1
print("أُضيف", kept)
