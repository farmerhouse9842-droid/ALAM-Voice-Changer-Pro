package com.alam.voicechanger

import android.media.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

object AudioEngine {

    data class Audio(
        val samples: ShortArray,
        val rate: Int
    )

    fun decode(file: File): Audio {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        try {
            var track = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""

                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }

            require(track >= 0) { "No audio track" }
            extractor.selectTrack(track)

            val fmt = format!!
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(fmt, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            val output = ByteArrayOutputStream()

            var inputDone = false
            var outputDone = false
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var loops = 0

            try {
                while (!outputDone && loops++ < 200000) {

                    if (!inputDone) {
                        val id = decoder.dequeueInputBuffer(10000)

                        if (id >= 0) {
                            val buffer = decoder.getInputBuffer(id)!!
                            buffer.clear()

                            val size = extractor.readSampleData(buffer, 0)

                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    id, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    id, 0, size,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val id = decoder.dequeueOutputBuffer(info, 10000)

                    if (id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat

                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            require(
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                    AudioFormat.ENCODING_PCM_16BIT
                            ) { "Unsupported PCM format" }
                        }

                    } else if (id >= 0) {
                        val buffer = decoder.getOutputBuffer(id)!!

                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)

                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        output.write(bytes)

                        outputDone =
                            (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                        decoder.releaseOutputBuffer(id, false)
                    }
                }
            } finally {
                decoder.stop()
                decoder.release()
            }

            require(outputDone) { "Decode incomplete" }
            require(channels > 0) { "Invalid channels" }

            val bytes = output.toByteArray()
            val count = bytes.size / 2 / channels

            require(count > 0) { "Empty audio" }

            val buffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)

            val mono = ShortArray(count)

            for (i in 0 until count) {
                var sum = 0

                for (c in 0 until channels) {
                    sum += buffer.short.toInt()
                }

                mono[i] = (sum / channels).toShort()
            }

            return Audio(mono, rate)

        } finally {
            extractor.release()
        }
    }

    fun transform(
        audio: Audio,
        effect: String,
        startSeconds: Float,
        endSeconds: Float
    ): Audio {

        require(audio.rate > 0) { "Invalid sample rate" }

        val from = (startSeconds * audio.rate)
            .toInt()
            .coerceIn(0, audio.samples.size)

        val to = (endSeconds * audio.rate)
            .toInt()
            .coerceIn(from, audio.samples.size)

        require(to > from) { "Trim range is empty" }

        val source = audio.samples.copyOfRange(from, to)

        val factor = when (effect) {
            "Female Voice", "Female" -> 1.28
            "Funny Girl" -> 1.65
            "Deep" -> 0.72
            "Baby" -> 1.55
            "Chipmunk" -> 1.85
            "Monster" -> 0.57
            "Old Man" -> 0.82
            "Helium" -> 1.70
            "Fast" -> 1.30
            "Slow" -> 0.75
            else -> 1.0
        }

        val size = (source.size / factor)
            .toInt()
            .coerceIn(1, audio.rate * 180)

        val result = ShortArray(size)
        val delay = (audio.rate * 0.22).toInt()

        for (i in result.indices) {

            val pos = i * factor

            val at = pos.toInt()
                .coerceIn(0, source.lastIndex)

            val next = (at + 1)
                .coerceAtMost(source.lastIndex)

            val fraction = pos - at

            var value =
                source[at] * (1.0 - fraction) +
                source[next] * fraction

            when (effect) {

                "Robot" -> {
                    val modulation = sin(
                        2.0 * Math.PI * 35.0 * i / audio.rate
                    )
                    value *= if (modulation > 0) 1.0 else -0.35
                }

                "Alien" -> {
                    value *= 0.7 + 0.3 * sin(
                        2.0 * Math.PI * 9.0 * i / audio.rate
                    )
                }

                "Echo" -> {
                    if (i >= delay) {
                        value += result[i - delay] * 0.38
                    }
                }

                "Noise Gate" -> {
                    if (abs(value) < 1100.0) {
                        value = 0.0
                    }
                }

                "Bass Boost" -> {
                    if (i > 0) {
                        value = value * 0.65 +
                            result[i - 1] * 0.5
                    }
                }
            }

            result[i] = value.roundToInt()
                .coerceIn(-32768, 32767)
                .toShort()
        }

        if (effect == "Normalize") {
            val peak = result.maxOf {
                abs(it.toInt())
            }.coerceAtLeast(1)

            val gain = 29000.0 / peak

            for (i in result.indices) {
                result[i] = (result[i] * gain)
                    .roundToInt()
                    .coerceIn(-32768, 32767)
                    .toShort()
            }
        }

        return Audio(result, audio.rate)
    }

    fun saveWav(audio: Audio, file: File) {
        val dataSize = audio.samples.size * 2

        DataOutputStream(
            BufferedOutputStream(FileOutputStream(file))
        ).use { out ->

            fun str(s: String) {
                out.write(s.toByteArray(Charsets.US_ASCII))
            }

            fun le16(n: Int) {
                out.writeByte(n and 255)
                out.writeByte((n shr 8) and 255)
            }

            fun le32(n: Int) {
                le16(n and 65535)
                le16(n ushr 16)
            }

            str("RIFF")
            le32(36 + dataSize)
            str("WAVEfmt ")
            le32(16)
            le16(1)
            le16(1)
            le32(audio.rate)
            le32(audio.rate * 2)
            le16(2)
            le16(16)
            str("data")
            le32(dataSize)

            for (sample in audio.samples) {
                le16(sample.toInt())
            }
        }
    }
}
