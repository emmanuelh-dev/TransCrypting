package app.transcrypting.mobile;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.IntConsumer;

final class WavRecorder {
    static final int SAMPLE_RATE = 16_000;
    static final int HEADER_BYTES = 44;
    private static final long MAX_PCM_BYTES = 0xffff_ffffL - HEADER_BYTES;

    private volatile boolean stop;
    private volatile boolean paused;

    synchronized void stop() {
        stop = true;
        notifyAll();
    }

    synchronized void setPaused(boolean paused) {
        this.paused = paused;
        notifyAll();
    }

    private synchronized void waitForResume() throws InterruptedException {
        while (paused && !stop) wait();
    }

    @SuppressLint("MissingPermission")
    void record(File destination, Runnable onStart, IntConsumer onLevel) throws IOException {
        int channels = AudioFormat.CHANNEL_IN_MONO;
        int format = AudioFormat.ENCODING_PCM_16BIT;
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channels, format);
        if (minBuffer <= 0) throw new IOException("El micrófono no admite audio mono a 16 kHz.");
        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, channels, format, Math.max(minBuffer, 8_192));
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        try {
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("No se pudo abrir el micrófono.");
            }
            long bytes = 0;
            try (RandomAccessFile output = new RandomAccessFile(temporary, "rw")) {
                output.setLength(0);
                output.seek(HEADER_BYTES);
                recorder.startRecording();
                if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IOException("El micrófono está ocupado.");
                }
                onStart.run();
                short[] samples = new short[1_600];
                ByteBuffer pcm = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                long lastLevel = 0;
                while (!stop && !Thread.currentThread().isInterrupted() && bytes + pcm.capacity() <= MAX_PCM_BYTES) {
                    if (paused) {
                        recorder.stop();
                        try {
                            waitForResume();
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (stop) break;
                        recorder.startRecording();
                        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) break;
                    }
                    int count = recorder.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                    if (count < 0) {
                        if (bytes > 0) break;
                        throw new IOException("El micrófono dejó de responder.");
                    }
                    pcm.clear();
                    pcm.asShortBuffer().put(samples, 0, count);
                    output.write(pcm.array(), 0, count * 2);
                    bytes += count * 2L;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastLevel >= 90) {
                        int peak = 0;
                        for (int i = 0; i < count; i++) peak = Math.max(peak, Math.abs((int) samples[i]));
                        onLevel.accept(peak);
                        lastLevel = now;
                    }
                }
                if (bytes == 0) throw new IOException("No se capturó audio.");
                output.seek(0);
                output.write(header(bytes));
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            recorder.release();
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static byte[] header(long bytes) {
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) (bytes + 36))
                .put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16)
                .putShort((short) 1).putShort((short) 1)
                .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) bytes).array();
    }
}
