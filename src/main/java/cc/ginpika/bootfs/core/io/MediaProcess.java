package cc.ginpika.bootfs.core.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Bound media parsing and exclude playlist/network demuxers for uploaded input. */
public final class MediaProcess {
    private static final Semaphore SLOTS = new Semaphore(4);
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "media-timeout"); thread.setDaemon(true); return thread;
    });
    private static final String FORMATS = "mov,mp4,m4a,3gp,3g2,mj2,matroska,webm,avi,flv,asf,mpegts,mpeg,mp3,wav,flac,ogg,aac,ape,amr,png_pipe,jpeg_pipe,webp_pipe,bmp_pipe,gif";
    private MediaProcess() { }

    public static ProcessBuilder restrictInput(ProcessBuilder builder) {
        List<String> command = new ArrayList<>(builder.command());
        command.addAll(1, List.of("-nostdin", "-protocol_whitelist", "file,pipe", "-format_whitelist", FORMATS));
        return builder.command(command);
    }

    public static byte[] capture(ProcessBuilder builder, int seconds, int maxBytes) throws IOException, InterruptedException {
        if (!SLOTS.tryAcquire()) throw new IOException("Media workers are busy");
        Process process = null;
        ScheduledFuture<?> timeout = null;
        try {
            process = builder.start();
            Process child = process;
            timeout = TIMER.schedule(child::destroyForcibly, seconds, TimeUnit.SECONDS);
            byte[] bytes;
            try (var input = process.getInputStream()) { bytes = input.readNBytes(maxBytes + 1); }
            if (bytes.length > maxBytes) throw new IOException("Media output exceeds limit");
            if (!process.waitFor(seconds, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("Media process failed or timed out");
            return bytes;
        } finally {
            if (timeout != null) timeout.cancel(false);
            if (process != null) process.destroyForcibly();
            SLOTS.release();
        }
    }
}
