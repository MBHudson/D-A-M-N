package com.damn.app.server

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface PhpEngine {
    fun render(file: File, docRoot: File): String
}

/**
 * Legacy regex-based engine for basic PHP tasks.
 */
class SimplePhpEngine : PhpEngine {
    override fun render(file: File, docRoot: File): String {
        val raw = file.readText(Charsets.UTF_8)
        return try {
            val phpBlockRegex = Regex("<\\?php(.*?)\\?>", RegexOption.DOT_MATCHES_ALL)
            var html = phpBlockRegex.replace(raw) { mr ->
                evaluateSimplePhp(mr.groupValues[1], docRoot)
            }
            html = html.replace(Regex("<\\?(.*?)\\?>", RegexOption.DOT_MATCHES_ALL)) { mr ->
                evaluateSimplePhp(mr.groupValues[1], docRoot)
            }
            html
        } catch (e: Exception) {
            "<!-- DAMN Simple engine error: ${e.message} -->\n" + raw.replace(Regex("<\\?php|\\?>"), "")
        }
    }

    private fun evaluateSimplePhp(code: String, docRoot: File): String {
        var c = code.trim()
        if (c.isEmpty()) return ""
        c = c.replace(Regex("//.*"), "").replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        val out = StringBuilder()
        val vars = mutableMapOf<String, String>()
        
        // Very simplified statement splitting
        val statements = c.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        for (s in statements) {
            when {
                s.startsWith("echo ") -> out.append(evalExpr(s.removePrefix("echo ").trim(), vars, docRoot))
                s.startsWith("print ") -> out.append(evalExpr(s.removePrefix("print ").trim(), vars, docRoot))
                s.startsWith("$") && "=" in s -> {
                    val name = s.substringBefore("=").trim().removePrefix("$").trim()
                    vars[name] = evalExpr(s.substringAfter("=").trim(), vars, docRoot)
                }
                s == "phpinfo()" -> {
                    out.append("<html><body><h1>PHP Version (DAMN Simple Engine)</h1><p>Date: ${Date()}</p><p>DocRoot: ${docRoot.absolutePath}</p></body></html>")
                }
            }
        }
        return out.toString()
    }

    private fun evalExpr(expr: String, vars: Map<String, String>, docRoot: File): String {
        var e = expr.trim()
        if ((e.startsWith("\"") && e.endsWith("\"")) || (e.startsWith("'") && e.endsWith("'"))) {
            var inner = e.substring(1, e.length - 1)
            if (e.startsWith("\"")) {
                for ((k, v) in vars) inner = inner.replace("\$$k", v)
            }
            return inner
        }
        if (e.startsWith("$")) {
            val name = e.removePrefix("$").trim().split(Regex("\\W"))[0]
            return vars[name] ?: ""
        }
        if (e.startsWith("date(")) {
            val fmt = Regex("date\\s*\\(\\s*[\"'](.*?)[\"']").find(e)?.groupValues?.get(1) ?: "Y-m-d H:i:s"
            return try { SimpleDateFormat(fmt.replace("Y", "yyyy").replace("m", "MM").replace("d", "dd"), Locale.US).format(Date()) } catch (_: Exception) { Date().toString() }
        }
        if (e == "__DIR__") return docRoot.absolutePath
        return e
    }
}
