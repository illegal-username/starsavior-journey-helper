package helper.journey.starsavior;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class OverlayCaptureService extends Service {
    public static final String ACTION_START = BuildConfig.APPLICATION_ID + ".START";
    public static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".STOP";
    public static final String ACTION_RELOAD_DATA = BuildConfig.APPLICATION_ID + ".RELOAD_DATA";
    public static final String ACTION_UPDATE_APPEARANCE = BuildConfig.APPLICATION_ID + ".UPDATE_APPEARANCE";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "journey_capture";
    private static final int NOTIFICATION_ID = 7124;
    private static final long LONG_PRESS_MS = 700L;
    private static volatile boolean running;
    private static volatile boolean captureActive;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final AtomicBoolean databaseUpdating = new AtomicBoolean(false);
    private final AtomicInteger projectionGeneration = new AtomicInteger();
    private final Object pipelineLock = new Object();

    private HandlerThread captureThread;
    private Handler captureHandler;
    private WindowManager windowManager;
    private WindowManager.LayoutParams bubbleParams;
    private BubbleIconView bubbleView;
    private View resultView;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer recognizer;
    private volatile JourneyMatcher matcher;
    private int captureWidth;
    private int captureHeight;
    private int densityDpi;
    private boolean destroying;
    private Runnable captureTimeout;
    private long lastPermissionRequestAt;
    private StaminaGaugeDetector.Anchor staminaAnchor;
    private StaminaGaugeDetector.Result lastStamina;

    public static boolean isRunning() {
        return running;
    }

    public static boolean isCaptureActive() {
        return captureActive;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        captureActive = false;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        captureThread = new HandlerThread("journey-screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        worker.execute(() -> {
            try {
                JourneyModels.Data data = JourneyRepository.load(this);
                matcher = new JourneyMatcher(data.events);
            } catch (Exception error) {
                mainHandler.post(() -> showError("데이터 준비 실패", "앱의 선택지 데이터를 읽지 못했습니다.", List.of()));
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            if (!Settings.canDrawOverlays(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            startAsForeground(false);
            showBubble();
            updateBubbleState();
            return START_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD_DATA.equals(intent.getAction())) {
            reloadMatcher();
            return START_STICKY;
        }
        if (ACTION_UPDATE_APPEARANCE.equals(intent.getAction())) {
            applyBubbleAppearance();
            return START_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_STICKY;
        if (mediaProjection != null) return START_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData = getProjectionIntent(intent);
        if (resultCode != Activity.RESULT_OK || resultData == null || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startAsForeground(true);
        try {
            startProjection(resultCode, resultData);
        } catch (Exception error) {
            moveToProjectionWaitingState(true);
            showError("화면 읽기 시작 실패", error.getClass().getSimpleName() + ": " + error.getMessage(), List.of());
        }
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getProjectionIntent(Intent source) {
        if (Build.VERSION.SDK_INT >= 33) return source.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        return source.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private void startAsForeground(boolean projectionReady) {
        createNotificationChannel();
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, OverlayCaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(projectionReady ? "스세 여정 도우미 실행 중" : "스세 여정 도우미 대기 중")
                .setContentText(projectionReady
                        ? "✦: 선택지 읽기 · 길게 누르기: 메뉴"
                        : "화면 공유가 풀렸습니다 · ✦를 눌러 다시 연결")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(R.drawable.ic_notification, "종료", stop).build())
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (projectionReady) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(NOTIFICATION_ID, notification, type);
        } else if (Build.VERSION.SDK_INT >= 29 && projectionReady) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("플로팅 아이콘과 화면 읽기 상태를 유지하는 서비스 알림입니다.");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void ensureRecognizer() {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
        }
    }

    private void moveToProjectionWaitingState() {
        moveToProjectionWaitingState(false);
    }

    private void moveToProjectionWaitingState(boolean stopProjection) {
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        captureActive = false;
        projectionGeneration.incrementAndGet();
        capturing.set(false);
        releaseCapturePipeline();
        if (stopProjection && projection != null) {
            try {
                projection.stop();
            } catch (RuntimeException ignored) {}
        }
        closeRecognizer();
        dismissResult();
        if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
        setBubbleGlyph("✦");
        startAsForeground(false);
        showBubble();
        updateBubbleState();
    }

    private void releaseCapturePipeline() {
        synchronized (pipelineLock) {
            if (captureTimeout != null && captureHandler != null) {
                captureHandler.removeCallbacks(captureTimeout);
            }
            captureTimeout = null;
            if (virtualDisplay != null) {
                try {
                    virtualDisplay.setSurface(null);
                } catch (RuntimeException ignored) {}
                try {
                    virtualDisplay.release();
                } catch (RuntimeException ignored) {}
            }
            virtualDisplay = null;
            if (imageReader != null) {
                try {
                    imageReader.setOnImageAvailableListener(null, null);
                    imageReader.close();
                } catch (RuntimeException ignored) {}
            }
            imageReader = null;
        }
    }

    private void closeRecognizer() {
        TextRecognizer current = recognizer;
        recognizer = null;
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException ignored) {}
        }
    }

    private void requestProjectionPermission() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastPermissionRequestAt < 900) return;
        lastPermissionRequestAt = now;

        Intent request = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_REQUEST_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(request);
        } catch (RuntimeException error) {
            showInfo("화면 공유 다시 연결", "도우미 앱을 열어 ‘화면 공유 다시 연결’을 눌러 주세요.");
        }
    }

    private void updateBubbleState() {
        mainHandler.post(() -> {
            if (bubbleView == null) return;
            bubbleView.setCaptureActive(captureActive);
            bubbleView.setContentDescription(captureActive ? "여정 선택지 읽기" : "화면 공유 다시 연결");
        });
    }

    private void applyBubbleAppearance() {
        mainHandler.post(() -> {
            if (bubbleView != null) {
                bubbleView.setCircleProgress(BubbleAppearance.loadCircleProgress(this));
            }
        });
    }

    private void startProjection(int resultCode, Intent resultData) {
        ensureRecognizer();
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        MediaProjection projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) throw new IllegalStateException("MediaProjection token is null");
        mediaProjection = projection;
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (!destroying && mediaProjection == projection) moveToProjectionWaitingState();
            }

            @Override
            public void onCapturedContentResize(int width, int height) {
                if (Build.VERSION.SDK_INT >= 34 && mediaProjection == projection && width > 0 && height > 0) {
                    resizePipeline(width, height);
                }
            }
        }, mainHandler);

        Rect bounds;
        if (Build.VERSION.SDK_INT >= 30) bounds = windowManager.getMaximumWindowMetrics().getBounds();
        else {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            bounds = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
        }
        densityDpi = getResources().getDisplayMetrics().densityDpi;
        createPipeline(bounds.width(), bounds.height());
        captureActive = true;
        projectionGeneration.incrementAndGet();
        startAsForeground(true);
        showBubble();
        updateBubbleState();
    }

    private void createPipeline(int width, int height) {
        synchronized (pipelineLock) {
            captureWidth = Math.max(2, width);
            captureHeight = Math.max(2, height);
            imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3);
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "StarJourneyChoiceCapture",
                    captureWidth,
                    captureHeight,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    captureHandler
            );
            captureHandler.postDelayed(() -> {
                synchronized (pipelineLock) {
                    if (virtualDisplay != null) virtualDisplay.setSurface(null);
                    drainImagesLocked();
                }
            }, 350);
        }
    }

    private void resizePipeline(int width, int height) {
        captureHandler.post(() -> {
            boolean interrupted;
            synchronized (pipelineLock) {
                if (virtualDisplay == null || (width == captureWidth && height == captureHeight)) return;
                interrupted = capturing.getAndSet(false);
                if (captureTimeout != null) captureHandler.removeCallbacks(captureTimeout);
                virtualDisplay.setSurface(null);
                if (imageReader != null) {
                    imageReader.setOnImageAvailableListener(null, null);
                    imageReader.close();
                }
                captureWidth = Math.max(2, width);
                captureHeight = Math.max(2, height);
                imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3);
                virtualDisplay.resize(captureWidth, captureHeight, densityDpi);
            }
            if (interrupted) captureFailed("화면 방향이 바뀌었습니다. 선택지가 멈춘 뒤 다시 눌러 주세요.", List.of());
        });
    }

    private void showBubble() {
        mainHandler.post(() -> {
            if (bubbleView != null || !Settings.canDrawOverlays(this)) return;
            BubbleIconView bubble = new BubbleIconView(this);
            bubble.setCircleProgress(BubbleAppearance.loadCircleProgress(this));
            bubble.setCaptureActive(captureActive);
            bubble.setContentDescription(captureActive ? "여정 선택지 읽기" : "화면 공유 다시 연결");
            bubble.setClickable(true);
            bubble.setOnClickListener(view -> beginCapture());

            bubbleParams = new WindowManager.LayoutParams(
                    Ui.dp(this, BubbleIconView.TOUCH_SIZE_DP),
                    Ui.dp(this, BubbleIconView.TOUCH_SIZE_DP),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            bubbleParams.gravity = Gravity.TOP | Gravity.START;
            bubbleParams.x = Ui.dp(this, 12);
            bubbleParams.y = Ui.dp(this, 110);
            bubble.setOnTouchListener(new BubbleTouchListener());
            windowManager.addView(bubble, bubbleParams);
            bubbleView = bubble;
        });
    }

    private void beginCapture() {
        if (resultView != null) {
            dismissResult();
            return;
        }
        if (!captureActive || mediaProjection == null) {
            requestProjectionPermission();
            return;
        }
        if (databaseUpdating.get() || JourneyDatabaseUpdater.isUpdating()) {
            showInfo("DB 업데이트 중", "업데이트가 끝난 뒤 선택지를 다시 읽어 주세요.");
            return;
        }
        if (matcher == null) {
            showError("잠시만 기다려 주세요", "선택지 데이터를 준비하고 있습니다.", List.of());
            return;
        }
        if (!capturing.compareAndSet(false, true)) return;
        if (bubbleView != null) bubbleView.setVisibility(View.INVISIBLE);
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ reports the exact shared-content size through
            // onCapturedContentResize(). Keep that established path untouched.
            mainHandler.postDelayed(() -> captureHandler.post(this::attachCaptureSurface), 130);
        } else {
            // Older releases have no captured-content resize callback. The helper
            // grants projection while its activity is portrait, then launches a
            // landscape game, so refresh the physical display size just before the
            // user requests a frame.
            mainHandler.postDelayed(this::attachLegacyCaptureSurface, 130);
        }
    }

    @SuppressWarnings("deprecation")
    private void attachLegacyCaptureSurface() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        int displayWidth = metrics.widthPixels;
        int displayHeight = metrics.heightPixels;
        captureHandler.post(() -> {
            resizeLegacyPipelineForOrientation(displayWidth, displayHeight);
            attachCaptureSurface();
        });
    }

    private void resizeLegacyPipelineForOrientation(int displayWidth, int displayHeight) {
        synchronized (pipelineLock) {
            if (virtualDisplay == null || imageReader == null
                    || !LegacyCaptureResizePolicy.shouldResize(
                    Build.VERSION.SDK_INT, captureWidth, captureHeight,
                    displayWidth, displayHeight)) {
                return;
            }

            ImageReader replacement = ImageReader.newInstance(
                    displayWidth, displayHeight, PixelFormat.RGBA_8888, 3);
            try {
                virtualDisplay.setSurface(null);
                virtualDisplay.resize(displayWidth, displayHeight, densityDpi);
            } catch (RuntimeException resizeFailure) {
                replacement.close();
                return;
            }

            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = replacement;
            captureWidth = displayWidth;
            captureHeight = displayHeight;
            staminaAnchor = null;
            lastStamina = null;
        }
    }

    private void attachCaptureSurface() {
        synchronized (pipelineLock) {
            if (!capturing.get() || virtualDisplay == null || imageReader == null) {
                captureFailed("화면 공유가 종료되었습니다.", List.of());
                return;
            }
            drainImagesLocked();
            imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
            virtualDisplay.setSurface(imageReader.getSurface());
            captureTimeout = () -> {
                if (!capturing.compareAndSet(true, false)) return;
                synchronized (pipelineLock) {
                    if (virtualDisplay != null) virtualDisplay.setSurface(null);
                    if (imageReader != null) imageReader.setOnImageAvailableListener(null, null);
                }
                captureFailed("화면을 가져오지 못했습니다. 화면 공유를 다시 시작해 주세요.", List.of());
            };
            captureHandler.postDelayed(captureTimeout, 3000);
        }
    }

    private void onImageAvailable(ImageReader reader) {
        if (!capturing.get()) return;
        final int generation = projectionGeneration.get();
        final Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (IllegalStateException closedReader) {
            captureFailed("화면 크기가 바뀌었습니다. 다시 눌러 주세요.", List.of());
            return;
        }
        if (image == null) return;

        synchronized (pipelineLock) {
            if (captureTimeout != null) captureHandler.removeCallbacks(captureTimeout);
            if (virtualDisplay != null) virtualDisplay.setSurface(null);
            reader.setOnImageAvailableListener(null, null);
        }
        mainHandler.post(() -> {
            if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
            setBubbleGlyph("…");
        });

        worker.execute(() -> {
            Bitmap full = null;
            Bitmap eventCrop = null;
            Bitmap choiceCrop = null;
            try {
                full = imageToBitmap(image);
                image.close();
                if (!isProjectionSessionActive(generation)) {
                    full.recycle();
                    capturing.set(false);
                    return;
                }
                StaminaGaugeDetector.Result stamina = detectStamina(full);
                eventCrop = cropEventArea(full);
                choiceCrop = cropChoiceArea(full);
                recognizeRegions(eventCrop, choiceCrop, full, generation, stamina);
            } catch (Exception error) {
                try {
                    image.close();
                } catch (RuntimeException ignored) {}
                recycleBitmaps(eventCrop, choiceCrop, full);
                captureFailed("화면 처리 중 오류가 발생했습니다: " + error.getMessage(), List.of());
            }
        });
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + rowPadding / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap result = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        if (padded != result) padded.recycle();
        return result;
    }

    private Bitmap cropChoiceArea(Bitmap full) {
        int width = full.getWidth();
        int height = full.getHeight();
        CaptureRegionPlanner.Region region = CaptureRegionPlanner.choice(width, height);
        return Bitmap.createBitmap(full, region.left, region.top, region.width(), region.height());
    }

    private Bitmap cropEventArea(Bitmap full) {
        int width = full.getWidth();
        int height = full.getHeight();
        CaptureRegionPlanner.Region region = CaptureRegionPlanner.event(width, height);
        Bitmap crop = Bitmap.createBitmap(full, region.left, region.top, region.width(), region.height());
        if (crop.getHeight() >= 280 || crop.getWidth() >= 1200) return crop;
        Bitmap enlarged = Bitmap.createScaledBitmap(crop, crop.getWidth() * 2, crop.getHeight() * 2, true);
        if (enlarged != crop) crop.recycle();
        return enlarged;
    }

    private StaminaGaugeDetector.Result detectStamina(Bitmap full) {
        StaminaGaugeDetector.Region region = StaminaGaugeDetector.scanRegion(
                full.getWidth(), full.getHeight());
        int[] pixels = new int[region.width * region.height];
        full.getPixels(pixels, 0, region.width, region.left, region.top, region.width, region.height);
        StaminaGaugeDetector.Result detected = StaminaGaugeDetector.detect(
                full.getWidth(), full.getHeight(), region, pixels, staminaAnchor);
        if (detected == null) return null;
        detected = detected.stabilize(lastStamina);
        staminaAnchor = detected.anchor;
        lastStamina = detected;
        return detected;
    }

    private void recognizeRegions(Bitmap eventBitmap, Bitmap choiceBitmap, Bitmap fullBitmap,
                                  int generation, StaminaGaugeDetector.Result stamina) {
        TextRecognizer currentRecognizer = recognizer;
        if (!isProjectionSessionActive(generation) || currentRecognizer == null) {
            recycleBitmaps(eventBitmap, choiceBitmap, fullBitmap);
            capturing.set(false);
            return;
        }
        Task<Text> choiceTask = currentRecognizer.process(InputImage.fromBitmap(choiceBitmap, 0));
        choiceTask.addOnSuccessListener(worker, choiceText -> {
            if (!isProjectionSessionActive(generation)) {
                recycleBitmaps(eventBitmap, choiceBitmap, fullBitmap);
                capturing.set(false);
                return;
            }
            List<String> choiceLines = extractLines(choiceText);
            if (!choiceBitmap.isRecycled()) choiceBitmap.recycle();

            JourneyMatcher currentMatcher = matcher;
            if (stamina != null && currentMatcher != null
                    && !currentMatcher.hasPlausibleChoiceSignal(choiceLines)) {
                recycleBitmaps(eventBitmap, fullBitmap);
                capturing.set(false);
                mainHandler.post(() -> showStamina(stamina));
                return;
            }

            TextRecognizer eventRecognizer = recognizer;
            if (eventRecognizer == null) {
                recycleBitmaps(eventBitmap, fullBitmap);
                capturing.set(false);
                return;
            }
            Task<Text> eventTask = eventRecognizer.process(InputImage.fromBitmap(eventBitmap, 0));
            eventTask.addOnSuccessListener(worker, eventText -> {
                if (!isProjectionSessionActive(generation)) {
                    recycleBitmaps(eventBitmap, fullBitmap);
                    capturing.set(false);
                    return;
                }
                List<String> eventLines = extractLines(eventText);
                if (!eventBitmap.isRecycled()) eventBitmap.recycle();
                matchOrFallback(eventLines, choiceLines, fullBitmap, generation, stamina);
            }).addOnFailureListener(worker, ignored -> {
                if (!eventBitmap.isRecycled()) eventBitmap.recycle();
                if (!isProjectionSessionActive(generation)) {
                    recycleBitmaps(fullBitmap);
                    capturing.set(false);
                    return;
                }
                matchOrFallback(List.of(), choiceLines, fullBitmap, generation, stamina);
            });
        }).addOnFailureListener(worker, error -> {
            recycleBitmaps(eventBitmap, choiceBitmap);
            if (!isProjectionSessionActive(generation)) {
                recycleBitmaps(fullBitmap);
                capturing.set(false);
                return;
            }
            if (stamina != null) {
                recycleBitmaps(fullBitmap);
                capturing.set(false);
                mainHandler.post(() -> showStamina(stamina));
            } else {
                recognizeFull(fullBitmap, generation, List.of(), null);
            }
        });
    }

    private void matchOrFallback(List<String> eventLines, List<String> choiceLines,
                                 Bitmap fullBitmap, int generation,
                                 StaminaGaugeDetector.Result stamina) {
        JourneyMatcher currentMatcher = matcher;
        if (currentMatcher == null) {
            recycleBitmaps(fullBitmap);
            capturing.set(false);
            captureFailed("선택지 데이터를 아직 준비하고 있습니다. 잠시 후 다시 눌러 주세요.", List.of());
            return;
        }
        JourneyModels.Match match = currentMatcher.match(eventLines, choiceLines);
        if (match.isConfident()) {
            String difficulty = DifficultyResolver.fromRecognizedLines(match.event, choiceLines);
            recycleBitmaps(fullBitmap);
            capturing.set(false);
            mainHandler.post(() -> showMatch(match, difficulty, stamina));
        } else {
            recognizeFull(fullBitmap, generation, choiceLines, stamina);
        }
    }

    private void recognizeFull(Bitmap fullBitmap, int generation, List<String> regionalChoiceLines,
                               StaminaGaugeDetector.Result stamina) {
        TextRecognizer currentRecognizer = recognizer;
        if (!isProjectionSessionActive(generation) || currentRecognizer == null) {
            recycleBitmaps(fullBitmap);
            capturing.set(false);
            return;
        }
        Task<Text> task = currentRecognizer.process(InputImage.fromBitmap(fullBitmap, 0));
        task.addOnSuccessListener(worker, text -> {
            if (!isProjectionSessionActive(generation)) {
                recycleBitmaps(fullBitmap);
                capturing.set(false);
                return;
            }
            List<String> lines = extractLines(text);
            JourneyMatcher currentMatcher = matcher;
            if (currentMatcher == null) {
                recycleBitmaps(fullBitmap);
                capturing.set(false);
                captureFailed("선택지 데이터를 아직 준비하고 있습니다. 잠시 후 다시 눌러 주세요.", List.of());
                return;
            }
            JourneyModels.Match match = currentMatcher.match(lines, lines);
            recycleBitmaps(fullBitmap);
            capturing.set(false);
            if (match.isConfident()) {
                String difficulty = DifficultyResolver.fromRecognizedLines(
                        match.event, regionalChoiceLines);
                if (difficulty.isEmpty()) {
                    difficulty = DifficultyResolver.fromRecognizedLines(match.event, lines);
                }
                String resolvedDifficulty = difficulty;
                mainHandler.post(() -> showMatch(match, resolvedDifficulty, stamina));
            } else if (stamina != null) {
                mainHandler.post(() -> showStamina(stamina));
            } else if (match.ambiguous) {
                captureFailed("같은 선택지를 사용하는 이벤트가 있습니다. 왼쪽 이벤트명이 모두 보이도록 한 뒤 다시 눌러 주세요.", lines);
            } else {
                captureFailed("선택지를 충분히 읽지 못했습니다. 선택지가 모두 보이는 화면에서 다시 눌러 주세요.", lines);
            }
        }).addOnFailureListener(worker, error -> {
            recycleBitmaps(fullBitmap);
            if (isProjectionSessionActive(generation)) {
                capturing.set(false);
                if (stamina != null) mainHandler.post(() -> showStamina(stamina));
                else captureFailed("한국어 글자 인식에 실패했습니다: " + error.getMessage(), List.of());
            } else {
                capturing.set(false);
            }
        });
    }

    private boolean isProjectionSessionActive(int generation) {
        return captureActive
                && mediaProjection != null
                && projectionGeneration.get() == generation;
    }

    private void recycleBitmaps(Bitmap... bitmaps) {
        for (int index = 0; index < bitmaps.length; index++) {
            Bitmap bitmap = bitmaps[index];
            if (bitmap == null || bitmap.isRecycled()) continue;
            boolean alreadyHandled = false;
            for (int previous = 0; previous < index; previous++) {
                if (bitmaps[previous] == bitmap) {
                    alreadyHandled = true;
                    break;
                }
            }
            if (!alreadyHandled) bitmap.recycle();
        }
    }

    private List<String> extractLines(Text text) {
        List<PositionedLine> positioned = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                positioned.add(new PositionedLine(line.getText(), box == null ? 0 : box.top, box == null ? 0 : box.left));
            }
        }
        positioned.sort(Comparator.comparingInt((PositionedLine line) -> line.top).thenComparingInt(line -> line.left));
        List<String> result = new ArrayList<>();
        for (PositionedLine line : positioned) {
            String trimmed = line.text.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private void showMatch(JourneyModels.Match match, String difficulty,
                           StaminaGaugeDetector.Result stamina) {
        if (!captureActive || destroying) return;
        setBubbleGlyph("✓");
        mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 900);
        dismissResult();
        resultView = OverlayResultView.match(this, match, difficulty, stamina, this::dismissResult);
        addResultView(resultView);
    }

    private void showStamina(StaminaGaugeDetector.Result stamina) {
        if (!captureActive || destroying || stamina == null) return;
        setBubbleGlyph("✓");
        mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 900);
        dismissResult();
        resultView = OverlayResultView.stamina(this, stamina, this::dismissResult);
        addResultView(resultView);
    }

    private void captureFailed(String message, List<String> lines) {
        mainHandler.post(() -> {
            capturing.set(false);
            if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
            if (!captureActive || destroying) return;
            setBubbleGlyph("!");
            mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 1200);
            showError("인식하지 못했습니다", message, lines);
        });
    }

    private void showError(String title, String message, List<String> lines) {
        mainHandler.post(() -> {
            dismissResult();
            resultView = OverlayResultView.error(this, title, message, lines, this::dismissResult);
            addResultView(resultView);
        });
    }

    private void showControlMenu() {
        mainHandler.post(() -> {
            if (destroying) return;
            dismissResult();
            resultView = OverlayResultView.controls(
                    this,
                    this::startDatabaseUpdateFromOverlay,
                    () -> {
                        dismissResult();
                        stopSelf();
                    },
                    this::dismissResult
            );
            addResultView(resultView);
        });
    }

    private void showInfo(String title, String message) {
        mainHandler.post(() -> {
            if (destroying) return;
            dismissResult();
            resultView = OverlayResultView.info(this, title, message, this::dismissResult);
            addResultView(resultView);
        });
    }

    private void updateInfoMessage(String message) {
        mainHandler.post(() -> {
            if (!destroying) OverlayResultView.updateInfo(resultView, message);
        });
    }

    private void startDatabaseUpdateFromOverlay() {
        dismissResult();
        if (!databaseUpdating.compareAndSet(false, true)) {
            showInfo("DB 업데이트 중", "이미 최신 데이터를 확인하고 있습니다.");
            return;
        }

        setBubbleGlyph("↻");
        showInfo("DB 업데이트", "최신 버전을 확인하고 있습니다…");
        worker.execute(() -> {
            try {
                JourneyDatabaseUpdater.UpdateResult result = JourneyDatabaseUpdater.update(
                        this, this::updateInfoMessage);
                if (result.data != null) matcher = new JourneyMatcher(result.data.events);
                databaseUpdating.set(false);
                mainHandler.post(() -> {
                    if (destroying) return;
                    setBubbleGlyph(result.changed ? "✓" : "✦");
                    showInfo(result.changed ? "DB 업데이트 완료" : "DB 업데이트", result.message);
                    if (result.changed) mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 1200);
                });
            } catch (Exception error) {
                databaseUpdating.set(false);
                mainHandler.post(() -> {
                    if (destroying) return;
                    setBubbleGlyph("!");
                    showInfo("DB 업데이트 실패", friendlyUpdateError(error));
                    mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 1400);
                });
            }
        });
    }

    private void reloadMatcher() {
        worker.execute(() -> {
            try {
                JourneyModels.Data data = JourneyRepository.load(this);
                matcher = new JourneyMatcher(data.events);
                mainHandler.post(() -> {
                    if (destroying) return;
                    setBubbleGlyph("✓");
                    mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 900);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!destroying) showError("DB 다시 읽기 실패", "기존 데이터를 계속 사용합니다.", List.of());
                });
            }
        });
    }

    private String friendlyUpdateError(Exception error) {
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) detail = error.getClass().getSimpleName();
        return "현재 DB는 그대로 유지했습니다. 인터넷 연결과 원자료 사이트 상태를 확인한 뒤 다시 시도해 주세요.\n\n" + detail;
    }

    private void addResultView(View view) {
        if (!Settings.canDrawOverlays(this)) return;
        Rect bounds;
        if (Build.VERSION.SDK_INT >= 30) bounds = windowManager.getMaximumWindowMetrics().getBounds();
        else {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            bounds = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
        }
        int width = bounds.width() > bounds.height()
                ? Math.min(Ui.dp(this, 720), Math.round(bounds.width() * 0.52f))
                : Math.round(bounds.width() * 0.92f);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = bounds.width() > bounds.height()
                ? Ui.dp(this, 76)
                : Math.max(0, (bounds.width() - width) / 2);
        params.y = Ui.dp(this, 12);
        try {
            windowManager.addView(view, params);
        } catch (Exception ignored) {
            resultView = null;
        }
    }

    private void dismissResult() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::dismissResult);
            return;
        }

        // Remove the view that is current at this exact moment.  Posting this
        // whole block unconditionally lets showMatch() install a new view first,
        // then the delayed removal accidentally closes that new result.
        View viewToRemove = resultView;
        resultView = null;
        if (viewToRemove == null) return;
        try {
            windowManager.removeView(viewToRemove);
        } catch (Exception ignored) {}
    }

    private void setBubbleGlyph(String glyph) {
        if (bubbleView != null) bubbleView.setGlyph(glyph);
    }

    private void drainImagesLocked() {
        if (imageReader == null) return;
        Image stale;
        while ((stale = imageReader.acquireLatestImage()) != null) stale.close();
    }

    @Override
    public void onDestroy() {
        destroying = true;
        running = false;
        captureActive = false;
        projectionGeneration.incrementAndGet();
        capturing.set(false);
        databaseUpdating.set(false);
        dismissResult();
        if (bubbleView != null) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Exception ignored) {}
            bubbleView = null;
        }
        releaseCapturePipeline();
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        if (projection != null) {
            try {
                projection.stop();
            } catch (RuntimeException ignored) {}
        }
        closeRecognizer();
        worker.shutdownNow();
        if (captureThread != null) captureThread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final class BubbleTouchListener implements View.OnTouchListener {
        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean moved;
        private boolean pointerDown;
        private boolean longPressed;
        private Runnable longPressAction;

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = bubbleParams.x;
                    startY = bubbleParams.y;
                    moved = false;
                    pointerDown = true;
                    longPressed = false;
                    longPressAction = () -> {
                        if (!pointerDown || moved) return;
                        longPressed = true;
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        view.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                        showControlMenu();
                    };
                    mainHandler.postDelayed(longPressAction, LONG_PRESS_MS);
                    view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (Math.hypot(dx, dy) > Ui.dp(OverlayCaptureService.this, 7)) {
                        moved = true;
                        if (longPressAction != null) mainHandler.removeCallbacks(longPressAction);
                    }
                    if (moved) {
                        Rect bounds;
                        if (Build.VERSION.SDK_INT >= 30) bounds = windowManager.getMaximumWindowMetrics().getBounds();
                        else {
                            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                            windowManager.getDefaultDisplay().getRealMetrics(metrics);
                            bounds = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
                        }
                        int maxX = Math.max(0, bounds.width() - bubbleParams.width);
                        int maxY = Math.max(0, bounds.height() - bubbleParams.height);
                        bubbleParams.x = Math.max(0, Math.min(maxX, startX + Math.round(dx)));
                        bubbleParams.y = Math.max(0, Math.min(maxY, startY + Math.round(dy)));
                        try {
                            windowManager.updateViewLayout(bubbleView, bubbleParams);
                        } catch (Exception ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pointerDown = false;
                    if (longPressAction != null) mainHandler.removeCallbacks(longPressAction);
                    view.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && !moved && !longPressed) view.performClick();
                    return true;
                default:
                    return false;
            }
        }
    }

    private static final class PositionedLine {
        final String text;
        final int top;
        final int left;

        PositionedLine(String text, int top, int left) {
            this.text = text;
            this.top = top;
            this.left = left;
        }
    }
}
