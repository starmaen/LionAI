#!/usr/bin/env python3
"""ضبط Lion-1 بـ LoRA فوق نموذج مفتوح يدعم العربية. يحتاج GPU (Colab/Kaggle T4 مجانية).

تشغيل:  python train_lora.py --base Qwen/Qwen2.5-1.5B-Instruct --epochs 3
الناتج: out/lion-1-lora (محوّل LoRA فقط، بضعة عشرات MB).
ملاحظة: واجهة TRL تتغير بين الإصدارات؛ إن اشتكى SFTConfig من اسم معامل فراجع توثيق إصدارك.
"""
import argparse, torch
from datasets import load_dataset
from peft import LoraConfig
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig
from trl import SFTConfig, SFTTrainer

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="Qwen/Qwen2.5-1.5B-Instruct")
ap.add_argument("--train", default="data/train.jsonl")
ap.add_argument("--val", default="data/val.jsonl")
ap.add_argument("--out", default="out/lion-1-lora")
ap.add_argument("--epochs", type=float, default=3)
ap.add_argument("--lr", type=float, default=2e-4)
ap.add_argument("--max_len", type=int, default=2048)
ap.add_argument("--no_4bit", action="store_true", help="تعطيل التكميم 4-bit (يحتاج ذاكرة أكبر)")
a = ap.parse_args()

tok = AutoTokenizer.from_pretrained(a.base)
if tok.pad_token is None:
    tok.pad_token = tok.eos_token

quant = None
if not a.no_4bit:
    quant = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                               bnb_4bit_compute_dtype=torch.float16, bnb_4bit_use_double_quant=True)
model = AutoModelForCausalLM.from_pretrained(a.base, quantization_config=quant,
                                             torch_dtype=torch.float16, device_map="auto")

lora = LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, task_type="CAUSAL_LM",
                  target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"])

ds = load_dataset("json", data_files={"train": a.train, "val": a.val})
cfg = SFTConfig(output_dir=a.out, num_train_epochs=a.epochs, learning_rate=a.lr,
                per_device_train_batch_size=2, gradient_accumulation_steps=8,
                logging_steps=5, save_strategy="epoch", eval_strategy="epoch",
                fp16=True, max_length=a.max_len, lr_scheduler_type="cosine", warmup_ratio=0.05,
                report_to="none")
trainer = SFTTrainer(model=model, args=cfg, train_dataset=ds["train"], eval_dataset=ds["val"],
                     peft_config=lora, processing_class=tok)
trainer.train()
trainer.save_model(a.out)
tok.save_pretrained(a.out)
print("saved:", a.out)
