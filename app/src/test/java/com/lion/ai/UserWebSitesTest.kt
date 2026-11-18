package com.lion.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * إضافة صفحات الويب يدوياً بالرابط: التطبيع والمعرّفات والحذف والكشف التلقائي وربط الجسر.
 * كلها دوال نقية أو على [FakePrefs] — بلا شبكة وبلا سياق أندرويد (الكشف الشبكي اختياري).
 */
class UserWebSitesTest {
    /** بنية deepai.org/chat الفعلية: محرر وزر إرسال بلا تسمية، وزر إيقاف، ونافذة دخول. */
    private val chatHtml = """
        <html><head><title>AI Chat - DeepAI</title>
        <style>.outputBox{color:white}</style></head><body>
        <div id="convo"></div>
        <div class="persistent-compose-area">
          <button id="attachFileButton" type="button"></button>
          <textarea id="persistentChatbox" class="chatbox" placeholder="Message AI Chat..." maxlength="100000"></textarea>
          <button id="chatSubmitButton" type="button" onclick="chatSubmit('', true)"></button>
          <button id="chatStopButton" type="button" style="display:none"></button>
        </div>
        <div id="loginModal" role="dialog" aria-modal="true">
          <input id="loginEmail" type="email" placeholder="Email Address">
          <button id="loginSubmit" type="submit">Login</button>
        </div>
        </body></html>
    """.trimIndent()

    @Test fun urlIsNormalizedAndUnsafeLinksAreRejected() {
        assertEquals("https://deepai.org/chat", UserWebSites.normalizeUrl("  deepai.org/chat/  "))
        assertEquals("https://deepai.org/chat", UserWebSites.normalizeUrl("\"https://deepai.org/chat#new\""))
        assertEquals("https://x.app/chat?mode=a", UserWebSites.normalizeUrl("https://x.app/chat/?mode=a"))
        assertThrows(IllegalArgumentException::class.java) { UserWebSites.normalizeUrl("http://x.app/chat") }
        assertThrows(IllegalArgumentException::class.java) { UserWebSites.normalizeUrl("https://user:pass@x.app/chat") }
        assertThrows(IllegalArgumentException::class.java) { UserWebSites.normalizeUrl("https://localhost:8080/chat") }
        assertThrows(IllegalArgumentException::class.java) { UserWebSites.normalizeUrl("") }
    }

    @Test fun idComesFromTheHostAndStaysUnique() {
        // اسم عربي: المعرّف يبنى من النطاق لا من الاسم (المعرّف يجب أن يبقى ascii قصيراً).
        assertEquals("web_deepai-org", UserWebSites.idFor(emptySet(), "ديب إي", "https://deepai.org/chat"))
        assertEquals("web_deepai-org-2", UserWebSites.idFor(setOf("web_deepai-org"), "DeepAI", "https://deepai.org/chat"))
        assertEquals("web_deepai-org-3", UserWebSites.idFor(setOf("web_deepai-org", "web_deepai-org-2"), "x", "https://deepai.org/chat"))
        assertEquals("web_ptero-pro", UserWebSites.idFor(emptySet(), "p", "https://www.ptero.pro/"))
    }

    @Test fun addedSiteCardIsACompleteWebProvider() {
        val entry = UserWebSites.Entry("web_mychat", "My Chat", "https://mychat.app/room")
        val info = UserWebSites.toInfo(entry)
        assertEquals(ProviderGroup.WEB, info.group)
        assertEquals("https://mychat.app/room", info.webviewUrl)
        assertTrue(info.keyless)
        assertTrue(info.supportsAppChat)
        assertFalse(WebChatRuntime.isWeb("deepai"))
        assertEquals("web-session", WebChatRuntime.model(FakePrefs(), "deepai_web"))
        // الربط لا يحتاج تسجيلاً في أي مكان آخر: الجسر يصفّي PROVIDERS على مجموعة WEB وحدها،
        // فأي بطاقة webviewUrl — مدمجة أو مضافة بالرابط — تمر بنفس المسار.
        assertEquals(
            PROVIDERS.filter { it.group == ProviderGroup.WEB }.map { it.id },
            WebSiteAdapters.all().map { it.id }
        )
    }

