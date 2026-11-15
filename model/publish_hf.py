#!/usr/bin/env python3
# نشر نموذج Lion-1 (GGUF) على Hugging Face Hub — بلا خطوات يدوية.
#
# الاستخدام:
#   export HF_TOKEN=hf_xxx
#   python publish_hf.py --gguf out/lion-1-Q4_K_M.gguf --repo <user>/lion-1
#
#   # مستودع خاص:
#   python publish_hf.py --gguf out/lion-1-Q4_K_M.gguf --repo <user>/lion-1 --private
#
#   # داخل Colab:
#   python publish_hf.py --gguf out/lion-1-Q4_K_M.gguf --repo <user>/lion-1 --token hf_xxx
#
# بعد النشر: التطبيق ← البيئة المحلية ← 🦁 محرّك Lion-1 ← ＋ نموذج مخصص ← الصق الرابط ← حفظ.
import argparse
import os
import sys


def fail(msg):
    print('❌ ' + msg, file=sys.stderr)
    sys.exit(1)


def human(n):
    for unit in ('B', 'KB', 'MB', 'GB'):
        if n < 1024 or unit == 'GB':
            return ('%d %s' % (n, unit)) if unit == 'B' else ('%.1f %s' % (n, unit))
        n /= 1024.0
    return '%.1f GB' % n


def model_card(repo_id, filename, quant, base):
    # بطاقة النموذج: تُقرأ على صفحة HF وتشرح ما هو النموذج وما ليس هو.
    return '\n'.join([
        '---',
        'license: apache-2.0',
        'base_model: ' + base,
        'library_name: llama.cpp',
        'tags:',
        '- gguf',
        '- llama.cpp',
        '- arabic',
        '- lion-1',
        'language:',
        '- ar',
        '- en',
        '---',
        '',
        '# 🦁 Lion-1 — ' + quant,
        '',
        'نموذج Lion-1 («الأسد») من Star Syria: نسخة مضبوطة بـ LoRA فوق ' + base,
        'على هوية Lion-1 وصيغة أدوات تطبيق LionAI الحقيقية.',
        '',
        '## الملف',
        '',
        '| العنصر | القيمة |',
        '|---|---|',
        '| الملف | `' + filename + '` |',
        '| التكميم | `' + quant + '` |',
        '| الصيغة | GGUF (llama.cpp) |',
        '',
        '## التشغيل',
        '',
        '```bash',
        'llama-server -m ' + filename + ' --alias lion-1 --host 127.0.0.1 --port 8089 -c 8192 --jinja',
        '```',
        '',
        '## في التطبيق',
        '',
        'البيئة المحلية ← 🦁 محرّك Lion-1 ← ＋ نموذج مخصص ← الصق:',
        '',
        '`https://huggingface.co/' + repo_id + '/resolve/main/' + filename + '`',
        '',
        '## ما يتعلّمه وما لا يتعلّمه',
        '',
        '| يتعلّم | لا يتعلّم |',
        '|---|---|',
        '| الهوية: أسد / Lion-1 من Star Syria | معرفة موسوعية |',
        '| الأسلوب: عربية موجزة تبدأ بالجواب | حقائق متجددة |',
        '| صيغة أدوات التطبيق | الإنترنت — الأدوات تجلب لا النموذج |',
        '',
        '**القاعدة:** التدريب = أسلوب ومهارة. المعرفة = أدوات واسترجاع.',
        '',
        '## تنبيه صادق',
        '',
        'النموذج يقلّد الأسلوب وقد يخطئ في الوقائع كما يخطئ الأساس.',
        'لا تعتمد عليه في معلومة بلا أداة تحقّق. البيانات التدريبية عيّنة بذرية صغيرة.',
        '',
    ])


