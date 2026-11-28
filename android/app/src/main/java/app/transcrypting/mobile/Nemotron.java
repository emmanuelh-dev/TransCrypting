package app.transcrypting.mobile;

import android.os.SystemClock;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Nemotron {
    interface Progress {
        void update(int percent);
    }

    record Transcription(String text, double audioSeconds, long modelLoadMs) {}

    private static final String MODEL_URL =
            "https://huggingface.co/pisar/nemotron-unified-asr-0.6b-sherpa-onnx/resolve/"
                    + "7691099b36bdb2978117a78834442435e55d59f3/";
    private static final String SPANISH_ENCODER_HASH =
            "4e90a728c702ea7f3d89380e4fabccec4b0cc72155217251e65c48eecb15f91b";
    // Offset, vector size and language index from langs.txt in this pinned export.
    private static final long PROMPT_OFFSET = 45_521_003;
    private static final int PROMPT_BYTES = 8_192;
    private static final int SPANISH_PROMPT_INDEX = 6;

    private static final Asset ENCODER = new Asset("encoder.onnx", 659_249_623,
            "cc129abcd7f8374bf421d29282047c746c154ca98b6435b358d0cafc926c7250");
    private static final Asset[] FILES = {
            new Asset("decoder.onnx", 9_223_194,
                    "18753da5ca260c9252106d9bb9574ff7d14273d7b064daf74ab90fd6db785b32"),
            new Asset("joiner.onnx", 3_713_589,
                    "4ca733f529e8c695ff971f9a6b940b7909cc9766dffb1e2c854fed759e87edbf"),
            new Asset("tokens.txt", 42_928,
                    "94139a0b2134fd40664ca72260026fa8a073336bb2f062239c8ecde2c8031763"),
            new Asset("bias.bin", 212_992,
                    "5604ccaa37dfd21345fd4c69256c2eb695c705943c97f30681e365938e3e6224"),
            ENCODER,
    };
    private record Asset(String name, long bytes, String sha256) {}

    private final File directory;
    private volatile OfflineRecognizer recognizer;

    Nemotron(File filesDir) {
        directory = new File(filesDir, "nemotron-es-7691099");
    }

    boolean installed() {
        return new File(directory, "ready").isFile()
                && new File(directory, "encoder.onnx").length() == ENCODER.bytes()
                && new File(directory, "decoder.onnx").length() == FILES[0].bytes()
                && new File(directory, "joiner.onnx").length() == FILES[1].bytes()
                && new File(directory, "tokens.txt").length() == FILES[2].bytes();
    }

    void install(Progress progress) throws IOException {
        Files.createDirectories(directory.toPath());
        long total = 0;
        for (Asset asset : FILES) total += asset.bytes();

        long complete = 0;
        for (Asset asset : FILES) {
            download(asset, complete, total, progress);
            complete += asset.bytes();
        }

        File encoder = new File(directory, ENCODER.name());
        if (!matchesHash(encoder, SPANISH_ENCODER_HASH)) {
            File bias = new File(directory, "bias.bin");
            byte[] prompt = new byte[PROMPT_BYTES];
            try (RandomAccessFile source = new RandomAccessFile(bias, "r");
                 RandomAccessFile target = new RandomAccessFile(encoder, "rw")) {
                source.seek((long) SPANISH_PROMPT_INDEX * PROMPT_BYTES);
                source.readFully(prompt);
                target.seek(PROMPT_OFFSET);
                target.write(prompt);
            }
            if (!matchesHash(encoder, SPANISH_ENCODER_HASH)) {
                Files.delete(encoder.toPath());
                throw new IOException("No se pudo preparar el modelo en español.");
            }
        }
        checkInterrupted();
        Files.write(new File(directory, "ready").toPath(), new byte[0]);
        progress.update(100);
    }

    private void download(Asset asset, long complete, long total, Progress progress)
            throws IOException {
        File destination = new File(directory, asset.name());
        if (destination.length() == asset.bytes()
                && (matchesHash(destination, asset.sha256())
                || (asset == ENCODER && matchesHash(destination, SPANISH_ENCODER_HASH)))) {
            progress.update((int) ((complete + asset.bytes()) * 100 / total));
            return;
        }
        Files.deleteIfExists(destination.toPath());

        File partial = new File(directory, asset.name() + ".part");
        if (partial.length() > asset.bytes()) Files.delete(partial.toPath());
        long offset = partial.length();
        if (directory.getUsableSpace() < total - complete - offset) {
            throw new IOException("Libera espacio para descargar el modelo.");
        }
        if (offset < asset.bytes()) {
            HttpURLConnection connection = (HttpURLConnection) new URL(MODEL_URL + asset.name()).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            try {
                int status = connection.getResponseCode();
                String range = connection.getHeaderField("Content-Range");
                boolean resume = status == HttpURLConnection.HTTP_PARTIAL && offset > 0
                        && range != null && range.startsWith("bytes " + offset + "-");
                if (!resume && status != HttpURLConnection.HTTP_OK) {
                    if (offset > 0) Files.deleteIfExists(partial.toPath());
                    throw new IOException("Descarga interrumpida (HTTP " + status + ").");
                }
                if (!resume) offset = 0;
                try (InputStream input = connection.getInputStream();
                     OutputStream output = Files.newOutputStream(partial.toPath(),
                             StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                             resume ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[64 * 1024];
                    int lastPercent = -1;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkInterrupted();
                        if (offset + count > asset.bytes()) {
                            throw new IOException("Tamaño inesperado durante la descarga.");
                        }
                        output.write(buffer, 0, count);
                        offset += count;
                        int percent = (int) ((complete + offset) * 100 / total);
                        if (lastPercent != percent) {
                            progress.update(Math.min(99, percent));
                            lastPercent = percent;
                        }
                    }
                }
            } finally {
                connection.disconnect();
            }
        }
        if (partial.length() != asset.bytes()) throw new IOException("Descarga incompleta.");
        if (!matchesHash(partial, asset.sha256())) {
            Files.delete(partial.toPath());
            throw new IOException("La descarga no coincide con el modelo esperado.");
        }
        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean matchesHash(File file, String sha256) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkInterrupted();
                digest.update(buffer, 0, count);
            }
        }
        byte[] hash = digest.digest();
        for (int index = 0; index < hash.length; index++) {
            if ((hash[index] & 0xff) != Integer.parseInt(sha256.substring(index * 2, index * 2 + 2), 16)) {
                return false;
            }
        }
        return true;
    }

    boolean loaded() {
        return recognizer != null;
    }

    long load() {
        if (loaded()) return 0;

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.getFeatConfig().setFeatureDim(128);
        var model = config.getModelConfig();
        model.setModelType("nemo_transducer");
        model.setProvider("cpu");
        model.setNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
        model.setTokens(new File(directory, "tokens.txt").getAbsolutePath());
        model.getTransducer().setEncoder(new File(directory, "encoder.onnx").getAbsolutePath());
        model.getTransducer().setDecoder(new File(directory, "decoder.onnx").getAbsolutePath());
        model.getTransducer().setJoiner(new File(directory, "joiner.onnx").getAbsolutePath());

        long loadStarted = SystemClock.elapsedRealtime();
        recognizer = new OfflineRecognizer(null, config);
        return SystemClock.elapsedRealtime() - loadStarted;
    }

    Transcription transcribe(File wav, Progress progress) throws IOException {
        checkInterrupted();
        long modelLoadMs = load();
        try (RandomAccessFile audio = new RandomAccessFile(wav, "r")) {
            StringBuilder text = new StringBuilder();
            long totalSamples = (audio.length() - WavRecorder.HEADER_BYTES) / 2;
            byte[] pcm = new byte[WavRecorder.SAMPLE_RATE * 20 * 2];
            ByteBuffer littleEndian = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            long position = 0;
            while (position < totalSamples) {
                checkInterrupted();
                int count = (int) Math.min(pcm.length / 2, totalSamples - position);
                audio.seek(WavRecorder.HEADER_BYTES + position * 2);
                audio.readFully(pcm, 0, count * 2);
                if (position + count < totalSamples) count = findPause(littleEndian, count);
                float[] samples = new float[count];
                for (int index = 0; index < count; index++) {
                    samples[index] = littleEndian.getShort(index * 2) / 32768f;
                }
                OfflineStream stream = recognizer.createStream();
                try {
                    stream.acceptWaveform(samples, WavRecorder.SAMPLE_RATE);
                    recognizer.decode(stream);
                    String part = recognizer.getResult(stream).getText().trim();
                    if (!part.isEmpty()) {
                        if (text.length() > 0) text.append("\n\n");
                        text.append(part);
                    }
                } finally {
                    stream.release();
                }
                position += count;
                progress.update((int) (position * 100 / totalSamples));
            }
            return new Transcription(text.toString(), (double) totalSamples / WavRecorder.SAMPLE_RATE, modelLoadMs);
        }
    }

    private static int findPause(ByteBuffer pcm, int count) {
        int window = WavRecorder.SAMPLE_RATE / 10;
        int start = count - 3 * WavRecorder.SAMPLE_RATE;
        long minEnergy = Long.MAX_VALUE;
        int cut = count;
        for (int offset = start; offset + window <= count; offset += window) {
            long energy = 0;
            for (int sample = offset; sample < offset + window; sample++) {
                int value = pcm.getShort(sample * 2);
                energy += (long) value * value;
            }
            if (energy <= minEnergy) {
                minEnergy = energy;
                cut = offset + window / 2;
            }
        }
        return cut;
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Operación interrumpida.");
    }
}
