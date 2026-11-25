package app.ibridge.probe;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Phase-2 feasibility spike, throwaway. Runs as the shell user (uid 2000, which holds
 * CALL_AUDIO_INTERCEPTION) through app_process and checks whether this phone lets the
 * shell hear a cellular call's far side (downlink) and play into it (uplink).
 *
 *   check    no call needed: is the HAL interceptable, what happens outside a call
 *   run W P  wait up to W s for a call, then four P-second phases (see PHASES)
 *   restore  unmute the mic after a killed run (a taken audio mode is tied to this
 *            process's Binder token, so AudioService drops it when the process dies)
 *   stop     kill an armed or running probe
 *   listen W P  like run, but writes no file and asks the open questions: phases E-H (1-4 beeps on
 *            the far phone) test whether the Galaxy's earpiece goes quiet. Starts once the call is
 *            ACTIVE (precise call state), so an outgoing call's ringing is skipped.
 *
 * The AudioRecord/AudioTrack are built the way AudioManager's hidden
 * getCallDownlinkExtractionAudioRecord / getCallUplinkInjectionAudioTrack build them
 * (VOICE_DOWNLINK / USAGE_CALL_ASSISTANT + CALL_REDIRECT_PSTN), without a Context.
 * Far-side audio is written to /data/local/tmp/ibridge-probe on the phone only.
 */
public final class CallAudioProbe {
    static final int RATE = 16_000;
    static final int FRAME = RATE / 50;              // 20 ms of samples
    static final String DIR = "/data/local/tmp/ibridge-probe";
    static final String PKG = "com.android.shell";

    static final int MODE_NORMAL = 0, MODE_IN_CALL = 2, MODE_CALL_REDIRECT = 5;
    static final int CALL_REDIRECT_PSTN = 1;
    static final int SOURCE_VOICE_UPLINK = 2, SOURCE_VOICE_DOWNLINK = 3;
    static final int USAGE_CALL_ASSISTANT = 17;

    /** A: plain IN_CALL, 1 beep. B: this phone's mic muted, 2 beeps. C: MODE_CALL_REDIRECT, 3 beeps.
     *  D: back in IN_CALL, 500 ms tone on/off, recorded from VOICE_UPLINK to prove the injection. */
    static final String PHASES = "ABCD";

    static Object audio;                             // IAudioService
    static final IBinder TOKEN = new Binder();
    static volatile int beeps = 1;                   // beeps per 2 s cycle; 0 = square 500/500
    static volatile boolean stop;                    // ends the uplink writer
    static volatile boolean micWasMuted;
    static volatile long toneStartNs;                // writer: when the last tone burst was written
    static volatile boolean modeTaken, micMuted;

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "check";
        audio = audioService();
        log("sdk=%d %s %s build=%s", Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, Build.DISPLAY);
        log("mode=%d interceptable=%s micMuted=%s", mode(), interceptable(), call("isMicrophoneMuted"));
        switch (cmd) {
            case "check": check(); break;
            case "restore": setMicMute(false); break;
            case "run": run(arg(args, 1, 60), arg(args, 2, 12)); break;
            case "listen": listen(arg(args, 1, 600), arg(args, 2, 15)); break;
            case "stop":
                Runtime.getRuntime().exec(new String[] {"pkill", "-f", "app.ibridge.probe.CallAudioProbe run"}).waitFor();
                log("armed/running probes killed"); break;
            default: log("usage: check | run <waitSec> <phaseSec> | listen <waitSec> <phaseSec> | restore | stop");
        }
        System.exit(0);
    }

    // ---- no call -------------------------------------------------------------------------

    static void check() {
        try {
            AudioRecord r = downlinkRecord();
            log("downlink record outside a call: state=%d", r.getState());
            r.startRecording();
            short[] buf = new short[FRAME];
            int n = r.read(buf, 0, FRAME);
            log("  read %d samples, rms %.1f dBFS, routed %s", n, dbfs(buf, Math.max(n, 0)), dev(r.getRoutedDevice()));
            r.stop(); r.release();
        } catch (Throwable t) { log("downlink record outside a call refused: %s", cause(t)); }
        try {
            AudioTrack t = uplinkTrack();
            log("uplink track outside a call: state=%d", t.getState());
            t.play();
            t.write(new short[FRAME], 0, FRAME);
            log("  routed %s", dev(t.getRoutedDevice()));
            t.stop(); t.release();
        } catch (Throwable t) { log("uplink track outside a call refused: %s", cause(t)); }
    }

    // ---- during a call -------------------------------------------------------------------

    static void run(int waitSec, int phaseSec) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(CallAudioProbe::restore));
        // app_process dies on an uncaught exception in any thread without running hooks: restore first.
        Thread.setDefaultUncaughtExceptionHandler((th, e) -> { log("crash in %s: %s", th.getName(), e); restore(); System.exit(1); });
        long deadline = SystemClock.elapsedRealtime() + waitSec * 1000L;
        log("waiting up to %d s for a call (mode %d = IN_CALL)", waitSec, MODE_IN_CALL);
        int last = -2;
        long beat = 0;
        while (mode() != MODE_IN_CALL) {
            int m = mode();
            if (m != last) { log("mode %d", m); last = m; }
            if (SystemClock.elapsedRealtime() - beat > 15_000) { beat = SystemClock.elapsedRealtime(); log("still waiting"); }
            if (SystemClock.elapsedRealtime() > deadline) { log("no call came; stopping"); return; }
            SystemClock.sleep(100);
        }
        long callAt = SystemClock.elapsedRealtime();
        micWasMuted = Boolean.TRUE.equals(call("isMicrophoneMuted"));
        log("call is up (mode %d); interceptable=%s", mode(), interceptable());
        SystemClock.sleep(1000);

        AudioTrack track = timed("uplink track", CallAudioProbe::uplinkTrack);
        AudioRecord record = timed("downlink record", CallAudioProbe::downlinkRecord);
        if (track == null && record == null) return;

        Down down = record != null ? new Down(record) : null;
        Thread up = track != null ? new Thread(() -> writer(track), "uplink") : null;
        if (down != null) down.start();
        if (up != null) up.start();
        log("open + started %d ms after the call came up (incl. a 1 s settle)", SystemClock.elapsedRealtime() - callAt);

        try {
            for (char p : PHASES.toCharArray()) {
                if (mode() == MODE_NORMAL && !modeTaken) { log("call ended"); break; }
                switch (p) {
                    case 'A': beeps = 1; break;
                    case 'B': beeps = 2; setMicMute(true); break;
                    case 'C': beeps = 3; setMicMute(micWasMuted); takeMode(MODE_CALL_REDIRECT); break;
                    case 'D':
                        giveBackMode();
                        if (down != null) { down.finish(); down = null; }
                        beeps = 0;
                        Up upRec = new Up();
                        if (upRec.record != null) upRec.start();
                        phase(p, phaseSec, null, track, upRec);
                        if (upRec.record != null) upRec.finish();
                        continue;
                }
                phase(p, phaseSec, down, track, null);
            }
        } finally {
            stop = true;
            restore();
            if (up != null) up.join(500);
            if (down != null) down.finish();
            if (track != null) { try { track.stop(); } catch (Throwable ignored) { } track.release(); }
        }
        log("done; far-side audio is in %s/downlink.pcm (16 kHz mono s16le, stays on the phone)", DIR);
    }

    static void phase(char p, int sec, Down down, AudioTrack track, Up upRec) {
        log("---- phase %c: mode=%d micMuted=%s beeps=%s", p, mode(), call("isMicrophoneMuted"),
                beeps == 0 ? "square" : String.valueOf(beeps));
        AudioTimestamp ts = new AudioTimestamp();
        for (int s = 0; s < sec; s++) {
            SystemClock.sleep(1000);
            StringBuilder line = new StringBuilder(String.format(Locale.US, "%c t=%2d mode=%d", p, s + 1, mode()));
            if (down != null) line.append(" | ").append(down.second());
            line.append(" micMuted=").append(call("isMicrophoneMuted"));
            if (upRec != null) line.append(" | ").append(upRec.second());
            if (track != null) {
                long lat = -1;
                if (track.getTimestamp(ts)) {
                    long written = Up.written;
                    double aheadMs = (written - ts.framePosition) * 1000.0 / RATE;
                    lat = Math.round(aheadMs - (System.nanoTime() - ts.nanoTime) / 1e6);   // written but not yet played
                    if (lat < 0) lat = 0;
                }
                line.append(String.format(Locale.US, " | up queued=%sms halLat=%s underruns=%d routed=%s",
                        lat < 0 ? "?" : lat, hiddenLatency(track), track.getUnderrunCount(), dev(track.getRoutedDevice())));
            }
            log("%s", line);
        }
    }


    // ---- listen: does the Galaxy's earpiece go quiet? (no file written) ------------------

    static final int STREAM_VOICE_CALL = 0;
    static volatile int volWas = -1;

    static void listen(int waitSec, int phaseSec) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(CallAudioProbe::restore));
        Thread.setDefaultUncaughtExceptionHandler((th, e) -> { log("crash in %s: %s", th.getName(), e); restore(); System.exit(1); });
        long deadline = SystemClock.elapsedRealtime() + waitSec * 1000L;
        log("waiting up to %d s for an ACTIVE call (incoming answered, or outgoing picked up)", waitSec);
        String last = "";
        while (true) {
            String st = preciseState();
            if (!st.equals(last)) { log("call state: %s (mode %d)", st, mode()); last = st; }
            if (st.contains("Foreground call state: 1,")) break;
            if (SystemClock.elapsedRealtime() > deadline) { log("no call came; stopping"); return; }
            SystemClock.sleep(200);
        }
        long callAt = SystemClock.elapsedRealtime();
        micWasMuted = Boolean.TRUE.equals(call("isMicrophoneMuted"));
        log("call ACTIVE (mode %d); voice volume %s, min %s, max %s", mode(),
                callInt("getStreamVolume", STREAM_VOICE_CALL), callInt("getStreamMinVolume", STREAM_VOICE_CALL),
                callInt("getStreamMaxVolume", STREAM_VOICE_CALL));
        AudioTrack track = timed("uplink track", CallAudioProbe::uplinkTrack);
        AudioRecord record = timed("downlink record", CallAudioProbe::downlinkRecord);
        Down down = record != null ? new Down(record, false) : null;
        Thread up = track != null ? new Thread(() -> writer(track), "uplink") : null;
        if (down != null) down.start();
        if (up != null) up.start();
        setMicMute(true);
        log("open + started %d ms after ACTIVE; Galaxy mic muted for the whole test", SystemClock.elapsedRealtime() - callAt);
        try {
            for (char p : "EFGH".toCharArray()) {
                if (mode() == MODE_NORMAL && !modeTaken) { log("call ended"); break; }
                switch (p) {
                    case 'E': beeps = 1; log(">>> E: plain IN_CALL. Galaxy earpiece should carry the far side"); break;
                    case 'F': beeps = 2; takeMode(MODE_CALL_REDIRECT); log(">>> F: MODE_CALL_REDIRECT. Is the earpiece quiet?"); break;
                    case 'G': beeps = 3; giveBackMode(); setVoiceVolume(true); log(">>> G: IN_CALL, voice volume at minimum. Is the earpiece quiet?"); break;
                    case 'H': beeps = 4; takeMode(MODE_CALL_REDIRECT); log(">>> H: REDIRECT + volume minimum"); break;
                }
                phase(p, phaseSec, down, track, null);
            }
        } finally {
            stop = true;
            restore();
            if (up != null) up.join(500);
            if (down != null) down.finish();
            if (track != null) { try { track.stop(); } catch (Throwable ignored) { } track.release(); }
        }
        log("listen done (nothing was written to a file)");
    }

    static String preciseState() {
        try {
            Process pr = Runtime.getRuntime().exec(new String[] {"dumpsys", "telephony.registry"});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream()));
            String line, found = "?";
            while ((line = r.readLine()) != null) {
                int i = line.indexOf("mPreciseCallState=");
                if (i >= 0) { found = line.substring(i + 18, Math.min(line.length(), i + 90)); break; }
            }
            pr.destroy();
            return found;
        } catch (IOException e) { return "error " + e; }
    }

    static Object callInt(String name, int arg) {
        try { return audio.getClass().getMethod(name, int.class).invoke(audio, arg); }
        catch (Throwable t) { return "error " + cause(t); }
    }

    /** IAudioService.setStreamVolume(stream, index, flags, callingPackage). */
    static void setVoiceVolume(boolean toMin) {
        try {
            if (toMin && volWas < 0) volWas = (Integer) callInt("getStreamVolume", STREAM_VOICE_CALL);
            int idx = toMin ? (Integer) callInt("getStreamMinVolume", STREAM_VOICE_CALL) : volWas;
            if (idx < 0) return;
            audio.getClass().getMethod("setStreamVolume", int.class, int.class, int.class, String.class)
                    .invoke(audio, STREAM_VOICE_CALL, idx, 0, PKG);
            log("voice volume set to %d, now %s", idx, callInt("getStreamVolume", STREAM_VOICE_CALL));
            if (!toMin) volWas = -1;
        } catch (Throwable t) { log("setStreamVolume refused: %s", cause(t)); }
    }

    // ---- uplink: beeps into the call -----------------------------------------------------

    static void writer(AudioTrack track) {
        track.play();
        short[] buf = new short[FRAME];
        long n = 0;                                  // samples since start
        boolean wasOn = false;
        while (!stop) {
            boolean anyOn = false;
            for (int i = 0; i < FRAME; i++, n++) {
                boolean on = toneAt(n);
                anyOn |= on;
                buf[i] = on ? (short) (8000 * Math.sin(2 * Math.PI * 1000 * n / RATE)) : 0;
            }
            if (anyOn && !wasOn) toneStartNs = System.nanoTime();
            wasOn = anyOn;
            int w = track.write(buf, 0, FRAME);     // blocking: paces at the call's clock
            if (w < 0) { log("uplink write error %d", w); return; }
            Up.written += w;
        }
    }

    static boolean toneAt(long n) {
        int b = beeps;
        if (b == 0) return (n % RATE) < RATE / 2;   // 500 ms on, 500 ms off
        long inCycle = n % (2L * RATE);              // 2 s cycle
        long slot = inCycle / (RATE / 5);            // 200 ms slots: on, off, on, off...
        return slot % 2 == 0 && slot / 2 < b;
    }

    // ---- downlink: far side to a file, with timing ---------------------------------------

    static final class Down extends Thread {
        final AudioRecord record;
        long frames, reads, maxGapNs, lastReadNs, sumSq, count, firstSoundNs, startNs;
        volatile double lastLatMs = -1;
        volatile boolean done;
        FileOutputStream out;
        final boolean toFile;

        Down(AudioRecord r) { this(r, true); }
        Down(AudioRecord r, boolean toFile) { super("downlink"); record = r; this.toFile = toFile; }

        @Override public void run() {
            try {
                if (toFile) { new java.io.File(DIR).mkdirs(); out = new FileOutputStream(DIR + "/downlink.pcm"); }
                startNs = System.nanoTime();
                record.startRecording();
                short[] buf = new short[FRAME];
                byte[] bytes = new byte[FRAME * 2];
                AudioTimestamp ts = new AudioTimestamp();
                while (!done && !stop) {
                    int n = record.read(buf, 0, FRAME);
                    if (n < 0) { log("downlink read error %d", n); return; }
                    long now = System.nanoTime();
                    synchronized (this) {
                        if (lastReadNs != 0) maxGapNs = Math.max(maxGapNs, now - lastReadNs);
                        lastReadNs = now;
                        reads++;
                        frames += n;
                        for (int i = 0; i < n; i++) { sumSq += (long) buf[i] * buf[i]; }
                        count += n;
                        if (firstSoundNs == 0 && dbfs(buf, n) > -60) firstSoundNs = now;
                    }
                    if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        // capture time of the newest sample we hold, against now
                        double newestAt = ts.nanoTime + (frames - ts.framePosition) * 1e9 / RATE;
                        lastLatMs = (now - newestAt) / 1e6;
                    }
                    if (out == null) continue;
                    for (int i = 0; i < n; i++) { bytes[2 * i] = (byte) buf[i]; bytes[2 * i + 1] = (byte) (buf[i] >> 8); }
                    out.write(bytes, 0, n * 2);
                }
            } catch (IOException e) {
                log("downlink file: %s", e);
            }
        }

        synchronized String second() {
            double rms = count == 0 ? -120 : 20 * Math.log10(Math.sqrt((double) sumSq / count) / 32768 + 1e-9);
            String s = String.format(Locale.US, "down rms=%.0fdB reads=%d maxGap=%dms readLag=%sms rate=%d routed=%s%s",
                    rms, reads, maxGapNs / 1_000_000, lastLatMs < 0 ? "?" : String.format(Locale.US, "%.0f", lastLatMs),
                    record.getSampleRate(), dev(record.getRoutedDevice()),
                    firstSoundNs == 0 ? " (no sound yet)" : "");
            sumSq = 0; count = 0; reads = 0; maxGapNs = 0;
            return s;
        }

        void finish() {
            done = true;
            try { join(500); } catch (InterruptedException ignored) { }
            if (firstSoundNs != 0) log("downlink: first sound %d ms after startRecording", (firstSoundNs - startNs) / 1_000_000);
            try { record.stop(); } catch (Throwable ignored) { }
            record.release();
            try { if (out != null) out.close(); } catch (IOException ignored) { }
        }
    }

    // ---- phase D: does the injected tone reach the uplink? -------------------------------

    static final class Up extends Thread {
        static volatile long written;                // samples written to the uplink track
        final AudioRecord record;
        int windows, toneWindows;
        long lastDetectNs, lagSumMs, lagCount;
        boolean wasTone;
        volatile boolean done;

        Up() {
            super("uplink-check");
            AudioRecord r = null;
            try {
                r = new AudioRecord.Builder().setAudioSource(SOURCE_VOICE_UPLINK).setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                        .setBufferSizeInBytes(FRAME * 2 * 4).build();
                if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); r = null; log("VOICE_UPLINK record: not initialized"); }
            } catch (Throwable t) { log("VOICE_UPLINK record refused: %s", cause(t)); }
            record = r;
        }

        @Override public void run() {
            record.startRecording();
            short[] buf = new short[FRAME];
            while (!done && !stop) {
                int n = record.read(buf, 0, FRAME);
                if (n <= 0) return;
                boolean tone = goertzelShare(buf, n, 1000) > 0.5 && dbfs(buf, n) > -45;
                long now = System.nanoTime();
                synchronized (this) {
                    windows++;
                    if (tone) toneWindows++;
                    if (tone && !wasTone && toneStartNs != 0) {
                        long lag = (now - toneStartNs) / 1_000_000;
                        if (lag > 0 && lag < 500) { lagSumMs += lag; lagCount++; }
                    }
                    wasTone = tone;
                }
            }
        }

        synchronized String second() {
            String s = String.format(Locale.US, "uplink tap: tone in %d/%d windows (expect ~half if injection works), inject->tap lag %s ms",
                    toneWindows, windows, lagCount == 0 ? "?" : String.valueOf(lagSumMs / lagCount));
            windows = 0; toneWindows = 0;
            return s;
        }

        void finish() {
            done = true;
            try { join(500); } catch (InterruptedException ignored) { }
            try { record.stop(); } catch (Throwable ignored) { }
            record.release();
        }
    }

    // ---- builders (what AudioManager's hidden methods build) -----------------------------

    static AudioRecord downlinkRecord() throws Exception {
        AudioAttributes.Builder a = new AudioAttributes.Builder();
        AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class).invoke(a, SOURCE_VOICE_DOWNLINK);
        AudioRecord.Builder b = new AudioRecord.Builder().setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                .setBufferSizeInBytes(FRAME * 2 * 4);
        AudioRecord.Builder.class.getMethod("setAudioAttributes", AudioAttributes.class).invoke(b, a.build());
        AudioRecord.Builder.class.getMethod("setCallRedirectionMode", int.class).invoke(b, CALL_REDIRECT_PSTN);
        AudioRecord r = b.build();
        if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); throw new IllegalStateException("record not initialized"); }
        return r;
    }

    static AudioTrack uplinkTrack() throws Exception {
        AudioAttributes.Builder a = new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
        AudioAttributes.Builder.class.getMethod("setSystemUsage", int.class).invoke(a, USAGE_CALL_ASSISTANT);
        AudioTrack.Builder b = new AudioTrack.Builder().setAudioAttributes(a.build())
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO)).setBufferSizeInBytes(FRAME * 2 * 4)
                .setTransferMode(AudioTrack.MODE_STREAM);
        AudioTrack.Builder.class.getMethod("setCallRedirectionMode", int.class).invoke(b, CALL_REDIRECT_PSTN);
        AudioTrack t = b.build();
        if (t.getState() != AudioTrack.STATE_INITIALIZED) { t.release(); throw new IllegalStateException("track not initialized"); }
        return t;
    }

    static AudioFormat format(int mask) {
        return new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build();
    }

    // ---- IAudioService -------------------------------------------------------------------

    static Object audioService() throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "audio");
        return Class.forName("android.media.IAudioService$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    static int mode() {
        Object m = call("getMode");
        return m instanceof Integer ? (Integer) m : -1;
    }

    static Object interceptable() { return call("isPstnCallAudioInterceptable"); }

    /** Calls a no-argument IAudioService method; returns the result or the error text. */
    static Object call(String name) {
        try { return audio.getClass().getMethod(name).invoke(audio); }
        catch (Throwable t) { return "error " + cause(t); }
    }

    /** Fills an IAudioService method's parameters by type: the given int/boolean, our package, token, user 0. */
    static void invoke(String name, Object value) {
        for (Method m : audio.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] args = new Object[p.length];
            boolean valueUsed = false, pkgUsed = false;
            for (int i = 0; i < p.length; i++) {
                if (!valueUsed && (p[i] == int.class || p[i] == boolean.class)) { args[i] = value; valueUsed = true; }
                else if (p[i] == int.class) args[i] = 0;                         // userId
                else if (p[i] == String.class) { args[i] = pkgUsed ? null : PKG; pkgUsed = true; } // package, then attribution tag
                else if (p[i] == IBinder.class) args[i] = TOKEN;
            }
            try { m.invoke(audio, args); log("%s(%s) ok", name, value); }
            catch (Throwable t) { log("%s(%s) refused: %s", name, value, cause(t)); }
            return;
        }
        log("%s: no such method", name);
    }

    static volatile boolean micTouched;
    static void setMicMute(boolean on) { invoke("setMicrophoneMute", on); micMuted = on; micTouched = true; }

    static void takeMode(int m) {
        invoke("setMode", m);
        modeTaken = true;
        SystemClock.sleep(300);
        log("mode is now %d", mode());
    }

    static void giveBackMode() {
        if (!modeTaken) return;
        invoke("setMode", MODE_NORMAL);              // drops this process's mode request; Telecom's IN_CALL returns
        modeTaken = false;
        SystemClock.sleep(300);
        log("mode given back, now %d", mode());
    }

    static synchronized void restore() {
        if (micTouched && micMuted != micWasMuted) setMicMute(micWasMuted);
        if (modeTaken) giveBackMode();
        if (volWas >= 0) setVoiceVolume(false);
    }

    // ---- small helpers -------------------------------------------------------------------

    interface Maker<T> { T make() throws Exception; }

    static <T> T timed(String what, Maker<T> maker) {
        long t0 = System.nanoTime();
        try {
            T v = maker.make();
            log("%s opened in %d ms", what, (System.nanoTime() - t0) / 1_000_000);
            return v;
        } catch (Throwable t) {
            log("%s refused: %s", what, cause(t));
            return null;
        }
    }

    static String hiddenLatency(AudioTrack t) {
        try { return String.valueOf(AudioTrack.class.getMethod("getLatency").invoke(t)) + "ms"; }
        catch (Throwable e) { return "?"; }
    }

    static double dbfs(short[] b, int n) {
        if (n <= 0) return -120;
        double s = 0;
        for (int i = 0; i < n; i++) s += (double) b[i] * b[i];
        return 20 * Math.log10(Math.sqrt(s / n) / 32768 + 1e-9);
    }

    /** Share of the window's energy at freq (Goertzel), 0..1. */
    static double goertzelShare(short[] b, int n, int freq) {
        double w = 2 * Math.PI * freq / RATE, c = 2 * Math.cos(w), s1 = 0, s2 = 0, total = 0;
        for (int i = 0; i < n; i++) {
            double s0 = b[i] + c * s1 - s2;
            s2 = s1; s1 = s0;
            total += (double) b[i] * b[i];
        }
        double power = s1 * s1 + s2 * s2 - c * s1 * s2;
        return total == 0 ? 0 : Math.min(1, 2 * power / (n * total));
    }

    static String dev(AudioDeviceInfo d) {
        return d == null ? "none" : d.getType() + "/" + d.getProductName();
    }

    static int arg(String[] a, int i, int def) { return a.length > i ? Integer.parseInt(a[i]) : def; }

    static String cause(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.toString();
    }

    static void log(String fmt, Object... args) {
        System.out.printf(Locale.US, "%6d  %s%n", SystemClock.elapsedRealtime() % 1_000_000, String.format(Locale.US, fmt, args));
        System.out.flush();
    }
}
