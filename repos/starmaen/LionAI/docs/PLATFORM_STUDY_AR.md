# دراسة منصّة Lion AI: وكيل تنفيذ كامل + اتصالات WhatsApp/Telegram

آخر تحديث: 2026-10-27

## 1. الخلاصة التنفيذية

Lion AI تطبيق أندرويد محلي (Kotlin + Jetpack Compose) يعمل كواجهة لمزوّدات ذكاء اصطناعي متعددة.
بدلاً من الاعتماد على function-calling الخاص بكل مزوّد، يستخدم التطبيق **بروتوكول أدوات نصّياً**:

```
[[TOOL]]
{"tool":"اسم_الأداة","args":{...}}
[[/TOOL]]
```

`ToolAgent` يرسل الطلب إلى النموذج، يقرأ استدعاءات الأدوات، ينفّذها محلياً على الجهاز أو عبر API،
ثم يعيد النتيجة إلى النموذج ويكرّر حتى إجابة نهائية. لذلك تعمل كل الأدوات مع أي مزوّد API
(OpenAI-compatible أو REST) دون تعديل المزوّد.

النتيجة: يستطيع الوكيل قراءة المشروع، تحليله، تعديل الملفات بدقة، رفعها إلى GitHub،
مراقبة CI، قراءة سجل الفشل، الإصلاح وإعادة الرفع، وكذلك إدارة Telegram Bot وWhatsApp Business،
وقراءة إشعارات WhatsApp/Telegram الشخصية والرد المباشر عليها محلياً.

## 2. البنية الفعلية

| المكوّن | الملف | الدور |
|---|---|---|
| سجل الأدوات | `app/src/main/java/com/lion/ai/AiTools.kt` | تعريف الأداة، التحقق، التنفيذ، سياسة المجموعات |
| حلقة الوكيل | `ToolAgent.kt` | 16 خطوة كحد أقصى، مهلة 15 دقيقة، كشف التكرار |
| GitHub | `GitHubClient.kt` | REST + Git Data API: tree/blob/commit/PR/Actions |
| Telegram | `TelegramClient.kt` | Bot API: رسائل، وسائط، إدارة، Webhook |
| WhatsApp Business | `WhatsAppClient.kt` | Cloud API: نص، قالب، وسائط، قراءة |
| جسر الإشعارات | `Notifications.kt` | NotificationListenerService + SQLite + RemoteInput |
| تعديل دقيق | `UnifiedDiff.kt` | تطبيق unified diff مع رفض السياق الخاطئ |
| التوجيه | `Router.kt` | تصنيف المهمة واختيار المزوّد |
| الواجهة | `ConnectorsScreen.kt`, `DevToolsScreen.kt` | ربط الحسابات، الصلاحيات، حالة الأدوات |

## 3. ما أُضيف في هذه النسخة

### 3.1 حلقة تطوير كاملة
- `github_push_workspace`: رفع مجلد مساحة العمل دفعة واحدة عبر Git Data API.
  يقارن بصمة `git blob` لكل ملف، يرفع المتغيّر فقط، يدعم الثنائي، `delete_missing`، و`dry_run`.
- `github_get_run` / `github_wait_run`: انتظار البناء ثم إرجاع الفشل وسجل الوظائف تلقائياً.
- `github_run_logs` / `github_job_log`: قراءة سجل CI الكامل.
- `github_compare` / `github_pr_files`: مراجعة الفروق قبل الدفع أو الدمج.
- `github_merge_pr` / `github_review_pr` / `github_close_issue`.
- `github_list_tags` / `github_create_tag` / `github_create_release` / `github_delete_branch`.
- `file_apply_patch`: تعديل ملف بـ unified diff دقيق، مع نسخة احتياطية ورفض عدم التطابق.
- `tools_schema` / `tools_groups`: اكتشاف الأدوات وحالة الصلاحيات داخل الحوار.

### 3.2 Telegram Bot API
`telegram_status`, `telegram_updates`, `telegram_send`, `telegram_send_media`, `telegram_edit`,
`telegram_delete`, `telegram_forward`, `telegram_pin`, `telegram_chat_info`, `telegram_members`,
`telegram_file_download`, `telegram_webhook_set`, `telegram_webhook_delete`, `telegram_webhook_info`,
`telegram_poll`, `telegram_commands`, `telegram_invite`, `telegram_admin`.

حدود حقيقية: البوت يرى فقط المحادثات التي أُضيف إليها، ولا يرى حساب المستخدم الشخصي
أو محادثاته الخاصة. للوصول الكامل يلزم TDLib/MTProto وجلسة مستخدم، وهو خارج نطاق هذه النسخة.

### 3.3 WhatsApp Business Cloud API
`whatsapp_status`, `whatsapp_send`, `whatsapp_template`, `whatsapp_media`, `whatsapp_mark_read`,
`whatsapp_templates`, `whatsapp_profile`, `whatsapp_numbers`, `whatsapp_download`.

