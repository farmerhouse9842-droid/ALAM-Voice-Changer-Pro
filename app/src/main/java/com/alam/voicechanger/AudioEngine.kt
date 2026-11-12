package com.alam.voicechanger

import android.media.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object AudioEngine {
    data class Audio(val samples: ShortArray, val rate: Int)

    fun decode(file: File): Audio {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i; format = f; break
                }
            }
            require(track >= 0) { "No audio track" }
            extractor.selectTrack(track)
            val f = format!!
            val codec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(f, null, null, 0)
            codec.start()
            var rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val bytes = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var attempts = 0
            try {
                while (!outputDone && attempts++ < 200000) {
                    if (!inputDone) {
                        val id = codec.dequeueInputBuffer(10000)
                        if (id >= 0) {
                            val b = codec.getInputBuffer(id)!!
                            b.clear()
                            val n = extractor.readSampleData(b, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(id, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(id, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val id = codec.dequeueOutputBuffer(info, 10000)
                    if (id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val out = codec.outputFormat
                        rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else if (id >= 0) {
                        val b = codec.getOutputBuffer(id)!!
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        b.get(chunk)
                        bytes.write(chunk)
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(id, false)
                    }
                }
            } finally { codec.stop(); codec.release() }
            require(outputDone && channels > 0) { "Audio decoding incomplete" }
            require(encoding == AudioFormat.ENCODING_PCM_16BIT) { "Only PCM16 decoding supported on this device" }
            val data = bytes.toByteArray()
            val count = data.size / (2 * channels)
            require(count > 0) { "Empty audio" }
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val mono = ShortArray(count)
            for (i in 0 until count) {
                var sum = 0
                repeat(channels) { sum += buffer.short.toInt() }
                mono[i] = (sum / channels).toShort()
            }
            return Audio(mono, rate)
        } finally { extractor.release() }
    }

    private fun interpolate(src: FloatArray, position: Double): Float {
        val at = floor(position).toInt().coerceIn(0, src.lastIndex)
        val next = (at + 1).coerceAtMost(src.lastIndex)
        return (src[at] * (1.0 - (position - at)) + src[next] * (position - at)).toFloat()
    }

    private fun resample(src: FloatArray, ratio: Double): FloatArray {
        val n = (src.size / ratio).roundToInt().coerceAtLeast(1)
        return FloatArray(n) { i -> interpolate(src, (i * ratio).coerceAtMost(src.lastIndex.toDouble())) }
    }

    // Windowed overlap-add: simple duration correction for a pitch-resampled signal.
    // Not a studio-grade phase vocoder; artifacts can occur with music or extreme settings.
    private fun stretch(src: FloatArray, targetLength: Int, rate: Int): FloatArray {
        if (src.size < 512 || targetLength < 512) return resample(src, src.size.toDouble() / targetLength)
        val frame = min(2048, max(512, (rate / 25 / 2) * 2))
        val hop = frame / 4
        val ratio = src.size.toDouble() / targetLength
        val output = FloatArray(targetLength)
        val weights = FloatArray(targetLength)
        val window = FloatArray(frame) { i -> (0.5 - 0.5 * cos(2.0 * Math.PI * i / (frame - 1))).toFloat() }
        var outStart = 0
        while (outStart < targetLength) {
            val inStart = (outStart * ratio).roundToInt().coerceIn(0, max(0, src.size - frame))
            val n = min(frame, targetLength - outStart)
            for (j in 0 until n) {
                val k = inStart + j
                if (k >= src.size) break
                val w = window[j]
                output[outStart + j] += src[k] * w
                weights[outStart + j] += w
            }
            outStart += hop
        }
        for (i in output.indices) {
            if (weights[i] > 0.00001f) output[i] /= weights[i]
        }
        return output
    }

    // Resonant spectral colour, not independent formant-frequency shifting.
    private fun toneColour(src: FloatArray, rate: Int, amount: Float): FloatArray {
        if (abs(amount - 1f) < 0.015f) return src
        val cutoff = (if (amount > 1f) 2400f else 950f).coerceAtMost(rate * 0.42f)
        val alpha = (1.0 - exp(-2.0 * Math.PI * cutoff / rate)).toFloat()
        val mix = (abs(amount - 1f) * 1.5f).coerceIn(0f, 0.75f)
        val out = FloatArray(src.size)
        var low = 0f
        for (i in src.indices) {
            low += alpha * (src[i] - low)
            out[i] = if (amount > 1f) src[i] + (src[i] - low) * mix else src[i] * (1f - mix) + low * mix
        }
        return out
    }

    fun transform(audio: Audio, effect: String, startSeconds: Float, endSeconds: Float,
                  pitch: Float = 1f, speed: Float = 1f, formant: Float = 1f): Audio {
        require(audio.rate > 0 && audio.samples.isNotEmpty()) { "Invalid audio" }
        val from = (startSeconds * audio.rate).toInt().coerceIn(0, audio.samples.size)
        val to = (endSeconds * audio.rate).toInt().coerceIn(from, audio.samples.size)
        require(to > from) { "Trim range is empty" }
        val src = FloatArray(to - from) { audio.samples[from + it] / 32768f }
        val preset = when (effect) {
            "Female Voice" -> 1.18f; "Funny Girl" -> 1.65f; "Funny Cartoon" -> 1.5f
            "Deep" -> 0.72f; "Baby" -> 1.55f; "Chipmunk" -> 1.85f
            "Monster" -> 0.57f; "Old Man" -> 0.82f; "Helium" -> 1.7f
            else -> 1f
        }
        val finalPitch = (pitch * preset).coerceIn(0.45f, 2.5f)
        val finalSpeed = (speed * when (effect) { "Fast" -> 1.3f; "Slow" -> 0.75f; else -> 1f }).coerceIn(0.5f, 2f)
        val desired = (src.size / finalSpeed).roundToInt().coerceIn(1, audio.rate * 180)
        var result = if (abs(finalPitch - 1f) > 0.01f) {
            val pitched = resample(src, finalPitch.toDouble())
            stretch(pitched, desired, audio.rate)
        } else if (abs(finalSpeed - 1f) > 0.01f) stretch(src, desired, audio.rate) else src.copyOf()
        result = toneColour(result, audio.rate, formant)
        val delay = (audio.rate * 0.22).toInt()
        var bass = 0f
        for (i in result.indices) {
            var v = result[i]
            when (effect) {
                "Robot" -> v *= if (sin(2.0 * Math.PI * 35.0 * i / audio.rate) > 0) 1f else -0.35f
                "Alien" -> v *= (0.7 + 0.3 * sin(2.0 * Math.PI * 9.0 * i / audio.rate)).toFloat()
                "Echo" -> if (i >= delay) v += result[i - delay] * 0.38f
                "Noise Gate" -> if (abs(v) < 0.035f) v = 0f
                "Bass Boost" -> { bass += 0.06f * (v - bass); v = v * 0.7f + bass * 0.45f }
            }
            result[i] = v.coerceIn(-1f, 1f)
        }
        if (effect == "Normalize") {
            val peak = result.maxOf { abs(it) }.coerceAtLeast(0.00001f)
            val gain = 0.88f / peak
            for (i in result.indices) result[i] = (result[i] * gain).coerceIn(-1f, 1f)
        }
        return Audio(ShortArray(result.size) { (result[it] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort() }, audio.rate)
    }

    fun saveWav(audio: Audio, file: File) {
        val size = audio.samples.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
            fun le16(n: Int) { out.writeByte(n and 255); out.writeByte((n shr 8) and 255) }
            fun le32(n: Int) { le16(n and 65535); le16(n ushr 16) }
            str("RIFF"); le32(36 + size); str("WAVEfmt "); le32(16); le16(1); le16(1)
            le32(audio.rate); le32(audio.rate * 2); le16(2); le16(16); str("data"); le32(size)
            for (sample in audio.samples) le16(sample.toInt())
        }
    }
}
