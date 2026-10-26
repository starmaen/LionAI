package com.lion.ai

import android.content.Context
import android.database.Cursor
import org.json.JSONArray
import org.json.JSONObject

/** SQLite محلي داخل التطبيق: استعلامات، مخطط، استيراد JSON وتصدير JSON. */
object DatabaseTools {
    private val NAME = Regex("^[A-Za-z_][A-Za-z0-9_]{0,40}$")
    private val IDENT = Regex("^[A-Za-z_][A-Za-z0-9_]{0,60}$")

    private fun safeName(name: String): String {
        val clean = name.trim().removeSuffix(".db").removeSuffix(".sqlite")
        if (!NAME.matches(clean)) throw IllegalArgumentException("اسم قاعدة البيانات غير صالح")
        return clean
    }

    private fun safeIdent(name: String): String {
        val clean = name.trim()
        if (!IDENT.matches(clean)) throw IllegalArgumentException("اسم الجدول/العمود غير صالح")
        return clean
    }

    fun list(context: Context): List<String> =
        context.databaseList().filter { it.endsWith(".db") || it.endsWith(".sqlite") }.sorted()

    fun schema(context: Context, db: String): String {
        val database = context.openOrCreateDatabase(safeName(db), Context.MODE_PRIVATE, null)
        return database.use { connection ->
            val cursor = connection.rawQuery(
                "SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
                null
            )
            val builder = StringBuilder()
            cursor.use {
                while (it.moveToNext()) {
                    val type = it.getString(0)
                    val name = it.getString(1)
                    val sql = it.getString(2)
                    builder.append(type).append(": ").append(name).append('\n')
                    if (!sql.isNullOrBlank()) builder.append(sql).append("\n\n")
                }
            }
            builder.toString().ifBlank { "لا توجد جداول بعد." }
        }
    }

    fun query(context: Context, db: String, sql: String, maxRows: Int = 200): String {
        val statement = sql.trim().removeSuffix(";").trim()
        if (statement.isBlank()) throw IllegalArgumentException("أدخل استعلاماً")
        if (statement.contains(';')) throw IllegalArgumentException("استعلام واحد في كل مرة (احذف الفاصلة المنقوطة المتوسطة)")
        val database = context.openOrCreateDatabase(safeName(db), Context.MODE_PRIVATE, null)
        return database.use { connection ->
            val verb = statement.substringBefore(' ').lowercase()
            val readOnly = verb in setOf("select", "pragma", "explain", "with")
            if (!readOnly) {
                connection.execSQL(statement)
                return@use "✅ نُفّذ الاستعلام. عدد الصفوف المتأثرة: ${countChanges(connection)}"
            }
            connection.rawQuery(statement, null).use { cursor ->
                cursorToJson(cursor, maxRows.coerceIn(1, 1000))
            }
        }
    }

    private fun countChanges(connection: android.database.sqlite.SQLiteDatabase): Int {
        return runCatching {
            connection.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getInt(0) }
        }.getOrDefault(0)
    }

    private fun cursorToJson(cursor: Cursor, maxRows: Int): String {
        val rows = JSONArray()
        var count = 0
        while (cursor.moveToNext() && count < maxRows) {
            val row = JSONObject()
            for (index in 0 until cursor.columnCount) {
                val key = cursor.getColumnName(index)
                row.put(
                    key,
                    when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        Cursor.FIELD_TYPE_BLOB -> "[BLOB ${cursor.getBlob(index)?.size ?: 0} bytes]"
                        else -> cursor.getString(index) ?: ""
                    }
                )
            }
            rows.put(row)
            count++
        }
        return JSONObject()
            .put("rows", rows)
            .put("count", count)
            .put("columns", JSONArray((0 until cursor.columnCount).map { cursor.getColumnName(it) }))
            .toString(2)
    }

    fun tables(context: Context, db: String): List<String> {
        val database = context.openOrCreateDatabase(safeName(db), Context.MODE_PRIVATE, null)
        return database.use { connection ->
            connection.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor ->
                val names = mutableListOf<String>()
                while (cursor.moveToNext()) names.add(cursor.getString(0))
                names
            }
        }
    }

    fun importJson(context: Context, db: String, tableName: String, json: String, replace: Boolean = false): String {
        val table = safeIdent(tableName)
        val array = runCatching { JSONArray(json) }.getOrElse {
            throw IllegalArgumentException("المطلوب مصفوفة JSON مثل [{\"name\":\"A\",\"age\":1}]")
        }
        if (array.length() == 0) return "لا توجد صفوف"
        val columns = linkedSetOf<String>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.keys()?.forEach { columns.add(safeIdent(it)) }
        }
        if (columns.isEmpty()) throw IllegalArgumentException("كل صف يجب أن يكون كائناً JSON")
        val database = context.openOrCreateDatabase(safeName(db), Context.MODE_PRIVATE, null)
        return database.use { connection ->
            if (replace) connection.execSQL("DROP TABLE IF EXISTS `$table`")
            val columnDefs = columns.joinToString(", ") { "`$it` TEXT" }
            connection.execSQL("CREATE TABLE IF NOT EXISTS `$table` ($columnDefs)")
            var inserted = 0
            connection.beginTransaction()
            try {
                val placeholders = columns.joinToString(", ") { "?" }
                val columnList = columns.joinToString(", ") { "`$it`" }
                val statement = connection.compileStatement("INSERT INTO `$table` ($columnList) VALUES ($placeholders)")
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    statement.clearBindings()
                    columns.forEachIndexed { index, column ->
                        val value = row.opt(column)
                        if (value == null || value == JSONObject.NULL) {
                            statement.bindNull(index + 1)
                        } else {
                            statement.bindString(index + 1, value.toString())
                        }
                    }
                    statement.executeInsert()
                    inserted++
                }
                connection.setTransactionSuccessful()
            } finally {
                connection.endTransaction()
            }
            "✅ أُدخل $inserted صفاً في $table"
        }
    }

    fun exportJson(context: Context, db: String, tableName: String, maxRows: Int = 1000): String {
        val table = safeIdent(tableName)
        val database = context.openOrCreateDatabase(safeName(db), Context.MODE_PRIVATE, null)
        return database.use { connection ->
            connection.rawQuery(
                "SELECT * FROM `$table` LIMIT ${maxRows.coerceIn(1, 10000)}",
                null
            ).use { cursorToJson(it, maxRows) }
        }
    }

    fun delete(context: Context, db: String): Boolean = context.deleteDatabase(safeName(db))

    fun columns(context: Context, db: String, tableName: String): List<String> {
        val table = safeIdent(tableName)
        val database = context.openOrCreateDatabase(safeName(db), Context.MODE_PRIVATE, null)
        return database.use { connection ->
            connection.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
                val columns = mutableListOf<String>()
                while (cursor.moveToNext()) columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                columns
            }
        }
    }
}
