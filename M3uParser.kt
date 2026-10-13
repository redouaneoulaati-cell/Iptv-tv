package com.example.iptv

object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var id: String? = null
        var name: String? = null
        var logo: String? = null
        var group: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val a = attr.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    id = a["tvg-id"]?.ifBlank { null }
                    logo = a["tvg-logo"]?.ifBlank { null }
                    group = a["group-title"]?.ifBlank { null }
                    name = line.substringAfterLast(",").trim()
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    out += Channel(id, name?.ifBlank { null } ?: line, line, logo, group)
                    id = null; name = null; logo = null; group = null
                }
            }
        }
        return out
    }
}
