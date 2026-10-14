package com.example.iptv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** Lit un guide XMLTV et ne garde que les émissions des 36 prochaines heures. */
object XmltvParser {
    fun parse(input: InputStream, now: Long = System.currentTimeMillis()): Map<String, List<Program>> {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val horizon = now + 36L * 3600 * 1000
        val out = HashMap<String, MutableList<Program>>()

        var channel: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var tag = ""

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "programme") {
                        channel = parser.getAttributeValue(null, "channel")
                        start = date(fmt, parser.getAttributeValue(null, "start"))
                        stop = date(fmt, parser.getAttributeValue(null, "stop"))
                        title = null
                    }
                }
                XmlPullParser.TEXT -> if (channel != null && tag == "title" && title == null) {
                    title = parser.text
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "programme") {
                        val c = channel
                        val t = title
                        if (c != null && t != null && stop > now && start < horizon) {
                            out.getOrPut(c) { mutableListOf() } += Program(c, start, stop, t.trim())
                        }
                        channel = null
                    }
                    tag = ""
                }
            }
            ev = parser.next()
        }
        return out.mapValues { (_, v) -> v.sortedBy { it.start } }
    }

    private fun date(fmt: SimpleDateFormat, s: String?): Long {
        if (s == null) return 0L
        val t = s.trim().let { if (it.length == 14) "$it +0000" else it }
        return try { fmt.parse(t)?.time ?: 0L } catch (e: Exception) { 0L }
    }
}
