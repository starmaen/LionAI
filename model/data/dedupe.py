#!/usr/bin/env python3
"""ترشيح التكرار وتسرّب التحقق في بيانات Lion-1 بعد توسيعها.

لماذا: التوسيع بالتقطير يولّد أسئلة متشابهة؛ التكرار يُفرط تدريب النموذج على صيغ بعينها،
وتسرّب أسئلة val إلى train يجعل eval_loss يبدو أفضل مما هو.

    python data/dedupe.py                       # يعمل على data/train.jsonl و data/val.jsonl في مكانهما
    python data/dedupe.py --threshold 0.85      # أصرم/أكثر تساهلاً (Jaccard على 5-grams حروف)
    python data/dedupe.py --semantic            # يضيف فحصاً دلالياً إن توفرت sentence-transformers

الافتراضي بلا أي مكتبة خارجية. يحتفظ بنسخة قبل التعديل: *.pre_dedupe
"""
import argparse
import json
import os
import re
import shutil

HERE = os.path.dirname(os.path.abspath(__file__))
HEADER = "مقتطفات ويب مرقمة"          # بداية كتلة المصادر في رسائل التأريض
AR_DIAC = re.compile(r"[\u064B-\u0652\u0640]")


def question_of(row):
    """نص السؤال فقط (قبل كتلة المصادر) لآخر رسالة مستخدم."""
    users = [m["content"] for m in row["messages"] if m["role"] == "user"]
    q = users[-1] if users else ""
    return q.split(HEADER)[0]


def norm(s):
    s = AR_DIAC.sub("", s.lower())
    s = re.sub(r"[أإآ]", "ا", s).replace("ى", "ي").replace("ة", "ه")
    return re.sub(r"[^\w]+", " ", s, flags=re.U).strip()


def shingles(s, n=5):
    s = f" {norm(s)} "
    return {s[i:i + n] for i in range(max(1, len(s) - n + 1))}


def jaccard(a, b):
    return len(a & b) / len(a | b) if a and b else 0.0


def is_tool_turn(row):
    return any("[[TOOL]]" in m["content"] for m in row["messages"] if m["role"] == "assistant")


def load(p):
    return [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()]


def save(p, rows):
    with open(p, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def semantic_filter(rows, val, thr):
    try:
        from sentence_transformers import SentenceTransformer
        import numpy as np
    except ImportError:
        print("ℹ️ sentence-transformers غير مثبتة — تخطّي الفحص الدلالي (pip install sentence-transformers)")
        return None
    model = SentenceTransformer("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
    tq = [question_of(r) for r in rows]
    vq = [question_of(r) for r in val]
    te = model.encode(tq, normalize_embeddings=True, batch_size=64)
    ve = model.encode(vq, normalize_embeddings=True, batch_size=64) if vq else None
    keep, dropped_val, dropped_dup = [], 0, 0
    kept_emb = []
    for i, r in enumerate(rows):
        if ve is not None and float((ve @ te[i]).max()) >= thr:
            dropped_val += 1; continue
        if kept_emb and float((np.array(kept_emb) @ te[i]).max()) >= thr:
            dropped_dup += 1; continue
        keep.append(r); kept_emb.append(te[i])
    print(f"🧠 دلالي: تسرّب {dropped_val} · تكرار {dropped_dup}")
    return keep


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--train", default=os.path.join(HERE, "train.jsonl"))
    ap.add_argument("--val", default=os.path.join(HERE, "val.jsonl"))
    ap.add_argument("--threshold", type=float, default=0.8)
    ap.add_argument("--semantic", action="store_true")
    ap.add_argument("--sem_threshold", type=float, default=0.92)
    ap.add_argument("--dry", action="store_true", help="إحصاء فقط بلا تعديل")
    a = ap.parse_args()

    train, val = load(a.train), load(a.val)
    val_sh = [shingles(question_of(r)) for r in val]
    keep, kept_sh, leak, dup = [], [], 0, 0
    for r in train:
        sh = shingles(question_of(r))
        if any(jaccard(sh, v) >= a.threshold for v in val_sh):
            leak += 1; continue
        # مكرر = سؤال متشابه (≥ 0.6) وجواب متشابه (≥ threshold) معاً. هكذا يبقى: سؤالان مختلفان بجواب واحد
        # (أسئلة الهوية)، وسؤال واحد بجوابين مختلفين (مثلاً مع مقتطف حاقن وبدونه)؛ ويُحذف: نفس السؤال
        # بمقدمة مختلفة («بسرعة:» / «لو سمحت،») وبالجواب نفسه، أو الأمثلة المنسوخة حرفياً.
        ans = shingles(r["messages"][-1]["content"])
        if any(jaccard(sh, kq) >= 0.6 and jaccard(ans, ka) >= a.threshold for kq, ka in kept_sh):
            dup += 1; continue
        keep.append(r); kept_sh.append((sh, ans)); continue
    print(f"قبل: {len(train)} · تسرّب من val: {leak} · مكرر: {dup} · بعد: {len(keep)}")
    if a.semantic:
        sem = semantic_filter(keep, val, a.sem_threshold)
        if sem is not None:
            keep = sem
    if a.dry:
        print("(dry) لم يُكتب شيء"); return
    for p in (a.train,):
        shutil.copyfile(p, p + ".pre_dedupe")
    save(a.train, keep)
    print(f"✅ كُتب {a.train} ({len(keep)} مثالاً)")


if __name__ == "__main__":
    main()
