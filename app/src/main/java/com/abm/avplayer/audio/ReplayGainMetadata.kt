package com.abm.avplayer.audio

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.mp3.Mp3InfoReplayGain
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlin.math.ln

data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null
)

/**
 * Reads standard ReplayGain comments and the LAME/Xing ReplayGain fields exposed by Media3.
 * Unsupported or malformed tags are ignored rather than interrupting playback.
 */
object ReplayGainMetadataParser {
    fun parse(metadata: Metadata): ReplayGainTags {
        var trackGain: Float? = null
        var albumGain: Float? = null
        var trackPeak: Float? = null
        var albumPeak: Float? = null

        fun accept(keyValue: String, rawValue: String) {
            val key = keyValue.trim().uppercase()
            val number = rawValue.trim().substringBefore(" ").toFloatOrNull()
                ?.takeIf { it.isFinite() } ?: return
            when (key) {
                "REPLAYGAIN_TRACK_GAIN" -> trackGain = number
                "REPLAYGAIN_ALBUM_GAIN" -> albumGain = number
                "REPLAYGAIN_TRACK_PEAK" -> trackPeak = number.takeIf { it > 0f }
                "REPLAYGAIN_ALBUM_PEAK" -> albumPeak = number.takeIf { it > 0f }
                "R128_TRACK_GAIN" -> trackGain = number / 256f
                "R128_ALBUM_GAIN" -> albumGain = number / 256f
            }
        }

        for (index in 0 until metadata.length()) {
            when (val entry = metadata[index]) {
                is VorbisComment -> accept(entry.key, entry.value)
                is TextInformationFrame -> {
                    if (entry.id == "TXXX") {
                        val description = entry.description ?: continue
                        entry.values.firstOrNull()?.let { accept(description, it) }
                    }
                }
                is CommentFrame -> accept(entry.description, entry.text)
                is Mp3InfoReplayGain -> {
                    listOfNotNull(entry.field1, entry.field2).forEach { field ->
                        when (field.name) {
                            Mp3InfoReplayGain.GainField.NAME_RADIO -> trackGain = field.gain
                            Mp3InfoReplayGain.GainField.NAME_AUDIOPHILE -> albumGain = field.gain
                        }
                    }
                    if (entry.peak > 0f) trackPeak = entry.peak
                }
            }
        }
        return ReplayGainTags(trackGain, albumGain, trackPeak, albumPeak)
    }
}
