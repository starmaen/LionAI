package com.lion.ai

/**
 * فصل أدوار صارم داخل وضع البرمجة. العقل المركزي يوزّع، ولا ينفّذ كل شيء بنفسه.
 * كل دور له مخرج محدد وقيد ميكانيكي، والدورة تُدار عبر PipelineStore.
 */
enum class AgentRole(
    val id: String,
    val label: String,
    val output: String,
    val constraint: String
) {
    PLANNER(
        "planner", "المخطط", "Manifest + عقد الملفات + عقد DAG", "لا يكتب كوداً؛ مخرجاته عقود فقط"
    ),
    IMPLEMENTER(
        "implementer", "المنفذ", "كود وحدة واحدة", "لا يرى أو يعدّل خارج ملفات وحدته"
    ),
    REVIEWER(
        "reviewer", "المدقق", "تقرير فروق وسلوك", "لا يرى كود المنفذ؛ يفحص الناتج والاختبارات"
    ),
    QA(
        "qa", "المختبر", "نتائج الاختبار والبناء", "سياق نظيف بلا انحياز تأكيدي"
    ),
    REPO_MANAGER(
        "repo_manager", "مدير المستودع", "فروع وcommits وPRs", "لا يدمج إلى main دون طلب صريح"
    ),
    ANDROID_ENGINEER(
        "android_engineer", "مهندس أندرويد", "APK وتشغيل وسجلات", "لا يعلن نجاح البناء دون exit code = 0"
    );

    companion object {
        fun fromId(id: String?): AgentRole = entries.firstOrNull { it.id == id } ?: IMPLEMENTER
    }
}

object DevRoles {
    fun prompt(): String = buildString {
        append("=== 👥 أدوار DAG ===\n")
        AgentRole.entries.forEach { role ->
            append("• ").append(role.label).append(" (").append(role.id).append("): ")
                .append(role.output).append(" — القيد: ").append(role.constraint).append('\n')
        }
        append("ابدأ بدور planner عند غياب Manifest، ثم implementer لوحدة واحدة، ثم gate_run، ثم reviewer، ثم qa، ثم android_build.")
    }
}
