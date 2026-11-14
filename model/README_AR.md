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
3. **درّب** على Colab/Kaggle (T4 مجانية): افتح `Lion1_Colab.ipynb` في Colab (Runtime → T4 GPU) ونفّذ الخلايا بالترتيب، أو يدوياً: `pip install -r requirements.txt && python train_lora.py`
4. `python merge_export.py` ثم `python export_gguf.py --merged out/lion-1-merged --quant Q4_K_M`
5. **انشر على Hugging Face** (انظر القسم أدناه) — يُنتج رابطاً جاهزاً للتطبيق
6. **اختبر**: `python eval_lion.py live --url http://127.0.0.1:8089/v1 --model lion-1`
7. أضفه في التطبيق: البيئة المحلية ← 🦁 محرّك Lion-1 ← ＋ نموذج مخصص ← الصق الرابط

## النشر على Hugging Face

`publish_hf.py` يرفع GGUF إلى HF Hub آلياً وينشئ المستودع وبطاقة النموذج إن لم يوجدا:

```bash
export HF_TOKEN=hf_xxx        # توكن Write من https://huggingface.co/settings/tokens
python publish_hf.py --gguf out/lion-1-Q4_K_M.gguf --repo <اسمك>/lion-1
```

يطبع في النهاية رابطاً بالشكل:
```
https://huggingface.co/<اسمك>/lion-1/resolve/main/lion-1-Q4_K_M.gguf
```
الصقه في التطبيق مباشرة. في Colab: أضف `HF_TOKEN` من أيقونة 🔑 (New secret ← Notebook access)،
ثم شغّل خلية «نشر النموذج على Hugging Face Hub» في الدفتر.

خيارات: `--private` لمستودع خاص · `--quant Q5_K_M` لاسم التكميم في البطاقة · `--no-card` بلا بطاقة.

## `integration/`
`LionModel.kt` (توجيه المهام وتبديل الخادم داخل التطبيق) و`lion-model.js` (نموذج افتراضي على البوابة) —
بديل خفيف يعمل فوق أي نموذج دون تدريب.
