#!/usr/bin/env python3
"""دمج محوّل LoRA في النموذج الأساسي → out/lion-1-merged (صيغة HF)، ثم GGUF للتشغيل المحلي.
تشغيل: python merge_export.py --base Qwen/Qwen2.5-1.5B-Instruct --lora out/lion-1-lora
بعدها (من مجلد llama.cpp):
  python convert_hf_to_gguf.py <path>/out/lion-1-merged --outfile lion-1.f16.gguf
  ./llama-quantize lion-1.f16.gguf lion-1.Q4_K_M.gguf Q4_K_M
"""
import argparse, torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

ap = argparse.ArgumentParser()
ap.add_argument("--base", default="Qwen/Qwen2.5-1.5B-Instruct")
ap.add_argument("--lora", default="out/lion-1-lora")
ap.add_argument("--out", default="out/lion-1-merged")
a = ap.parse_args()
m = AutoModelForCausalLM.from_pretrained(a.base, torch_dtype=torch.float16)
m = PeftModel.from_pretrained(m, a.lora).merge_and_unload()
m.save_pretrained(a.out, safe_serialization=True)
AutoTokenizer.from_pretrained(a.base).save_pretrained(a.out)
print("merged ->", a.out)
