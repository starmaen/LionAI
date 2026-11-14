package com.lion.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSelfTestAndLoopTest {

    @Test
    fun toolSelfTestPassesFullFourStageRoundTripOnWebStyleReply() = runBlocking {
        val token = "LION-PROBE-A1B2C3"
        val report = ToolSelfTest.runProbe(
            provider = "chatgpt_web",
            providerName = "ChatGPT Web",
            model = "web-session",
            isWeb = true,
            probeToken = token,
            asker = { turn, sys, msg, hist ->
                if (turn == 1) {
                    assertTrue(sys.contains("وكيل برمجة وتطوير داخل Lion AI"))
                    "```json\n[[TOOL]]{“name”:“file_tree”,“args”:{“max”:5}}[[/TOOL]]\n```"
                } else {
                    assertTrue(msg.startsWith("نتائج الأدوات"))
                    assertEquals(2, hist.size)
                    "يوجد 3 ملفات في مساحة العمل، ورمز التحقق هو $token."
                }
            },
            executor = { call ->
                assertEquals("file_tree", call.name)
                assertEquals(5, call.args.optInt("max"))
                ToolResult("file_tree", "📄 a.kt\n📄 b.kt\n📄 c.kt\n— المجموع: 3 ملف، 0 مجلد", true)
            }
        )
        assertTrue(report.ok)
        assertEquals(4, report.stages.size)
        assertTrue(report.summaryLine().startsWith("✅"))
    }

    @Test
    fun toolSelfTestFailsWhenModelHallucinatesWithoutProbeTokenOrRequestsMutation() = runBlocking {
        val hallucinated = ToolSelfTest.runProbe(
            provider = "free_api",
            providerName = "Free API",
            model = "small-7b",
            isWeb = false,
            probeToken = "LION-PROBE-998877",
            asker = { turn, _, _, _ ->
                if (turn == 1) "[[TOOL]]{\"name\":\"file_tree\",\"args\":{\"max\":5}}[[/TOOL]]"
                else "تم فحص الملفات بنجاح ويوجد 3 ملفات." // تجاهل الرمز الفريد!
            },
            executor = { ToolResult("file_tree", "📄 a.kt", true) }
        )
        assertFalse(hallucinated.ok)
        assertEquals("verify", hallucinated.stages.last().id)
        assertFalse(hallucinated.stages.last().ok)

        val unsafeMutation = ToolSelfTest.runProbe(
            provider = "bad_model",
            providerName = "Bad Model",
            model = "rogue",
            isWeb = false,
            asker = { _, _, _, _ -> "[[TOOL]]{\"name\":\"file_delete\",\"args\":{\"path\":\"a.kt\"}}[[/TOOL]]" },
            executor = { error("يجب ألا تُنفَّذ أداة كتابة في الفحص التشخيصي") }
        )
        assertFalse(unsafeMutation.ok)
        assertEquals("parse", unsafeMutation.stages.last().id)
        assertTrue(unsafeMutation.stages.last().detail.contains("رفض أمني"))
    }

    @Test
    fun classifyLoopStopDistinguishesEveryTerminationReason() {
        val now = 1_000_000L
        // 1) التقدّم مستمر والتمديد مفعّل والوقت متاح -> لا توقف (null)
        assertNull(
            ToolAgent.classifyLoopStop(
                now = now,
                deadline = now + 300_000L,
                hardDeadline = now + 3_600_000L,
                autoExtend = true,
                cycle = 1,
                maxCycles = 8,
                cycleProgressOk = 3,
                stepBudget = 40
            )
        )

        // 2) التمديد التلقائي معطّل -> يتوقف عند سقف الخطوات اليدوي
        val manualCap = ToolAgent.classifyLoopStop(
            now = now,
            deadline = now + 300_000L,
            hardDeadline = now + 3_600_000L,
            autoExtend = false,
            cycle = 1,
            maxCycles = 8,
            cycleProgressOk = 5,
            stepBudget = 40
        )
        assertNotNull(manualCap)
        assertTrue(manualCap!!.reason.contains("سقف الخطوات اليدوي"))

        // 3) دورة كاملة بلا أي أداة ناجحة -> يتوقف بدل الدوران العقيم
        val stalled = ToolAgent.classifyLoopStop(
            now = now,
            deadline = now + 300_000L,
            hardDeadline = now + 3_600_000L,
            autoExtend = true,
            cycle = 1,
            maxCycles = 8,
            cycleProgressOk = 0,
            stepBudget = 40
        )
        assertNotNull(stalled)
        assertTrue(stalled!!.reason.contains("دون تقدّم ناجح"))

        // 4) السقف المطلق (3 ساعات) مقابل مهلة الجولة العادية
        val hardStop = ToolAgent.classifyLoopStop(
            now = now,
            deadline = now + 10_000L,
            hardDeadline = now + 10_000L,
            autoExtend = true,
            cycle = 2,
            maxCycles = 8,
            cycleProgressOk = 4,
            stepBudget = 80
        )
        assertNotNull(hardStop)
        assertTrue(hardStop!!.reason.contains("3 ساعات"))

        val cycleTimeout = ToolAgent.classifyLoopStop(
            now = now,
            deadline = now + 10_000L,
            hardDeadline = now + 3_600_000L,
            autoExtend = true,
            cycle = 2,
            maxCycles = 8,
            cycleProgressOk = 4,
            stepBudget = 80
        )
        assertNotNull(cycleTimeout)
        assertTrue(cycleTimeout!!.reason.contains("المهلة الزمنية للجولة"))

        // 5) استنفاد MAX_CYCLES
        val maxCyclesStop = ToolAgent.classifyLoopStop(
            now = now,
            deadline = now + 300_000L,
            hardDeadline = now + 3_600_000L,
            autoExtend = true,
            cycle = 8,
            maxCycles = 8,
            cycleProgressOk = 2,
            stepBudget = 120
        )
        assertNotNull(maxCyclesStop)
        assertTrue(maxCyclesStop!!.reason.contains("الحد الأقصى لدورات المتابعة"))
    }

    @Test
    fun webPreSubmitRecoveryNeverRetriesAfterSubmitOrSslOrAuthRejection() {
        // بعد الإرسال: ممنوع الاستشفاء منعاً باتاً لعدم تكرار الرسالة.
        assertFalse(
            WebPreSubmitRecovery.decide(
                trigger = WebPreSubmitRecovery.Trigger.RENDER_GONE,
                submitted = true,
                alreadyRecovered = false,
                restoredChatUrl = false
            ).recover
        )
        // لا استشفاء أكثر من مرة واحدة لكل طلب.
        assertFalse(
            WebPreSubmitRecovery.decide(
                trigger = WebPreSubmitRecovery.Trigger.PAGE_ERROR,
                submitted = false,
                alreadyRecovered = true,
                restoredChatUrl = false
            ).recover
        )
        // أخطاء الشهادة أو الحجب 401/403 لا يُعاد تحميلها تلقائياً.
        assertFalse(
            WebPreSubmitRecovery.decide(
                trigger = WebPreSubmitRecovery.Trigger.SSL_ERROR,
                submitted = false,
                alreadyRecovered = false,
                restoredChatUrl = false
            ).recover
        )
        assertFalse(
            WebPreSubmitRecovery.decide(
                trigger = WebPreSubmitRecovery.Trigger.HTTP_ERROR,
                submitted = false,
                alreadyRecovered = false,
                restoredChatUrl = false,
                httpStatus = 403
            ).recover
        )

        // رابط محادثة محفوظ حُذف (404) قبل الإرسال -> يعود للمدخل الأساسي ويمسح الرابط المحفوظ.
        val brokenRestore = WebPreSubmitRecovery.decide(
            trigger = WebPreSubmitRecovery.Trigger.HTTP_ERROR,
            submitted = false,
            alreadyRecovered = false,
            restoredChatUrl = true,
            httpStatus = 404
        )
        assertTrue(brokenRestore.recover)
        assertTrue(brokenRestore.clearSavedChatUrl)
        assertTrue(brokenRestore.fallbackToBaseUrl)

        // توقف عارض WebView قبل الإرسال -> يستشفي لمرة واحدة.
        val renderCrash = WebPreSubmitRecovery.decide(
            trigger = WebPreSubmitRecovery.Trigger.RENDER_GONE,
            submitted = false,
            alreadyRecovered = false,
            restoredChatUrl = false
        )
        assertTrue(renderCrash.recover)
    }
}
