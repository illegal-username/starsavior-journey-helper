package helper.journey.starsavior;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OverlayCaptureService extends Service {
    public static final String ACTION_START = BuildConfig.APPLICATION_ID + ".START";
    public static final String ACTION_STOP = BuildConfig.APPLICATION_ID + ".STOP";
    public static final String ACTION_CHANGE_LANGUAGE = BuildConfig.APPLICATION_ID + ".CHANGE_LANGUAGE";
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
    private final AtomicBoolean databaseUpdating = new AtomicBoolean(false);
    private final CaptureSessionStateMachine captureSession = new CaptureSessionStateMachine();
    private final Object pipelineLock = new Object();
    private final JourneyMatcherStore matcherStore = new JourneyMatcherStore();

    private HandlerThread captureThread;
    private Handler captureHandler;
    private WindowManager windowManager;
    private WindowManager.LayoutParams bubbleParams;
    private BubbleIconView bubbleView;
    private View resultView;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private volatile TextRecognizer recognizer;
    private volatile Context languageContext;
    private volatile GameLanguage language;
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
    protected void attachBaseContext(Context base) {
        languageContext = AppLanguage.wrap(base);
        language = AppLanguage.of(languageContext);
        super.attachBaseContext(languageContext);
    }

    @Override
    public Resources getResources() {
        Context current = languageContext;
        return current == null ? super.getResources() : current.getResources();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        refreshLanguage();
    }

    private void refreshLanguage() {
        GameLanguage selected = AppLanguage.selected(getApplicationContext());
        languageContext = AppLanguage.wrap(getApplicationContext());
        if (selected == language) return;
        language = selected;
        // End the old capture generation before loading a different language.
        moveToProjectionWaitingState(true);
        matcherStore.selectLanguage(language);
        reloadMatcher();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        matcherStore.selectLanguage(language);
        running = true;
        captureActive = false;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        captureThread = new HandlerThread("journey-screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        Context selectedContext = languageContext;
        worker.execute(() -> {
            try {
                JourneyModels.Data loaded = JourneyRepository.load(selectedContext);
                if (loaded.language.equals(language.tag)) matcherStore.reload(() -> loaded);
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!destroying && AppLanguage.of(selectedContext) == language) {
                        showError(getString(R.string.data_load_failed), getString(R.string.data_load_help), List.of());
                    }
                });
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
        if (ACTION_CHANGE_LANGUAGE.equals(intent.getAction())) {
            refreshLanguage();
            return START_STICKY;
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
        refreshLanguage();
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
            showError(getString(R.string.capture_start_failed), error.getClass().getSimpleName() + ": " + error.getMessage(), List.of());
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
                .setContentTitle(projectionReady ? getString(R.string.notification_running) : getString(R.string.notification_waiting))
                .setContentText(projectionReady
                        ? getString(R.string.notification_controls)
                        : getString(R.string.notification_reconnect))
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(R.drawable.ic_notification, getString(R.string.stop), stop).build())
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
        channel.setDescription(getString(R.string.notification_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void ensureRecognizer() {
        if (recognizer == null) {
            recognizer = AppLanguage.recognizer(language);
        }
    }

    private void moveToProjectionWaitingState() {
        moveToProjectionWaitingState(false);
    }

    private void moveToProjectionWaitingState(boolean stopProjection) {
        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        captureActive = false;
        captureSession.waitForPermission();
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
            showInfo(getString(R.string.reconnect_capture), getString(R.string.reconnect_help));
        }
    }

    private void updateBubbleState() {
        mainHandler.post(() -> {
            if (bubbleView == null) return;
            bubbleView.setCaptureActive(captureActive);
            bubbleView.setContentDescription(captureActive ? getString(R.string.read_choices) : getString(R.string.reconnect_capture));
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
        captureSession.activate();
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
                interrupted = captureSession.interruptForResize();
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
            if (interrupted) captureFailed(getString(R.string.orientation_changed), List.of());
        });
    }

    private void showBubble() {
        mainHandler.post(() -> {
            if (bubbleView != null || !Settings.canDrawOverlays(this)) return;
            BubbleIconView bubble = new BubbleIconView(this);
            bubble.setCircleProgress(BubbleAppearance.loadCircleProgress(this));
            bubble.setCaptureActive(captureActive);
            bubble.setContentDescription(captureActive ? getString(R.string.read_choices) : getString(R.string.reconnect_capture));
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
            showInfo(getString(R.string.update_busy), getString(R.string.wait_for_update));
            return;
        }
        if (matcherStore.current() == null) {
            showError(getString(R.string.please_wait), getString(R.string.preparing_data), List.of());
            return;
        }
        if (!captureSession.beginCapture()) return;
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
            if (!captureSession.isCapturing() || virtualDisplay == null || imageReader == null) {
                captureSession.finishCapture();
                captureFailed(getString(R.string.capture_ended), List.of());
                return;
            }
            drainImagesLocked();
            imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
            virtualDisplay.setSurface(imageReader.getSurface());
            int captureGeneration = captureSession.generation();
            captureTimeout = () -> {
                if (!captureSession.finishCapture(captureGeneration)) return;
                synchronized (pipelineLock) {
                    if (virtualDisplay != null) virtualDisplay.setSurface(null);
                    if (imageReader != null) imageReader.setOnImageAvailableListener(null, null);
                }
                captureFailed(getString(R.string.capture_frame_failed), List.of());
            };
            captureHandler.postDelayed(captureTimeout, 3000);
        }
    }

    private void onImageAvailable(ImageReader reader) {
        if (!captureSession.isCapturing()) return;
        final int generation = captureSession.generation();
        final Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (IllegalStateException closedReader) {
            captureSession.finishCapture(generation);
            captureFailed(generation, getString(R.string.screen_resized), List.of());
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
                    captureSession.finishCapture(generation);
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
                captureSession.finishCapture(generation);
                captureFailed(generation, getString(R.string.capture_error_prefix) + error.getMessage(), List.of());
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
            captureSession.finishCapture(generation);
            return;
        }
        Task<Text> choiceTask = currentRecognizer.process(InputImage.fromBitmap(choiceBitmap, 0));
        choiceTask.addOnSuccessListener(worker, choiceText -> {
            if (!isProjectionSessionActive(generation)) {
                recycleBitmaps(eventBitmap, choiceBitmap, fullBitmap);
                captureSession.finishCapture(generation);
                return;
            }
            List<String> choiceLines = extractLines(choiceText);
            if (!choiceBitmap.isRecycled()) choiceBitmap.recycle();

            JourneyMatcher currentMatcher = matcherStore.current();
            if (stamina != null && currentMatcher != null
                    && !currentMatcher.hasPlausibleChoiceSignal(choiceLines)) {
                recycleBitmaps(eventBitmap, fullBitmap);
                captureSession.finishCapture(generation);
                mainHandler.post(() -> showStamina(generation, stamina));
                return;
            }

            TextRecognizer eventRecognizer = recognizer;
            if (eventRecognizer == null) {
                recycleBitmaps(eventBitmap, fullBitmap);
                captureSession.finishCapture(generation);
                return;
            }
            Task<Text> eventTask = eventRecognizer.process(InputImage.fromBitmap(eventBitmap, 0));
            eventTask.addOnSuccessListener(worker, eventText -> {
                if (!isProjectionSessionActive(generation)) {
                    recycleBitmaps(eventBitmap, fullBitmap);
                    captureSession.finishCapture(generation);
                    return;
                }
                List<String> eventLines = extractLines(eventText);
                ArcanaImageRecognizer.Anchor arcanaAnchor = regionalArcanaAnchor(
                        eventText, fullBitmap.getWidth(), fullBitmap.getHeight(),
                        eventBitmap.getWidth(), eventBitmap.getHeight());
                if (!eventBitmap.isRecycled()) eventBitmap.recycle();
                matchOrFallback(
                        eventLines, choiceLines, fullBitmap, generation, stamina, arcanaAnchor);
            }).addOnFailureListener(worker, ignored -> {
                if (!eventBitmap.isRecycled()) eventBitmap.recycle();
                if (!isProjectionSessionActive(generation)) {
                    recycleBitmaps(fullBitmap);
                    captureSession.finishCapture(generation);
                    return;
                }
                matchOrFallback(List.of(), choiceLines, fullBitmap, generation, stamina, null);
            });
        }).addOnFailureListener(worker, error -> {
            recycleBitmaps(eventBitmap, choiceBitmap);
            if (!isProjectionSessionActive(generation)) {
                recycleBitmaps(fullBitmap);
                captureSession.finishCapture(generation);
                return;
            }
            if (stamina != null) {
                recycleBitmaps(fullBitmap);
                captureSession.finishCapture(generation);
                mainHandler.post(() -> showStamina(generation, stamina));
            } else {
                recognizeFull(fullBitmap, generation, List.of(), null, null);
            }
        });
    }

    private void matchOrFallback(List<String> eventLines, List<String> choiceLines,
                                 Bitmap fullBitmap, int generation,
                                 StaminaGaugeDetector.Result stamina,
                                 ArcanaImageRecognizer.Anchor arcanaAnchor) {
        JourneyMatcher currentMatcher = matcherStore.current();
        JourneyRecognitionCoordinator.Decision decision =
                JourneyRecognitionCoordinator.evaluateRegional(
                        currentMatcher, eventLines, choiceLines);
        if (decision.action == JourneyRecognitionCoordinator.Action.DATA_UNAVAILABLE) {
            recycleBitmaps(fullBitmap);
            captureSession.finishCapture(generation);
            captureFailed(generation, getString(R.string.data_not_ready), List.of());
            return;
        }
        if (decision.action == JourneyRecognitionCoordinator.Action.SHOW_MATCH) {
            Set<String> recognizedArcanaIds = recognizeArcana(
                    fullBitmap, decision.match.event, decision.difficulty, arcanaAnchor);
            recycleBitmaps(fullBitmap);
            captureSession.finishCapture(generation);
            mainHandler.post(() -> showMatch(generation,
                    decision.match, decision.difficulty, stamina, recognizedArcanaIds));
        } else {
            recognizeFull(fullBitmap, generation, choiceLines, stamina, arcanaAnchor);
        }
    }

    private void recognizeFull(Bitmap fullBitmap, int generation, List<String> regionalChoiceLines,
                               StaminaGaugeDetector.Result stamina,
                               ArcanaImageRecognizer.Anchor regionalArcanaAnchor) {
        TextRecognizer currentRecognizer = recognizer;
        if (!isProjectionSessionActive(generation) || currentRecognizer == null) {
            recycleBitmaps(fullBitmap);
            captureSession.finishCapture(generation);
            return;
        }
        Task<Text> task = currentRecognizer.process(InputImage.fromBitmap(fullBitmap, 0));
        task.addOnSuccessListener(worker, text -> {
            if (!isProjectionSessionActive(generation)) {
                recycleBitmaps(fullBitmap);
                captureSession.finishCapture(generation);
                return;
            }
            List<String> lines = extractLines(text);
            ArcanaImageRecognizer.Anchor detectedArcanaAnchor = fullArcanaAnchor(text);
            ArcanaImageRecognizer.Anchor arcanaAnchor = detectedArcanaAnchor == null
                    ? regionalArcanaAnchor : detectedArcanaAnchor;
            JourneyRecognitionCoordinator.Decision decision =
                    JourneyRecognitionCoordinator.evaluateFull(
                            matcherStore.current(), lines, regionalChoiceLines);
            if (decision.action == JourneyRecognitionCoordinator.Action.DATA_UNAVAILABLE) {
                recycleBitmaps(fullBitmap);
                captureSession.finishCapture(generation);
                captureFailed(generation, getString(R.string.data_not_ready), List.of());
                return;
            }
            if (decision.action == JourneyRecognitionCoordinator.Action.SHOW_MATCH) {
                Set<String> recognizedArcanaIds = recognizeArcana(
                        fullBitmap, decision.match.event, decision.difficulty, arcanaAnchor);
                recycleBitmaps(fullBitmap);
                captureSession.finishCapture(generation);
                mainHandler.post(() -> showMatch(generation,
                        decision.match, decision.difficulty, stamina, recognizedArcanaIds));
                return;
            }
            recycleBitmaps(fullBitmap);
            captureSession.finishCapture(generation);
            if (stamina != null) {
                mainHandler.post(() -> showStamina(generation, stamina));
            } else if (decision.action == JourneyRecognitionCoordinator.Action.AMBIGUOUS) {
                captureFailed(generation, getString(R.string.ambiguous_event), lines);
            } else {
                captureFailed(generation, getString(R.string.choices_unreadable), lines);
            }
        }).addOnFailureListener(worker, error -> {
            recycleBitmaps(fullBitmap);
            if (isProjectionSessionActive(generation)) {
                captureSession.finishCapture(generation);
                if (stamina != null) mainHandler.post(() -> showStamina(generation, stamina));
                else captureFailed(generation, getString(R.string.ocr_error_prefix) + error.getMessage(), List.of());
            } else {
                captureSession.finishCapture(generation);
            }
        });
    }

    private ArcanaImageRecognizer.Anchor regionalArcanaAnchor(
            Text text, int fullWidth, int fullHeight, int eventBitmapWidth,
            int eventBitmapHeight) {
        CaptureRegionPlanner.Region region = CaptureRegionPlanner.event(fullWidth, fullHeight);
        double scaleX = eventBitmapWidth / (double) region.width();
        double scaleY = eventBitmapHeight / (double) region.height();
        return findArcanaAnchor(text, region.left, region.top, scaleX, scaleY);
    }

    private ArcanaImageRecognizer.Anchor fullArcanaAnchor(Text text) {
        return findArcanaAnchor(text, 0, 0, 1.0, 1.0);
    }

    private ArcanaImageRecognizer.Anchor findArcanaAnchor(
            Text text, int offsetX, int offsetY, double scaleX, double scaleY) {
        if (text == null || scaleX <= 0.0 || scaleY <= 0.0) return null;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (!language.isArcanaHeader(line.getText())) continue;
                Rect box = line.getBoundingBox();
                if (box == null || box.height() <= 0) continue;
                return new ArcanaImageRecognizer.Anchor(
                        offsetX + (int) Math.round(box.left / scaleX),
                        offsetY + (int) Math.round(box.top / scaleY),
                        Math.max(1, (int) Math.round(box.height() / scaleY)));
            }
        }
        return null;
    }

    private Set<String> recognizeArcana(
            Bitmap fullBitmap, JourneyModels.Event event, String difficulty,
            ArcanaImageRecognizer.Anchor anchor) {
        Set<String> candidates = ArcanaImageRecognizer.candidateIds(event, difficulty);
        if (anchor == null || candidates.size() < 2) return Set.of();
        JourneyModels.Data data = matcherStore.currentData();
        if (data == null || data.arcanaImageFeatures.isEmpty()) return Set.of();
        ArcanaImageRecognizer.Region region = ArcanaImageRecognizer.scanRegion(
                fullBitmap.getWidth(), fullBitmap.getHeight(), anchor);
        if (region.width <= 0 || region.height <= 0) return Set.of();
        try {
            int[] pixels = new int[region.width * region.height];
            fullBitmap.getPixels(
                    pixels, 0, region.width, region.left, region.top,
                    region.width, region.height);
            return ArcanaImageRecognizer.recognize(
                    region, pixels, anchor, data.arcanaImageFeatures,
                    candidates).recognizedArcanaIds;
        } catch (RuntimeException ignored) {
            return Set.of();
        }
    }

    private boolean isProjectionSessionActive(int generation) {
        return captureActive
                && mediaProjection != null
                && captureSession.isActive(generation);
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

    private void showMatch(int generation, JourneyModels.Match match, String difficulty,
                           StaminaGaugeDetector.Result stamina,
                           Set<String> recognizedArcanaIds) {
        if (!isProjectionSessionActive(generation) || destroying) return;
        setBubbleGlyph("✓");
        mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 900);
        dismissResult();
        resultView = OverlayResultView.match(
                this, match, difficulty, stamina, recognizedArcanaIds, this::dismissResult);
        addResultView(resultView);
    }

    private void showStamina(int generation, StaminaGaugeDetector.Result stamina) {
        if (!isProjectionSessionActive(generation) || destroying || stamina == null) return;
        setBubbleGlyph("✓");
        mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 900);
        dismissResult();
        resultView = OverlayResultView.stamina(this, stamina, this::dismissResult);
        addResultView(resultView);
    }

    private void captureFailed(String message, List<String> lines) {
        captureFailed(captureSession.generation(), message, lines);
    }

    private void captureFailed(int generation, String message, List<String> lines) {
        mainHandler.post(() -> {
            if (!isProjectionSessionActive(generation) || destroying) return;
            if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
            setBubbleGlyph("!");
            mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 1200);
            showError(getString(R.string.recognition_failed), message, lines);
        });
    }

    private void showError(String title, String message, List<String> lines) {
        Context renderingContext = languageContext;
        mainHandler.post(() -> {
            if (destroying || AppLanguage.of(renderingContext) != language) return;
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
        Context renderingContext = languageContext;
        mainHandler.post(() -> {
            if (destroying || AppLanguage.of(renderingContext) != language) return;
            dismissResult();
            resultView = OverlayResultView.info(this, title, message, this::dismissResult);
            addResultView(resultView);
        });
    }

    private void updateInfoMessage(GameLanguage messageLanguage, String message) {
        mainHandler.post(() -> {
            if (!destroying && messageLanguage == language) OverlayResultView.updateInfo(resultView, message);
        });
    }

    private void startDatabaseUpdateFromOverlay() {
        dismissResult();
        if (!databaseUpdating.compareAndSet(false, true)) {
            showInfo(getString(R.string.update_busy), getString(R.string.already_checking));
            return;
        }

        setBubbleGlyph("↻");
        showInfo(getString(R.string.update_database), getString(R.string.checking_latest));
        Context operationContext = languageContext;
        worker.execute(() -> {
            try {
                JourneyDatabaseUpdater.UpdateResult result = JourneyDatabaseUpdater.update(
                        operationContext, message -> updateInfoMessage(AppLanguage.of(operationContext), message));
                if (result.data != null && result.data.language.equals(language.tag)) matcherStore.reload(() -> result.data);
                databaseUpdating.set(false);
                mainHandler.post(() -> {
                    if (destroying || AppLanguage.of(operationContext) != language) return;
                    setBubbleGlyph(result.changed ? "✓" : "✦");
                    showInfo(result.changed ? getString(R.string.update_complete) : getString(R.string.update_database), result.message(languageContext));
                    if (result.changed) mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 1200);
                });
            } catch (Exception error) {
                databaseUpdating.set(false);
                mainHandler.post(() -> {
                    if (destroying || AppLanguage.of(operationContext) != language) return;
                    setBubbleGlyph("!");
                    showInfo(getString(R.string.update_failed), friendlyUpdateError(error));
                    mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 1400);
                });
            }
        });
    }

    private void reloadMatcher() {
        Context selectedContext = languageContext;
        worker.execute(() -> {
            try {
                JourneyModels.Data loaded = JourneyRepository.load(selectedContext);
                if (loaded.language.equals(language.tag)) matcherStore.reload(() -> loaded);
                mainHandler.post(() -> {
                    if (destroying || AppLanguage.of(selectedContext) != language) return;
                    setBubbleGlyph("✓");
                    mainHandler.postDelayed(() -> setBubbleGlyph("✦"), 900);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!destroying && AppLanguage.of(selectedContext) == language) {
                        showError(getString(R.string.reload_failed), getString(R.string.keep_existing_data), List.of());
                    }
                });
            }
        });
    }

    private String friendlyUpdateError(Exception error) {
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) detail = error.getClass().getSimpleName();
        return getString(R.string.overlay_update_error_detail) + detail;
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
        captureSession.destroy();
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
