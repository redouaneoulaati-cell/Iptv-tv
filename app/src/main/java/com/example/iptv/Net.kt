package com.example.iptv

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Net {
    /** Ouvre une URL http(s) ou un fichier local (content://). Détecte le gzip automatiquement. */
    fun open(ctx: Context, source: String): InputStream {
        val raw: InputStream = if (source.startsWith("content:")) {
            ctx.contentResolver.openInputStream(Uri.parse(source))
                ?: throw IllegalStateException("Fichier introuvable")
        } else {
            val c = URL(source).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "IPTVApp/1.0")
            c.inputStream
        }
        val b = BufferedInputStream(raw)
        b.mark(2)
        val b1 = b.read()
        val b2 = b.read()
        b.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(b) else b
    }
}