def main():
    ap = argparse.ArgumentParser(description='نشر GGUF على Hugging Face Hub')
    ap.add_argument('--gguf', required=True, help='مسار ملف GGUF المحلي')
    ap.add_argument('--repo', required=True, help='معرّف المستودع: <user>/<name>')
    ap.add_argument('--token', default='', help='توكن HF (افتراضياً من HF_TOKEN)')
    ap.add_argument('--private', action='store_true', help='مستودع خاص')
    ap.add_argument('--quant', default='', help='اسم التكميم للبطاقة')
    ap.add_argument('--base', default='Qwen/Qwen2.5-1.5B-Instruct', help='النموذج الأساس')
    ap.add_argument('--path-in-repo', default='', help='اسم الملف داخل المستودع')
    ap.add_argument('--no-card', action='store_true', help='لا ترفع بطاقة النموذج')
    a = ap.parse_args()

    # 1 التحقق من الملف
    if not os.path.isfile(a.gguf):
        fail('لم أجد ملف GGUF: ' + a.gguf + '\n   نفّذ أولاً: python export_gguf.py --merged out/lion-1-merged')
    size = os.path.getsize(a.gguf)
    if size < 1024 * 1024:
        fail('الملف صغير جداً (' + human(size) + ') — ليس GGUF صالحاً على الأرجح.')

    # 2 التحقق من التوكن
    token = (a.token or os.environ.get('HF_TOKEN', '')).strip()
    if not token:
        fail('لا يوجد توكن Hugging Face.\n'
             '   في Colab: أيقونة 🔑 في الشريط الجانبي ← New secret ← الاسم HF_TOKEN.\n'
             '   محلياً: export HF_TOKEN=hf_xxx\n'
             '   أنشئ توكن كتابة من: https://huggingface.co/settings/tokens')

    # 3 المكتبة
    try:
        from huggingface_hub import HfApi, create_repo
        from huggingface_hub.utils import HfHubHTTPError
    except ImportError:
        fail('مكتبة huggingface_hub غير مثبّتة.\n   نفّذ: pip install -r requirements.txt')

    if '/' not in a.repo:
        fail('معرّف المستودع يجب أن يكون <user>/<name> — وصلني: ' + a.repo)

    filename = a.path_in_repo or os.path.basename(a.gguf)
    parts = filename.split('.')
    quant = a.quant or (parts[-2] if len(parts) >= 3 else 'GGUF')

    api = HfApi(token=token)

    # 4 التحقق من الهوية أولاً — رسالة أوضح من خطأ 401 لاحقاً
    try:
        who = api.whoami()
    except HfHubHTTPError as e:
        fail('التوكن مرفوض (' + str(e.response.status_code) + '). تحقق من صلاحية write في https://huggingface.co/settings/tokens')
    print('👤 مسجّل الدخول: ' + who.get('name', '?'))

    # 5 إنشاء المستودع إن لم يوجد
    print('📦 المستودع: ' + a.repo + (' (خاص)' if a.private else ' (عام)'))
    try:
        create_repo(a.repo, repo_type='model', private=a.private, exist_ok=True, token=token)
    except HfHubHTTPError as e:
        fail('تعذّر إنشاء المستودع: ' + str(e))

    # 6 بطاقة النموذج
    if not a.no_card:
        card = model_card(a.repo, filename, quant, a.base)
        api.upload_file(
            path_or_fileobj=card.encode('utf-8'),
            path_in_repo='README.md',
            repo_id=a.repo,
            repo_type='model',
            commit_message='docs: بطاقة Lion-1 (' + quant + ')',
        )
        print('📝 رُفعت بطاقة النموذج (README.md)')

    # 7 رفع GGUF
    print('⬆️  رفع ' + filename + ' (' + human(size) + ') … قد يستغرق دقائق')
    try:
        api.upload_file(
            path_or_fileobj=a.gguf,
            path_in_repo=filename,
            repo_id=a.repo,
            repo_type='model',
            commit_message='feat: Lion-1 ' + quant + ' (' + human(size) + ')',
        )
    except HfHubHTTPError as e:
        fail('فشل الرفع: ' + str(e))

    # 8 الرابط الجاهز للتطبيق
    url = 'https://huggingface.co/' + a.repo + '/resolve/main/' + filename
    print('\n✅ تم النشر')
    print('   الصفحة: https://huggingface.co/' + a.repo)
    print('   الرابط: ' + url)
    print('\nخطوات التطبيق:')
    print('   1) البيئة المحلية ← 🦁 محرّك Lion-1 ← ＋ نموذج مخصص')
    print('   2) الصق: ' + url)
    print('   3) حفظ ← ⬇️ تنزيل النموذج ← ▶️ تشغيل ← 🧪 اختبار')


if __name__ == '__main__':
    main()
