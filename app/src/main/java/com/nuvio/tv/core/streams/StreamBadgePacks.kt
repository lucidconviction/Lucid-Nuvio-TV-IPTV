package com.nuvio.tv.core.streams

/**
 * A badge pack built into the app: the same filters an imported Fusion JSON provides, but defined
 * locally. Adding one puts it in [StreamBadgeRules.imports] like any imported pack, so it can be
 * updated, removed or chosen for the player with the existing badge settings.
 */
data class StreamBadgePack(
    val id: String,
    val name: String,
    val description: String,
    val filters: List<StreamBadgeFilter>,
    val groups: List<StreamBadgeGroup> = emptyList()
) {
    val sourceUrl: String
        get() = StreamBadgePacks.sourceUrlFor(id)

    fun toImport(): StreamBadgeImport = StreamBadgeImport(
        sourceUrl = sourceUrl,
        filters = filters,
        groups = groups
    )
}

object StreamBadgePacks {
    const val PREFIX = "preset://"

    fun sourceUrlFor(id: String): String = "$PREFIX$id"

    fun packFor(sourceUrl: String): StreamBadgePack? =
        all.firstOrNull { it.sourceUrl.equals(sourceUrl.trim(), ignoreCase = true) }

    fun isPreset(sourceUrl: String): Boolean = sourceUrl.trim().startsWith(PREFIX, ignoreCase = true)

    private fun filter(
        id: String,
        groupId: String,
        name: String,
        pattern: String,
        tagColor: String = "",
        textColor: String = "",
        borderColor: String = ""
    ) = StreamBadgeFilter(
        id = id,
        groupId = groupId,
        name = name,
        pattern = pattern,
        tagColor = tagColor,
        tagStyle = "filled",
        textColor = textColor,
        borderColor = borderColor
    )

    private fun group(id: String, name: String) = StreamBadgeGroup(id = id, name = name)

    /** Picture resolution, from the stream name or the decoder. */
    val resolution = StreamBadgePack(
        id = "resolution",
        name = "Resolution",
        description = "8K, 4K, 1440p, 1080p, 720p and 480p",
        groups = listOf(group("resolution", "Resolution")),
        filters = listOf(
            filter("resolution-8k", "resolution", "8K", "(?i)\\b(8k|4320p)\\b", "6B4FBB"),
            filter("resolution-4k", "resolution", "4K", "(?i)\\b(4k|2160p|uhd)\\b", "B8860B"),
            filter("resolution-1440p", "resolution", "1440p", "(?i)\\b(1440p|qhd|2k)\\b", "2E7D6B"),
            filter("resolution-1080p", "resolution", "1080p", "(?i)\\b(1080p|fhd|full\\s*hd)\\b", "1F6FB2"),
            filter("resolution-720p", "resolution", "720p", "(?i)\\b(720p)\\b", "4A6572"),
            filter("resolution-480p", "resolution", "480p", "(?i)\\b(480p|sd)\\b", "5D4E37")
        )
    )

    /** High dynamic range formats. */
    val hdr = StreamBadgePack(
        id = "hdr",
        name = "HDR",
        description = "Dolby Vision, HDR10+, HDR10 and HLG",
        groups = listOf(group("hdr", "HDR")),
        filters = listOf(
            filter("hdr-dv", "hdr", "Dolby Vision", "(?i)\\b(dolby\\s*vision|dovi|dv)\\b", "7B1FA2"),
            filter("hdr-hdr10plus", "hdr", "HDR10+", "(?i)\\b(hdr10\\+|hdr10plus)\\b", "8E24AA"),
            filter("hdr-hdr10", "hdr", "HDR10", "(?i)\\bhdr10\\b(?!\\+)", "5E35B1"),
            filter("hdr-hlg", "hdr", "HLG", "(?i)\\bhlg\\b", "3949AB"),
            filter("hdr-hdr", "hdr", "HDR", "(?i)\\bhdr\\b(?!10|\\+)", "4527A0")
        )
    )

    /** Audio formats and channel layouts. */
    val audio = StreamBadgePack(
        id = "audio",
        name = "Audio",
        description = "Atmos, DTS:X, DTS-HD, TrueHD, 7.1, 5.1 and AAC",
        groups = listOf(group("audio", "Audio")),
        filters = listOf(
            filter("audio-atmos", "audio", "Atmos", "(?i)\\batmos\\b", "00695C"),
            filter("audio-dtsx", "audio", "DTS:X", "(?i)\\bdts[:-]?\\s*x\\b", "00796B"),
            filter("audio-dtshd", "audio", "DTS-HD", "(?i)\\bdts[- ]?hd(\\s*(ma|hra))?\\b", "00838F"),
            filter("audio-truehd", "audio", "TrueHD", "(?i)\\btruehd\\b", "00838F"),
            filter("audio-dts", "audio", "DTS", "(?i)\\bdts\\b(?![- ]?hd|[:-]?\\s*x)", "006064"),
            filter("audio-71", "audio", "7.1", "(?i)(?<!\\d)7[.\\s]1(?!\\d)", "37474F"),
            filter("audio-51", "audio", "5.1", "(?i)(?<!\\d)5[.\\s]1(?!\\d)", "455A64"),
            filter("audio-aac", "audio", "AAC", "(?i)\\baac\\b", "546E7A")
        )
    )

    /** Release source and video codec. */
    val source = StreamBadgePack(
        id = "source",
        name = "Source & codec",
        description = "Remux, BluRay, WEB-DL, WEBRip, HDTV, HEVC, H.264 and AV1",
        groups = listOf(group("source", "Source"), group("codec", "Codec")),
        filters = listOf(
            filter("source-remux", "source", "Remux", "(?i)\\bremux\\b", "4E342E"),
            filter("source-bluray", "source", "BluRay", "(?i)\\b(blu-?ray|bdrip|brrip)\\b", "5D4037"),
            filter("source-webdl", "source", "WEB-DL", "(?i)\\bweb[- ]?dl\\b", "37474F"),
            filter("source-webrip", "source", "WEBRip", "(?i)\\bweb[- ]?rip\\b", "455A64"),
            filter("source-hdtv", "source", "HDTV", "(?i)\\bhdtv\\b", "546E7A"),
            filter("codec-hevc", "codec", "HEVC", "(?i)\\b(hevc|h\\.?265|x265)\\b", "1B5E20"),
            filter("codec-h264", "codec", "H.264", "(?i)\\b(avc|h\\.?264|x264)\\b", "2E7D32"),
            filter("codec-av1", "codec", "AV1", "(?i)\\bav1\\b", "33691E")
        )
    )

    val all: List<StreamBadgePack> = listOf(resolution, hdr, audio, source)
}
