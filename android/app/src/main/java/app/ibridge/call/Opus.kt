package app.ibridge.call

import android.media.MediaCodec
import android.media.MediaFormat
import app.ibridge.call.helper.Wire
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus for call audio through Android's own codec (PROTOCOL.md 8): 16 kHz mono, 20 ms packets, ~24 kbit/s VBR,
 * no FEC, no DTX. Synchronous MediaCodec on the caller's thread; one instance per direction per call.
 */
class OpusEncoder {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, Wire.RATE, 1)
        f.setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Wire.FRAME_BYTES)
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** One 20 ms PCM frame in; the Opus packets that came out (usually one, sometimes a frame late). */
    fun encode(pcm: ByteArray): List<ByteArray> {
        val i = codec.dequeueInputBuffer(20_000)
        if (i >= 0) {
            codec.getInputBuffer(i)!!.apply { clear(); put(pcm) }
            codec.queueInputBuffer(i, 0, pcm.size, pts, 0)
            pts += 20_000
        }
        return drain()
    }

    private fun drain(): List<ByteArray> {
        val out = ArrayList<ByteArray>(1)
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 2_000)
            if (o < 0) { if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break else continue }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val b = codec.getOutputBuffer(o)!!
                b.position(info.offset); b.limit(info.offset + info.size)
                out += ByteArray(info.size).also { b.get(it) }
            }
            codec.releaseOutputBuffer(o, false)
        }
        return out
    }

    fun release() { runCatching { codec.stop() }; codec.release() }
}

/** Opus packets in, 16 kHz PCM frames out (Android's decoder works at 48 kHz; this takes every third sample's mean). */
class OpusDecoder {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L
    private var rate = 48_000
    private val pending = ShortArray(Wire.FRAME * 4)
    private var pendingCount = 0

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1)
        f.setByteBuffer("csd-0", opusHead())
        f.setByteBuffer("csd-1", le64(0)) // pre-skip: none (a few ms of start-up sound are kept)
        f.setByteBuffer("csd-2", le64(80_000_000)) // seek pre-roll, 80 ms
        codec.configure(f, null, null, 0)
        codec.start()
    }

    private fun opusHead(): ByteBuffer = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("OpusHead".toByteArray()); put(1); put(1); putShort(0); putInt(Wire.RATE); putShort(0); put(0)
        flip()
    }

    private fun le64(v: Long): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).apply { flip() }

    /** One packet in; the 20 ms 16 kHz s16le frames that came out. */
    fun decode(opus: ByteArray): List<ByteArray> {
        val i = codec.dequeueInputBuffer(20_000)
        if (i >= 0) {
            codec.getInputBuffer(i)!!.apply { clear(); put(opus) }
            codec.queueInputBuffer(i, 0, opus.size, pts, 0)
            pts += 20_000
        }
        val frames = ArrayList<ByteArray>(1)
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 2_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); continue }
            if (o < 0) { if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break else continue }
            if (info.size > 0) {
                val s = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                s.position(info.offset); s.limit(info.offset + info.size)
                take(s.asShortBuffer(), frames)
            }
            codec.releaseOutputBuffer(o, false)
        }
        return frames
    }

    private fun take(src: java.nio.ShortBuffer, frames: MutableList<ByteArray>) {
        val step = (rate / Wire.RATE).coerceAtLeast(1)
        while (src.remaining() >= step) {
            var sum = 0
            repeat(step) { sum += src.get() }
            pending[pendingCount++] = (sum / step).toShort()
            if (pendingCount == Wire.FRAME) {
                val b = ByteBuffer.allocate(Wire.FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                b.asShortBuffer().put(pending, 0, Wire.FRAME)
                frames += b.array()
                pendingCount = 0
            }
        }
    }

    fun release() { runCatching { codec.stop() }; codec.release() }
}

/**
 * One second of a 440 Hz tone through the encoder and back through the decoder: packets per frame, their size, and how
 * close the sound that came back is (correlation after the codec's delay). For checking a phone's codecs over adb.
 */
fun opusRoundTrip(): String {
    val enc = OpusEncoder()
    val dec = OpusDecoder()
    val input = ShortArray(Wire.RATE) { (8000 * kotlin.math.sin(2 * Math.PI * 440 * it / Wire.RATE)).toInt().toShort() }
    val output = ArrayList<Short>()
    val sizes = ArrayList<Int>()
    val t0 = System.nanoTime()
    for (f in 0 until 50) {
        val b = ByteBuffer.allocate(Wire.FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.asShortBuffer().put(input, f * Wire.FRAME, Wire.FRAME)
        enc.encode(b.array()).forEach { p ->
            sizes += p.size
            dec.decode(p).forEach { pcm -> val s = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(); while (s.hasRemaining()) output += s.get() }
        }
    }
    val ms = (System.nanoTime() - t0) / 1_000_000
    enc.release(); dec.release()
    // Best normalised correlation over delays up to 40 ms: a tone that survived the round trip scores near 1.
    var best = 0.0
    for (lag in 0..640) {
        var xy = 0.0; var xx = 0.0; var yy = 0.0
        for (i in 1600 until minOf(output.size - lag, input.size)) { val x = input[i].toDouble(); val y = output[i + lag].toDouble(); xy += x * y; xx += x * x; yy += y * y }
        if (xx > 0 && yy > 0) best = maxOf(best, xy / Math.sqrt(xx * yy))
    }
    return "frames in 50, packets out ${sizes.size}, bytes avg ${if (sizes.isEmpty()) 0 else sizes.sum() / sizes.size} " +
        "(min ${sizes.minOrNull()}, max ${sizes.maxOrNull()}), samples back ${output.size} of ${input.size}, correlation ${"%.3f".format(best)}, $ms ms"
}
