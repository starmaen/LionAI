# Lion-1 — نموذج «الأسد» القابل للضبط

نموذج مفتوح (افتراضياً `Qwen2.5-1.5B-Instruct`) يُضبط بـ LoRA على هوية Lion-1 ومهامه المتعددة وعلى
**صيغة أدوات LionAI الحقيقية** (`[[TOOL]]{"name":…,"args":{…}}[[/TOOL]]` مع 234 اسم أداة من `data/tools.json`).

## الحالة الصادقة
- البيانات الحالية **عيّنة بذرية صغيرة (~75 مثالاً)**: تعلّم الهوية وصيغة الأدوات والأسلوب، ولا تُكسب النموذج معرفة جديدة.
- لم يُجرَّ تدريب فعلي بعد (يحتاج GPU). سكربتات البيانات والفحص اختُبرت؛ سكربت التدريب لم يُشغَّل.

## الخطوات
1. **وسّع البيانات**: أضف أمثلة إلى `data/seed_extra.jsonl`، أو ولّدها من مزودات قوية عبر بوابتك:
   `LION_API_KEY=… python distill.py --url <gateway>/v1 --model <provider/model> prompts.txt`
2. `python data/generate_dataset.py` ثم `python eval_lion.py data data/train.jsonl data/val.jsonl`
3. **درّب** على Colab/Kaggle (T4 مجانية): `pip install -r requirements.txt && python train_lora.py`
4. `python merge_export.py` ثم حوّل إلى GGUF (الأوامر في أعلى الملف) وشغّل `./serve.sh` أو `ollama create lion-1 -f Modelfile`
5. **اختبر**: `python eval_lion.py live --url http://127.0.0.1:8089/v1 --model lion-1`
6. أضفه في التطبيق مزوداً مخصصاً (OpenAI-Compatible): `http://127.0.0.1:8089/v1` والموديل `lion-1`.

## `integration/`
`LionModel.kt` (توجيه المهام وتبديل الخادم داخل التطبيق) و`lion-model.js` (نموذج افتراضي على البوابة) —
بديل خفيف يعمل فوق أي نموذج دون تدريب.
