#!/usr/bin/env python3
"""ضبط Lion-1 بـ LoRA فوق نموذج مفتوح يدعم العربية. يحتاج GPU (Colab/Kaggle T4 مجانية).

الاستخدام الأساسي:
    python train_lora.py                            # Qwen2.5-1.5B، 3 دورات (مناسب لهاتف 4GB)
    python train_lora.py --base Qwen/Qwen2.5-0.5B-Instruct   # الأسرع للأجهزة الضعيفة
    python train_lora.py --base Qwen/Qwen2.5-3B-Instruct    # يحتاج T4/L4 وذاكرة أكبر
    python train_lora.py --dry_run                   # 12 خطوة فقط للتحقق من أن كل شيء يعمل

الناتج: out/lion-1-lora (محوّل LoRA فقط، بضعة عشرات MB) — ثم merge_export.py ثم export_gguf.py.

ملاحظات توافق: واجهات TRL/transformers تتغير بين الإصدارات، فهذا الملف يبني SFTConfig
بالمعاملات المتاحة فعلاً في الإصدار المثبّت (max_length/max_seq_length، eval_strategy/
evaluation_strategy، processing_class/tokenizer) بدل الانهيار على اسم معامل واحد.
"""
import argparse
import inspect
import os
import sys

import torch
from datasets import load_dataset
from peft import LoraConfig
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig, TrainingArguments
from trl import SFTConfig, SFTTrainer

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="Qwen/Qwen2.5-1.5B-Instruct")
ap.add_argument("--train", default="data/train.jsonl")
ap.add_argument("--val", default="data/val.jsonl")
ap.add_argument("--out", default="out/lion-1-lora")
ap.add_argument("--epochs", type=float, default=3)
ap.add_argument("--lr", type=float, default=2e-4)
ap.add_argument("--max_len", type=int, default=2048)
ap.add_argument("--batch", type=int, default=2)
ap.add_argument("--grad_accum", type=int, default=8)
ap.add_argument("--lora_r", type=int, default=16)
ap.add_argument("--lora_alpha", type=int, default=32)
ap.add_argument("--seed", type=int, default=7)
ap.add_argument("--no_4bit", action="store_true", help="تعطيل التكميم 4-bit (يحتاج ذاكرة أكبر)")
ap.add_argument("--dry_run", action="store_true", help="12 خطوة فقط للتحقق من المسار كاملاً")
ap.add_argument("--resume", action="store_true")
a = ap.parse_args()

if not torch.cuda.is_available() and not a.no_4bit:
    print("⚠️ لا يوجد GPU: سيعمل بـ CPU ببطء شديد. استخدم Colab/Kaggle (T4) أو أضف --no_4bit.")
if not os.path.exists(a.train):
    sys.exit(f"❌ لم أجد {a.train} — شغّل أولاً: python data/generate_dataset.py")
if not os.path.exists(a.val):
    sys.exit(f"❌ لم أجد {a.val} — شغّل أولاً: python data/generate_dataset.py")

print(f"🧠 الأساس: {a.base}")
tok = AutoTokenizer.from_pretrained(a.base, trust_remote_code=True)
if tok.pad_token is None:
    tok.pad_token = tok.eos_token
tok.padding_side = "right"

quant = None
if not a.no_4bit:
    quant = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16,
        bnb_4bit_use_double_quant=True,
    )
dtype = torch.bfloat16 if (torch.cuda.is_available() and torch.cuda.is_bf16_supported()) else torch.float16
model = AutoModelForCausalLM.from_pretrained(
    a.base,
    quantization_config=quant,
    torch_dtype=None if quant else dtype,
    device_map="auto" if torch.cuda.is_available() else None,
    trust_remote_code=True,
)
model.config.use_cache = False
if hasattr(model, "enable_input_require_grads"):
    model.enable_input_require_grads()

lora = LoraConfig(
    r=a.lora_r,
    lora_alpha=a.lora_alpha,
    lora_dropout=0.05,
    bias="none",
    task_type="CAUSAL_LM",
    target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
)

