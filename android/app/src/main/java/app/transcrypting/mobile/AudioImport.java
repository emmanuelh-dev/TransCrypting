package app.transcrypting.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.IntConsumer;

final class AudioImport {
    private static final int RATE = WavRecorder.SAMPLE_RATE;
    private static final long MAX_PCM_BYTES = 0xffff_ffffL - WavRecorder.HEADER_BYTES;

    private AudioImport() {}

    static void convert(Context context, Uri uri, File destination, IntConsumer progress) throws IOException {
        checkInterrupted();
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IOException("Selecciona el archivo desde el selector o compártelo desde una app.");
        }
        Path directory = destination.toPath().toAbsolutePath().getParent();
        Path temporary = null;
        Path copiedSource = null;
        MediaExtractor extractor = new MediaExtractor();
        try {
            try (AssetFileDescriptor source = context.getContentResolver()
                    .openAssetFileDescriptor(uri, "r")) {
                if (source == null) throw new ImportException("No se pudo abrir el archivo compartido.");
                if (isSeekable(source)) {
                    extractor.setDataSource(source);
                } else {
                    copiedSource = Files.createTempFile(directory, "import-source-", ".part");
                    try (InputStream input = source.createInputStream();
                         OutputStream output = Files.newOutputStream(copiedSource)) {
                        byte[] buffer = new byte[64 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            checkInterrupted();
                            output.write(buffer, 0, count);
                        }
                    }
                    extractor.setDataSource(copiedSource.toString());
                }
            }
            MediaFormat format = selectAudioTrack(extractor);
            temporary = Files.createTempFile(directory, "import-audio-", ".part");
            try (RandomAccessFile output = new RandomAccessFile(temporary.toFile(), "rw")) {
                output.seek(WavRecorder.HEADER_BYTES);
                long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                        ? format.getLong(MediaFormat.KEY_DURATION) : 0;
                PcmWriter writer = new PcmWriter(output, durationUs, progress);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if ("audio/raw".equals(mime)) {
                    writeRaw(extractor, format, writer);
                } else {
                    decode(extractor, format, mime, writer);
                }
                writer.finish();
            }
            checkInterrupted();
            Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (ImportException error) {
            throw error;
        } catch (SecurityException error) {
            throw new IOException("La app no tiene acceso al archivo. Selecciónalo de nuevo.");
        } catch (IOException | RuntimeException error) {
            throw new IOException("No se pudo importar el audio. Revisa el formato y el espacio disponible.");
        } finally {
            try {
                extractor.release();
            } finally {
                deletePartial(temporary);
                deletePartial(copiedSource);
            }
        }
    }

    private static boolean isSeekable(AssetFileDescriptor source) {
        try {
            Os.lseek(source.getFileDescriptor(), 0, OsConstants.SEEK_CUR);
            return true;
        } catch (ErrnoException error) {
            return false;
        }
    }

