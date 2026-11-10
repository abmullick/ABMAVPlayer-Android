package com.abm.avplayer.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Application-owned 10-band peaking equalizer.
 *
 * Processing is applied to decoded PCM16 or PCM-float audio. Encoded passthrough is deliberately
 * not modified; Media3 decodes supported formats to PCM before this processor receives them.
 */
object TenBandEqSettings {
    const val BAND_COUNT = 10
    val frequenciesHz = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var currentGains = FloatArray(BAND_COUNT)

    @Volatile var preampDb: Float = 0f
    @Volatile var autoHeadroom: Boolean = true
    @Volatile var compressorEnabled: Boolean = false
    @Volatile var compressorThresholdDb: Float = -18f
    @Volatile var compressorRatio: Float = 3f
    @Volatile var compressorAttackMs: Float = 10f
    @Volatile var compressorReleaseMs: Float = 120f
    @Volatile var compressorMakeupDb: Float = 0f
    @Volatile var replayGainEnabled: Boolean = false
    @Volatile var replayGainMode: String = "TRACK"
    @Volatile var replayGainTrackDb: Float? = null
    @Volatile var replayGainAlbumDb: Float? = null
    @Volatile var replayGainTrackPeak: Float? = null
    @Volatile var replayGainAlbumPeak: Float? = null

    fun gains(): FloatArray = currentGains.copyOf()

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setGains(values: FloatArray) {
        require(values.size == BAND_COUNT)
        currentGains = FloatArray(BAND_COUNT) { values[it].coerceIn(-12f, 12f) }
    }

    fun setBand(index: Int, valueDb: Float) {
        require(index in 0 until BAND_COUNT)
        val updated = gains()
        updated[index] = valueDb.coerceIn(-12f, 12f)
        setGains(updated)
    }

    fun load(context: Context) {
        val preferences = context.getSharedPreferences("abm_equalizer", Context.MODE_PRIVATE)
        enabled = preferences.getBoolean("enabled", true)
        val stored = preferences.getString("gains", null)
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
        if (stored != null && stored.size == BAND_COUNT) setGains(stored.toFloatArray())
        preampDb = preferences.getFloat("preamp_db", 0f).coerceIn(-12f, 12f)
        autoHeadroom = preferences.getBoolean("auto_headroom", true)
        compressorEnabled = preferences.getBoolean("compressor_enabled", false)
        compressorThresholdDb = preferences.getFloat("compressor_threshold_db", -18f).coerceIn(-36f, 0f)
        compressorRatio = preferences.getFloat("compressor_ratio", 3f).coerceIn(1f, 10f)
        compressorAttackMs = preferences.getFloat("compressor_attack_ms", 10f).coerceIn(1f, 100f)
        compressorReleaseMs = preferences.getFloat("compressor_release_ms", 120f).coerceIn(10f, 1000f)
        compressorMakeupDb = preferences.getFloat("compressor_makeup_db", 0f).coerceIn(0f, 12f)
        replayGainEnabled = preferences.getBoolean("replaygain_enabled", false)
        replayGainMode = preferences.getString("replaygain_mode", "TRACK") ?: "TRACK"
    }