صيغة التوكن: `PHONE_NUMBER_ID:ACCESS_TOKEN`. هذا رقم أعمال رسمي من Meta، وليس حساب واتساب شخصي.
إرسال نص خارج نافذة 24 ساعة يتطلب قالباً معتمداً.

### 3.4 جسر إشعارات WhatsApp/Telegram الشخصي
- `NotificationAccess`: التحقق من صلاحية الوصول إلى الإشعارات وتفعيل/إيقاف الالتقاط.
- `LionNotificationListener`: يقرأ فقط حزم WhatsApp/Telegram، يستخرج MessagingStyle أو النص،
  ويحفظه محلياً في SQLite (`lion_notifications.db`) دون رفع لأي خادم.
- `notifications_list` / `notifications_chats`: قراءة ما وصل بعد منح الصلاحية.
- `notifications_reply`: رد مباشر على إشعار نشط عبر RemoteInput (WhatsApp/Telegram يدعمانه).
- `notifications_dismiss` / `notifications_clear`: إغلاق إشعار أو مسح السجل المحلي.

حدود حقيقية: لا يمكن قراءة المحادثات القديمة قبل التثبيت/الصلاحية، ولا المجموعات المقفلة،
ولا الرسائل التي لا يصدر عنها إشعار. الرد يعمل فقط على إشعار ما زال نشطاً ويدعم الرد المباشر.

## 4. سياسة الأمان والموافقة

- الأدوات الحسّاسة معلّمة `[كتابة]`، ولا تُنفّذ إلا بطلب صريح في رسالة المستخدم.
- `github_push_workspace` يدعم `dry_run` ويُفضّل فرعاً + PR بدل الدفع المباشر إلى `main`.
- لا تُطبع التوكنات في الردود أو السجلات.
- جسر الإشعارات محلي فقط، ومحدود بحزم WhatsApp/Telegram.
- `allowBackup=false` والتوكنات في `EncryptedSharedPreferences`.
- يمكن للمستخدم إيقاف التقاط الرسائل أو مسح السجل من شاشة الموصلات.

## 5. خطوات التفعيل

1. **GitHub**: الإعدادات ← موصلات ← GitHub ← الصق Personal Access Token بصلاحيات `repo` و`workflow`.
2. **Telegram**: أنشئ بوتاً من @BotFather، أضفه إلى المجموعة/القناة، والصق التوكن في الموصلات.
3. **WhatsApp Business**: من Meta for Developers فعّل WhatsApp Cloud API، ثم الصق `PHONE_NUMBER_ID:ACCESS_TOKEN`.
4. **WhatsApp/Telegram الشخصي**: الموصلات ← «قراءة وردّ WhatsApp/Telegram» ← منح صلاحية الإشعارات،
   ثم أبقِ «التقاط الرسائل» مفعّلاً.
5. من الإعدادات فعّل «الكتابة والتنفيذ» و«التشغيل التلقائي للأدوات» حسب الحاجة.

## 6. خطة الاختبار

### اختبارات وحدة
- `UnifiedDiffTest`: hunk واحد، hunks متعددة، غياب سطر جديد ختامي، رفض سياق خاطئ، إدراج في البداية.
- اختبارات قائمة: AnswerGuard، Consensus، LocalRag، SmartRouter، UtilTools، WebGrounding.

### اختبار يدوي مقترح
1. «اقرأ README ثم أصلح خطأ البناء» → قراءة، patch، dry_run، دفع، انتظار CI، إصلاح.
2. «أرسل لتيليغرام رسالة ...» → تأكيد صريح، إرسال، ثم `telegram_updates` للتأكد.
3. «ما آخر رسائل واتساب؟» → بعد وصول إشعار، `notifications_list`.
4. «رد على آخر رسالة واتساب» → `notifications_reply` على إشعار نشط.
5. «ارفع المشروع إلى GitHub» → `github_push_workspace` مع `dry_run=true` أولاً.

## 7. ما لا تدّعيه النسخة الحالية

- لا تقرأ سجل WhatsApp/Telegram الشخصي بالكامل؛ فقط الإشعارات التي وصلت بعد التفعيل.
- لا ترسل من حساب WhatsApp الشخصي؛ الإرسال الرسمي عبر Cloud API فقط.
- لا تدعم مكالمات WhatsApp/Telegram أو القنوات السرية.
- Telegram Bot لا يرى محادثات المستخدم الخاصة.
- لا تُشغّل Gradle محلياً على الجهاز؛ البناء عبر GitHub Actions (JDK + Android SDK).

## 8. تطوير مستقبلي

- TDLib لجلسة Telegram مستخدم كامل (قراءة/إرسال كل المحادثات) مع تخزين مشفّر.
- WhatsApp Business webhook لاستقبال الرسائل بدل الاعتماد على الإشعارات فقط.
- شاشة «صندوق موحّد» تجمع Telegram + WhatsApp Business + الإشعارات مع بحث موحّد.
- جدولة ردود/متابعة تلقائية بموافقة صريحة لكل محادثة.
