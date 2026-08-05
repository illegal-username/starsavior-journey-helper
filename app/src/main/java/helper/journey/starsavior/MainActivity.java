package helper.journey.starsavior;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
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
    private volatile boolean destroyed;
    private final Runnable appearanceUpdate = this::notifyBubbleAppearanceChanged;
    private final Runnable captureRequest = this::consumeCaptureRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(buildContent());
            configureSystemBars();
            loadDataSummary();
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
            title.setText("앱 화면을 준비하지 못했습니다");
            title.setTextSize(23);
            title.setTextColor(Color.WHITE);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            root.addView(title, new LinearLayout.LayoutParams(-1, -2));

            TextView message = new TextView(this);
            message.setText(String.format(Locale.KOREA,
                    "앱이 종료되지 않도록 복구 화면을 열었습니다. 아래 버튼으로 오류 정보를 복사해 전달해 주세요.\n\n%s: %s",
                    error.getClass().getSimpleName(), String.valueOf(error.getMessage())));
            message.setTextSize(15);
            message.setTextColor(Color.rgb(205, 200, 226));
            message.setLineSpacing(0, 1.2f);
            LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, -2);
            messageParams.setMargins(0, side / 2, 0, side);
            root.addView(message, messageParams);

            TextView copy = new TextView(this);
            copy.setText("오류 정보 복사");
            copy.setTextSize(16);
            copy.setTextColor(Color.WHITE);
            copy.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            copy.setGravity(Gravity.CENTER);
            copy.setPadding(side, side / 2, side, side / 2);
            copy.setBackgroundColor(Color.rgb(99, 80, 222));
            copy.setClickable(true);
            copy.setOnClickListener(view -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("스세 여정 도우미 오류", details));
                Toast.makeText(this, "오류 정보를 복사했습니다.", Toast.LENGTH_SHORT).show();
            });
            root.addView(copy, new LinearLayout.LayoutParams(-1, -2));
            setContentView(root);
        } catch (Throwable ignored) {
            TextView fallback = new TextView(this);
            fallback.setText(String.format(Locale.KOREA, "스세 여정 도우미를 시작하지 못했습니다.\n%s",
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

        TextView eyebrow = Ui.text(this, "STAR SAVIOR · JOURNEY", 12, Ui.PRIMARY);
        eyebrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.14f);
        root.addView(eyebrow);

        TextView title = Ui.text(this, "스세 여정 도우미", 32, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, marginParams(-1, -2, 0, 7, 0, 0));

        TextView subtitle = Ui.text(this, "게임 화면의 선택지를 읽고, 보상과 실패 효과만 빠르게 보여줍니다.", 15, Ui.MUTED);
        subtitle.setLineSpacing(0, 1.18f);
        root.addView(subtitle, marginParams(-1, -2, 0, 0, 0, 24));

        LinearLayout statusCard = card();
        TextView statusTitle = Ui.text(this, "준비 상태", 17, Ui.TEXT);
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusCard.addView(statusTitle, marginParams(-1, -2, 0, 0, 0, 12));
        overlayState = statusRow(statusCard, "다른 앱 위에 표시", false);
        captureState = statusRow(statusCard, "화면 읽기 서비스", false);
        dataState = statusRow(statusCard, "선택지 데이터 확인 중", false);
        root.addView(statusCard, marginParams(-1, -2, 0, 0, 0, 18));

        root.addView(buildAppearanceCard(), marginParams(-1, -2, 0, 0, 0, 18));

        startButton = Ui.button(this, "권한 설정하고 시작", true);
        startButton.setOnClickListener(v -> startFlow());
        root.addView(startButton, marginParams(-1, Ui.dp(this, 56), 0, 0, 0, 10));

        updateButton = Ui.button(this, "DB 업데이트", false);
        updateButton.setOnClickListener(v -> updateDatabase());
        root.addView(updateButton, marginParams(-1, Ui.dp(this, 52), 0, 0, 0, 10));

        stopButton = Ui.button(this, "오버레이 종료", false);
        stopButton.setOnClickListener(v -> {
            Intent stop = new Intent(this, OverlayCaptureService.class).setAction(OverlayCaptureService.ACTION_STOP);
            startService(stop);
            mainHandler.postDelayed(this::refreshStatus, 250);
        });
        root.addView(stopButton, marginParams(-1, Ui.dp(this, 52), 0, 0, 0, 22));

        LinearLayout howTo = card();
        TextView howTitle = Ui.text(this, "사용법", 17, Ui.TEXT);
        howTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        howTo.addView(howTitle, marginParams(-1, -2, 0, 0, 0, 10));
        howTo.addView(body("1. 시작을 누르고 Android의 화면 공유 창에서 스타 세이비어를 선택합니다."));
        howTo.addView(body("2. 여정 선택지가 나타나면 화면 가장자리의 ✦ 아이콘을 한 번 누릅니다."));
        howTo.addView(body("3. 결과 카드는 선택지를 가리지 않도록 왼쪽에 표시됩니다. 아이콘은 드래그해 옮길 수 있습니다."));
        howTo.addView(body("4. ✦ 아이콘을 길게 누르면 DB 업데이트와 도우미 종료 메뉴가 열립니다."));
        root.addView(howTo, marginParams(-1, -2, 0, 0, 0, 14));

        LinearLayout privacy = card();
        privacy.setBackground(Ui.roundedStroke(this, Color.rgb(24, 41, 45), 20, Color.rgb(48, 94, 91), 1));
        TextView privacyTitle = Ui.text(this, "화면 내용은 기기 안에서만 처리", 16, Ui.GREEN);
        privacyTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        privacy.addView(privacyTitle, marginParams(-1, -2, 0, 0, 0, 7));
        TextView privacyBody = body("캡처 이미지와 인식한 글자는 저장하거나 전송하지 않습니다. 광고·자체 추적 서버는 없습니다. DB 업데이트 시 원자료에 접속하며, ML Kit SDK는 호환성 정보와 성능 지표를 위해 Google과 통신할 수 있습니다.");
        privacy.addView(privacyBody);
        root.addView(privacy, marginParams(-1, -2, 0, 0, 0, 14));

        TextView source = Ui.text(this, "원자료: 스타 세이비어 DB  ↗", 14, Ui.BLUE);
        source.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        source.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://star-savior-arcana-db.pages.dev/journey"))));
        root.addView(source);

        TextView openSource = Ui.text(this, "오픈소스·개인정보 안내  ↗", 14, Ui.BLUE);
        openSource.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        openSource.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.source_code_url)))));
        root.addView(openSource);

        TextView disclaimer = Ui.text(this, "비공식 팬 도우미이며 STUDIOBSIDE 및 원자료 사이트와 제휴·보증 관계가 없습니다.", 12, Color.rgb(125, 121, 151));
        disclaimer.setLineSpacing(0, 1.15f);
        root.addView(disclaimer, marginParams(-1, -2, 4, 4, 4, 0));
        return scroll;
    }

    private View buildAppearanceCard() {
        LinearLayout appearance = card();

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = Ui.text(this, "플로팅 아이콘", 17, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copy.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView description = body("흰색 ✦와 터치 영역은 그대로 두고 보라색 원만 조절합니다.");
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
        slider.setContentDescription("플로팅 아이콘 보라색 원 크기");
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
        TextView minimum = Ui.text(this, "✦에 닿는 최소", 12, Color.rgb(134, 129, 160));
        TextView maximum = Ui.text(this, "기존 최대", 12, Color.rgb(134, 129, 160));
        maximum.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        endpoints.addView(minimum, new LinearLayout.LayoutParams(0, -2, 1f));
        endpoints.addView(maximum, new LinearLayout.LayoutParams(0, -2, 1f));
        appearance.addView(endpoints, new LinearLayout.LayoutParams(-1, -2));
        return appearance;
    }

    private String circleSizeLabel(int progress) {
        if (progress <= BubbleAppearance.MIN_PROGRESS) return "보라색 원 크기 · 최소";
        if (progress >= BubbleAppearance.MAX_PROGRESS) return "보라색 원 크기 · 최대";
        return String.format(Locale.KOREA, "보라색 원 크기 · %d%%", progress);
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
        view.setText(String.format(Locale.KOREA, "%s  %s", ok ? "●" : "○", label));
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
                    if (!destroyed) setStatus(dataState, "선택지 데이터 오류", false);
                });
            }
        });
    }

    private void showDataSummary(JourneyModels.Data data) {
        if (destroyed || dataState == null) return;
        if (JourneyRepository.isExampleDatabase(data)) {
            setStatus(dataState, "공개 예제 DB · 실제 사용 전 DB 업데이트 필요", false);
            dataState.setTextColor(Ui.ORANGE);
            return;
        }
        String date = data.generatedAt == null || data.generatedAt.isEmpty() ? "날짜 미상" : data.generatedAt;
        try {
            Instant instant = Instant.parse(data.generatedAt);
            date = DateTimeFormatter.ofPattern("yyyy.MM.dd")
                    .withZone(ZoneId.of("Asia/Seoul"))
                    .format(instant);
        } catch (Exception ignored) {}
        String kind = JourneyRepository.hasDownloadedDatabase(this) ? "업데이트 DB" : "내장 DB";
        String summary = String.format(Locale.KOREA, "%s · 선택지 %,d개 · %s 기준", kind, data.choiceCount, date);
        setStatus(dataState, summary, true);
    }

    private void updateDatabase() {
        if (JourneyDatabaseUpdater.isUpdating()) {
            Toast.makeText(this, "이미 DB를 업데이트하고 있습니다.", Toast.LENGTH_SHORT).show();
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
                    setUpdateBusy(false);
                    if (result.data != null) showDataSummary(result.data);
                    new AlertDialog.Builder(this)
                            .setTitle(result.changed ? "DB 업데이트 완료" : "DB 업데이트")
                            .setMessage(result.message)
                            .setPositiveButton("확인", null)
                            .show();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    setUpdateBusy(false);
                    loadDataSummary();
                    new AlertDialog.Builder(this)
                            .setTitle("DB 업데이트 실패")
                            .setMessage(friendlyUpdateError(error))
                            .setPositiveButton("확인", null)
                            .show();
                });
            }
        });
    }

    private void setUpdateBusy(boolean busy) {
        if (updateButton == null) return;
        updateButton.setEnabled(!busy);
        updateButton.setAlpha(busy ? 0.55f : 1f);
        updateButton.setText(busy ? "DB 업데이트 중…" : "DB 업데이트");
    }

    private String friendlyUpdateError(Exception error) {
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) detail = error.getClass().getSimpleName();
        return "새 데이터는 적용하지 않았으며 현재 DB는 그대로입니다. 인터넷 연결과 원자료 사이트 상태를 확인한 뒤 다시 시도해 주세요.\n\n" + detail;
    }

    private void refreshStatus() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean running = OverlayCaptureService.isRunning();
        boolean captureActive = OverlayCaptureService.isCaptureActive();
        setStatus(overlayState, overlay ? "다른 앱 위에 표시 허용됨" : "다른 앱 위에 표시 권한 필요", overlay);
        if (running && captureActive) {
            setStatus(captureState, "화면 읽기 실행 중", true);
        } else if (running) {
            setStatus(captureState, "플로팅 아이콘 유지 중 · 화면 공유 다시 필요", false);
            captureState.setTextColor(Ui.ORANGE);
        } else {
            setStatus(captureState, "화면 읽기 서비스 꺼짐", false);
        }
        startButton.setText(running
                ? (captureActive ? "스타 세이비어 열기" : "화면 공유 다시 연결")
                : "권한 설정하고 시작");
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
                .setTitle("오버레이 설정을 열 수 없습니다")
                        .setMessage("휴대전화 설정에서 ‘특별한 접근’ → ‘다른 앱 위에 표시’ → ‘스세 여정 도우미’를 허용해 주세요.")
                .setNegativeButton("닫기", null)
                .setPositiveButton("설정 열기", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (ActivityNotFoundException | SecurityException ignored) {
                        Toast.makeText(this, "설정 앱을 직접 열어 주세요.", Toast.LENGTH_LONG).show();
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
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "화면 공유를 허용해야 선택지를 읽을 수 있습니다.", Toast.LENGTH_LONG).show();
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
        Intent launch = getPackageManager().getLaunchIntentForPackage(GAME_PACKAGE);
        if (launch == null) {
            new AlertDialog.Builder(this)
                    .setTitle("게임을 찾지 못했습니다")
                    .setMessage("스타 세이비어를 직접 실행해 주세요. 오버레이는 계속 켜져 있습니다.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(launch);
    }
}
