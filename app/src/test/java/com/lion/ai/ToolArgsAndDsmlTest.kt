package com.lion.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgsAndDsmlTest {

    @Test
    fun validateArgsBatchesMissingParametersAndIncludesSignature() {
        val err = AiTools.validateArgs(ToolCall("notion_create", JSONObject().put("foo", "bar")))
        assertNotNull(err)
        assertTrue(err!!.contains("parent_page"))
        assertTrue(err.contains("title"))
        assertTrue(err.contains("content"))
        assertTrue(err.contains("foo"))
        assertTrue(err.contains("التوقيع المتوقع"))
    }

    @Test
    fun validateArgsAllowsEmptyStringForNewAndContentWhenKeyPresent() {
        // حذف نص عبر file_edit (new = "") وإنشاء ملف فارغ عبر file_write (content = "") عمليتان صحيحتان.
        val validDelete = ToolCall(
            "file_edit",
            JSONObject().put("path", "Main.kt").put("old", "unused()").put("new", "")
        )
        assertNull(AiTools.validateArgs(validDelete))

        val missingNew = ToolCall(
            "file_edit",
            JSONObject().put("path", "Main.kt").put("old", "unused()")
        )
        val err = AiTools.validateArgs(missingNew)
        assertNotNull(err)
        assertTrue(err!!.contains("«new»"))

        val validEmptyFile = ToolCall(
            "file_write",
            JSONObject().put("path", "empty.txt").put("content", "")
        )
        assertNull(AiTools.validateArgs(validEmptyFile))

        val missingContent = ToolCall("file_write", JSONObject().put("path", "empty.txt"))
        assertNotNull(AiTools.validateArgs(missingContent))
    }

    @Test
    fun validateArgsRejectsZeroOrNegativeRequiredIdsWhileKeepingOptionalRunIds() {
        assertNotNull(AiTools.validateArgs(ToolCall("github_rerun", JSONObject())))
        assertNotNull(AiTools.validateArgs(ToolCall("github_rerun", JSONObject().put("run_id", 0))))
        assertNull(AiTools.validateArgs(ToolCall("github_rerun", JSONObject().put("run_id", 987654L))))

        assertNotNull(AiTools.validateArgs(ToolCall("github_comment_issue", JSONObject().put("body", "hi"))))
        assertNull(
            AiTools.validateArgs(
                ToolCall("github_comment_issue", JSONObject().put("number", 12).put("body", "hi"))
            )
        )

        assertNotNull(AiTools.validateArgs(ToolCall("github_job_log", JSONObject())))
        assertNotNull(AiTools.validateArgs(ToolCall("github_download_artifact", JSONObject().put("artifact_id", -5))))
        assertNotNull(AiTools.validateArgs(ToolCall("telegram_delete", JSONObject().put("chat_id", "123"))))

        // في المقابل: github_wait_run وbuild_diagnose يقبلان 0 (= أحدث عملية بناء).
        assertNull(AiTools.validateArgs(ToolCall("github_wait_run", JSONObject())))
        assertNull(AiTools.validateArgs(ToolCall("build_diagnose", JSONObject())))
    }

    @Test
    fun githubDispatchAcceptsWorkflowFileNameOrNumericIdAndNormalizesAlias() {
        assertNotNull(AiTools.validateArgs(ToolCall("github_dispatch", JSONObject())))
        assertNotNull(AiTools.validateArgs(ToolCall("github_dispatch", JSONObject().put("workflow_id", 0))))

        val byName = ToolCall("github_dispatch", JSONObject().put("workflow", "build.yml"))
        assertNull(AiTools.validateArgs(byName))
        assertEquals("build.yml", byName.args.optString("workflow_id"))

        val byId = ToolCall("github_dispatch", JSONObject().put("workflow_id", 44123L))
        assertNull(AiTools.validateArgs(byId))
    }

    @Test
    fun normalizeArgsScopesAliasesPerToolAndCoercesStringifiedArrays() {
        val patchCall = ToolCall(
            "code_patch",
            JSONObject().put("file_path", "App.kt").put("old", "val a = 1").put("new", "val a = 2")
        )
        assertNull(AiTools.validateArgs(patchCall))
        assertEquals("App.kt", patchCall.args.getString("path"))
        assertEquals("val a = 1", patchCall.args.getString("find"))
        assertEquals("val a = 2", patchCall.args.getString("replace"))

        val readMany = ToolCall(
            "file_read_many",
            JSONObject().put("paths", "[\"a.kt\", \"b.kt\"]")
        )
        assertNull(AiTools.validateArgs(readMany))
        val arr = readMany.args.optJSONArray("paths")
        assertNotNull(arr)
        assertEquals(2, arr!!.length())
    }

    @Test
    fun dsmlParsesTypedArraysNumbersBooleansWhileKeepingRawContentStrings() {
        val dsml = """
            سأقرأ الملفين ثم أكتب النتيجة:
            <｜DSML｜function_calls>
            <｜DSML｜invoke id="1" name="file_read_many">
            <｜DSML｜parameter name="paths">["app/Main.kt","app/Util.kt"]</｜DSML｜parameter>
            <｜DSML｜parameter name="max_bytes" string="false">8192</｜DSML｜parameter>
            </｜DSML｜invoke>
            <｜DSML｜invoke name='file_write'>
            <｜DSML｜parameter name='path'>config.json</｜DSML｜parameter>
            <｜DSML｜parameter name='content'>["keep","as","raw","string"]</｜DSML｜parameter>
            <｜DSML｜parameter name='allow_shrink'>true</｜DSML｜parameter>
            </｜DSML｜invoke>
            </｜DSML｜function_calls>
        """.trimIndent()

        val calls = AiTools.parseCalls(dsml)
        assertEquals(2, calls.size)

        assertEquals("file_read_many", calls[0].name)
        assertTrue(calls[0].args.get("paths") is JSONArray)
        assertEquals(2, calls[0].args.getJSONArray("paths").length())
        assertEquals(8192L, calls[0].args.getLong("max_bytes"))

        assertEquals("file_write", calls[1].name)
        // content في RAW_TEXT_KEYS فيجب أن يبقى String نصياً لا JSONArray!
        assertTrue(calls[1].args.get("content") is String)
        assertEquals("[\"keep\",\"as\",\"raw\",\"string\"]", calls[1].args.getString("content"))
        assertEquals(true, calls[1].args.getBoolean("allow_shrink"))

        // ToolMarkup.strip يحذف كتلة function_calls بالكامل فلا تتسرّب قيم المعاملات إلى فقاعة الشات.
        val stripped = ToolMarkup.strip(dsml)
        assertEquals("سأقرأ الملفين ثم أكتب النتيجة:", stripped)
        assertFalse(stripped.contains("Main.kt"))
        assertFalse(stripped.contains("config.json"))
    }

    @Test
    fun multipleBareJsonCallsAndWebWrappedDsmlAndToolCallTagsParseCleanly() {
        val multiBare = """
            {"name":"file_tree","args":{"max":5}}
            {"name":"file_read","args":{"path":"README.md"}}
        """.trimIndent()
        val bareCalls = AiTools.parseCalls(multiBare)
        assertEquals(2, bareCalls.size)
        assertEquals("file_tree", bareCalls[0].name)
        assertEquals("file_read", bareCalls[1].name)

        val fencedWebToolCall = "رد الصفحة:\n```xml\n<tool_call>\n{\u200B“name”: “web_search”, “args”: {“q”: “kotlin coroutines”}}\n</tool_call>\n```"
        val normalized = WebToolBridge.normalizeReply(fencedWebToolCall)
        val parsed = AiTools.parseCalls(normalized)
        assertEquals(1, parsed.size)
        assertEquals("web_search", parsed[0].name)
        assertNull(AiTools.validateArgs(parsed[0]))
        assertEquals("kotlin coroutines", parsed[0].args.getString("query"))
        assertEquals("رد الصفحة:", ToolMarkup.strip(normalized))
    }
}
