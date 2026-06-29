package com.g7monitor.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import com.g7monitor.shared.platform.systemLanguage
import com.g7monitor.shared.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Eigene Mehrsprachigkeit: lädt die strings.xml (de/en/es) als Dateien und
 *  liefert die Texte je nach AppState.language — auch live auf iOS. */
@OptIn(ExperimentalResourceApi::class)
object I18n {
    val maps = mutableStateMapOf<String, Map<String, String>>()

    suspend fun preload() {
        for (l in listOf("de", "en", "es")) {
            if (l !in maps) maps[l] = parse(Res.readBytes("files/$l.xml").decodeToString())
        }
    }

    private val re = Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
    private fun parse(xml: String): Map<String, String> = re.findAll(xml).associate { m ->
        m.groupValues[1] to m.groupValues[2]
            .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }

    fun get(lang: String, key: String): String =
        maps[lang]?.get(key) ?: maps["de"]?.get(key) ?: key
}

@Composable
fun tr(key: String, vararg args: Any): String {
    val sys = systemLanguage()
    val lang = AppState.language.ifEmpty { sys }.let { if (it == "de" || it == "en" || it == "es") it else "de" }
    var s = I18n.get(lang, key)
    args.forEachIndexed { i, a ->
        s = s.replace("%${i + 1}\$d", a.toString()).replace("%${i + 1}\$s", a.toString())
    }
    return s
}