ds = load_dataset("json", data_files={"train": a.train, "val": a.val})
print(f"📚 أمثلة: تدريب {len(ds['train'])} · تحقق {len(ds['val'])}")
if len(ds["train"]) < 100:
    print("⚠️ أقل من 100 مثال: سيتعلّم الأسلوب وصيغة الأدوات لكن قد يحفظها حرفياً (overfit). "
          "وسّعها بـ distill.py قبل تدريب جدي.")

# ─── بناء الإعدادات بما يدعمه إصدار TRL المثبّت (تحصين ضد تغيّر الأسماء) ───
cfg_fields = set(SFTConfig.__dataclass_fields__.keys()) if hasattr(SFTConfig, "__dataclass_fields__") else set()
cfg_kwargs = dict(
    output_dir=a.out,
    num_train_epochs=1 if a.dry_run else a.epochs,
    max_steps=12 if a.dry_run else -1,
    learning_rate=a.lr,
    per_device_train_batch_size=a.batch,
    per_device_eval_batch_size=a.batch,
    gradient_accumulation_steps=a.grad_accum,
    logging_steps=1 if a.dry_run else 5,
    save_strategy="no" if a.dry_run else "epoch",
    save_total_limit=2,
    fp16=not (dtype == torch.bfloat16),
    bf16=(dtype == torch.bfloat16),
    gradient_checkpointing=True,
    lr_scheduler_type="cosine",
    warmup_ratio=0.05,
    report_to="none",
    seed=a.seed,
    data_seed=a.seed,
)
seq_key = "max_length" if "max_length" in cfg_fields else ("max_seq_length" if "max_seq_length" in cfg_fields else None)
if seq_key:
    cfg_kwargs[seq_key] = a.max_len
eval_key = "eval_strategy" if "eval_strategy" in cfg_fields else ("evaluation_strategy" if "evaluation_strategy" in cfg_fields else None)
if eval_key and not a.dry_run:
    cfg_kwargs[eval_key] = "epoch"
    # أفضل نقطة حفظ بحسب eval_loss بدل آخر دورة (يحدّ من أثر الإفراط في التخصيص)
    cfg_kwargs["load_best_model_at_end"] = True
    cfg_kwargs["metric_for_best_model"] = "eval_loss"
    cfg_kwargs["greater_is_better"] = False
# التدريب يحتاج مساحة أكبر من الذاكرة المتوفرة على T4 مع حزمة معيّنة → التغليف اختياري
if "packing" in cfg_fields:
    cfg_kwargs["packing"] = False
cfg = SFTConfig(**{k: v for k, v in cfg_kwargs.items() if not cfg_fields or k in cfg_fields})

trainer_kwargs = dict(model=model, args=cfg, train_dataset=ds["train"], eval_dataset=ds["val"], peft_config=lora)
sig = inspect.signature(SFTTrainer.__init__).parameters
if "processing_class" in sig:
    trainer_kwargs["processing_class"] = tok
elif "tokenizer" in sig:
    trainer_kwargs["tokenizer"] = tok
if "dataset_text_field" in cfg_fields:
    # بعض الإصدارات القديمة تتوقع حقل نص جاهز؛ نمرّر messages كي يُطبَّق قالب المحادثة.
    pass

trainer = SFTTrainer(**trainer_kwargs)
stats = trainer.train(resume_from_checkpoint=a.resume)
trainer.save_model(a.out)
tok.save_pretrained(a.out)

loss = None
for entry in reversed(stats.metrics.values() if isinstance(stats.metrics, dict) else []):
    pass
if isinstance(stats.metrics, dict):
    loss = stats.metrics.get("train_loss")
print("\n==== نتيجة التدريب ====")
print(f"المخرجات: {a.out}")
if loss is not None:
    print(f"train_loss = {loss:.4f}" + (" (تشغيل تجريبي — ليس تدريباً حقيقياً)" if a.dry_run else ""))
print("الخطوة التالية:")
print("  python merge_export.py --base %s --lora %s" % (a.base, a.out))
print("  python export_gguf.py --merged out/lion-1-merged      # ينتج lion-1-Q4_K_M.gguf")
