package app.transcrypting.mobile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.EditText;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int MIC_PERMISSION = 1;
    private static final int PICK_AUDIO = 2;
    private static final int BACKGROUND = Color.rgb(24, 28, 26);
    private static final int SURFACE = Color.rgb(36, 42, 38);
    private static final int CONTROL = Color.rgb(52, 61, 55);
    private static final int INK = Color.rgb(242, 246, 237);
    private static final int MUTED = Color.rgb(173, 187, 178);
    private static final String[] ACCENT_NAMES = {"Lima", "Cobalto", "Esmeralda", "Ámbar", "Coral", "Violeta"};
    private static final int[] ACCENTS = {0xFFC8EE26, 0xFF3B82F6, 0xFF10B981, 0xFFF59E0B, 0xFFF43F5E, 0xFF8B5CF6};
    private static final int ERROR = Color.rgb(255, 170, 162);
    private static final Locale SPANISH = Locale.forLanguageTag("es-MX");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", SPANISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", SPANISH);

    private enum State { IDLE, DOWNLOADING, RECORDING, SAVING, IMPORTING, TRANSCRIBING }
    private enum Page { LIBRARY, CAPTURE, RECORDING, CHAT, RESOURCES }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService chatWorker = Executors.newSingleThreadExecutor();
    private final List<DeepSeek.Turn> chatHistory = new ArrayList<>();
    private int accentIndex;
    private int accent;
    private State state = State.IDLE;
    private Page page = Page.LIBRARY;
    private Page resourcesParent = Page.LIBRARY;
    private OnBackInvokedCallback backCallback;
    private int libraryScroll;
    private int recordingScroll;
    private int chatRequest;
    private boolean asking;
    private Nemotron model;
    private File recordings;
    private File selected;
    private WavRecorder recorder;
    private long captureElapsed;
    private boolean captureReady;
    private boolean capturePaused;
    private MediaPlayer player;
    private boolean playerReady;
    private boolean scrubbing;
    private final Runnable playbackTick = this::updatePlayback;

    private ScrollView screen;
    private LinearLayout libraryPage;
    private LinearLayout capturePage;
    private LinearLayout recordingPage;
    private LinearLayout resourcesPage;
    private LinearLayout chatPage;
    private LinearLayout libraryControls;
    private LinearLayout captureControls;
    private LinearLayout recordingControls;
    private LinearLayout chatControls;
    private LinearLayout recordingList;
    private LinearLayout chatMessages;
    private EditText librarySearch;
    private EditText questionInput;
    private TextView headerTitle;
    private Button back;
    private Button resources;
    private Button theme;
    private Button delete;
    private Button chatSettings;
    private TextView modelStatus;
    private TextView downloadStatus;
    private TextView captureStatus;
    private TextView microphoneStatus;
    private TextView transcriptionStatus;
    private TextView keyStatus;
    private TextView chatSource;
    private TextView chatStatus;
    private TextView transcript;
    private TextView playbackTime;
    private ProgressBar downloadProgress;
    private ProgressBar transcriptionProgress;
    private SeekBar timeline;
    private Chronometer timer;
    private MicrophoneLevels microphoneLevels;
    private Button download;
    private Button record;
    private Button pauseCapture;
    private Button saveCapture;
    private Button importAudio;
    private Button play;
    private Button skipBack;
    private Button skipForward;
    private Button transcribe;
    private Button copy;
    private Button getModel;
    private Button ask;
    private Button send;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        accentIndex = Math.max(0, Math.min(ACCENTS.length - 1, getPreferences(MODE_PRIVATE).getInt("accent", 0)));
        accent = ACCENTS[accentIndex];
        model = new Nemotron(getFilesDir());
        recordings = new File(getFilesDir(), "recordings");
        createScreen();

        File[] files = recentRecordings();
        if (saved != null) {
            String lastSelection = saved.getString("recording");
            for (File file : files) {
                if (file.getName().equals(lastSelection)) selected = file;
            }
            recordingScroll = saved.getInt("recordingScroll");
            librarySearch.setText(saved.getString("librarySearch", ""));
            libraryScroll = saved.getInt("libraryScroll");
            resourcesParent = selected != null && saved.getBoolean("resourcesFromRecording")
                    ? Page.RECORDING : Page.LIBRARY;
        }
        refreshModel();
        refreshLibrary();
        refreshRecording();
        String lastPage = saved == null ? "" : saved.getString("page", "");
        showPage(Page.RESOURCES.name().equals(lastPage) ? Page.RESOURCES
                : selected != null && (Page.RECORDING.name().equals(lastPage)
                        || Page.CHAT.name().equals(lastPage)) ? Page.RECORDING : Page.LIBRARY);
        if (saved == null) handleSharedAudio(getIntent());
    }

    private void createScreen() {
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 30) {
                bottom = Math.max(bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            }
            root.setPadding(dp(20) + insets.getSystemWindowInsetLeft(),
                    dp(6) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(),
                    bottom);
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
        headerTitle.setMaxLines(2);
        headerTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        resources = button("Recursos", false);
        setIcon(resources, R.drawable.ic_settings, MUTED);
        resources.setOnClickListener(view -> openResources());
        header.addView(resources);
        theme = button("", false);
        theme.setContentDescription("Tema");
        setIcon(theme, R.drawable.ic_palette, accent);
        theme.setOnClickListener(view -> chooseThemeAccent());
        LinearLayout.LayoutParams themeLayout = new LinearLayout.LayoutParams(dp(48), dp(48));
        themeLayout.setMarginStart(dp(8));
        header.addView(theme, themeLayout);
        delete = button("", false);
        delete.setContentDescription("Eliminar grabación");
        setIcon(delete, R.drawable.ic_trash, ERROR);
        delete.setOnClickListener(view -> confirmDeleteRecording());
        header.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(48)));
        chatSettings = button("Clave", false);
        chatSettings.setOnClickListener(view -> openResources());
        header.addView(chatSettings);

        librarySearch = field("Buscar por fecha o estado", InputType.TYPE_CLASS_TEXT);
        librarySearch.setSingleLine(true);
        librarySearch.setImeOptions(EditorInfo.IME_ACTION_DONE);
        setIcon(librarySearch, R.drawable.ic_search, MUTED, true);
        root.addView(librarySearch, spaced(8));
        librarySearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                refreshLibrary();
                libraryScroll = 0;
                screen.scrollTo(0, 0);
            }
            @Override public void afterTextChanged(Editable text) {}
        });

        screen = new ScrollView(this);
        screen.setFillViewport(true);
        screen.setClipToPadding(false);
        screen.setPadding(0, dp(18), 0, dp(24));
        root.addView(screen, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout content = column();
        screen.addView(content);
        libraryPage = column();
        capturePage = column();
        recordingPage = column();
        resourcesPage = column();
        chatPage = column();
        content.addView(libraryPage);
        content.addView(recordingPage);
        content.addView(chatPage);
        content.addView(resourcesPage);
        capturePage.setPadding(0, dp(18), 0, dp(16));
        root.addView(capturePage, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        libraryControls = column();
        captureControls = column();
        recordingControls = card();
        chatControls = new LinearLayout(this);
        root.addView(libraryControls);
        root.addView(captureControls);
        root.addView(recordingControls);
        root.addView(chatControls, spaced(8));
        createLibraryPage();
        createCapturePage();
        createRecordingPage();
        createResourcesPage();
        createChatPage();
        root.requestFocus();
    }

    private void createLibraryPage() {
        captureStatus = statusLabel();
        libraryControls.addView(captureStatus);
        record = button("Grabar con micrófono", true);
        setIcon(record, R.drawable.ic_mic, BACKGROUND);
        record.setMinimumHeight(dp(56));
        record.setOnClickListener(view -> recordWithPermission());
        libraryControls.addView(record, spaced(8));
        importAudio = button("Importar audio", false);
        setIcon(importAudio, R.drawable.ic_file_music, INK);
        importAudio.setOnClickListener(view -> openAudioPicker());
        libraryControls.addView(importAudio, spaced(6));
        recordingList = column();
        libraryPage.addView(recordingList);
    }

    private void createCapturePage() {
        LinearLayout meter = card();
        capturePage.addView(meter, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        microphoneStatus = statusLabel();
        microphoneStatus.setTextSize(18);
        meter.addView(microphoneStatus);
        microphoneLevels = new MicrophoneLevels();
        meter.addView(microphoneLevels, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));
        meter.addView(label("La transcripción comienza al guardar si Nemotron está instalado.", 14, MUTED, false));

        timer = new Chronometer(this);
        timer.setGravity(Gravity.CENTER);
        timer.setTypeface(Typeface.MONOSPACE);
        timer.setTextColor(INK);
        timer.setTextSize(48);
        captureControls.addView(timer, spaced(4));
        LinearLayout buttons = new LinearLayout(this);
        pauseCapture = button("Pausar", false);
        pauseCapture.setOnClickListener(view -> toggleCapturePause());
        LinearLayout.LayoutParams pauseLayout = new LinearLayout.LayoutParams(0, dp(56), 1);
        pauseLayout.setMarginEnd(dp(8));
        buttons.addView(pauseCapture, pauseLayout);
        saveCapture = button("Guardar", true);
        saveCapture.setContentDescription("Detener y guardar");
        setIcon(saveCapture, R.drawable.ic_square, BACKGROUND);
        saveCapture.setOnClickListener(view -> stopRecording());
        buttons.addView(saveCapture, new LinearLayout.LayoutParams(0, dp(56), 1));
        captureControls.addView(buttons, spaced(12));
    }

    private void createRecordingPage() {
        timeline = new SeekBar(this);
        timeline.setContentDescription("Posición del audio");
        timeline.setProgressTintList(ColorStateList.valueOf(accent));
        timeline.setThumbTintList(ColorStateList.valueOf(accent));
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
        recordingControls.addView(timeline, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));
        playbackTime = label("", 14, MUTED, false);
        playbackTime.setTypeface(Typeface.MONOSPACE);
        recordingControls.addView(playbackTime);
        LinearLayout transport = new LinearLayout(this);
        skipBack = button("−5 s", false);
        skipBack.setContentDescription("Retroceder 5 segundos");
        skipBack.setOnClickListener(view -> seekBy(-5000));
        transport.addView(skipBack);
        play = button("Escuchar", true);
        setIcon(play, R.drawable.ic_play, BACKGROUND);
        play.setOnClickListener(view -> togglePlayback());
        LinearLayout.LayoutParams playLayout = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        playLayout.setMarginStart(dp(8));
        playLayout.setMarginEnd(dp(8));
        transport.addView(play, playLayout);
        skipForward = button("+10 s", false);
        skipForward.setContentDescription("Avanzar 10 segundos");
        skipForward.setOnClickListener(view -> seekBy(10000));
        transport.addView(skipForward);
        recordingControls.addView(transport, spaced(8));

        LinearLayout reading = card();
        recordingPage.addView(reading);
        LinearLayout transcriptHeader = new LinearLayout(this);
        transcriptHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("Transcripción", 20, INK, true);
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
        reading.addView(transcriptHeader);
        ask = button("Preguntar a DeepSeek", false);
        setIcon(ask, R.drawable.ic_message_circle, accent);
        ask.setOnClickListener(view -> {
            chatSource.setText(recordingName(selected));
            showPage(Page.CHAT);
        });
        reading.addView(ask, spaced(8));
        transcriptionStatus = statusLabel();
        reading.addView(transcriptionStatus, spaced(8));
        transcriptionProgress = progressBar();
        reading.addView(transcriptionProgress);
        transcript = label("", 17, INK, false);
        transcript.setLineSpacing(dp(5), 1f);
        transcript.setTextIsSelectable(true);
        reading.addView(transcript, spaced(12));
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
        modelStatus = label("", 15, accent, false);
        modelCard.addView(modelStatus, spaced(16));
        download = button("Descargar modelo", true);
        download.setOnClickListener(view -> downloadModel());
        modelCard.addView(download, spaced(12));
        downloadStatus = statusLabel();
        modelCard.addView(downloadStatus, spaced(8));
        downloadProgress = progressBar();
        modelCard.addView(downloadProgress);

        LinearLayout textCard = card();
        resourcesPage.addView(textCard, spaced(16));
        textCard.addView(label("Modelo de texto", 14, MUTED, true));
        textCard.addView(label("DeepSeek Flash", 26, INK, true), spaced(4));
        keyStatus = statusLabel();
        textCard.addView(keyStatus, spaced(8));
        Button configureKey = button("Configurar clave", false);
        configureKey.setOnClickListener(view -> editDeepSeekKey());
        textCard.addView(configureKey, spaced(12));
    }

    private void createChatPage() {
        LinearLayout source = card();
        chatPage.addView(source);
        chatSource = label("", 18, INK, true);
        source.addView(chatSource);
        source.addView(label("Al preguntar, se envían a DeepSeek la transcripción de esta grabación y los mensajes de este chat.",
                14, MUTED, false), spaced(4));
        chatMessages = column();
        chatPage.addView(chatMessages);
        chatStatus = statusLabel();
        chatPage.addView(chatStatus, spaced(12));

        chatControls.setGravity(Gravity.CENTER_VERTICAL);
        questionInput = field("Pregunta sobre esta grabación",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        questionInput.setSingleLine(true);
        questionInput.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        questionInput.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEND) return false;
            askQuestion();
            return true;
        });
        chatControls.addView(questionInput, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        send = button("Enviar", true);
        send.setOnClickListener(view -> askQuestion());
        LinearLayout.LayoutParams sendLayout =
                new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        sendLayout.setMarginStart(dp(8));
        chatControls.addView(send, sendLayout);
    }

    private void refreshDeepSeekKey() {
        try {
            boolean configured = !DeepSeek.readKey(this).isEmpty();
            setStatus(keyStatus, configured ? "Clave guardada en este dispositivo" : "Sin clave configurada", false);
        } catch (IOException error) {
            setStatus(keyStatus, error.getMessage(), true);
        }
    }

    private void editDeepSeekKey() {
        EditText input = field("Clave API de DeepSeek",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout body = column();
        body.setPadding(dp(24), dp(8), dp(24), 0);
        body.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Clave de DeepSeek Flash")
                .setMessage("Al enviar una pregunta, se comparte con DeepSeek el texto de la grabación abierta.")
                .setView(body)
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Quitar clave", null)
                .setPositiveButton("Guardar", null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String key = input.getText().toString().trim();
            if (key.isEmpty()) {
                input.setError("Ingresa tu clave API.");
                return;
            }
            saveDeepSeekKey(dialog, input, key);
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> saveDeepSeekKey(dialog, input, ""));
    }

    private void saveDeepSeekKey(AlertDialog dialog, EditText input, String key) {
        try {
            DeepSeek.saveKey(this, key);
            refreshDeepSeekKey();
            dialog.dismiss();
        } catch (IOException error) {
            input.setError(error.getMessage());
        }
    }

    private void askQuestion() {
        if (page != Page.CHAT || asking || ask.getVisibility() != View.VISIBLE) return;
        String question = questionInput.getText().toString().trim();
        if (question.isEmpty()) return;
        String text = transcript.getText().toString();
        List<DeepSeek.Turn> history = new ArrayList<>(chatHistory);
        int request = ++chatRequest;
        asking = true;
        setEnabled(send, false);
        questionInput.setText("");
        hideKeyboard(questionInput);
        LinearLayout pending = addChatMessage("Tú", question);
        setStatus(chatStatus, "Consultando a DeepSeek Flash…", false);
        scrollChatToEnd();
        chatWorker.execute(() -> {
            try {
                String key = DeepSeek.readKey(this);
                String answer = DeepSeek.ask(key, text, history, question);
                post(() -> {
                    if (request != chatRequest) return;
                    chatHistory.add(new DeepSeek.Turn(question, answer));
                    addChatMessage("DeepSeek Flash", answer);
                    finishQuestion("");
                });
            } catch (IOException error) {
                post(() -> {
                    if (request != chatRequest) return;
                    chatMessages.removeView(pending);
                    if (questionInput.getText().length() == 0) questionInput.setText(question);
                    finishQuestion(error.getMessage());
                });
            }
        });
    }

    private LinearLayout addChatMessage(String author, String text) {
        LinearLayout message = card();
        message.addView(label(author, 14, accent, true));
        TextView content = label(text, 16, INK, false);
        content.setTextIsSelectable(true);
        content.setLineSpacing(dp(3), 1f);
        message.addView(content, spaced(4));
        chatMessages.addView(message, spaced(12));
        return message;
    }

    private void finishQuestion(String error) {
        asking = false;
        setEnabled(send, true);
        setStatus(chatStatus, error, !error.isEmpty());
        scrollChatToEnd();
    }

    private void scrollChatToEnd() {
        screen.post(() -> {
            if (page == Page.CHAT) screen.scrollTo(0, screen.getChildAt(0).getHeight());
        });
    }

    private void clearChat() {
        chatRequest++;
        chatHistory.clear();
        chatMessages.removeAllViews();
        questionInput.setText("");
        finishQuestion("");
    }

    private void hideKeyboard(EditText input) {
        if (!input.hasFocus()) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private void showPage(Page destination) {
        if (page == Page.LIBRARY && destination != page && screen.isLaidOut()) {
            libraryScroll = screen.getScrollY();
            hideKeyboard(librarySearch);
        }
        if (page == Page.RECORDING && destination != page) {
            recordingScroll = screen.getScrollY();
            stopPlayback();
        }
        if (page == Page.CHAT && destination != page) hideKeyboard(questionInput);
        page = destination;
        libraryPage.setVisibility(page == Page.LIBRARY ? View.VISIBLE : View.GONE);
        librarySearch.setVisibility(page == Page.LIBRARY ? View.VISIBLE : View.GONE);
        libraryControls.setVisibility(page == Page.LIBRARY ? View.VISIBLE : View.GONE);
        screen.setVisibility(page == Page.CAPTURE ? View.GONE : View.VISIBLE);
        capturePage.setVisibility(page == Page.CAPTURE ? View.VISIBLE : View.GONE);
        captureControls.setVisibility(page == Page.CAPTURE ? View.VISIBLE : View.GONE);
        recordingPage.setVisibility(page == Page.RECORDING ? View.VISIBLE : View.GONE);
        recordingControls.setVisibility(page == Page.RECORDING ? View.VISIBLE : View.GONE);
        resourcesPage.setVisibility(page == Page.RESOURCES ? View.VISIBLE : View.GONE);
        chatPage.setVisibility(page == Page.CHAT ? View.VISIBLE : View.GONE);
        chatControls.setVisibility(page == Page.CHAT ? View.VISIBLE : View.GONE);
        back.setVisibility(page == Page.LIBRARY ? View.GONE : View.VISIBLE);
        resources.setVisibility(page == Page.LIBRARY ? View.VISIBLE : View.GONE);
        theme.setVisibility(page == Page.LIBRARY ? View.VISIBLE : View.GONE);
        delete.setVisibility(page == Page.RECORDING ? View.VISIBLE : View.GONE);
        chatSettings.setVisibility(page == Page.CHAT ? View.VISIBLE : View.GONE);
        headerTitle.setText(switch (page) {
            case LIBRARY -> "TransCrypting";
            case CAPTURE -> "Nueva grabación";
            case RECORDING -> recordingName(selected);
            case CHAT -> "DeepSeek Flash";
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
        if (page == Page.RESOURCES) refreshDeepSeekKey();
        screen.scrollTo(0, 0);
        if (page == Page.CHAT) scrollChatToEnd();
        screen.post(() -> {
            if (page != destination) return;
            if (page == Page.LIBRARY) screen.scrollTo(0, libraryScroll);
            if (page == Page.RECORDING) screen.scrollTo(0, recordingScroll);
        });
    }

    private void openResources() {
        resourcesParent = page;
        showPage(Page.RESOURCES);
    }

    private void chooseThemeAccent() {
        if (state != State.IDLE) return;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Color de acento")
                .setSingleChoiceItems(ACCENT_NAMES, accentIndex, (window, selectedAccent) -> {
                    window.dismiss();
                    if (selectedAccent == accentIndex) return;
                    getPreferences(MODE_PRIVATE).edit().putInt("accent", selectedAccent).apply();
                    recreate();
                })
                .setNegativeButton("Cancelar", null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent);
    }

    private void goBack() {
        if (page == Page.CAPTURE) {
            if (state == State.RECORDING) {
                new AlertDialog.Builder(this)
                        .setTitle("¿Detener y guardar?")
                        .setNegativeButton("Seguir grabando", null)
                        .setPositiveButton("Guardar", (dialog, which) -> stopRecording())
                        .show();
            }
            return;
        }
        showPage(switch (page) {
            case RESOURCES -> resourcesParent;
            case CHAT -> Page.RECORDING;
            default -> Page.LIBRARY;
        });
    }

    @Override
    @SuppressLint("GestureBackNavigation") // Android 13+ uses OnBackInvokedCallback.
    public void onBackPressed() {
        if (page == Page.LIBRARY) super.onBackPressed();
        else goBack();
    }

    @SuppressWarnings("deprecation")
    private void openAudioPicker() {
        if (state != State.IDLE) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*");
        startActivityForResult(picker, PICK_AUDIO);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_AUDIO && result == RESULT_OK && data != null && data.getData() != null) {
            importAudio(data.getData());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleSharedAudio(intent);
    }

    @SuppressWarnings("deprecation")
    private void handleSharedAudio(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class)
                : intent.getParcelableExtra(Intent.EXTRA_STREAM);
        ClipData clip = intent.getClipData();
        if (uri == null && clip != null && clip.getItemCount() > 0) uri = clip.getItemAt(0).getUri();
        if (uri != null) importAudio(uri);
        else Toast.makeText(this, "La app de origen no compartió un archivo de audio.", Toast.LENGTH_LONG).show();
    }

    private void importAudio(Uri uri) {
        if (state != State.IDLE) {
            Toast.makeText(this, "Espera a que termine la operación para importar el audio.", Toast.LENGTH_LONG).show();
            return;
        }
        state = State.IMPORTING;
        setStatus(captureStatus, "Importando audio…", false);
        showPage(Page.LIBRARY);
        worker.execute(() -> {
            try {
                Files.createDirectories(recordings.toPath());
                String name = importedAudioName(uri);
                File wav = new File(recordings, System.currentTimeMillis() + "-" + name + ".wav");
                AudioImport.convert(this, uri, wav);
                post(() -> {
                    finishOperation("", false);
                    librarySearch.setText("");
                    openRecording(wav);
                    if (model.installed()) transcribeSelected();
                });
            } catch (IOException | RuntimeException error) {
                post(() -> finishOperation(message(error, "No se pudo importar el audio."), true));
            }
        });
    }

    private String importedAudioName(Uri uri) {
        if (!"content".equals(uri.getScheme())) return "Audio importado";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                String name = cursor.getString(0);
                int extension = name.lastIndexOf('.');
                if (extension > 0) name = name.substring(0, extension);
                name = name.replace('/', '_').replace('\\', '_').replaceAll("\\p{C}", "_").trim();
                int size = name.codePointCount(0, name.length());
                if (size > 55) name = name.substring(0, name.offsetByCodePoints(0, 55));
                if (!name.isEmpty()) return name;
            }
        } catch (RuntimeException ignored) {
        }
        return "Audio importado";
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
        long startedAt = System.currentTimeMillis();
        captureReady = false;
        capturePaused = false;
        captureElapsed = 0;
        microphoneLevels.reset();
        timer.setBase(SystemClock.elapsedRealtime());
        setStatus(microphoneStatus, "Abriendo micrófono…", false);
        showPage(Page.CAPTURE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            try {
                Files.createDirectories(recordings.toPath());
                File wav = new File(recordings, startedAt + ".wav");
                capture.record(wav, () -> post(() -> {
                    if (state != State.RECORDING) return;
                    captureReady = true;
                    timer.setBase(SystemClock.elapsedRealtime());
                    timer.start();
                    setStatus(microphoneStatus, "Grabando…", false);
                    renderState();
                }), peak -> post(() -> {
                    if (recorder == capture && state == State.RECORDING && !capturePaused) {
                        microphoneLevels.add(peak);
                    }
                }));
                post(() -> {
                    finishOperation("", false);
                    openRecording(wav);
                    if (model.installed()) transcribeSelected();
                });
            } catch (IOException | RuntimeException error) {
                post(() -> {
                    finishOperation(message(error, "No se pudo guardar el audio."), true);
                    showPage(Page.LIBRARY);
                });
            }
        });
    }

    private void toggleCapturePause() {
        if (state != State.RECORDING || !captureReady) return;
        capturePaused = !capturePaused;
        recorder.setPaused(capturePaused);
        long now = SystemClock.elapsedRealtime();
        if (capturePaused) {
            captureElapsed = now - timer.getBase();
            timer.stop();
        } else {
            timer.setBase(now - captureElapsed);
            timer.start();
        }
        setStatus(microphoneStatus, capturePaused ? "En pausa" : "Grabando…", false);
        renderState();
    }

    private void stopRecording() {
        if (state != State.RECORDING) return;
        recorder.stop();
        state = State.SAVING;
        timer.stop();
        setStatus(microphoneStatus, "Guardando audio…", false);
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
                File temporary = partialTextFile(destination);
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
                post(() -> {
                    clearChat();
                    finishOperation(message + "\n" + timing, false);
                });
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
        modelStatus.setTextColor(model.installed() ? accent : MUTED);
        download.setVisibility(model.installed() ? View.GONE : View.VISIBLE);
    }

    private void refreshLibrary() {
        recordingList.removeAllViews();
        String query = librarySearch.getText().toString().trim().toLowerCase(SPANISH);
        String previousMonth = "";
        File[] files = recentRecordings();
        for (File wav : files) {
            String name = recordingName(wav);
            String date = DATE.format(recordingDate(wav));
            String month = MONTH.format(recordingDate(wav));
            File text = textFile(wav);
            String status = "Transcripción pendiente";
            if (state == State.TRANSCRIBING && wav.equals(selected)) {
                status = "Transcribiendo…";
            } else if (text.isFile()) {
                status = text.length() > 0 ? "Transcrita" : "Sin voz detectada";
            }
            if (!(name + " " + date + " " + month + " " + status).toLowerCase(SPANISH).contains(query)) continue;
            if (!month.equals(previousMonth)) {
                TextView heading = label(month.substring(0, 1).toUpperCase(SPANISH) + month.substring(1),
                        15, MUTED, true);
                if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
                recordingList.addView(heading, spaced(previousMonth.isEmpty() ? 0 : 18));
                previousMonth = month;
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(72));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(interactiveBackground(SURFACE, 18));
            row.setTag(wav);
            row.setOnClickListener(view -> openRecording(wav));
            row.setFocusable(true);
            setEnabled(row, canOpenRecording(wav));

            ImageView audio = new ImageView(this);
            audio.setImageResource(R.drawable.ic_audio_lines);
            audio.setImageTintList(ColorStateList.valueOf(accent));
            audio.setPadding(dp(10), dp(10), dp(10), dp(10));
            audio.setBackground(rounded(CONTROL, 12));
            LinearLayout.LayoutParams iconLayout = new LinearLayout.LayoutParams(dp(40), dp(40));
            iconLayout.setMarginEnd(dp(12));
            row.addView(audio, iconLayout);

            LinearLayout info = column();
            info.addView(label(name, 16, INK, true));
            String metadata = importedTitle(wav).isEmpty() ? status : date + " · " + status;
            info.addView(label(metadata, 14, MUTED, false), spaced(2));
            row.addView(info, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

            String duration = formatTime(durationMs(wav));
            TextView time = label(duration, 14, MUTED, false);
            time.setTypeface(Typeface.MONOSPACE);
            setIcon(time, R.drawable.ic_arrow_right, MUTED, false);
            time.setPadding(dp(8), 0, 0, 0);
            row.addView(time);
            row.setContentDescription(name + ". " + metadata + ". " + duration);
            info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            time.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            recordingList.addView(row, spaced(8));
        }
        if (recordingList.getChildCount() == 0) {
            String message = query.isEmpty() ? "Tus grabaciones aparecerán aquí." : "No se encontraron grabaciones.";
            recordingList.addView(label(message, 16, MUTED, false), spaced(6));
        }
    }

    private void openRecording(File wav) {
        if (!canOpenRecording(wav)) return;
        if (!wav.equals(selected)) {
            stopPlayback();
            selected = wav;
            recordingScroll = 0;
            clearChat();
            setStatus(transcriptionStatus, "", false);
        }
        refreshRecording();
        showPage(Page.RECORDING);
    }

    private void confirmDeleteRecording() {
        if (state != State.IDLE || selected == null) return;
        File wav = selected;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("¿Eliminar grabación?")
                .setMessage(recordingName(wav) + "\n\nSe borrará el audio y su transcripción de este teléfono.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (ignored, which) -> deleteRecording(wav))
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ERROR);
    }

    private void deleteRecording(File wav) {
        if (state != State.IDLE || !wav.equals(selected)) return;
        stopPlayback();
        File text = textFile(wav);
        try {
            Files.deleteIfExists(partialTextFile(text).toPath());
            Files.deleteIfExists(text.toPath());
            Files.deleteIfExists(wav.toPath());
        } catch (IOException error) {
            refreshLibrary();
            refreshRecording();
            setStatus(transcriptionStatus, "No se pudo eliminar la grabación.", true);
            return;
        }
        clearChat();
        selected = null;
        refreshLibrary();
        showPage(Page.LIBRARY);
        Toast.makeText(this, "Grabación eliminada", Toast.LENGTH_SHORT).show();
    }

    private void refreshRecording() {
        if (selected == null) return;
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
        ask.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
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
        setEnabled(record, idle);
        setEnabled(importAudio, idle);
        setEnabled(theme, idle);
        setEnabled(pauseCapture, recording && captureReady);
        setEnabled(saveCapture, recording && captureReady);
        pauseCapture.setText(capturePaused ? "Reanudar" : "Pausar");
        setIcon(pauseCapture, capturePaused ? R.drawable.ic_play : R.drawable.ic_pause, INK);
        setEnabled(resources, state != State.RECORDING && state != State.SAVING);
        setEnabled(delete, idle);
        setEnabled(download, idle);
        setEnabled(play, idle);
        setEnabled(skipBack, idle);
        setEnabled(skipForward, idle);
        setEnabled(ask, idle);
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
        String title = importedTitle(wav);
        if (!title.isEmpty()) return title;
        long modified = wav.lastModified();
        LocalDate date = recordingDate(wav);
        String day = date.equals(LocalDate.now()) ? "Hoy" : DATE.format(date);
        return day + ", " + android.text.format.DateFormat.getTimeFormat(this).format(new Date(modified));
    }

    private static String importedTitle(File wav) {
        String name = wav.getName();
        int separator = name.indexOf('-');
        return separator < 0 ? "" : name.substring(separator + 1, name.length() - 4);
    }

    private static LocalDate recordingDate(File wav) {
        return Instant.ofEpochMilli(wav.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate();
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
        String name = wav.getName();
        return new File(wav.getParentFile(), name.substring(0, name.length() - 4) + ".txt");
    }

    private static File partialTextFile(File text) {
        return new File(text.getPath() + ".part");
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

    private void seekBy(int delta) {
        int position = Math.max(0, Math.min(timeline.getMax(), timeline.getProgress() + delta));
        timeline.setProgress(position);
        if (playerReady) player.seekTo(position, MediaPlayer.SEEK_CLOSEST);
        updatePlaybackTime();
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
        state.putBoolean("resourcesFromRecording", resourcesParent == Page.RECORDING || resourcesParent == Page.CHAT);
        state.putInt("libraryScroll", page == Page.LIBRARY ? screen.getScrollY() : libraryScroll);
        state.putInt("recordingScroll", page == Page.RECORDING ? screen.getScrollY() : recordingScroll);
        state.putString("librarySearch", librarySearch.getText().toString());
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
        chatWorker.shutdownNow();
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
        button.setBackground(interactiveBackground(primary ? accent : CONTROL, 14));
        return button;
    }

    private EditText field(String hint, int type) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(type);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(15);
        input.setMinimumHeight(dp(48));
        input.setPaddingRelative(dp(14), dp(8), dp(14), dp(8));
        input.setBackground(interactiveBackground(CONTROL, 14));
        return input;
    }

    private Drawable interactiveBackground(int color, int radius) {
        GradientDrawable focused = rounded(color, radius);
        focused.setStroke(dp(2), color == accent ? BACKGROUND : accent);
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
        bar.setProgressTintList(ColorStateList.valueOf(accent));
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

    private final class MicrophoneLevels extends View {
        private final int[] peaks = new int[32];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int next;

        MicrophoneLevels() {
            super(MainActivity.this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setColor(accent);
        }

        void add(int peak) {
            peaks[next] = peak;
            next = (next + 1) % peaks.length;
            invalidate();
        }

        void reset() {
            Arrays.fill(peaks, 0);
            next = 0;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float step = (float) getWidth() / peaks.length;
            float center = getHeight() / 2f;
            float radius = step * 0.23f;
            for (int i = 0; i < peaks.length; i++) {
                int peak = peaks[(next + i) % peaks.length];
                float height = Math.max(dp(2), (float) Math.sqrt(peak / 32768.0) * center * 0.82f);
                float x = (i + 0.5f) * step;
                paint.setAlpha(peak == 0 ? 70 : 240);
                canvas.drawRoundRect(x - radius, center - height, x + radius, center + height,
                        radius, radius, paint);
            }
        }
    }
}
