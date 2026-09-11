package helper.journey.starsavior;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    static final String ACTION_REQUEST_CAPTURE = BuildConfig.APPLICATION_ID + ".REQUEST_CAPTURE";

    private static final int REQUEST_OVERLAY = 1001;
    private static final int REQUEST_CAPTURE = 1002;
    private static final int REQUEST_NOTIFICATIONS = 1003;
    private static final String GAME_PACKAGE = "com.studiobside.starMain";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private TextView overlayState;
    private TextView captureState;
    private TextView dataState;
    private TextView startButton;
    private TextView updateButton;
    private TextView stopButton;
    private TextView circleSizeValue;
    private BubbleIconView bubblePreview;
    private boolean continueAfterOverlaySettings;
    private boolean requestCaptureOnResume;
    private boolean databaseUpdateAvailable;
    private boolean databaseAppUpdateRequired;
    private volatile boolean destroyed;
    private final Runnable appearanceUpdate = this::notifyBubbleAppearanceChanged;
    private final Runnable captureRequest = this::consumeCaptureRequest;

    @Override
    protected void attachBaseContext(Context context) {
        super.attachBaseContext(AppLanguage.wrap(context));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(buildContent());
            configureSystemBars();
            loadDataSummary();
            checkDatabaseUpdateOnLaunch();
            handleLaunchIntent(getIntent());
        } catch (Throwable error) {
            showStartupRecovery(error);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (overlayState == null || captureState == null || startButton == null || stopButton == null) return;
        refreshStatus();
        if (requestCaptureOnResume) {
            scheduleCaptureRequest();
            return;
        }
        if (continueAfterOverlaySettings && Settings.canDrawOverlays(this)) {
            continueAfterOverlaySettings = false;
            mainHandler.postDelayed(this::continueStartFlow, 250);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
        scheduleCaptureRequest();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(appearanceUpdate);
        mainHandler.removeCallbacks(captureRequest);
        loader.shutdown();
        super.onDestroy();
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(23, 20, 43));
        window.setNavigationBarColor(Ui.BG);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
    }

    private void showStartupRecovery(Throwable error) {
        String details = Log.getStackTraceString(error);
        try {
            int side = Math.round(24 * getResources().getDisplayMetrics().density);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(side, side, side, side);
            root.setBackgroundColor(Color.rgb(14, 13, 24));

            TextView title = new TextView(this);
            title.setText(getString(R.string.startup_failed));
            title.setTextSize(23);
            title.setTextColor(Color.WHITE);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            root.addView(title, new LinearLayout.LayoutParams(-1, -2));

            TextView message = new TextView(this);
            message.setText(String.format(AppLanguage.of(this).locale(),
                    getString(R.string.startup_recovery),
                    error.getClass().getSimpleName(), String.valueOf(error.getMessage())));
            message.setTextSize(15);
            message.setTextColor(Color.rgb(205, 200, 226));
            message.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
            messageParams.setMargins(0, side / 2, 0, side);
            root.addView(message, messageParams);

            TextView copy = new TextView(this);
            copy.setText(getString(R.string.copy_error));
            copy.setTextSize(16);
            copy.setTextColor(Color.WHITE);
            copy.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            copy.setGravity(Gravity.CENTER);
            copy.setPadding(side, side / 2, side, side / 2);
            copy.setBackgroundColor(Color.rgb(99, 80, 222));
            copy.setClickable(true);
            copy.setOnClickListener(view -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.error_clipboard), details));
                Toast.makeText(this, getString(R.string.error_copied), Toast.LENGTH_SHORT).show();
            });
            root.addView(copy, new LinearLayout.LayoutParams(-1, -2));
            setContentView(root);
        } catch (Throwable ignored) {
            TextView fallback = new TextView(this);
            fallback.setText(String.format(AppLanguage.of(this).locale(), getString(R.string.startup_fallback),
                    error.getClass().getSimpleName()));
            fallback.setTextColor(Color.WHITE);
            fallback.setTextSize(18);
            fallback.setGravity(Gravity.CENTER);
            fallback.setBackgroundColor(Color.BLACK);
            setContentView(fallback);
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 22);
        root.setPadding(side, Ui.dp(this, 28), side, Ui.dp(this, 36));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = Ui.text(this, getString(R.string.app_eyebrow), 12, Ui.PRIMARY);
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.14f);
        root.addView(eyebrow);

        TextView title = Ui.text(this, getString(R.string.app_name), 32, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, marginParams(-1, -2, 0, 7, 0, 0));

        TextView subtitle = Ui.text(this, getString(R.string.app_subtitle), 15, Ui.MUTED);
        subtitle.setLineSpacing(0, 1.18f);
        root.addView(subtitle, marginParams(-1, -2, 0, 0, 0, 24));

        root.addView(buildLanguageCard(), marginParams(-1, -2, 0, 0, 0, 18));

        LinearLayout statusCard = card();
        TextView statusTitle = Ui.text(this, getString(R.string.ready_status), 17, Ui.TEXT);
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusCard.addView(statusTitle, marginParams(-1, -2, 0, 0, 0, 12));
        overlayState = statusRow(statusCard, getString(R.string.overlay_permission), false);
        captureState = statusRow(statusCard, getString(R.string.capture_service), false);
        dataState = statusRow(statusCard, getString(R.string.checking_data), false);
        root.addView(statusCard, marginParams(-1, -2, 0, 0, 0, 18));

        root.addView(buildAppearanceCard(), marginParams(-1, -2, 0, 0, 0, 18));

        startButton = Ui.button(this, getString(R.string.start_helper), true);
        startButton.setOnClickListener(v -> startFlow());
        root.addView(startButton, marginParams(-1, -2, 0, 0, 0, 10));

        updateButton = Ui.button(this,
                BuildConfig.BUNDLED_TEST_DATABASE ? getString(R.string.test_bundled) : getString(R.string.update_database), false);
        if (BuildConfig.BUNDLED_TEST_DATABASE) {
            updateButton.setEnabled(false);
            updateButton.setAlpha(0.65f);
        } else {
            updateButton.setOnClickListener(v -> updateDatabase());
        }
        root.addView(updateButton, marginParams(-1, -2, 0, 0, 0, 10));

        stopButton = Ui.button(this, getString(R.string.stop_overlay), false);
        stopButton.setOnClickListener(v -> {
            Intent stop = new Intent(this, OverlayCaptureService.class).setAction(OverlayCaptureService.ACTION_STOP);
            startService(stop);
            mainHandler.postDelayed(this::refreshStatus, 250);
        });
        root.addView(stopButton, marginParams(-1, -2, 0, 0, 0, 22));

        LinearLayout howTo = card();
        TextView howTitle = Ui.text(this, getString(R.string.how_to), 17, Ui.TEXT);
        howTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        howTo.addView(howTitle, marginParams(-1, -2, 0, 0, 0, 10));
        howTo.addView(body(getString(R.string.how_step_1)));
        howTo.addView(body(getString(R.string.how_step_2)));
        howTo.addView(body(getString(R.string.how_step_3)));
        howTo.addView(body(getString(R.string.how_step_4)));
        root.addView(howTo, marginParams(-1, -2, 0, 0, 0, 14));

        LinearLayout privacy = card();
        privacy.setBackground(Ui.roundedStroke(this, Color.rgb(24, 41, 45), 20, Color.rgb(48, 94, 91), 1));
        TextView privacyTitle = Ui.text(this, getString(R.string.privacy_title), 16, Ui.GREEN);
        privacyTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        privacy.addView(privacyTitle, marginParams(-1, -2, 0, 0, 0, 7));
        TextView privacyBody = body(getString(R.string.privacy_body));
        privacy.addView(privacyBody);
        root.addView(privacy, marginParams(-1, -2, 0, 0, 0, 14));

        TextView source = Ui.text(this, getString(R.string.database_source), 14, Ui.BLUE);
        source.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        source.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse(AppLanguage.of(this).databaseUrl()))));
        root.addView(source);

        TextView openSource = Ui.text(this, getString(R.string.source_privacy), 14, Ui.BLUE);
        openSource.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        openSource.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.source_code_url)))));
        root.addView(openSource);

        TextView disclaimer = Ui.text(this, getString(R.string.disclaimer), 12, Color.rgb(125, 121, 151));
        disclaimer.setLineSpacing(0, 1.15f);
        root.addView(disclaimer, marginParams(-1, -2, 4, 4, 4, 0));
        return scroll;
    }

    private View buildLanguageCard() {
        LinearLayout languageCard = card();
        TextView select = Ui.button(this, getString(R.string.language_title) + ": " + (AppLanguage.preference(this).isEmpty()
                ? getString(R.string.system_language) + " · " : "") + AppLanguage.of(this).nativeName(), false);
        select.setOnClickListener(view -> {
            GameLanguage[] languages = GameLanguage.values();
            String[] labels = new String[languages.length + 1];
            labels[0] = getString(R.string.system_language);
            String saved = AppLanguage.preference(this);
            int checked = 0;
            for (int index = 0; index < languages.length; index++) {
                labels[index + 1] = languages[index].nativeName();
                if (languages[index].tag.equals(saved)) checked = index + 1;
            }
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.language_title))
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        String next = which == 0 ? "" : languages[which - 1].tag;
                        dialog.dismiss();
                        if (next.equals(saved)) return;
                        AppLanguage.setLanguage(this, next);
                        requestCaptureOnResume = false;
                        continueAfterOverlaySettings = false;
                        mainHandler.removeCallbacks(captureRequest);
                        if (OverlayCaptureService.isRunning()) {
                            startService(new Intent(this, OverlayCaptureService.class)
                                    .setAction(OverlayCaptureService.ACTION_CHANGE_LANGUAGE));
                        }
                        recreate();
                    })
                    .setNegativeButton(getString(R.string.close), null)
                    .show();
        });
        languageCard.addView(select, new LinearLayout.LayoutParams(-1, -2));
        languageCard.addView(body(getString(R.string.language_help)),
                marginParams(-1, -2, 0, 8, 0, 0));
        return languageCard;
    }

    private View buildAppearanceCard() {
        LinearLayout appearance = card();

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, getString(R.string.floating_icon), 17, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView description = body(getString(R.string.icon_description));
        copy.addView(description, marginParams(-1, -2, 0, 4, 12, 0));
        heading.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        bubblePreview = new BubbleIconView(this);
        int touchSize = Ui.dp(this, BubbleIconView.TOUCH_SIZE_DP);
        heading.addView(bubblePreview, new LinearLayout.LayoutParams(touchSize, touchSize));
        appearance.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        int savedProgress = BubbleAppearance.loadCircleProgress(this);
        bubblePreview.setCircleProgress(savedProgress);

        circleSizeValue = Ui.text(this, circleSizeLabel(savedProgress), 14, Ui.MUTED);
        circleSizeValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        appearance.addView(circleSizeValue, marginParams(-1, -2, 0, 12, 0, 0));

        SeekBar slider = new SeekBar(this);
        slider.setMax(BubbleAppearance.MAX_PROGRESS);
        slider.setProgress(savedProgress);
        slider.setContentDescription(getString(R.string.icon_size_accessibility));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                bubblePreview.setCircleProgress(progress);
                circleSizeValue.setText(circleSizeLabel(progress));
                if (!fromUser) return;
                BubbleAppearance.saveCircleProgress(MainActivity.this, progress);
                mainHandler.removeCallbacks(appearanceUpdate);
                mainHandler.postDelayed(appearanceUpdate, 70);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mainHandler.removeCallbacks(appearanceUpdate);
                notifyBubbleAppearanceChanged();
            }
        });
        appearance.addView(slider, marginParams(-1, -2, -8, 3, -8, 0));

        LinearLayout endpoints = new LinearLayout(this);
        endpoints.setOrientation(LinearLayout.HORIZONTAL);
        TextView minimum = Ui.text(this, getString(R.string.icon_minimum), 12, Color.rgb(134, 129, 160));
        TextView maximum = Ui.text(this, getString(R.string.icon_maximum), 12, Color.rgb(134, 129, 160));
        maximum.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        endpoints.addView(minimum, new LinearLayout.LayoutParams(0, -2, 1f));
        endpoints.addView(maximum, new LinearLayout.LayoutParams(0, -2, 1f));
        appearance.addView(endpoints, new LinearLayout.LayoutParams(-1, -2));
        return appearance;
    }

    private String circleSizeLabel(int progress) {
        if (progress <= BubbleAppearance.MIN_PROGRESS) return getString(R.string.circle_minimum);
        if (progress >= BubbleAppearance.MAX_PROGRESS) return getString(R.string.circle_maximum);
        return String.format(AppLanguage.of(this).locale(), getString(R.string.circle_size), progress);
    }

    private void notifyBubbleAppearanceChanged() {
        if (destroyed || !OverlayCaptureService.isRunning()) return;
        try {
            startService(new Intent(this, OverlayCaptureService.class)
                    .setAction(OverlayCaptureService.ACTION_UPDATE_APPEARANCE));
        } catch (RuntimeException ignored) {}
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null || !ACTION_REQUEST_CAPTURE.equals(intent.getAction())) return;
        intent.setAction(null);
        requestCaptureOnResume = true;
    }

    private void scheduleCaptureRequest() {
        mainHandler.removeCallbacks(captureRequest);
        mainHandler.postDelayed(captureRequest, 180);
    }

    private void consumeCaptureRequest() {
        if (destroyed || !requestCaptureOnResume) return;
        requestCaptureOnResume = false;
        startFlow();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 17), Ui.dp(this, 18), Ui.dp(this, 17));
        card.setBackground(Ui.rounded(this, Ui.CARD, 20));
        return card;
    }

    private TextView statusRow(LinearLayout parent, String label, boolean ok) {
        TextView row = Ui.text(this, "", 14, Ui.MUTED);
        row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        row.setTag(label);
        setStatus(row, label, ok);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private void setStatus(TextView view, String label, boolean ok) {
        view.setText(String.format(AppLanguage.of(this).locale(), "%s  %s", ok ? "●" : "○", label));
        view.setTextColor(ok ? Ui.GREEN : Ui.MUTED);
    }

    private TextView body(String value) {
        TextView body = Ui.text(this, value, 14, Ui.MUTED);
        body.setLineSpacing(0, 1.25f);
        body.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        return body;
    }

    private LinearLayout.LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(Ui.dp(this, left), Ui.dp(this, top), Ui.dp(this, right), Ui.dp(this, bottom));
        return params;
    }

    private void loadDataSummary() {
        loader.execute(() -> {
            try {
                JourneyModels.Data data = JourneyRepository.load(this);
                mainHandler.post(() -> showDataSummary(data));
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!destroyed) setStatus(dataState, getString(R.string.data_error), false);
                });
            }
        });
    }

    private void checkDatabaseUpdateOnLaunch() {
        if (BuildConfig.BUNDLED_TEST_DATABASE) return;
        loader.execute(() -> {
            try {
                JourneyDatabaseUpdater.CheckResult result =
                        JourneyDatabaseUpdater.checkForUpdate(this, false);
                mainHandler.post(() -> showUpdateCheckResult(result));
            } catch (Exception ignored) {
                // Automatic checks never interrupt startup or replace the usable local DB state.
            }
        });
    }

    private void showUpdateCheckResult(JourneyDatabaseUpdater.CheckResult result) {
        if (destroyed || result == null || result.busy || dataState == null) return;
        databaseUpdateAvailable = result.available;
        databaseAppUpdateRequired = result.incompatible;
        if (result.incompatible) {
            setStatus(dataState, getString(R.string.new_db_requires_app), false);
            dataState.setTextColor(Ui.ORANGE);
        } else if (result.available && result.manifest != null) {
            String summary = String.format(
                    AppLanguage.of(this).locale(),
                    getString(R.string.new_db_summary),
                    result.manifest.choiceCount,
                    formatDatabaseDate(result.manifest.generatedAt));
            setStatus(dataState, summary, false);
            dataState.setTextColor(Ui.ORANGE);
        } else if (result.networkChecked && result.current != null) {
            showDataSummary(result.current);
        }
        setUpdateBusy(false);
    }

    private void showDataSummary(JourneyModels.Data data) {
        if (destroyed || dataState == null) return;
        if (JourneyRepository.isExampleDatabase(data)) {
            setStatus(dataState, getString(R.string.example_warning), false);
            dataState.setTextColor(Ui.ORANGE);
            return;
        }
        String date = formatDatabaseDate(data.generatedAt);
        String kind = BuildConfig.BUNDLED_TEST_DATABASE
                ? getString(R.string.db_test_kind)
                : JourneyRepository.hasDownloadedDatabase(this) ? getString(R.string.db_updated_kind) : getString(R.string.db_bundled_kind);
        String summary = String.format(AppLanguage.of(this).locale(), getString(R.string.db_summary), kind, data.choiceCount, date);
        setStatus(dataState, summary, true);
    }

    private String formatDatabaseDate(String generatedAt) {
        String date = generatedAt == null || generatedAt.isEmpty() ? getString(R.string.date_unknown) : generatedAt;
        try {
            Instant instant = Instant.parse(generatedAt);
            date = DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                    .withLocale(AppLanguage.of(this).locale())
                    .withZone(ZoneId.systemDefault())
                    .format(instant);
        } catch (Exception ignored) {}
        return date;
    }

    private void updateDatabase() {
        if (BuildConfig.BUNDLED_TEST_DATABASE) {
            Toast.makeText(this, getString(R.string.test_uses_bundled), Toast.LENGTH_SHORT).show();
            return;
        }
        if (JourneyDatabaseUpdater.isUpdating()) {
            Toast.makeText(this, getString(R.string.update_already_busy), Toast.LENGTH_SHORT).show();
            return;
        }
        setUpdateBusy(true);
        loader.execute(() -> {
            try {
                JourneyDatabaseUpdater.UpdateResult result = JourneyDatabaseUpdater.update(this,
                        message -> mainHandler.post(() -> {
                            if (!destroyed && dataState != null) setStatus(dataState, message, false);
                        }));

                if (result.data != null && OverlayCaptureService.isRunning()) {
                    try {
                        startService(new Intent(this, OverlayCaptureService.class)
                                .setAction(OverlayCaptureService.ACTION_RELOAD_DATA));
                    } catch (RuntimeException ignored) {}
                }
                mainHandler.post(() -> {
                    if (destroyed) return;
                    if (result.incompatible) {
                        databaseUpdateAvailable = false;
                        databaseAppUpdateRequired = true;
                    } else {
                        databaseUpdateAvailable = false;
                        databaseAppUpdateRequired = false;
                    }
                    setUpdateBusy(false);
                    if (result.incompatible) {
                        setStatus(dataState, getString(R.string.new_db_requires_app), false);
                        dataState.setTextColor(Ui.ORANGE);
                    } else if (result.data != null) {
                        showDataSummary(result.data);
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(result.changed ? getString(R.string.update_complete) : getString(R.string.update_database))
                            .setMessage(result.message(this))
                            .setPositiveButton(getString(R.string.confirm), null)
                            .show();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    setUpdateBusy(false);
                    loadDataSummary();
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.update_failed))
                            .setMessage(friendlyUpdateError(error))
                            .setPositiveButton(getString(R.string.confirm), null)
                            .show();
                });
            }
        });
    }

    private void setUpdateBusy(boolean busy) {
        if (updateButton == null) return;
        updateButton.setEnabled(!busy);
        updateButton.setAlpha(busy ? 0.55f : 1f);
        if (!busy && (databaseUpdateAvailable || databaseAppUpdateRequired)) {
            Ui.styleAttentionButton(this, updateButton);
        } else {
            Ui.styleSecondaryButton(this, updateButton);
        }
        if (busy) {
            updateButton.setText(getString(R.string.update_busy_ellipsis));
        } else if (databaseAppUpdateRequired) {
            updateButton.setText(getString(R.string.app_update_required));
        } else if (databaseUpdateAvailable) {
            updateButton.setText(getString(R.string.get_new_db));
        } else {
            updateButton.setText(getString(R.string.update_database));
        }
    }

    private String friendlyUpdateError(Exception error) {
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) detail = error.getClass().getSimpleName();
        return getString(R.string.update_error_detail) + detail;
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean running = OverlayCaptureService.isRunning();
        boolean captureActive = OverlayCaptureService.isCaptureActive();
        setStatus(overlayState, overlay ? getString(R.string.overlay_allowed) : getString(R.string.overlay_required), overlay);
        if (running && captureActive) {
            setStatus(captureState, getString(R.string.capture_running), true);
        } else if (running) {
            setStatus(captureState, getString(R.string.capture_waiting), false);
            captureState.setTextColor(Ui.ORANGE);
        } else {
            setStatus(captureState, getString(R.string.capture_stopped), false);
        }
        startButton.setText(running
                ? (captureActive ? getString(BuildConfig.BUNDLED_TEST_DATABASE
                        ? R.string.test_return_to_image : R.string.open_game) : getString(R.string.reconnect_capture))
                : getString(R.string.start_helper));
        Ui.setVisible(stopButton, running);
    }

    private void startFlow() {
        if (OverlayCaptureService.isRunning() && OverlayCaptureService.isCaptureActive()) {
            launchGame();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            continueAfterOverlaySettings = true;
            openOverlayPermissionSettings();
            return;
        }
        continueStartFlow();
    }

    private void openOverlayPermissionSettings() {
        Uri packageUri = Uri.fromParts("package", getPackageName(), null);
        if (tryOpenSettings(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri))) return;
        if (tryOpenSettings(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))) return;

        continueAfterOverlaySettings = false;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.overlay_settings_failed))
                        .setMessage(getString(R.string.overlay_settings_help))
                .setNegativeButton(getString(R.string.close), null)
                .setPositiveButton(getString(R.string.open_settings), (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (ActivityNotFoundException | SecurityException ignored) {
                        Toast.makeText(this, getString(R.string.open_settings_manually), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private boolean tryOpenSettings(Intent intent) {
        try {
            startActivityForResult(intent, REQUEST_OVERLAY);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }

    private void continueStartFlow() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) requestScreenCapture();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE) {
            ScreenCapturePermissionDecision.Action decision =
                    ScreenCapturePermissionDecision.fromResult(
                            resultCode == RESULT_OK, data != null);
            if (decision != ScreenCapturePermissionDecision.Action.START_CAPTURE_SERVICE) {
                Toast.makeText(this, getString(R.string.capture_permission_help), Toast.LENGTH_LONG).show();
                return;
            }
            Intent service = new Intent(this, OverlayCaptureService.class)
                    .setAction(OverlayCaptureService.ACTION_START)
                    .putExtra(OverlayCaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(OverlayCaptureService.EXTRA_RESULT_DATA, data);
            startForegroundService(service);
            mainHandler.postDelayed(() -> {
                refreshStatus();
                launchGame();
            }, 600);
        }
    }

    private void launchGame() {
        if (BuildConfig.BUNDLED_TEST_DATABASE) {
            Toast.makeText(this, getString(R.string.test_open_gallery), Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(GAME_PACKAGE);
        if (launch == null) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.game_not_found))
                    .setMessage(getString(R.string.launch_game_manually))
                    .setPositiveButton(getString(R.string.confirm), null)
                    .show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(launch);
    }
}
