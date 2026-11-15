#!/usr/bin/env python3
# تحويل نموذج HF المدمج إلى GGUF (f16) ثم تكميمه — بلا خطوات يدوية.
#
# الاستخدام:
#   python export_gguf.py --merged out/lion-1-merged --quant Q4_K_M
#
# يفعل تلقائياً:
#   1 يتحقق من مجلد النموذج المدمج ناتج merge_export.py.
#   2 ينزّل llama.cpp إن لم يكن موجوداً ويبني أدواته.
#   3 يحوّل HF → GGUF f16 عبر convert_hf_to_gguf.py.
#   4 يكمّم إلى Q4_K_M أو التكميم المطلوب عبر llama-quantize.
#   5 يطبع مسار الملف الناتج — جاهز لـ publish_hf.py.
#
# بعدها: python publish_hf.py --gguf out/lion-1-Q4_K_M.gguf --repo <user>/lion-1
import argparse
import os
import shutil
import subprocess
import sys

LLAMA_DIR = '/content/llama.cpp'  # مسار Colab الافتراضي


def fail(msg):
    print('❌ ' + msg, file=sys.stderr)
    sys.exit(1)


def run(cmd, cwd=None):
    print('▶ ' + ' '.join(cmd))
    r = subprocess.run(cmd, cwd=cwd)
    if r.returncode != 0:
        fail('فشل الأمر (exit ' + str(r.returncode) + '): ' + ' '.join(cmd))


def main():
    ap = argparse.ArgumentParser(description='تحويل HF المدمج إلى GGUF مكمّم')
    ap.add_argument('--merged', default='out/lion-1-merged', help='مجلد النموذج المدمج (HF)')
    ap.add_argument('--out', default='out', help='مجلد الإخراج')
    ap.add_argument('--quant', default='Q4_K_M', help='نوع التكميم (Q4_K_M / Q5_K_M / Q8_0)')
    ap.add_argument('--llama-dir', default=LLAMA_DIR, help='مسار llama.cpp')
    ap.add_argument('--skip-build', action='store_true', help='لا تبنِ llama.cpp (إن كان جاهزاً)')
    a = ap.parse_args()

    # 1 التحقق من النموذج المدمج
    if not os.path.isdir(a.merged):
        fail('لم أجد مجلد النموذج المدمج: ' + a.merged +
             '\n   نفّذ أولاً: python merge_export.py --base Qwen/Qwen2.5-1.5B-Instruct --lora out/lion-1-lora')
    if not os.path.isfile(os.path.join(a.merged, 'config.json')):
        fail('المجلد لا يحتوي config.json — ليس نموذج HF صالحاً: ' + a.merged)

    os.makedirs(a.out, exist_ok=True)
    base_name = os.path.basename(a.merged.rstrip('/'))
    f16 = os.path.join(a.out, base_name + '.f16.gguf')
    final = os.path.join(a.out, base_name + '.' + a.quant + '.gguf')

    # 2 llama.cpp
    if not os.path.isdir(a.llama_dir):
        print('📥 تنزيل llama.cpp …')
        run(['git', 'clone', '--depth', '1', 'https://github.com/ggml-org/llama.cpp', a.llama_dir])
        run([sys.executable, '-m', 'pip', '-q', 'install', '-r',
             os.path.join(a.llama_dir, 'requirements.txt')])

    if not a.skip_build:
        print('🔨 بناء llama-quantize …')
        run(['cmake', '-S', a.llama_dir, '-B', os.path.join(a.llama_dir, 'build'),
             '-DLLAMA_CURL=OFF', '-DGGML_NATIVE=OFF'])
        run(['cmake', '--build', os.path.join(a.llama_dir, 'build'),
             '--target', 'llama-quantize', '-j', '4'])

    # 3 HF → GGUF f16
    convert = os.path.join(a.llama_dir, 'convert_hf_to_gguf.py')
    if not os.path.isfile(convert):
        fail('لم أجد convert_hf_to_gguf.py في ' + a.llama_dir)
    print('🔄 تحويل HF → GGUF f16 …')
    run([sys.executable, convert, a.merged, '--outfile', f16, '--outtype', 'f16'])

    # 4 تكميم
    quantize = os.path.join(a.llama_dir, 'build', 'bin', 'llama-quantize')
    if not os.path.isfile(quantize):
        quantize = shutil.which('llama-quantize') or quantize
    if not os.path.isfile(quantize):
        fail('لم أجد llama-quantize — أعد البناء بلا --skip-build')
    print('⚙️  تكميم إلى ' + a.quant + ' …')
    run([quantize, f16, final, a.quant])

    # 5 النتيجة
    if not os.path.isfile(final):
        fail('لم يُنتج ملف GGUF نهائي: ' + final)
    size = os.path.getsize(final) / (1024.0 * 1024.0)
    print('\n✅ تم التصدير')
    print('   الملف: ' + final + ' (' + ('%.1f MB' % size) + ')')
    print('\nالخطوة التالية — النشر على Hugging Face:')
    print('   python publish_hf.py --gguf ' + final + ' --repo <اسمك>/lion-1')


if __name__ == '__main__':
    main()