    fun save(context: Context) {
        context.getSharedPreferences("abm_equalizer", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", enabled)
            .putString("gains", gains().joinToString(","))
            .putFloat("preamp_db", preampDb)
            .putBoolean("auto_headroom", autoHeadroom)
            .putBoolean("compressor_enabled", compressorEnabled)
            .putFloat("compressor_threshold_db", compressorThresholdDb)
            .putFloat("compressor_ratio", compressorRatio)
            .putFloat("compressor_attack_ms", compressorAttackMs)
            .putFloat("compressor_release_ms", compressorReleaseMs)
            .putFloat("compressor_makeup_db", compressorMakeupDb)
            .putBoolean("replaygain_enabled", replayGainEnabled)
            .putString("replaygain_mode", replayGainMode)
            .apply()
    }

    fun updateReplayGain(tags: ReplayGainTags) {
        replayGainTrackDb = tags.trackGainDb
        replayGainAlbumDb = tags.albumGainDb
        replayGainTrackPeak = tags.trackPeak
        replayGainAlbumPeak = tags.albumPeak
    }

    fun resetReplayGainForTrack() {
        replayGainTrackDb = null
        replayGainAlbumDb = null
        replayGainTrackPeak = null
        replayGainAlbumPeak = null
    }

    fun selectedReplayGainDb(): Float {
        if (!replayGainEnabled) return 0f
        val gain = when (replayGainMode) {
            "ALBUM" -> replayGainAlbumDb ?: replayGainTrackDb
            else -> replayGainTrackDb ?: replayGainAlbumDb
        } ?: return 0f
        val peak = when (replayGainMode) {
            "ALBUM" -> replayGainAlbumPeak ?: replayGainTrackPeak
            else -> replayGainTrackPeak ?: replayGainAlbumPeak
        }
        val peakSafeGain = peak?.takeIf { it > 0f }?.let {
            (-20.0 * kotlin.math.log10(it.toDouble())).toFloat()
        }
        return (peakSafeGain?.let { minOf(gain, it) } ?: gain).coerceIn(-24f, 18f)
    }

    fun applyPreset(name: String): FloatArray {
        val preset = when (name) {
            "Bass Boost" -> floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 1f, 1f)
            "Treble Boost" -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)
            "Rock" -> floatArrayOf(5f, 3f, -1f, -3f, -1f, 2f, 4f, 5f, 5f, 5f)
            "Classical" -> floatArrayOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)
            "Jazz" -> floatArrayOf(4f, 3f, 1f, 2f, -2f, -2f, 0f, 2f, 3f, 4f)
            "Vocal" -> floatArrayOf(-2f, -1f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)
            else -> FloatArray(BAND_COUNT)
        }
        setGains(preset)
        return preset.copyOf()
    }
}

private data class BiquadCoefficients(
    val b0: Double, val b1: Double, val b2: Double,
    val a1: Double, val a2: Double
)

private class BiquadState {
    var x1 = 0.0
    var x2 = 0.0
    var y1 = 0.0
    var y2 = 0.0

    fun process(input: Double, c: BiquadCoefficients): Double {
        val output = c.b0 * input + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }

    fun clear() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
    }
}