    @Test fun bridgeMergePutsDetectedSelectorsBeforeTheGenericOnes() {
        val entry = UserWebSites.Entry(
            "web_mychat", "My Chat", "https://mychat.app/room", hosts = listOf("chat.mychat.app"),
            input = "#persistentChatbox", send = "#chatSubmitButton", stop = "#chatStopButton",
            answer = ".outputBox", settleMs = 4000
        )
        val adapters = WebSiteAdapters.build(listOf(UserWebSites.toInfo(entry)), listOf(entry))
        val adapter = adapters.single()
        assertEquals("https://mychat.app/room", adapter.url)
        assertTrue(adapter.accepts("https://mychat.app/room"))
        assertTrue(adapter.accepts("https://www.mychat.app/room"))
        assertTrue(adapter.accepts("https://chat.mychat.app/room"))
        assertFalse(adapter.accepts("https://evil.example/room"))
        // المكتشف أولاً والعامة بعده: كشف خاطئ لا يُسقط ما يعمل به بقية المواقع.
        assertTrue(adapter.input, adapter.input.startsWith("#persistentChatbox, textarea"))
        assertTrue(adapter.send, adapter.send.startsWith("#chatSubmitButton, button[data-testid='send-button']"))
        assertTrue(adapter.stop, adapter.stop.startsWith("#chatStopButton, button[data-testid='stop-button']"))
        assertTrue(adapter.answer, adapter.answer.startsWith(".outputBox, [data-message-role='assistant']"))
        assertEquals(4000, adapter.settleMs)
    }

    @Test fun developerOverrideReplacesTheWholeSelectorList() {
        val prefs = FakePrefs().apply {
            edit().putString(WebSiteAdapters.DOM_PREFIX + "deepai_web_send", "#mineOnly").apply()
        }
        val adapter = requireNotNull(WebSiteAdapters.get("deepai_web", prefs))
        assertEquals("#mineOnly", adapter.send)
        assertEquals(
            "#persistentChatbox, textarea.chatbox, textarea, " +
                "[contenteditable='true'][role='textbox'], [contenteditable='true'].ProseMirror, " +
                "[contenteditable='true']",
            adapter.input
        )
        // بلا مفتاح المطور تبدأ قائمة الموقع نفسها بالمُسمّى صراحةً: هذا ما أصلح صمت DeepAI.
        val plain = requireNotNull(WebSiteAdapters.get("deepai_web"))
        assertEquals("#chatSubmitButton", plain.send.split(", ").first())
        assertEquals("#chatStopButton", plain.stop.split(", ").first())
    }

    @Test fun addStoresTheSiteAndRemoveTakesEverythingWithIt() {
        val prefs = FakePrefs()
        val entry = UserWebSites.add(prefs, "", "https://chat.example.com/room")
        assertEquals("web_chat-example-com", entry.id)
        assertEquals("chat.example.com", entry.label)
        assertEquals(listOf(entry.id), UserWebSites.list(prefs).map { it.id })
        prefs.edit()
            .putString("web_dom_${entry.id}_input", "textarea.me")
            .putBoolean("web_chat_enabled_${entry.id}", true)
            .putLong("web_timeout_${entry.id}", 180L)
            .apply()
        UserWebSites.remove(prefs, entry.id)
        assertTrue(UserWebSites.list(prefs).isEmpty())
        assertFalse(prefs.contains("web_dom_${entry.id}_input"))
        assertFalse(prefs.getBoolean("web_chat_enabled_${entry.id}", false))
        assertFalse(prefs.contains("web_timeout_${entry.id}"))
    }

    @Test fun addingAnAlreadyKnownPageIsRejectedWithAPointer() {
        val prefs = FakePrefs()
        val error = assertThrows(IllegalArgumentException::class.java) {
            UserWebSites.add(prefs, "my claude", "https://claude.ai/new")
        }
        assertTrue(error.message.orEmpty(), error.message!!.contains("Claude"))
        assertTrue(UserWebSites.list(prefs).isEmpty())
    }

    @Test fun removalThroughProviderRemovalDeletesTheWholeEntry() {
        val prefs = FakePrefs()
        val entry = UserWebSites.add(prefs, "Example", "https://chat.example.com/room")
        assertTrue(ProviderRemoval.remove(prefs, entry.id))
        assertTrue(UserWebSites.list(prefs).isEmpty())
    }

    @Test fun bridgeSwitchConsentsTheSiteToSessions() {
        val prefs = FakePrefs()
        val entry = UserWebSites.add(prefs, "Example", "https://chat.example.com/room")
        assertFalse(prefs.getBoolean("web_chat_enabled_${entry.id}", false))
        UserWebSites.setBridgeEnabled(prefs, entry.id, true)
        assertTrue(prefs.getBoolean(WebChatRuntime.ENABLED, false))
        assertTrue(prefs.getBoolean("web_chat_enabled_${entry.id}", false))
        UserWebSites.setBridgeEnabled(prefs, entry.id, false)
        // إيقاف موقع لا يُطفئ المفتاح العام لغيره
        assertFalse(prefs.getBoolean("web_chat_enabled_${entry.id}", false))
        assertTrue(prefs.getBoolean(WebChatRuntime.ENABLED, false))
    }

