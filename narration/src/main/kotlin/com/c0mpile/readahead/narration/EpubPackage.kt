package com.c0mpile.readahead.narration

import android.util.Xml
import org.apache.commons.compress.archivers.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import java.net.URLDecoder

internal object EpubPackage {
    class Package(
        val title: String?,
        val author: String?,
        val spine: List<String>,
        val navPath: String?,
        val ncxPath: String?,
        val bodyHref: String?,
        val coverPath: String?,
    )

    fun read(zip: ZipFile): Package {
        val container =
            entryText(zip, "META-INF/container.xml", MAX_CONTAINER_BYTES) ?: throw EpubException("Not an EPUB: no container.xml")
        val opfPath =
            Markup
                .tags(container)
                .firstOrNull { it.name == "rootfile" }
                ?.let { Markup.attr(it.attrs, "full-path") }
                ?.let(::decodePath)
                ?: throw EpubException("Not an EPUB: no package file")
        val opf = entryText(zip, opfPath, MAX_XML_BYTES) ?: throw EpubException("Not an EPUB: package file unreadable")
        val parsed = parseOpf(opf)
        val base = opfPath

        fun abs(href: String) = resolve(base, href).first
        val manifest = parsed.manifest.mapValues { abs(it.value.href) to it.value }
        val spine = parsed.spine.mapNotNull { manifest[it.first] }.map { it.first }
        val nav =
            parsed.manifest.values
                .firstOrNull { "nav" in it.properties.split(' ') }
                ?.let { abs(it.href) }
        val ncx = parsed.ncxId?.let(manifest::get)?.first
        val landmark = nav?.let { entryText(zip, it, MAX_XML_BYTES) }?.let(EpubToc::bodyMatterHref)?.let { resolve(nav, it).first }
        val guide = parsed.guideBody?.let { abs(it) }
        // EPUB 3 marks the image in the manifest; EPUB 2 names its id in a <meta name="cover">.
        val cover =
            parsed.manifest.values
                .firstOrNull { "cover-image" in it.properties.split(' ') }
                ?.let { abs(it.href) }
                ?: parsed.coverId?.let(manifest::get)?.first
        return Package(parsed.title, parsed.author, spine, nav, ncx, landmark ?: guide, cover)
    }

    private class Item(
        val href: String,
        val properties: String,
    )

    private class Opf(
        val title: String?,
        val author: String?,
        val manifest: Map<String, Item>,
        val spine: List<Pair<String, Boolean>>,
        val ncxId: String?,
        val guideBody: String?,
        val coverId: String?,
    )

    private fun parseOpf(xml: String): Opf {
        val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }
        parser.setInput(xml.reader())
        var title: String? = null
        var author: String? = null
        var ncxId: String? = null
        var guideBody: String? = null
        var coverId: String? = null
        val manifest = LinkedHashMap<String, Item>()
        val spine = ArrayList<Pair<String, Boolean>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name.substringAfter(':')) {
                "title" -> if (title == null) title = parser.text()
                "creator" -> if (author == null) author = parser.text()
                "item" -> {
                    val id = parser.getAttributeValue(null, "id")
                    val href = parser.getAttributeValue(null, "href")
                    if (id != null && href != null) manifest[id] = Item(href, parser.getAttributeValue(null, "properties").orEmpty())
                }
                "meta" -> coverId = coverId ?: parser.coverContent()
                "spine" -> ncxId = parser.getAttributeValue(null, "toc")
                "itemref" ->
                    parser.getAttributeValue(null, "idref")?.let {
                        spine += it to (parser.getAttributeValue(null, "linear") != "no")
                    }
                "reference" ->
                    if (parser.getAttributeValue(null, "type") in GUIDE_BODY && guideBody == null) {
                        guideBody = parser.getAttributeValue(null, "href")?.substringBefore('#')
                    }
            }
        }
        return Opf(title, author, manifest, spine, ncxId, guideBody, coverId)
    }

    /** The `content` of an EPUB 2 `<meta name="cover" content="id"/>`; null for any other meta element. */
    private fun XmlPullParser.coverContent(): String? =
        getAttributeValue(null, "content").takeIf { getAttributeValue(null, "name") == "cover" }

    private fun XmlPullParser.text(): String? = runCatching { nextText().trim() }.getOrNull()?.ifEmpty { null }

    /** [href] relative to the file [base], as a zip path and its fragment. */
    fun resolve(
        base: String,
        href: String,
    ): Pair<String, String?> {
        val path = href.substringBefore('#')
        val fragment = href.substringAfter('#', "").ifEmpty { null }?.let(::decodePath)
        val dir = base.substringBeforeLast('/', "")
        val parts = ArrayList<String>()
        for (part in (if (dir.isEmpty() || path.startsWith("/")) path.trimStart('/') else "$dir/$path").split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return decodePath(parts.joinToString("/")) to fragment
    }

    private fun decodePath(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    fun entryText(
        zip: ZipFile,
        name: String,
        max: Long,
    ): String? =
        zip
            .getEntry(name)
            ?.let { entry ->
                zip.getInputStream(entry).use { it.readBounded(max) }
            }?.toString(Charsets.UTF_8)
            ?.removePrefix("\uFEFF")

    const val MAX_XML_BYTES = 4L shl 20
    private val GUIDE_BODY = setOf("text", "bodymatter", "start", "beginning")
    private const val MAX_CONTAINER_BYTES = 64L shl 10
}