    private static MediaFormat selectAudioTrack(MediaExtractor extractor) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                return format;
            }
        }
        throw new ImportException("El archivo no contiene una pista de audio compatible.");
    }

    private static void writeRaw(MediaExtractor extractor, MediaFormat format, PcmWriter writer)
            throws IOException {
        writer.setFormat(format);
        ByteBuffer input = ByteBuffer.allocateDirect(64 * 1024);
        while (true) {
            checkInterrupted();
            if (readSample(extractor, input) < 0) break;
            writer.write(input);
            if (!extractor.advance()) break;
        }
    }

    private static void decode(MediaExtractor extractor, MediaFormat format, String mime,
                               PcmWriter writer) throws IOException {
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        try {
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnded = false;
            boolean outputEnded = false;
            long lastProgress = SystemClock.elapsedRealtime();
            while (!outputEnded) {
                checkInterrupted();
                if (!inputEnded) {
                    int id = codec.dequeueInputBuffer(0);
                    if (id >= 0) {
                        ByteBuffer input = codec.getInputBuffer(id);
                        if (input == null) throw new ImportException("No se pudo leer la pista de audio.");
                        long time = extractor.getSampleTime();
                        int flags = extractor.getSampleFlags();
                        int count = readSample(extractor, input);
                        if (count < 0) {
                            codec.queueInputBuffer(id, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            int codecFlags = (flags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0
                                    ? MediaCodec.BUFFER_FLAG_PARTIAL_FRAME : 0;
                            codec.queueInputBuffer(id, 0, count, time, codecFlags);
                            extractor.advance();
                        }
                        lastProgress = SystemClock.elapsedRealtime();
                    }
                }
                int id = codec.dequeueOutputBuffer(info, 10_000);
                if (id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    writer.setFormat(codec.getOutputFormat());
                    lastProgress = SystemClock.elapsedRealtime();
                } else if (id >= 0) {
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            if (writer.sampleRate == 0) writer.setFormat(codec.getOutputFormat(id));
                            ByteBuffer pcm = codec.getOutputBuffer(id);
                            if (pcm == null) throw new ImportException("No se pudo decodificar el audio.");
                            pcm.limit(info.offset + info.size);
                            pcm.position(info.offset);
                            writer.write(pcm);
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally {
                        codec.releaseOutputBuffer(id, false);
                    }
                    lastProgress = SystemClock.elapsedRealtime();
                } else if (SystemClock.elapsedRealtime() - lastProgress > 15_000) {
                    throw new ImportException("El decodificador dejó de responder.");
                }
            }
        } finally {
            codec.release();
        }
    }

    private static int readSample(MediaExtractor extractor, ByteBuffer buffer) throws IOException {
        if (extractor.getSampleTrackIndex() < 0) return -1;
        if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
            throw new ImportException("El archivo de audio está protegido.");
        }
        if (Build.VERSION.SDK_INT >= 28 && extractor.getSampleSize() > buffer.capacity()) {
            throw new ImportException("La pista de audio contiene un bloque incompatible.");
        }
        buffer.clear();
        return extractor.readSampleData(buffer, 0);
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new ImportException("Importación cancelada.");
    }

    private static void deletePartial(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static final class ImportException extends IOException {
        private static final long serialVersionUID = 1L;

        ImportException(String message) {
            super(message);
        }
    }

    private static final class PcmWriter {
        private final RandomAccessFile output;
        private final long durationUs;
        private final IntConsumer progress;
        private final ByteBuffer wav = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        private int sampleRate;
        private int channels;
        private int encoding;
        private int frameSize;
        private int ticks;
        private long weightedSample;
        private long pcmBytes;
        private int lastPercent;

        PcmWriter(RandomAccessFile output, long durationUs, IntConsumer progress) {
            this.output = output;
            this.durationUs = durationUs;
            this.progress = progress;
            if (durationUs > 0) progress.accept(0);
        }

        void setFormat(MediaFormat format) throws IOException {
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int count = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (rate < 4_000 || rate > 384_000 || count <= 0 || count > 32) {
                throw new ImportException("La frecuencia o los canales del audio son incompatibles.");
            }
            int pcmEncoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
            int bytesPerSample = switch (pcmEncoding) {
                case AudioFormat.ENCODING_PCM_8BIT -> 1;
                case AudioFormat.ENCODING_PCM_16BIT -> 2;
                case AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3;
                case AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4;
                default -> throw new ImportException("El formato PCM de este audio es incompatible.");
            };
            if (rate != sampleRate) writePartialSample();
            sampleRate = rate;
            channels = count;
            encoding = pcmEncoding;
            frameSize = count * bytesPerSample;
        }

        void write(ByteBuffer pcm) throws IOException {
            pcm.order(ByteOrder.nativeOrder());
            if (pcm.remaining() % frameSize != 0) {
                throw new ImportException("La pista de audio tiene muestras incompletas.");
            }
            while (pcm.hasRemaining()) {
                int sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    sum += switch (encoding) {
                        case AudioFormat.ENCODING_PCM_8BIT -> ((pcm.get() & 0xff) - 128) << 8;
                        case AudioFormat.ENCODING_PCM_16BIT -> pcm.getShort();
                        case AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                            pcm.get();
                            yield (pcm.get() & 0xff) | (pcm.get() << 8);
                        }
                        case AudioFormat.ENCODING_PCM_32BIT -> pcm.getInt() >> 16;
                        default -> Math.max(Short.MIN_VALUE,
                                Math.min(Short.MAX_VALUE, Math.round(pcm.getFloat() * 32768)));
                    };
                }
                int mono = sum / channels;
                // Each input frame spans RATE ticks and each output frame spans sampleRate ticks.
                int remaining = RATE;
                while (remaining > 0) {
                    int weight = Math.min(remaining, sampleRate - ticks);
                    weightedSample += (long) mono * weight;
                    ticks += weight;
                    remaining -= weight;
                    if (ticks == sampleRate) writePartialSample();
                }
            }
            if (durationUs > 0) {
                long writtenUs = (pcmBytes + wav.position()) * 1_000_000L / (RATE * 2);
                int percent = 5 * (int) Math.min(19, 20 * writtenUs / durationUs);
                if (percent > lastPercent) {
                    lastPercent = percent;
                    progress.accept(percent);
                }
            }
        }

        void finish() throws IOException {
            writePartialSample();
            flush();
            if (pcmBytes == 0) throw new ImportException("El archivo no contiene muestras de audio.");
            output.seek(0);
            output.write(ByteBuffer.allocate(WavRecorder.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    .put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) (pcmBytes + 36))
                    .put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16)
                    .putShort((short) 1).putShort((short) 1)
                    .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16)
                    .put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) pcmBytes).array());
        }

        private void writePartialSample() throws IOException {
            if (ticks == 0) return;
            wav.putShort((short) (weightedSample / ticks));
            if (!wav.hasRemaining()) flush();
            ticks = 0;
            weightedSample = 0;
        }

        private void flush() throws IOException {
            int count = wav.position();
            if (pcmBytes + count > MAX_PCM_BYTES) {
                throw new ImportException("El audio supera el tamaño que permite el formato WAV.");
            }
            output.write(wav.array(), 0, count);
            pcmBytes += count;
            wav.clear();
        }
    }
}
