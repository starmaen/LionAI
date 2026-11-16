package com.lion.ai

import android.content.SharedPreferences

/**
 * التحويل عند الفشل: المزود (أو صفحة الويب) الذي اختاره المستخدم يبقى الأول دائماً،
 * ولا يتدخل أي بديل ما دام يرد. فإذا فشل (مشغول، حصة، مهلة، خطأ شبكة، رد فارغ)
 * تُجرَّب البدائل بالترتيب، وتبقى جلسة العمل وأدوات المطوّر تتابع من النقطة نفسها.
 *
 * مفتاح الإعداد [PREF] افتراضه مفعّل؛ تعطيله يعيد التثبيت اليدوي الصارم.
 */
object FailoverPolicy {
    const val PREF = "failover_on_failure"

    fun enabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF, true)

    /**
     * بدائل تأتي بعد [primary] فقط. تشمل مزودات API المتاحة وجلسات الويب الموثّقة،
     * وتستثني المزود نفسه والخادم المحلي/المخصص (لهما مسارهما).
     */
    fun backupsFor(
        prefs: SharedPreferences,
        kind: TaskKind,
        primary: Route,
        limit: Int = 3
    ): List<Route> {
        if (!enabled(prefs)) return emptyList()
        return CentralBrain.rescueRoutes(prefs, kind, emptyList(), image = false, limit = limit + 2)
            .filter {
                it.provider != primary.provider &&
                    it.provider != RoutePolicy.LOCAL_PROXY &&
                    it.provider != RoutePolicy.CUSTOM
            }
            .take(limit)
    }
}
