package app.transcrypting.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int MIC_PERMISSION = 1;
    private static final int BACKGROUND = Color.rgb(24, 28, 26);
    private static final int SURFACE = Color.rgb(36, 42, 38);
    private static final int CONTROL = Color.rgb(52, 61, 55);
    private static final int INK = Color.rgb(242, 246, 237);
    private static final int MUTED = Color.rgb(173, 187, 178);
    private static final int ACCENT = Color.rgb(205, 238, 86);
    private static final int ERROR = Color.rgb(255, 170, 162);

    private enum State { IDLE, DOWNLOADING, RECORDING, SAVING, TRANSCRIBING }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private State state = State.IDLE;
    private Nemotron model;
    private File recordings;
    private File selected;
    private WavRecorder recorder;
    private MediaPlayer player;

    private TextView modelStatus;
    private TextView status;
    private TextView recordingTitle;
    private TextView transcript;
    private ProgressBar progress;
    private Chronometer timer;
    private Button download;
    private Button record;
    private Button history;
    private Button play;
    private Button transcribe;
    private Button copy;
    private LinearLayout resultCard;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        model = new Nemotron(getFilesDir());
        recordings = new File(getFilesDir(), "recordings");
        createScreen();

        File[] files = recentRecordings();
        if (saved != null) {
            String lastSelection = saved.getString("recording");
            for (File file : files) {
                if (file.getName().equals(lastSelection)) selected = file;
            }
        }
        if (selected == null && files.length > 0) selected = files[0];
        refreshModel();
        refreshRecording();
        renderState();
    }

    private void createScreen() {
        ScrollView screen = new ScrollView(this);
        screen.setBackgroundColor(BACKGROUND);
        screen.setFillViewport(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        screen.addView(column, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        screen.setOnApplyWindowInsetsListener((view, insets) -> {
            column.setPadding(dp(20) + insets.getSystemWindowInsetLeft(),
                    dp(22) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(),
                    dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(screen);

        TextView brand = label("TransCrypting", 16, ACCENT, true);
        column.addView(brand);
        TextView title = label("Grabadora", 36, INK, true);
        column.addView(title, spaced(6));

        LinearLayout modelCard = card();
        column.addView(modelCard, spaced(20));
        modelCard.addView(label("Nemotron Labs Unified ASR 0.6B", 17, INK, true));
        modelStatus = label("", 14, MUTED, false);
        modelCard.addView(modelStatus, spaced(6));
        download = button("Descargar modelo", false);
        download.setOnClickListener(view -> downloadModel());
        modelCard.addView(download, spaced(12));

        LinearLayout captureCard = card();
        column.addView(captureCard, spaced(14));
        timer = new Chronometer(this);
        timer.setGravity(Gravity.CENTER);
        timer.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        timer.setTextColor(INK);
        timer.setTextSize(32);
        captureCard.addView(timer, spaced(0));
        record = button("Grabar con micrófono", true);
        record.setOnClickListener(view -> {
            if (state == State.RECORDING) stopRecording();
            else recordWithPermission();
        });
        captureCard.addView(record, spaced(14));
        status = label("", 14, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        captureCard.addView(status, spaced(10));
        setStatus("", false);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        captureCard.addView(progress, spaced(6));

        history = button("Grabaciones", false);
        history.setOnClickListener(view -> showHistory());
        column.addView(history, spaced(14));

        resultCard = card();
        column.addView(resultCard, spaced(12));
        recordingTitle = label("", 17, INK, true);
        resultCard.addView(recordingTitle);
        LinearLayout actions = new LinearLayout(this);
        resultCard.addView(actions, spaced(8));
        play = button("Escuchar", false);
        play.setOnClickListener(view -> togglePlayback());
        actions.addView(play, new LinearLayout.LayoutParams(0, dp(48), 1));
        transcribe = button("Transcribir", false);
        transcribe.setOnClickListener(view -> transcribeSelected());
        LinearLayout.LayoutParams secondary = new LinearLayout.LayoutParams(0, dp(48), 1);
        secondary.setMarginStart(dp(8));
        actions.addView(transcribe, secondary);
        transcript = label("", 16, INK, false);
        transcript.setTextIsSelectable(true);
        resultCard.addView(transcript, spaced(14));
        copy = button("Copiar texto", false);
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("Transcripción", transcript.getText()));
            if (android.os.Build.VERSION.SDK_INT < 33) {
                Toast.makeText(this, "Texto copiado", Toast.LENGTH_SHORT).show();
            }
        });
        resultCard.addView(copy, spaced(12));
    }

    private void downloadModel() {
        if (state != State.IDLE) return;
        state = State.DOWNLOADING;
        renderState();
        showProgress("Descargando modelo", 0);
        worker.execute(() -> {
            try {
                model.install(percent -> post(() -> showProgress("Descargando modelo", percent)));
                post(() -> {
                    finishOperation("Modelo listo para transcribir en el teléfono.", false);
                    if (selected != null && !textFile(selected).isFile()) transcribeSelected();
                });
            } catch (IOException | RuntimeException error) {
                post(() -> finishOperation(message(error, "No se pudo descargar el modelo."), true));
            }
        });
    }

    private void recordWithPermission() {
        if (state != State.IDLE) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginRecording();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != MIC_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) beginRecording();
        else setStatus("Permite el micrófono en los ajustes de Android para grabar.", true);
    }

    private void beginRecording() {
        if (state != State.IDLE) return;
        stopPlayback();
        WavRecorder capture = new WavRecorder();
        recorder = capture;
        state = State.RECORDING;
        setStatus("Abriendo micrófono…", false);
        renderState();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            try {
                Files.createDirectories(recordings.toPath());
                File wav = new File(recordings, System.currentTimeMillis() + ".wav");
                capture.record(wav, () -> post(() -> {
                    if (state != State.RECORDING) return;
                    timer.setBase(SystemClock.elapsedRealtime());
                    timer.start();
                    setStatus("Grabando…", false);
                }));
                post(() -> {
                    selected = wav;
                    finishOperation(model.installed() ? "Grabación guardada."
                            : "Grabación guardada. Descarga el modelo para transcribir.", false);
                    if (model.installed()) transcribeSelected();
                });
            } catch (IOException | RuntimeException error) {
                post(() -> finishOperation(message(error, "No se pudo guardar el audio."), true));
            }
        });
    }

    private void stopRecording() {
        if (state != State.RECORDING) return;
        recorder.stop();
        state = State.SAVING;
        timer.stop();
        setStatus("Guardando audio…", false);
        renderState();
    }

    private void transcribeSelected() {
        if (state != State.IDLE || selected == null || !model.installed()) return;
        File wav = selected;
        state = State.TRANSCRIBING;
        renderState();
        showProgress("Abriendo Nemotron", 0);
        worker.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            try {
                String text = model.transcribe(wav,
                        percent -> post(() -> showProgress("Transcribiendo en el teléfono", percent)));
                File destination = textFile(wav);
                File temporary = new File(recordings, destination.getName() + ".part");
                Files.write(temporary.toPath(), text.getBytes(StandardCharsets.UTF_8));
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                android.util.Log.i("TransCrypting", "Nemotron transcribed " + (wav.length() - 44) / 32_000.0
                        + "s of audio in " + (SystemClock.elapsedRealtime() - started) + "ms");
                post(() -> finishOperation(text.isEmpty() ? "Nemotron no detectó voz."
                        : "Transcripción guardada.", false));
            } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError error) {
                android.util.Log.w("TransCrypting", "Local transcription failed", error);
                post(() -> finishOperation(message(error, "No se pudo transcribir. La grabación sigue guardada."), true));
            }
        });
    }

    private void finishOperation(String message, boolean failed) {
        state = State.IDLE;
        recorder = null;
        timer.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setStatus(message, failed);
        refreshModel();
        refreshRecording();
        renderState();
    }

    private void refreshModel() {
        modelStatus.setText(model.installed()
                ? "Español · CPU · Disponible sin conexión"
                : "Español · CPU · Descarga de 672 MB");
        download.setVisibility(model.installed() ? View.GONE : View.VISIBLE);
    }

    private void refreshRecording() {
        File[] files = recentRecordings();
        history.setVisibility(files.length > 0 ? View.VISIBLE : View.GONE);
        history.setText("Grabaciones · " + files.length);
        resultCard.setVisibility(selected != null ? View.VISIBLE : View.GONE);
        if (selected == null) return;
        recordingTitle.setText(recordingName(selected));
        String text = "";
        File file = textFile(selected);
        if (file.isFile()) {
            try {
                text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (IOException error) {
                setStatus("No se pudo abrir la transcripción guardada.", true);
            }
        }
        copy.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        transcribe.setVisibility(model.installed() && text.isEmpty() ? View.VISIBLE : View.GONE);
        transcript.setText(text.isEmpty()
                ? (file.isFile() ? "Nemotron no detectó voz en este audio." : "Transcripción pendiente.")
                : text);
    }

    private void renderState() {
        boolean idle = state == State.IDLE;
        record.setEnabled(idle || state == State.RECORDING);
        record.setText(state == State.RECORDING ? "Detener y guardar" : "Grabar con micrófono");
        download.setEnabled(idle);
        history.setEnabled(idle);
        play.setEnabled(idle);
        transcribe.setEnabled(idle);
        progress.setVisibility(state == State.DOWNLOADING || state == State.TRANSCRIBING
                ? View.VISIBLE : View.GONE);
    }

    private void showProgress(String operation, int value) {
        progress.setProgress(value);
        setStatus(operation + " · " + value + "%", false);
    }

    private void setStatus(String message, boolean error) {
        status.setText(message);
        status.setTextColor(error ? ERROR : MUTED);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private File[] recentRecordings() {
        File[] files = recordings.listFiles(file -> file.getName().endsWith(".wav"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        return files;
    }

    private void showHistory() {
        File[] files = recentRecordings();
        String[] names = Arrays.stream(files).map(this::recordingName).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Grabaciones")
                .setItems(names, (dialog, index) -> {
                    stopPlayback();
                    selected = files[index];
                    setStatus("", false);
                    refreshRecording();
                })
                .setNegativeButton("Cerrar", null).show();
    }

    private String recordingName(File wav) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                .format(new Date(wav.lastModified()));
    }

    private static File textFile(File wav) {
        return new File(wav.getParentFile(), wav.getName().replace(".wav", ".txt"));
    }

    private void togglePlayback() {
        if (player != null) {
            stopPlayback();
            return;
        }
        player = new MediaPlayer();
        try {
            player.setDataSource(selected.getAbsolutePath());
            player.setOnCompletionListener(ignored -> stopPlayback());
            player.setOnErrorListener((ignored, what, extra) -> {
                stopPlayback();
                setStatus("No se pudo reproducir el audio.", true);
                return true;
            });
            player.setOnPreparedListener(prepared -> {
                if (prepared == player) prepared.start();
            });
            player.prepareAsync();
            play.setText("Detener");
        } catch (IOException | IllegalStateException error) {
            stopPlayback();
            setStatus("No se pudo reproducir el audio.", true);
        }
    }

    private void stopPlayback() {
        if (player == null) return;
        player.release();
        player = null;
        play.setText("Escuchar");
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (selected != null) state.putString("recording", selected.getName());
    }

    @Override
    protected void onStop() {
        stopRecording();
        stopPlayback();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void post(Runnable action) {
        runOnUiThread(() -> {
            if (!isDestroyed()) action.run();
        });
    }

    private static String message(Throwable error, String fallback) {
        return error instanceof IOException && error.getMessage() != null ? error.getMessage() : fallback;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(SURFACE, 20));
        return card;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? BACKGROUND : INK);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x30000000), rounded(primary ? ACCENT : CONTROL, 14), null));
        return button;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private LinearLayout.LayoutParams spaced(int margin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        layout.topMargin = dp(margin);
        return layout;
    }

    private int dp(int units) {
        return Math.round(units * getResources().getDisplayMetrics().density);
    }
}
