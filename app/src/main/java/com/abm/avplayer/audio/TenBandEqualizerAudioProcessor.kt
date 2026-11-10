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
 * Processing is applied to decoded PCM16 audio only. Encoded passthrough is deliberately
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
        if (stored != null && stored.size == BAND_COUNT) {
            setGains(stored.toFloatArray())
        }
    }

    fun save(context: Context) {
        context.getSharedPreferences("abm_equalizer", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", enabled)
            .putString("gains", gains().joinToString(","))
            .apply()
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

        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (input.remaining() >= 4 * channelCount) {
                for (channel in 0 until channelCount) {
                    val raw = input.float
                    if (!enabled) {
                        output.putFloat(raw)
                    } else {
                        var sample = raw.toDouble()
                        for (band in coefficients.indices) {
                            sample = states[band][channel].process(sample, coefficients[band])
                        }
                        output.putFloat(sample.toFloat().coerceIn(-1f, 1f))
                    }
                }
            }
        } else {
            while (input.remaining() >= 2 * channelCount) {
                for (channel in 0 until channelCount) {
                    val raw = input.short.toInt()
                    if (!enabled) {
                        output.putShort(raw.toShort())
                    } else {
                        var sample = raw / 32768.0
                        for (band in coefficients.indices) {
                            sample = states[band][channel].process(sample, coefficients[band])
                        }
                        val scaled = (sample * 32768.0).toInt().coerceIn(-32768, 32767)
                        output.putShort(scaled.toShort())
                    }
                }
            }
        }
        inputBuffer.position(input.position())
        output.flip()
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
    }

    override fun onReset() {
        sampleRateHz = 0
        channelCount = 0
        coefficients = emptyArray()
        states = emptyArray()
        lastGains = FloatArray(TenBandEqSettings.BAND_COUNT) { Float.NaN }
    }
}
