package app.ibridge.call

import android.os.Process
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The sound of one relayed call between the helper (PCM) and the link (`AUDIO`, PROTOCOL.md 8), on two threads:
 * call → Opus → [send] (stream || seq || opus), and the iPhone's Opus → PCM → helper. A missing uplink frame is
 * silence at the helper; seq gaps are the iPhone's to conceal.
 */
class Relay(private val stream: Int, private val send: (plain: ByteArray, queued: () -> Unit) -> Unit) {
    /** A frame and when it reached this relay (nanoTime). */
    private class Timed(val at: Long, val data: ByteArray)
    private val down = LinkedBlockingDeque<Timed>()
    private val up = LinkedBlockingDeque<Timed>()
    @Volatile private var running = true
    private var seq = 0

    val sent = AtomicInteger()
    val received = AtomicInteger()
    val late = AtomicInteger()
    /** PCM from the helper until its `AUDIO` is queued for the link; `AUDIO` from the link until its PCM went to the helper. */
    val encodeLag = Lag()
    val decodeLag = Lag()

    private val encoder = Thread({ encodeLoop() }, "ibridge-opus-enc")
    private val decoder = Thread({ decodeLoop() }, "ibridge-opus-dec")

    fun start() { encoder.start(); decoder.start() }

    fun stop() {
        running = false
        encoder.interrupt(); decoder.interrupt()
    }

    /** 20 ms of the far side, from the helper. */
    fun onDownlink(pcm: ByteArray) {
        down.offer(Timed(System.nanoTime(), pcm))
        while (down.size > QUEUE) { down.poll(); late.incrementAndGet() }
    }

    /** One `AUDIO` frame of this call's stream from the iPhone: the Opus part. */
    fun onUplink(opus: ByteArray) {
        received.incrementAndGet()
        up.offer(Timed(System.nanoTime(), opus))
        while (up.size > QUEUE) { up.poll(); late.incrementAndGet() }
    }

    private fun encodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val enc = try { OpusEncoder() } catch (e: Exception) { Log.e(TAG, "relay: no Opus encoder", e); return }
        try {
            while (running) {
                val pcm = down.poll(200, TimeUnit.MILLISECONDS) ?: continue
                for (opus in enc.encode(pcm.data)) {
                    val plain = ByteArray(3 + opus.size)
                    plain[0] = stream.toByte()
                    plain[1] = (seq shr 8).toByte(); plain[2] = seq.toByte()
                    System.arraycopy(opus, 0, plain, 3, opus.size)
                    seq = (seq + 1) and 0xFFFF
                    send(plain) { encodeLag.add(pcm.at) }
                    sent.incrementAndGet()
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) { Log.e(TAG, "relay: encoder", e) }
        enc.release()
    }

    private fun decodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val dec = try { OpusDecoder() } catch (e: Exception) { Log.e(TAG, "relay: no Opus decoder", e); return }
        try {
            while (running) {
                val opus = up.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val pcm = dec.decode(opus.data)
                pcm.forEach { CallAudio.uplink(it) }
                if (pcm.isNotEmpty()) decodeLag.add(opus.at)
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
