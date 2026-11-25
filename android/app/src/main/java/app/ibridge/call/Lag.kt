package app.ibridge.call

/** How long one stage of the relay held its frames, for the once-a-second log: "avg/max" in ms, "-" when none passed. */
class Lag {
    private var n = 0
    private var sum = 0L
    private var max = 0L

    @Synchronized fun add(sinceNanos: Long) {
        val d = System.nanoTime() - sinceNanos
        n++; sum += d; if (d > max) max = d
    }

    @Synchronized fun take(): String {
        val s = if (n == 0) "-" else "${sum / n / 1_000_000}/${max / 1_000_000}"
        n = 0; sum = 0; max = 0
        return s
    }
}
