#!/usr/bin/env python3
"""توسيع بيانات Lion-1 داخل Colab بنموذج «معلّم» مفتوح أكبر (بلا مفتاح API ولا بوابة).

الفكرة: نموذج 1.5B يتعلّم الأسلوب من أمثلة جيدة. نولّد مئات الأمثلة العربية المتنوعة
بنموذج أقوى (افتراضياً Qwen2.5-7B-Instruct بتكميم 4-bit على T4)، ثم نرشّحها بصرامة:
  - لا تسريب لاسم مزود/نموذج (LEAK) ولا كتل [[TOOL]] (هذه أمثلة محادثة فقط)
  - لا ردود مقطوعة (بلغت الحد الأقصى) ولا مكرّرة/حلقية
  - طول معقول، وعربية في الأسئلة العربية
وتُلحق الناتج بـ data/seed_extra.jsonl ثم يعيد generate_dataset.py دمجه.

الاستخدام (Colab، بعد توليد البيانات الأساسية):
    python distill_local.py --n 400
    python data/generate_dataset.py          # يدمج seed_extra.jsonl
    python eval_lion.py data data/train.jsonl data/val.jsonl

الترخيص: Qwen2.5-7B-Instruct بترخيص Apache-2.0. غيّر --teacher فقط بنموذج تسمح شروطه
بتدريب نموذج على مخرجاته، وراجع عيّنة من الأمثلة بنفسك قبل التدريب.
"""
import argparse
import json
import os
import random
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from data.generate_dataset import SYSTEM          # noqa: E402
from eval_lion import LEAK, check_tool_blocks     # noqa: E402

TOPICS = [
    "الذكاء الاصطناعي", "الأمن السيبراني", "الشبكات والإنترنت", "قواعد البيانات", "نظام أندرويد",
    "لغة Kotlin", "لغة Python", "Git وGitHub", "الحوسبة السحابية", "البطارية والطاقة",
    "الطاقة الشمسية", "الاقتصاد المنزلي", "الادخار والميزانية", "ريادة الأعمال", "التسويق الرقمي",
    "إدارة الوقت", "تعلّم لغة جديدة", "الدراسة الفعّالة", "مهارات المقابلة الوظيفية", "كتابة السيرة الذاتية",
    "الصحة العامة والنوم", "التغذية المتوازنة", "الرياضة المنزلية", "الإسعافات الأولية البسيطة", "الصحة النفسية والضغط",
    "تاريخ الحضارة الإسلامية", "الجغرافيا", "الفلك والكواكب", "الفيزياء اليومية", "الكيمياء المنزلية",
    "الرياضيات", "الإحصاء البسيط", "الهندسة المدنية", "الكهرباء المنزلية", "صيانة الحاسوب",
    "صيانة الهاتف", "التصوير بالهاتف", "المونتاج", "تصميم الواجهات", "تجربة المستخدم",
    "القانون المدني (مفاهيم عامة)", "حقوق المستهلك", "العقود البسيطة", "التجارة الإلكترونية", "الدفع الإلكتروني الآمن",
    "الزراعة المنزلية", "البيئة وإعادة التدوير", "الطبخ", "السفر وتخطيط الرحلات", "الأدب العربي",
    "النحو العربي", "الشعر العربي", "الترجمة", "الكتابة الإبداعية", "الخطابة والإلقاء",
]

# (قالب السؤال، نوع) — النوع يحدّد شروط الترشيح
TEMPLATES = [
    ("اشرح لي «{t}» ببساطة كأنني مبتدئ.", "ar"),
    ("ما أهم ٥ نصائح عملية في موضوع {t}؟", "ar"),
    ("قارن بين مفهومين أو خيارين شائعين في {t} مع مثال.", "ar"),
    ("ما الأخطاء الشائعة عند التعامل مع {t} وكيف أتجنبها؟", "ar"),
    ("لخّص لي في ثلاث جمل أبرز ما يجب معرفته عن {t}.", "ar"),
    ("اكتب لي رسالة قصيرة ومهذبة أطلب فيها مساعدة في موضوع يخص {t}.", "ar"),
    ("أعطني خطة تعلّم لمدة أسبوع في {t}.", "ar"),
    ("اطرح عليّ ثلاثة أسئلة اختبار قصيرة عن {t} مع أجوبتها.", "ar"),
    ("ترجم إلى الإنجليزية جملة بسيطة تصف {t}، ثم اشرح كلمة صعبة فيها.", "mixed"),
    ("اكتب مثالاً برمجياً صغيراً (Python أو Kotlin) له علاقة بـ {t} مع شرح موجز.", "code"),
]
EXTRA_DIRECT = [
    ("كم يساوي 15% من 240؟ اشرح الخطوات باختصار.", "ar"),
    ("صحّح لغوياً: «انا ذهبت الى المدرسه وكان الجو جميل جدا»", "ar"),
    ("ما الفرق بين الهمزة المتصلة والمنفصلة؟", "ar"),
    ("اشرح الفرق بين RAM وذاكرة التخزين في الهاتف.", "ar"),
    ("كيف أكتب رسالة اعتذار رسمية عن التأخر في تسليم عمل؟", "ar"),
]