@androidx.media3.common.util.UnstableApi
class TenBandEqualizerAudioProcessor : BaseAudioProcessor() {
    private var sampleRateHz = 0
    private var channelCount = 0
    private var coefficients = emptyArray<BiquadCoefficients>()
    private var states = emptyArray<Array<BiquadState>>()
    private var lastGains = FloatArray(TenBandEqSettings.BAND_COUNT) { Float.NaN }
    private var compressorEnvelope = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if ((inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) ||
            inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        states = Array(TenBandEqSettings.BAND_COUNT) {
            Array(channelCount) { BiquadState() }
        }
        compressorEnvelope = DoubleArray(channelCount)
        updateCoefficients(TenBandEqSettings.gains())
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val bytes = input.remaining()
        val output = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder())
        val enabled = TenBandEqSettings.enabled
        val gains = TenBandEqSettings.gains()
        if (enabled && !gains.contentEquals(lastGains)) updateCoefficients(gains)
        val maxBoost = if (enabled) gains.maxOrNull()?.coerceAtLeast(0f) ?: 0f else 0f
        val preampDb = TenBandEqSettings.preampDb - if (TenBandEqSettings.autoHeadroom) maxBoost else 0f
        val replayGainDb = TenBandEqSettings.selectedReplayGainDb()
        val preampLinear = 10.0.pow((preampDb + replayGainDb).toDouble() / 20.0)
        val makeupLinear = 10.0.pow(TenBandEqSettings.compressorMakeupDb.toDouble() / 20.0)
        val attack = kotlin.math.exp(-1.0 / (sampleRateHz * TenBandEqSettings.compressorAttackMs / 1000.0).coerceAtLeast(1.0))
        val release = kotlin.math.exp(-1.0 / (sampleRateHz * TenBandEqSettings.compressorReleaseMs / 1000.0).coerceAtLeast(1.0))
        val threshold = TenBandEqSettings.compressorThresholdDb.toDouble()
        val ratio = TenBandEqSettings.compressorRatio.toDouble().coerceAtLeast(1.0)

        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (input.remaining() >= 4 * channelCount) {
                for (channel in 0 until channelCount) {
                    val raw = input.float
                    var sample = raw.toDouble()
                    if (enabled) {
                        for (band in coefficients.indices) sample = states[band][channel].process(sample, coefficients[band])
                    }
                    sample = processDynamics(sample, channel, preampLinear, makeupLinear, attack, release, threshold, ratio)
                    output.putFloat(sample.toFloat().coerceIn(-1f, 1f))
                }
            }
        } else {
            while (input.remaining() >= 2 * channelCount) {
                for (channel in 0 until channelCount) {
                    val raw = input.short.toInt()
                    var sample = raw / 32768.0
                    if (enabled) {
                        for (band in coefficients.indices) sample = states[band][channel].process(sample, coefficients[band])
                    }
                    sample = processDynamics(sample, channel, preampLinear, makeupLinear, attack, release, threshold, ratio)
                    val scaled = (sample * 32768.0).toInt().coerceIn(-32768, 32767)
                    output.putShort(scaled.toShort())
                }
            }
        }
        inputBuffer.position(input.position())
        output.flip()
    }

    private fun processDynamics(
        input: Double, channel: Int, preamp: Double, makeup: Double,
        attack: Double, release: Double, thresholdDb: Double, ratio: Double
    ): Double {
        var sample = input * preamp
        if (TenBandEqSettings.compressorEnabled) {
            val level = kotlin.math.abs(sample)
            val coefficient = if (level > compressorEnvelope[channel]) attack else release
            compressorEnvelope[channel] = coefficient * compressorEnvelope[channel] + (1.0 - coefficient) * level
            val envelopeDb = 20.0 * kotlin.math.log10(compressorEnvelope[channel].coerceAtLeast(1e-9))
            val compressedDb = if (envelopeDb > thresholdDb) thresholdDb + (envelopeDb - thresholdDb) / ratio else envelopeDb
            val gainDb = (compressedDb - envelopeDb).coerceAtMost(0.0)
            sample *= 10.0.pow(gainDb / 20.0) * makeup
        } else {
            compressorEnvelope[channel] = 0.0
        }
        return sample
    }

    private fun updateCoefficients(gains: FloatArray) {
        coefficients = Array(TenBandEqSettings.BAND_COUNT) { band ->
            peakingFilter(
                sampleRateHz.toDouble(),
                TenBandEqSettings.frequenciesHz[band].toDouble(),
                1.4,
                gains[band].toDouble()
            )
        }
        lastGains = gains.copyOf()
    }

    private fun peakingFilter(
        sampleRate: Double,
        frequency: Double,
        q: Double,
        gainDb: Double
    ): BiquadCoefficients {
        if (frequency >= sampleRate * 0.45 || gainDb == 0.0) {
            return BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)
        }
        val nyquistSafeFrequency = frequency.coerceAtLeast(10.0)
        val a = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * nyquistSafeFrequency / sampleRate
        val alpha = sin(omega) / (2.0 * q)
        val cosOmega = cos(omega)
        val a0 = 1.0 + alpha / a
        return BiquadCoefficients(
            b0 = (1.0 + alpha * a) / a0,
            b1 = (-2.0 * cosOmega) / a0,
            b2 = (1.0 - alpha * a) / a0,
            a1 = (-2.0 * cosOmega) / a0,
            a2 = (1.0 - alpha / a) / a0
        )
    }

    override fun onFlush() {
        states.forEach { channelStates -> channelStates.forEach { it.clear() } }
        compressorEnvelope.fill(0.0)
    }

    override fun onReset() {
        sampleRateHz = 0
        channelCount = 0
        coefficients = emptyArray()
        states = emptyArray()
        compressorEnvelope = DoubleArray(0)
        lastGains = FloatArray(TenBandEqSettings.BAND_COUNT) { Float.NaN }
    }
}