    @Test fun detectionReadsTheComposersOwnNamesFromHtml() {
        val d = UserWebSites.detect(chatHtml)
        assertEquals("AI Chat - DeepAI", d.title)
        // محرر الكتابة لا فقاعات الحوار: النص الإرشادي يحسم التعادل بين textarea المتشابهة.
        assertEquals("#persistentChatbox", d.input)
        assertEquals("#chatSubmitButton", d.send)
        assertEquals("#chatStopButton", d.stop)
        assertEquals(".outputBox", d.answer)
        assertTrue(d.summary(), !d.summary().contains("Login", true))
    }

    @Test fun detectionRefusesAuthAndNewsletterButtonsAndEmptyPages() {
        val modalOnly = """
            <html><head><title>Sign in</title></head><body>
            <div role="dialog" aria-modal="true">
              <textarea id="feedbackBox" placeholder="Message"></textarea>
              <button id="sendNewsletter">Send</button>
              <button id="loginSubmit" type="submit">Login</button>
            </div></body></html>
        """.trimIndent()
        val d = UserWebSites.detect(modalOnly)
        assertEquals("Sign in", d.title)
        assertEquals("", d.send)
        assertEquals("", d.stop)
        assertEquals("", d.answer)
        assertTrue(UserWebSites.detect("").isEmpty)
        assertTrue(UserWebSites.detect("<html><head><title>x</title></head></html>").input.isBlank())
    }

    @Test fun entitiesAndOddIdsBecomeUsableSelectors() {
        val tricky = """
            <html><head><title>Q &amp; A &#8212; chat</title></head><body>
            <textarea id="msg box" placeholder="Message"></textarea>
            <button id="send'quote"></button>
            </body></html>
        """.trimIndent()
        val d = UserWebSites.detect(tricky)
        assertEquals("Q & A — chat", d.title)
        assertEquals("[id='msg box']", d.input)
        assertEquals("[id='send\\'quote']", d.send)
        assertEquals("#safe_id", UserWebSites.idSelector("safe_id"))
    }

    @Test fun detectionIsStoredInSiteCardAndKeepsRoomForOverrides() {
        val prefs = FakePrefs()
        val entry = UserWebSites.add(prefs, "", "https://deepai.org.example/chat")
        UserWebSites.storeDetection(prefs, entry.id, UserWebSites.detect(chatHtml), "www.deepai.org.example", entry.label)
        val stored = UserWebSites.list(prefs).single()
        assertEquals("#persistentChatbox", stored.input)
        assertEquals("#chatSubmitButton", stored.send)
        assertEquals("AI Chat - DeepAI", stored.label)
        assertEquals(listOf("www.deepai.org.example"), stored.hosts)
        val adapter = WebSiteAdapters.build(listOf(UserWebSites.toInfo(stored)), listOf(stored)).single()
        assertTrue(adapter.accepts("https://www.deepai.org.example/chat"))
        assertTrue(adapter.input.startsWith("#persistentChatbox, textarea"))
        // كشف فاشل لا يمحو ما سبق، والاسم المكتوب يدوياً لا يبدَّل بعنوان الصفحة.
        UserWebSites.storeDetection(prefs, entry.id, UserWebSites.Detected(), "")
        val after = UserWebSites.list(prefs).single()
        assertEquals("#persistentChatbox", after.input)
        UserWebSites.storeDetection(
            prefs, entry.id, UserWebSites.Detected(title = "Another", send = "#x2"), "", ""
        )
        assertEquals("AI Chat - DeepAI", UserWebSites.list(prefs).single().label)
        assertEquals("#x2", UserWebSites.list(prefs).single().send)
    }

    @Test fun entriesSurviveJsonRoundTripAndBadData() {
        val entries = listOf(
            UserWebSites.Entry(
                "web_a", "A", "https://a.app/chat", listOf("x.a.app"),
                "#i", "#s", "#t", ".ans", 4000
            )
        )
        assertEquals(entries.single(), UserWebSites.parse(UserWebSites.serialize(entries)).single())
        assertTrue(UserWebSites.parse("").isEmpty())
        assertTrue(UserWebSites.parse("{not json}").isEmpty())
        assertTrue(UserWebSites.parse("""[{"id":"","url":""}]""").isEmpty())
        val partial = UserWebSites.parse("""[{"id":"web_b","url":"https://b.app"}]""").single()
        assertEquals(UserWebSites.DEFAULT_SETTLE_MS, partial.settleMs)
        assertTrue(partial.input.isBlank())
        assertTrue(UserWebSites.byId("deepai_web") == null)   // لا سياق في اختبارات JVM
        assertTrue(UserWebSites.entries().isEmpty())
    }
}
