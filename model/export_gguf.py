#!/usr/bin/env python3
"""تحويل نموذج Lion-1 المدمج (HF) إلى GGUF كمّي جاهز للتشغيل داخل التطبيق.

ما يفعله (بلا خطوات يدوية):
  1) يجلب llama.cpp (نسخة واحدة، تُخزَّن في .llama.cpp/ لإعادة الاستخدام).
  2) يحوّل مجلد HF المدمج إلى GGUF بدقة f16.
  3) يكمّم إلى Q4_K_M (الافتراضي: أفضل توازن حجم/جودة لهواتف 4–6GB) أو Q5_K_M/Q8_0.
  4) يطبع المسار النهائي والبصمة SHA-256 وحجم الملف.

الاستخدام:
    python export_gguf.py --merged out/lion-1-merged
    python export_gguf.py --merged out/lion-1-merged --quant Q5_K_M

بعدها: ارفع الملف إلى HuggingFace أو انسخه للهاتف، ثم في التطبيق:
    البيئة المحلية ← 🦁 محرّك Lion-1 ← ＋ نموذج مخصص ← ضع الرابط/المسار ← تنزيل/تشغيل.
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LLAMA_DIR = os.path.join(HERE, ".llama.cpp")
QUANTS = ["Q4_K_M", "Q5_K_M", "Q8_0", "Q4_0", "F16"]


def run(cmd, cwd=None, env=None):
    print("$", " ".join(cmd) if isinstance(cmd, list) else cmd)
    p = subprocess.run(cmd, cwd=cwd, env=env, shell=isinstance(cmd, str))
    if p.returncode != 0:
        sys.exit(f"❌ فشل الأمر (exit {p.returncode})")
    return p


def ensure_llamacpp():
    if not os.path.isdir(os.path.join(LLAMA_DIR, "gguf-py")):
        print("📥 جلب llama.cpp …")
        if os.path.isdir(LLAMA_DIR):
            shutil.rmtree(LLAMA_DIR, ignore_errors=True)
        run(["git", "clone", "--depth", "1", "https://github.com/ggml-org/llama.cpp", LLAMA_DIR])
    req = os.path.join(LLAMA_DIR, "requirements", "requirements-convert_hf_to_gguf.txt")
    if not os.path.exists(req):
        req = os.path.join(LLAMA_DIR, "requirements.txt")
    run([sys.executable, "-m", "pip", "install", "-q", "-r", req])


def build_quantizer():
    """يبني llama-quantize إن لم يوجد (يحتاج cmake؛ موجود في Colab)."""
    for cand in ("build/bin/llama-quantize", "build/bin/quantize", "llama-quantize"):
        p = os.path.join(LLAMA_DIR, cand)
        if os.path.exists(p):
            return p
    print("🔨 بناء أدوات llama.cpp (cmake) …")
    run(["cmake", "-B", "build", "-DCMAKE_BUILD_TYPE=Release", "-DLLAMA_CURL=OFF"], cwd=LLAMA_DIR)
    run(["cmake", "--build", "build", "--config", "Release", "-j", str(os.cpu_count() or 2),
         "--target", "llama-quantize"], cwd=LLAMA_DIR)
    for cand in ("build/bin/llama-quantize", "build/bin/quantize"):
        p = os.path.join(LLAMA_DIR, cand)
        if os.path.exists(p):
            return p
    sys.exit("❌ لم أجد llama-quantize بعد البناء")


def sha256(path, chunk=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(chunk), b""):
            h.update(block)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--merged", default="out/lion-1-merged", help="مجلد النموذج المدمج بصيغة HF")
    ap.add_argument("--out", default="", help="اسم ملف GGUF النهائي (افتراضي: lion-1-<quant>.gguf)")
    ap.add_argument("--quant", default="Q4_K_M", choices=QUANTS)
    ap.add_argument("--name", default="lion-1", help="اسم النموذج في اسم الملف")
    a = ap.parse_args()

    merged = a.merged if os.path.isabs(a.merged) else os.path.join(HERE, a.merged)
    if not os.path.isdir(merged):
        sys.exit(f"❌ لم أجد مجلد النموذج المدمج: {merged}\n   نفّذ أولاً: python merge_export.py")

    ensure_llamacpp()
    f16 = os.path.join(HERE, "out", f"{a.name}.f16.gguf")
    os.makedirs(os.path.dirname(f16), exist_ok=True)

    print("🔄 تحويل HF → GGUF (f16) …")
    run([sys.executable, os.path.join(LLAMA_DIR, "convert_hf_to_gguf.py"), merged,
         "--outfile", f16, "--outtype", "f16"])

    if a.quant == "F16":
        final = f16
    else:
        q = build_quantizer()
        final = a.out or os.path.join(HERE, "out", f"{a.name}-{a.quant}.gguf")
        if not os.path.isabs(final):
            final = os.path.join(HERE, final)
        print(f"🗜️ تكميم {a.quant} …")
        run([q, f16, final, a.quant])
        os.remove(f16)

    size_mb = os.path.getsize(final) / (1024 * 1024)
    print("\n✅ جاهز:", final)
    print(f"   الحجم: {size_mb:.0f} MB")
    print("   SHA-256:", sha256(final))
    print("\nخطوات الاستخدام في التطبيق:")
    print("   1) ارفع الملف إلى HuggingFace (repo عام) أو انسخه إلى الهاتف.")
    print("   2) البيئة المحلية ← 🦁 محرّك Lion-1 ← ＋ نموذج مخصص ← الصق الرابط/المسار ← حفظ.")
    print("   3) ⬇️ تنزيل النموذج ← ▶️ تشغيل ← 🧪 اختبار.")
    print("\nللتقييم قبل النشر:")
    print(f"   python eval_lion.py live --url http://127.0.0.1:8089/v1 --model lion-1")


if __name__ == "__main__":
    main()
