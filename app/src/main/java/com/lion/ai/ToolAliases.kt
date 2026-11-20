package com.lion.ai

/**
 * يترجم أسماء الأدوات التي تعوّدت عليها النماذج (read/write/edit/bash/grep… أسلوب Claude Code وغيره)
 * إلى أسماء أدوات التطبيق، فلا تفشل الحلقة على اسم مختلف. يعمل مع كل المزودات: الأصلي والنصي والويب.
 */
internal object ToolAliases {
    private val PREFIXES = listOf("functions.", "function.", "default_api.", "tools.", "tool.", "api.")
    private val CAMEL = Regex("([a-z0-9])([A-Z])")

    private val ALIASES: Map<String, String> = listOf(
        "file_read" to listOf("read", "view", "cat", "open_file", "readfile", "read_text", "get_file"),
        "file_write" to listOf("write", "create", "create_file", "createfile", "save_file", "writefile"),
        "file_edit" to listOf("edit", "str_replace", "strreplace", "str_replace_editor", "replace", "update_file", "modify_file", "edit_file"),
        "file_list" to listOf("ls", "list", "list_files", "list_directory", "listdir", "dir"),
        "file_tree" to listOf("tree"),
        "file_search" to listOf("glob", "find", "find_files", "search_files", "grep", "search", "ripgrep", "rg"),
        "execute_command" to listOf("bash", "sh", "shell", "terminal", "run", "run_command", "exec", "cmd", "command"),
        "todo_write" to listOf("todo", "todos", "todowrite", "todo_list", "update_plan", "update_todos"),
        "file_delete" to listOf("delete", "remove", "rm", "delete_file"),
        "file_mkdir" to listOf("mkdir"),
        "file_move" to listOf("move", "mv", "rename"),
        "file_copy" to listOf("copy", "cp")
    ).flatMap { (target, names) -> names.map { it to target } }.toMap()

    fun normalize(raw: String): String {
        var s = raw.trim()
        PREFIXES.forEach { p -> if (s.startsWith(p, ignoreCase = true)) s = s.substring(p.length) }
        return s.replace(CAMEL, "\$1_\$2").lowercase().replace(' ', '_').replace('-', '_')
    }

    /** الاسم المعتمد في التطبيق، أو "" إن لم يُعرف حتى بالمرادفات. */
    fun resolve(raw: String, known: Set<String>): String {
        val n = normalize(raw)
        if (n.isEmpty()) return ""
        if (n in known) return n
        known.firstOrNull { it.equals(n, ignoreCase = true) }?.let { return it }
        val target = ALIASES[n] ?: return ""
        return if (target in known) target else ""
    }

    fun canonical(call: ToolCall, known: Set<String>): ToolCall {
        if (call.name in known) return call
        val resolved = resolve(call.name, known)
        return if (resolved.isEmpty()) call else ToolCall(resolved, call.args, "")
    }

    private fun distance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var diagonal = prev[0]
            prev[0] = i
            for (j in 1..b.length) {
                val keep = prev[j]
                prev[j] = minOf(prev[j] + 1, prev[j - 1] + 1, diagonal + if (a[i - 1] == b[j - 1]) 0 else 1)
                diagonal = keep
            }
        }
        return prev[b.length]
    }

    fun suggest(raw: String, known: Set<String>, limit: Int = 3): List<String> {
        val n = normalize(raw)
        if (n.length < 2) return emptyList()
        val first = ALIASES[n]?.takeIf { it in known }
        val rest = known.asSequence()
            .filter { it != first }
            .mapNotNull { name ->
                val near = name.contains(n) || (name.length >= 4 && n.contains(name))
                val d = distance(n, name)
                when {
                    near -> Triple(name, 0, name.length)
                    d <= maxOf(2, n.length / 3) -> Triple(name, d, name.length)
                    else -> null
                }
            }
            .sortedWith(compareBy({ it.second }, { it.third }, { it.first }))
            .map { it.first }
            .toList()
        return (listOfNotNull(first) + rest).take(limit)
    }

    /** السطر الأول يبقى «❌ أداة غير معروفة: …» كي تعمل عدّادات الفشل كما هي. */
    fun unknownMessage(name: String, known: Set<String>): String = buildString {
        append("❌ أداة غير معروفة: ").append(name)
        val sg = suggest(name, known)
        if (sg.isNotEmpty()) append("\nهل تقصد: ").append(sg.joinToString("، ")).append("؟")
        append("\nأسماء الأدوات حرفية كما في القائمة (file_read لا read، file_write لا write، file_edit لا edit، execute_command لا bash). ")
        append("لمعرفة أدوات مجموعة استدعِ tools_schema.")
    }
}
