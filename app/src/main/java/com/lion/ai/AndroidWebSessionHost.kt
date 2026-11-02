private fun tick(p: Pending) {
    if (System.currentTimeMillis() > p.deadline) {
        fail(p, "انتهت مهلة الجلسة. ${if (p.lastText.isNotEmpty()) "قد تم إرسال الرسالة." else ""}")
        return
    }

    val js = script.replace("__LION_CONFIG__", p.adapter.json().toString())
        .replace("__LION_TOKEN__", JSONObject.quote(p.request.token))
        .replace("__LION_ACTION__", JSONObject.quote(p.stage))
        .replace("__LION_PROMPT__", JSONObject.quote(p.request.prompt))

    p.view.evaluateJavascript(js) { raw ->
        val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
        val result = runCatching { JSONObject(text ?: "{}") }.getOrElse { JSONObject() }

        when (result.optString("status")) {
            "ready" -> {
                p.stage = "prepare"
                status(p, "إعداد الرسالة")
                tick(p)
            }
            "prepared" -> {
                p.stage = "submit"
                status(p, "إرسال الرسالة")
                tick(p)
            }
            "clicked" -> {
                p.submitted = true
                p.stage = "poll"
                status(p, "انتظار الرد")
                tick(p)
            }
            "streaming" -> {
                val text = result.optString("text")
                if (text.isNotEmpty()) p.lastText = text
                status(p, "استلام الرد: ${text.take(20)}...")
                tick(p)
            }
            "complete" -> {
                val text = result.optString("text")
                finish(p, WebSessionTransport.Reply(text, true, true))
            }
            "sent" -> {
                if (p.submitted) {
                    status(p, "الرد قيد التوليد")
                    tick(p)
                } else {
                    p.stage = "submit"
                    tick(p)
                }
            }
            else -> {
                val error = result.optString("error", "حالة غير معروفة: ${result.optString("status")}")
                fail(p, error)
            }
        }
    }

    main.postDelayed({ tick(p) }, 500)
}