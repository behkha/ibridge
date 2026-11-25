package app.ibridge.call

import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The sound of one relayed call between the helper (PCM) and the link (`AUDIO`, PROTOCOL.md 8), on two threads:
 * call → Opus → [send] (stream || seq || opus), and the iPhone's Opus → PCM → helper. A missing uplink frame is
 * silence at the helper; seq gaps are the iPhone's to conceal.
 */
class Relay(private val stream: Int, private val send: (ByteArray) -> Unit) {
    private val down = LinkedBlockingDeque<ByteArray>()
    private val up = LinkedBlockingDeque<ByteArray>()
    @Volatile private var running = true
    private var seq = 0

    val sent = AtomicInteger()
    val received = AtomicInteger()
    val late = AtomicInteger()

    private val encoder = Thread({ encodeLoop() }, "ibridge-opus-enc")
    private val decoder = Thread({ decodeLoop() }, "ibridge-opus-dec")

    fun start() { encoder.start(); decoder.start() }

    fun stop() {
        running = false
        encoder.interrupt(); decoder.interrupt()
    }

    /** 20 ms of the far side, from the helper. */
    fun onDownlink(pcm: ByteArray) {
        down.offer(pcm)
        while (down.size > QUEUE) { down.poll(); late.incrementAndGet() }
    }

    /** One `AUDIO` frame of this call's stream from the iPhone: the Opus part. */
    fun onUplink(opus: ByteArray) {
        received.incrementAndGet()
        up.offer(opus)
        while (up.size > QUEUE) { up.poll(); late.incrementAndGet() }
    }

    private fun encodeLoop() {
        val enc = try { OpusEncoder() } catch (e: Exception) { Log.e(TAG, "relay: no Opus encoder", e); return }
        try {
            while (running) {
                val pcm = down.poll(200, TimeUnit.MILLISECONDS) ?: continue
                for (opus in enc.encode(pcm)) {
                    val plain = ByteArray(3 + opus.size)
                    plain[0] = stream.toByte()
                    plain[1] = (seq shr 8).toByte(); plain[2] = seq.toByte()
                    System.arraycopy(opus, 0, plain, 3, opus.size)
                    seq = (seq + 1) and 0xFFFF
                    send(plain)
                    sent.incrementAndGet()
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) { Log.e(TAG, "relay: encoder", e) }
        enc.release()
    }

    private fun decodeLoop() {
        val dec = try { OpusDecoder() } catch (e: Exception) { Log.e(TAG, "relay: no Opus decoder", e); return }
        try {
            while (running) {
                val opus = up.poll(200, TimeUnit.MILLISECONDS) ?: continue
                dec.decode(opus).forEach { CallAudio.uplink(it) }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) { Log.e(TAG, "relay: decoder", e) }
        dec.release()
    }

    companion object {
        const val TAG = "iBridge"
        /** Frames waiting for a codec before the oldest is dropped. */
        const val QUEUE = 3
    }
}