ARABIC = re.compile(r"[\u0600-\u06FF]")


def build_prompts(n, seed):
    rng = random.Random(seed)
    pool = [(tpl.format(t=t), kind) for t in TOPICS for tpl, kind in TEMPLATES] + EXTRA_DIRECT
    rng.shuffle(pool)
    return pool[:n]


def arabic_ratio(s):
    letters = [c for c in s if c.isalpha()]
    return (sum(1 for c in letters if ARABIC.match(c)) / len(letters)) if letters else 0.0


def looks_loopy(s):
    lines = [l.strip() for l in s.splitlines() if l.strip()]
    if len(lines) >= 6 and len(set(lines)) / len(lines) < 0.6:
        return True
    words = s.split()
    return len(words) >= 40 and len(set(words)) / len(words) < 0.25


def accept(ans, kind, truncated):
    """يعيد None إن قُبل الرد، وإلا سبب الرفض."""
    if truncated:
        return "مقطوع"
    n = len(ans.strip())
    if n < 20 or n > 2200:
        return "طول"
    if LEAK.search(ans):
        return "تسريب"
    if "[[TOOL]]" in ans or "[[/TOOL]]" in ans or check_tool_blocks(ans):
        return "كتلة أداة"
    if looks_loopy(ans):
        return "تكرار"
    if kind == "ar" and arabic_ratio(ans) < 0.45:
        return "ليس عربياً"
    if kind == "mixed" and arabic_ratio(ans) < 0.15:
        return "ليس عربياً"
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher", default="Qwen/Qwen2.5-7B-Instruct")
    ap.add_argument("--n", type=int, default=400, help="عدد الأسئلة المولّدة")
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--max_new", type=int, default=450)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--no_4bit", action="store_true")
    ap.add_argument("--dry_run", action="store_true", help="8 أسئلة فقط للتحقق من المسار")
    a = ap.parse_args()

    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig

    if not torch.cuda.is_available():
        sys.exit("❌ يلزم GPU (Runtime → T4). على CPU سيكون التوليد بطيئاً جداً.")
    n = 8 if a.dry_run else a.n
    prompts = build_prompts(n, a.seed)
    out = os.path.join(HERE, "data", "seed_extra.jsonl")
    have = set()
    if os.path.exists(out):
        for line in open(out, encoding="utf-8"):
            line = line.strip()
            if line:
                try:
                    have.add(json.loads(line)["messages"][-2]["content"])
                except Exception:
                    pass
    prompts = [(q, k) for q, k in prompts if q not in have]
    print(f"🧑‍🏫 المعلّم: {a.teacher} · أسئلة جديدة: {len(prompts)}")

    tok = AutoTokenizer.from_pretrained(a.teacher)
    tok.padding_side = "left"
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    quant = None if a.no_4bit else BitsAndBytesConfig(
        load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_use_double_quant=True,
        bnb_4bit_compute_dtype=torch.float16)
    model = AutoModelForCausalLM.from_pretrained(
        a.teacher, quantization_config=quant, torch_dtype=torch.float16, device_map="auto")
    model.eval()

    teacher_sys = SYSTEM + "\n- هذه محادثة عادية: لا تستدعِ أدوات ولا تكتب [[TOOL]]. اجعل الرد مفيداً ومنظماً وغير مطوّل."
    kept, rejected = 0, {}
    with open(out, "a", encoding="utf-8") as f:
        for i in range(0, len(prompts), a.batch):
            chunk = prompts[i:i + a.batch]
            texts = [tok.apply_chat_template(
                [{"role": "system", "content": teacher_sys}, {"role": "user", "content": q}],
                tokenize=False, add_generation_prompt=True) for q, _ in chunk]
            enc = tok(texts, return_tensors="pt", padding=True).to(model.device)
            with torch.no_grad():
                gen = model.generate(**enc, max_new_tokens=a.max_new, do_sample=True,
                                     temperature=0.6, top_p=0.9, repetition_penalty=1.05,
                                     pad_token_id=tok.pad_token_id)
            for (q, kind), ids in zip(chunk, gen):
                new = ids[enc["input_ids"].shape[1]:]
                truncated = int((new != tok.pad_token_id).sum()) >= a.max_new
                ans = tok.decode(new, skip_special_tokens=True).strip()
                why = accept(ans, kind, truncated)
                if why:
                    rejected[why] = rejected.get(why, 0) + 1
                    continue
                f.write(json.dumps({"messages": [
                    {"role": "system", "content": SYSTEM},
                    {"role": "user", "content": q},
                    {"role": "assistant", "content": ans}]}, ensure_ascii=False) + "\n")
                f.flush()
                kept += 1
            print(f"  {min(i + a.batch, len(prompts))}/{len(prompts)} · مقبول {kept}")
    print(f"\n✅ أُضيف {kept} مثالاً إلى data/seed_extra.jsonl · مرفوض: {rejected or 'لا شيء'}")
    print("الخطوة التالية: python data/generate_dataset.py ثم راجع عيّنة: shuf -n 5 data/seed_extra.jsonl")


if __name__ == "__main__":
    main()
