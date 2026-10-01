package helper.journey.starsavior;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReportEmailTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private CaptureJob job() {
        CaptureSessionStateMachine session = new CaptureSessionStateMachine();
        session.activate(); session.beginCapture();
        return new CaptureJob(session, session.generation(), Runnable::run, error -> fail());
    }

    @Test public void exactOriginalSurvivesOcrCompletionAndClosingResultDuringEmailPreparation() throws Exception {
        CaptureJob job = job();
        Bitmap original = job.own(Bitmap.createBitmap(39, 27, Bitmap.Config.ARGB_8888));
        original.eraseColor(Color.BLUE); original.setPixel(12, 9, Color.RED);
        ReportCapture result = new ReportCapture(job, original);
        job.finish();
        assertFalse(original.isRecycled());
        ReportCapture email = result.acquire();
        result.release();
        File file = ReportEmail.writeAttachment(context, email);
        Bitmap decoded = BitmapFactory.decodeFile(file.getPath());
        assertEquals(39, decoded.getWidth()); assertEquals(27, decoded.getHeight());
        assertEquals(Color.RED, decoded.getPixel(12, 9));
        assertEquals(Color.BLUE, decoded.getPixel(1, 1));
        email.release();
        assertTrue(original.isRecycled());
        assertTrue(file.isFile());
        decoded.recycle(); file.delete();
    }

    @Test public void closingResultStillWaitsForOcrBorrowerAndNeverCreatesAttachment() {
        File directory = ReportAttachmentProvider.directory(context);
        int before = directory.list() == null ? 0 : directory.list().length;
        CaptureJob job = job();
        Bitmap original = job.own(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888));
        ReportCapture capture = new ReportCapture(job, original);
        job.beginUse(original);
        job.finish(); capture.release();
        assertFalse(original.isRecycled());
        job.endUse(original);
        assertTrue(original.isRecycled());
        assertEquals(before, directory.list() == null ? 0 : directory.list().length);
        assertThrows(IllegalStateException.class, capture::acquire);
    }

    @Test public void attachmentProviderIsReadOnlyBoundedToReportsAndExpiresFiles() throws Exception {
        ReportAttachmentProvider provider = Robolectric.buildContentProvider(ReportAttachmentProvider.class).create().get();
        File directory = ReportAttachmentProvider.directory(context);
        directory.mkdirs();
        File file = new File(directory, UUID.randomUUID() + ".png");
        Files.write(file.toPath(), new byte[]{1, 2, 3});
        Uri uri = ReportAttachmentProvider.uri(file);
        try (ParcelFileDescriptor fd = provider.openFile(uri, "r")) {
            assertNotNull(fd);
        }
        try (android.database.Cursor cursor = provider.query(uri, null, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("recognition.png", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
            assertEquals(3, cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)));
        }
        assertThrows(FileNotFoundException.class, () -> provider.openFile(uri, "w"));
        for (String path : List.of("../secret", "%2e%2e%2fsecret", "nested/file.png", "not-a-report.png")) {
            Uri bad = Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".reports/" + path);
            assertThrows(FileNotFoundException.class, () -> provider.openFile(bad, "r"));
        }
        assertThrows(FileNotFoundException.class, () -> provider.openFile(uri.buildUpon().authority("other.app").build(), "r"));
        assertTrue(file.setLastModified(System.currentTimeMillis() - ReportAttachmentProvider.MAX_AGE_MS - 1000));
        assertThrows(FileNotFoundException.class, () -> provider.openFile(uri, "r"));
        assertFalse(file.exists());
    }

    @Test public void privateProviderManifestAllowsOnlyExplicitUriGrants() throws Exception {
        android.content.pm.ProviderInfo info = context.getPackageManager().getProviderInfo(
                new android.content.ComponentName(context, ReportAttachmentProvider.class), 0);
        assertFalse(info.exported); assertTrue(info.grantUriPermissions);
    }

    @Test public void emailKeepsDetailsInJsonAndGrantsReadAccessToBothAttachments() throws Exception {
        AppDiagnostics.record(AppDiagnostics.Stage.CAPTURE, new IllegalStateException("private OCR/path"), null);
        CaptureJob job = job();
        Bitmap original = job.own(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888));
        ReportCapture capture = new ReportCapture(job, original);
        job.finish();
        List<File> files = ReportEmail.writeAttachments(context, capture, ReportEmail.diagnostics(context, null));
        capture.release();
        Intent message = ReportEmail.message(context, files);
        assertEquals(Intent.ACTION_SEND_MULTIPLE, message.getAction());
        assertEquals("*/*", message.getType());
        assertArrayEquals(new String[]{context.getString(R.string.report_email_address)}, message.getStringArrayExtra(Intent.EXTRA_EMAIL));
        java.util.ArrayList<Uri> uris = message.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        assertEquals(2, uris.size()); assertEquals(2, message.getClipData().getItemCount());
        for (int i = 0; i < files.size(); i++) {
            assertEquals(ReportAttachmentProvider.uri(files.get(i)), uris.get(i));
            assertEquals(uris.get(i), message.getClipData().getItemAt(i).getUri());
        }
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, message.getFlags());
        assertEquals(context.getString(R.string.report_body_prompt), message.getStringExtra(Intent.EXTRA_TEXT));
        String log = new String(Files.readAllBytes(files.get(1).toPath()), java.nio.charset.StandardCharsets.UTF_8);
        org.json.JSONObject json = new org.json.JSONObject(log);
        assertEquals(1, json.getInt("schemaVersion"));
        assertEquals(BuildConfig.VERSION_CODE, json.getInt("appVersionCode"));
        assertTrue(log.contains("CAPTURE STATE")); assertFalse(log.contains("private OCR/path"));
        assertEquals(files.get(0).getName().replace(".png", ""), json.getString("reportId"));
        ReportEmail.deleteAttachments(files);
        assertFalse(files.get(0).exists()); assertFalse(files.get(1).exists());
    }

    @Test public void failureWithoutImageStillHasReadableJsonAttachment() throws Exception {
        RecognitionDiagnostics trace = new RecognitionDiagnostics(1, null);
        trace.failure(RecognitionDiagnostics.Failure.NO_FRAME);
        trace.finish(RecognitionDiagnostics.Result.ERROR);
        List<File> files = ReportEmail.writeAttachments(context, null, ReportEmail.diagnostics(context, trace.snapshot()));
        assertEquals(1, files.size());
        Intent message = ReportEmail.message(context, files);
        assertEquals(Intent.ACTION_SEND, message.getAction()); assertEquals("application/json", message.getType());
        ReportAttachmentProvider provider = Robolectric.buildContentProvider(ReportAttachmentProvider.class).create().get();
        Uri uri = message.getParcelableExtra(Intent.EXTRA_STREAM);
        assertEquals("application/json", provider.getType(uri));
        try (ParcelFileDescriptor descriptor = provider.openFile(uri, "r")) { assertNotNull(descriptor); }
        try (android.database.Cursor cursor = provider.query(uri, null, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("diagnostics.json", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)));
        }
        assertThrows(FileNotFoundException.class, () -> provider.openFile(uri, "rw"));
        assertTrue(files.get(0).setLastModified(System.currentTimeMillis() - ReportAttachmentProvider.MAX_AGE_MS - 1000));
        assertThrows(FileNotFoundException.class, () -> provider.openFile(uri, "r"));
    }

    private ResolveInfo activity(String pkg, String name) {
        ResolveInfo result = new ResolveInfo(); result.activityInfo = new ActivityInfo();
        result.activityInfo.packageName = pkg; result.activityInfo.name = name;
        result.activityInfo.exported = true; result.activityInfo.enabled = true;
        return result;
    }

    @Test public void jsonEncodingFailureDoesNotLeaveAnOrphanScreenshot() throws Exception {
        File directory = ReportAttachmentProvider.directory(context);
        int before = directory.list() == null ? 0 : directory.list().length;
        CaptureJob job = job();
        ReportCapture capture = new ReportCapture(job, job.own(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)));
        job.finish();
        org.json.JSONObject broken = new org.json.JSONObject() {
            @Override public String toString() { throw new IllegalStateException("Synthetic encoding failure"); }
        };
        try {
            assertThrows(java.io.IOException.class, () -> ReportEmail.writeAttachments(context, capture, broken));
            assertEquals(before, directory.list() == null ? 0 : directory.list().length);
        } finally { capture.release(); }
    }

    @Test public void chooserExcludesSocialAppsAndPreservesAttachmentsForEmailTargets() {
        org.robolectric.shadows.ShadowPackageManager manager = Shadows.shadowOf(context.getPackageManager());
        Intent message = ReportEmail.message(context, List.of(
                new File(ReportAttachmentProvider.directory(context), UUID.randomUUID() + ".png"),
                new File(ReportAttachmentProvider.directory(context), UUID.randomUUID() + ".json")));
        Intent mailto = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        manager.addResolveInfoForIntent(mailto, activity("test.mail", "Mailto"));
        manager.addResolveInfoForIntent(message, activity("test.social", "Upload"));
        manager.addResolveInfoForIntent(new Intent(message).setPackage("test.mail"), activity("test.mail", "Compose"));
        List<Intent> targets = ReportEmail.targets(context, message);
        assertEquals(1, targets.size());
        assertEquals("test.mail", targets.get(0).getComponent().getPackageName());
        assertEquals(message.getClipData().getItemAt(0).getUri(), targets.get(0).getClipData().getItemAt(0).getUri());
        assertEquals(message.getParcelableArrayListExtra(Intent.EXTRA_STREAM), targets.get(0).getParcelableArrayListExtra(Intent.EXTRA_STREAM));
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, targets.get(0).getFlags());
        assertEquals(Intent.ACTION_CHOOSER, ReportEmail.chooser(context, targets).getAction());
    }
}
