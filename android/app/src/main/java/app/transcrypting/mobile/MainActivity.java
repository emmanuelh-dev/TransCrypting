package app.transcrypting.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
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
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy",
            Locale.forLanguageTag("es-MX"));

    private enum State { IDLE, DOWNLOADING, RECORDING, SAVING, TRANSCRIBING }
    private enum Page { LIBRARY, RECORDING, RESOURCES }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private State state = State.IDLE;
    private Page page = Page.LIBRARY;
    private Page resourcesParent = Page.LIBRARY;
    private OnBackInvokedCallback backCallback;
    private int libraryScroll;
    private Nemotron model;
    private File recordings;
    private File selected;
    private WavRecorder recorder;
    private MediaPlayer player;
    private boolean playerReady;
    private boolean scrubbing;
    private final Runnable playbackTick = this::updatePlayback;

    private ScrollView screen;
    private LinearLayout libraryPage;
    private LinearLayout recordingPage;
    private LinearLayout resourcesPage;
    private LinearLayout recordingList;
    private TextView headerTitle;
    private Button back;
    private Button resources;
    private TextView modelStatus;
    private TextView downloadStatus;
    private TextView captureStatus;
    private TextView transcriptionStatus;
    private TextView recordingTitle;
    private TextView transcript;
    private TextView playbackTime;
    private ProgressBar downloadProgress;
    private ProgressBar transcriptionProgress;
    private SeekBar timeline;
    private Chronometer timer;
    private Button download;
    private Button record;
    private Button play;
    private Button transcribe;
    private Button copy;
    private Button getModel;

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
            libraryScroll = saved.getInt("libraryScroll");
            resourcesParent = selected != null && saved.getBoolean("resourcesFromRecording")
                    ? Page.RECORDING : Page.LIBRARY;
        }
        refreshModel();
        refreshLibrary();
        refreshRecording();
        String lastPage = saved == null ? "" : saved.getString("page", "");
        showPage(Page.RESOURCES.name().equals(lastPage) ? Page.RESOURCES
                : Page.RECORDING.name().equals(lastPage) && selected != null ? Page.RECORDING : Page.LIBRARY);
    }

    private void createScreen() {
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(dp(20) + insets.getSystemWindowInsetLeft(),
                    dp(6) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(56));
        root.addView(header, spaced(0));
        back = button("", false);
        back.setContentDescription("Volver");
        setIcon(back, R.drawable.ic_arrow_left, INK);
        back.setCompoundDrawablePadding(0);
        back.setPadding(dp(15), 0, dp(15), 0);
        back.setOnClickListener(view -> goBack());
        LinearLayout.LayoutParams backLayout = new LinearLayout.LayoutParams(dp(48), dp(48));
        backLayout.setMarginEnd(dp(12));
        header.addView(back, backLayout);
        headerTitle = label("", 21, INK, true);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        resources = button("Recursos", false);
        setIcon(resources, R.drawable.ic_settings, MUTED);
        resources.setOnClickListener(view -> openResources());
        header.addView(resources);

        screen = new ScrollView(this);
        screen.setFillViewport(true);
        screen.setClipToPadding(false);
        screen.setPadding(0, dp(18), 0, dp(24));
        root.addView(screen, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout content = column();
        screen.addView(content);
        libraryPage = column();
        recordingPage = column();
        resourcesPage = column();
        content.addView(libraryPage);
        content.addView(recordingPage);
        content.addView(resourcesPage);
        createLibraryPage();
        createRecordingPage();
        createResourcesPage();
    }

    private void createLibraryPage() {
        LinearLayout captureCard = card();
        libraryPage.addView(captureCard);
        LinearLayout captureHeader = new LinearLayout(this);
        captureHeader.setGravity(Gravity.CENTER_VERTICAL);
        captureHeader.addView(label("Micrófono", 16, INK, true),
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        timer = new Chronometer(this);
        timer.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        timer.setTextColor(MUTED);
        timer.setTextSize(26);
        captureHeader.addView(timer);
        captureCard.addView(captureHeader);
        record = button("Grabar con micrófono", true);
        record.setMinimumHeight(dp(56));
        record.setOnClickListener(view -> {
            if (state == State.RECORDING) stopRecording();
            else recordWithPermission();
        });
        captureCard.addView(record, spaced(12));
        captureStatus = statusLabel();
        captureCard.addView(captureStatus, spaced(10));
        TextView title = label("Grabaciones", 30, INK, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        libraryPage.addView(title, spaced(28));
        recordingList = column();
        libraryPage.addView(recordingList, spaced(10));
    }

    private void createRecordingPage() {
        recordingTitle = label("", 30, INK, true);
        if (Build.VERSION.SDK_INT >= 28) recordingTitle.setAccessibilityHeading(true);
        recordingPage.addView(recordingTitle);

        LinearLayout audio = card();
        recordingPage.addView(audio, spaced(16));
        LinearLayout audioHeader = new LinearLayout(this);
        audioHeader.setGravity(Gravity.CENTER_VERTICAL);
        audioHeader.addView(label("Audio", 16, INK, true),
                new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        playbackTime = label("", 14, MUTED, false);
        audioHeader.addView(playbackTime);
        audio.addView(audioHeader);
        timeline = new SeekBar(this);
        timeline.setContentDescription("Posición del audio");
        timeline.setProgressTintList(ColorStateList.valueOf(ACCENT));
        timeline.setThumbTintList(ColorStateList.valueOf(ACCENT));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int position, boolean fromUser) {
                if (!fromUser) return;
                if (playerReady) player.seekTo(position, MediaPlayer.SEEK_CLOSEST);
                updatePlaybackTime();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                scrubbing = false;
            }
        });
        audio.addView(timeline, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        play = button("Escuchar", true);
        setIcon(play, R.drawable.ic_play, BACKGROUND);
        play.setOnClickListener(view -> togglePlayback());
        audio.addView(play);

        LinearLayout transcriptHeader = new LinearLayout(this);
        transcriptHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("Transcripción", 24, INK, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        transcriptHeader.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        copy = button("Copiar", false);
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("Transcripción", transcript.getText()));
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(this, "Texto copiado", Toast.LENGTH_SHORT).show();
            }
        });
        transcriptHeader.addView(copy);
        recordingPage.addView(transcriptHeader, spaced(24));
        transcriptionStatus = statusLabel();
        recordingPage.addView(transcriptionStatus, spaced(8));
        transcriptionProgress = progressBar();
        recordingPage.addView(transcriptionProgress);
        transcript = label("", 17, INK, false);
        transcript.setLineSpacing(dp(5), 1f);
        transcript.setTextIsSelectable(true);
        recordingPage.addView(transcript, spaced(12));
        transcribe = button("Transcribir", false);
        transcribe.setOnClickListener(view -> transcribeSelected());
        recordingPage.addView(transcribe, spaced(18));
        getModel = button("Ir a Recursos", false);
        setIcon(getModel, R.drawable.ic_settings, INK);
        getModel.setOnClickListener(view -> openResources());
        recordingPage.addView(getModel, spaced(16));
    }

    private void createResourcesPage() {
        LinearLayout modelCard = card();
        resourcesPage.addView(modelCard);
        modelCard.addView(label("Modelo de transcripción", 14, MUTED, true));
        modelCard.addView(label("Nemotron 0.6B", 26, INK, true), spaced(4));
        modelCard.addView(label("Español · CPU", 15, MUTED, false), spaced(4));
        modelStatus = label("", 15, ACCENT, false);
        modelCard.addView(modelStatus, spaced(16));
        download = button("Descargar modelo", true);
        download.setOnClickListener(view -> downloadModel());
        modelCard.addView(download, spaced(12));
        downloadStatus = statusLabel();
        modelCard.addView(downloadStatus, spaced(8));
        downloadProgress = progressBar();
        modelCard.addView(downloadProgress);
    }

    private void showPage(Page destination) {
        if (page == Page.LIBRARY && destination != page && screen.isLaidOut()) {
            libraryScroll = screen.getScrollY();
        }
        if (page == Page.RECORDING && destination != page) stopPlayback();
        page = destination;
        libraryPage.setVisibility(page == Page.LIBRARY ? View.VISIBLE : View.GONE);
        recordingPage.setVisibility(page == Page.RECORDING ? View.VISIBLE : View.GONE);
        resourcesPage.setVisibility(page == Page.RESOURCES ? View.VISIBLE : View.GONE);
        back.setVisibility(page == Page.LIBRARY ? View.GONE : View.VISIBLE);
        resources.setVisibility(page == Page.LIBRARY ? View.VISIBLE : View.GONE);
        headerTitle.setText(switch (page) {
            case LIBRARY -> "TransCrypting";
            case RECORDING -> "Grabación";
            case RESOURCES -> "Recursos";
        });
        if (Build.VERSION.SDK_INT >= 33) {
            if (page != Page.LIBRARY && backCallback == null) {
                backCallback = this::goBack;
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
            } else if (page == Page.LIBRARY && backCallback != null) {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
                backCallback = null;
            }
        }
        renderState();
        screen.scrollTo(0, 0);
        screen.post(() -> {
            if (page == destination && page == Page.LIBRARY) screen.scrollTo(0, libraryScroll);
        });
    }

    private void openResources() {
        resourcesParent = page;
        showPage(Page.RESOURCES);
    }

    private void goBack() {
        showPage(page == Page.RESOURCES ? resourcesParent : Page.LIBRARY);
    }

    @Override
    @SuppressLint("GestureBackNavigation") // Android 13+ uses OnBackInvokedCallback.
    public void onBackPressed() {
        if (page == Page.LIBRARY) super.onBackPressed();
        else goBack();
    }

    private void downloadModel() {
        if (state != State.IDLE) return;
        File pending = resourcesParent == Page.RECORDING ? selected : null;
        state = State.DOWNLOADING;
        renderState();
        showProgress("Descargando modelo", 0);
        worker.execute(() -> {
            try {
                model.install(percent -> post(() -> showProgress("Descargando modelo", percent)));
                post(() -> {
                    finishOperation("Modelo listo para transcribir en el teléfono.", false);
                    if (pending != null && pending.equals(selected) && !textFile(pending).isFile()) {
                        transcribeSelected();
                    }
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
        else setStatus(captureStatus, "Permite el micrófono en los ajustes de Android para grabar.", true);
    }

    private void beginRecording() {
        if (state != State.IDLE) return;
        stopPlayback();
        WavRecorder capture = new WavRecorder();
        recorder = capture;
        state = State.RECORDING;
        timer.setBase(SystemClock.elapsedRealtime());
        setStatus(captureStatus, "Abriendo micrófono…", false);
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
                    setStatus(captureStatus, "Grabando…", false);
                }));
                post(() -> {
                    finishOperation("", false);
                    openRecording(wav);
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
        setStatus(captureStatus, "Guardando audio…", false);
        renderState();
    }

    private void transcribeSelected() {
        if (state != State.IDLE || selected == null || !model.installed()) return;
        File wav = selected;
        stopPlayback();
        state = State.TRANSCRIBING;
        refreshLibrary();
        renderState();
        showProgress("Abriendo Nemotron", 0);
        worker.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            try {
                Nemotron.Transcription result = model.transcribe(wav,
                        percent -> post(() -> showProgress("Transcribiendo en el teléfono", percent)));
                File destination = textFile(wav);
                File temporary = new File(recordings, destination.getName() + ".part");
                Files.write(temporary.toPath(), result.text().getBytes(StandardCharsets.UTF_8));
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                long totalMs = SystemClock.elapsedRealtime() - started;
                android.util.Log.i("TransCrypting", "Nemotron CPU: audio=" + result.audioSeconds()
                        + "s, load=" + result.modelLoadMs() + "ms, total=" + totalMs + "ms");
                String timing = String.format(Locale.getDefault(),
                        "CPU · %.1f s de audio · %.1f s en total (%.1f s de carga)",
                        result.audioSeconds(), totalMs / 1_000.0, result.modelLoadMs() / 1_000.0);
                String message = result.text().isEmpty() ? "Nemotron no detectó voz." : "Transcripción guardada.";
                post(() -> finishOperation(message + "\n" + timing, false));
            } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError error) {
                android.util.Log.w("TransCrypting", "Local transcription failed", error);
                post(() -> finishOperation(message(error, "No se pudo transcribir. La grabación sigue guardada."), true));
            }
        });
    }

    private void finishOperation(String message, boolean failed) {
        setStatus(operationStatus(), message, failed);
        state = State.IDLE;
        recorder = null;
        timer.stop();
        timer.setBase(SystemClock.elapsedRealtime());
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshModel();
        refreshLibrary();
        refreshRecording();
        renderState();
    }

    private void refreshModel() {
        modelStatus.setText(model.installed()
                ? "Disponible sin conexión"
                : "Descarga de 672 MB");
        modelStatus.setTextColor(model.installed() ? ACCENT : MUTED);
        download.setVisibility(model.installed() ? View.GONE : View.VISIBLE);
    }

    private void refreshLibrary() {
        recordingList.removeAllViews();
        File[] files = recentRecordings();
        if (files.length == 0) {
            recordingList.addView(label("Tus grabaciones aparecerán aquí.", 16, MUTED, false), spaced(6));
        }
        for (File wav : files) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(72));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(interactiveBackground(SURFACE, 18));
            row.setTag(wav);
            row.setOnClickListener(view -> openRecording(wav));
            row.setFocusable(true);

            ImageView audio = new ImageView(this);
            audio.setImageResource(R.drawable.ic_audio_lines);
            audio.setImageTintList(ColorStateList.valueOf(ACCENT));
            audio.setPadding(dp(10), dp(10), dp(10), dp(10));
            audio.setBackground(rounded(CONTROL, 12));
            LinearLayout.LayoutParams iconLayout = new LinearLayout.LayoutParams(dp(40), dp(40));
            iconLayout.setMarginEnd(dp(12));
            row.addView(audio, iconLayout);

            LinearLayout info = column();
            String name = recordingName(wav);
            File text = textFile(wav);
            String status = "Transcripción pendiente";
            if (state == State.TRANSCRIBING && wav.equals(selected)) {
                status = "Transcribiendo…";
            } else if (text.isFile()) {
                status = text.length() > 0 ? "Transcrita" : "Sin voz detectada";
            }
            info.addView(label(name, 16, INK, true));
            info.addView(label(status, 14, MUTED, false), spaced(2));
            row.addView(info, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

            String duration = formatTime(durationMs(wav));
            TextView time = label(duration, 14, MUTED, false);
            time.setTypeface(Typeface.MONOSPACE);
            setIcon(time, R.drawable.ic_arrow_right, MUTED, false);
            time.setPadding(dp(8), 0, 0, 0);
            row.addView(time);
            row.setContentDescription(name + ". " + status + ". " + duration);
            info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            time.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            recordingList.addView(row, spaced(8));
        }
    }

    private void openRecording(File wav) {
        if (!canOpenRecording(wav)) return;
        if (!wav.equals(selected)) {
            stopPlayback();
            selected = wav;
            setStatus(transcriptionStatus, "", false);
        }
        refreshRecording();
        showPage(Page.RECORDING);
    }

    private void refreshRecording() {
        if (selected == null) return;
        recordingTitle.setText(recordingName(selected));
        timeline.setMax(durationMs(selected));
        updatePlaybackTime();
        String text = "";
        File file = textFile(selected);
        boolean unreadable = false;
        if (file.isFile()) {
            try {
                text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (IOException error) {
                unreadable = true;
                setStatus(transcriptionStatus, "No se pudo abrir la transcripción guardada.", true);
            }
        }
        boolean installed = model.installed();
        copy.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        transcribe.setText(file.isFile() ? "Repetir" : "Transcribir");
        transcribe.setVisibility(installed ? View.VISIBLE : View.GONE);
        getModel.setVisibility(!installed && !file.isFile() ? View.VISIBLE : View.GONE);
        String emptyText = file.isFile() ? "Nemotron no detectó voz en este audio."
                : installed ? "Transcripción pendiente." : "Descarga Nemotron para transcribir este audio.";
        transcript.setText(text.isEmpty() && !unreadable ? emptyText : text);
        transcript.setVisibility(unreadable ? View.GONE : View.VISIBLE);
    }

    private void renderState() {
        boolean idle = state == State.IDLE;
        boolean recording = state == State.RECORDING;
        setEnabled(record, idle || recording);
        record.setText(recording ? "Detener y guardar" : "Grabar con micrófono");
        setIcon(record, recording ? R.drawable.ic_square : R.drawable.ic_mic, BACKGROUND);
        setEnabled(resources, state != State.RECORDING && state != State.SAVING);
        setEnabled(download, idle);
        setEnabled(play, idle);
        timeline.setEnabled(idle);
        setEnabled(transcribe, idle);
        setEnabled(getModel, idle);
        for (int i = 0; i < recordingList.getChildCount(); i++) {
            View row = recordingList.getChildAt(i);
            if (row.getTag() instanceof File wav) setEnabled(row, canOpenRecording(wav));
        }
        downloadProgress.setVisibility(state == State.DOWNLOADING ? View.VISIBLE : View.GONE);
        transcriptionProgress.setVisibility(state == State.TRANSCRIBING ? View.VISIBLE : View.GONE);
    }

    private void showProgress(String operation, int value) {
        ProgressBar progress = state == State.DOWNLOADING ? downloadProgress : transcriptionProgress;
        progress.setProgress(value);
        setStatus(operationStatus(), operation + " · " + value + "%", false);
    }

    private TextView operationStatus() {
        return switch (state) {
            case DOWNLOADING -> downloadStatus;
            case TRANSCRIBING -> transcriptionStatus;
            default -> captureStatus;
        };
    }

    private void setStatus(TextView status, String message, boolean error) {
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

    private boolean canOpenRecording(File wav) {
        return state == State.IDLE || (state == State.TRANSCRIBING && wav.equals(selected));
    }

    private String recordingName(File wav) {
        long modified = wav.lastModified();
        LocalDate date = Instant.ofEpochMilli(modified).atZone(ZoneId.systemDefault()).toLocalDate();
        String day = date.equals(LocalDate.now()) ? "Hoy" : DATE.format(date);
        return day + ", " + android.text.format.DateFormat.getTimeFormat(this).format(new Date(modified));
    }

    private static int durationMs(File wav) {
        return (int) (Math.max(0, wav.length() - WavRecorder.HEADER_BYTES)
                * 1_000 / (2 * WavRecorder.SAMPLE_RATE));
    }

    private static String formatTime(int milliseconds) {
        int seconds = milliseconds / 1_000;
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static File textFile(File wav) {
        return new File(wav.getParentFile(), wav.getName().replace(".wav", ".txt"));
    }

    private void togglePlayback() {
        if (player != null) {
            if (!playerReady) return;
            if (player.isPlaying()) {
                player.pause();
                setPlaying(false);
                updatePlayback();
            } else {
                player.start();
                setPlaying(true);
                updatePlayback();
            }
            return;
        }
        player = new MediaPlayer();
        try {
            player.setDataSource(selected.getAbsolutePath());
            player.setOnCompletionListener(ignored -> stopPlayback());
            player.setOnErrorListener((ignored, what, extra) -> {
                stopPlayback();
                setStatus(transcriptionStatus, "No se pudo reproducir el audio.", true);
                return true;
            });
            player.setOnPreparedListener(prepared -> {
                if (prepared != player) return;
                playerReady = true;
                if (timeline.getProgress() > 0) {
                    prepared.seekTo(timeline.getProgress(), MediaPlayer.SEEK_CLOSEST);
                }
                prepared.start();
                setPlaying(true);
                updatePlayback();
            });
            player.prepareAsync();
        } catch (IOException | IllegalStateException error) {
            stopPlayback();
            setStatus(transcriptionStatus, "No se pudo reproducir el audio.", true);
        }
    }

    private void updatePlayback() {
        timeline.removeCallbacks(playbackTick);
        if (!playerReady) return;
        if (!scrubbing) {
            timeline.setProgress(player.getCurrentPosition());
            updatePlaybackTime();
        }
        if (player.isPlaying()) timeline.postDelayed(playbackTick, 250);
    }

    private void updatePlaybackTime() {
        String time = getString(R.string.playback_time,
                formatTime(timeline.getProgress()), formatTime(timeline.getMax()));
        if (!time.contentEquals(playbackTime.getText())) playbackTime.setText(time);
    }

    private void setPlaying(boolean playing) {
        play.setText(playing ? "Pausar" : "Escuchar");
        setIcon(play, playing ? R.drawable.ic_pause : R.drawable.ic_play, BACKGROUND);
    }

    private void stopPlayback() {
        timeline.removeCallbacks(playbackTick);
        if (player != null) player.release();
        player = null;
        playerReady = false;
        scrubbing = false;
        setPlaying(false);
        timeline.setProgress(0);
        updatePlaybackTime();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (selected != null) state.putString("recording", selected.getName());
        state.putString("page", page.name());
        state.putBoolean("resourcesFromRecording", resourcesParent == Page.RECORDING);
        state.putInt("libraryScroll", page == Page.LIBRARY ? screen.getScrollY() : libraryScroll);
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

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout card() {
        LinearLayout card = column();
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
        button.setPaddingRelative(dp(14), dp(8), dp(14), dp(8));
        button.setBackground(interactiveBackground(primary ? ACCENT : CONTROL, 14));
        return button;
    }

    private Drawable interactiveBackground(int color, int radius) {
        GradientDrawable focused = rounded(color, radius);
        focused.setStroke(dp(2), color == ACCENT ? BACKGROUND : ACCENT);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[0], rounded(color, radius));
        return new RippleDrawable(ColorStateList.valueOf(0x28000000), states, null);
    }

    private void setIcon(Button button, int icon, int color) {
        setIcon(button, icon, color, true);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    }

    private void setIcon(TextView view, int icon, int color, boolean start) {
        Drawable drawable = getDrawable(icon).mutate();
        drawable.setTint(color);
        drawable.setBounds(0, 0, dp(18), dp(18));
        view.setCompoundDrawablePadding(dp(8));
        view.setCompoundDrawablesRelative(start ? drawable : null, null, start ? null : drawable, null);
    }

    private static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.55f);
    }

    private TextView statusLabel() {
        TextView status = label("", 14, MUTED, false);
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return status;
    }

    private ProgressBar progressBar() {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setMax(100);
        return bar;
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
