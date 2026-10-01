package helper.journey.starsavior;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.json.JSONException;

final class ReportEmail {
    private ReportEmail() { }

    static File writeAttachment(Context context, ReportCapture capture) throws IOException {
        ReportAttachmentProvider.prune(context);
        File directory = ReportAttachmentProvider.directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create report directory");
        File file = new File(directory, UUID.randomUUID() + ".png");
        boolean complete = false;
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!capture.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("Cannot encode report image");
                }
            }
            complete = true;
        } finally {
            if (!complete) file.delete();
        }
        return file;
    }

    static JSONObject diagnostics(Context context, JSONObject recognition) {
        JSONObject result = new JSONObject();
        RecognitionDiagnostics.put(result, "schemaVersion", 1);
        RecognitionDiagnostics.put(result, "appVersion", BuildConfig.VERSION_NAME);
        RecognitionDiagnostics.put(result, "appVersionCode", BuildConfig.VERSION_CODE);
        RecognitionDiagnostics.put(result, "androidVersion", Build.VERSION.RELEASE);
        RecognitionDiagnostics.put(result, "androidApi", Build.VERSION.SDK_INT);
        RecognitionDiagnostics.put(result, "manufacturer", Build.MANUFACTURER);
        RecognitionDiagnostics.put(result, "model", Build.MODEL);
        RecognitionDiagnostics.put(result, "language", AppLanguage.of(context).locale().toLanguageTag());
        RecognitionDiagnostics.put(result, "recognition", recognition == null ? JSONObject.NULL : recognition);
        RecognitionDiagnostics.put(result, "recentErrors", AppDiagnostics.recentErrors());
        return result;
    }

    static List<File> writeAttachments(Context context, ReportCapture capture, JSONObject diagnostics) throws IOException {
        ReportAttachmentProvider.prune(context);
        File directory = ReportAttachmentProvider.directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create report directory");
        List<File> files = new ArrayList<>();
        try {
            if (capture != null) files.add(writeAttachment(context, capture));
            String id = files.isEmpty() ? UUID.randomUUID().toString() : files.get(0).getName().replace(".png", "");
            JSONObject document = new JSONObject(diagnostics.toString());
            RecognitionDiagnostics.put(document, "reportId", id);
            File log = new File(directory, id + ".json");
            files.add(log);
            Files.write(log.toPath(), document.toString(2).getBytes(StandardCharsets.UTF_8));
            return files;
        } catch (JSONException | IOException | RuntimeException error) {
            deleteAttachments(files);
            throw new IOException("Cannot prepare report attachments", error);
        }
    }

    static void deleteAttachments(List<File> files) { for (File file : files) file.delete(); }

    static Intent message(Context context, List<File> attachments) {
        Intent intent = new Intent(attachments.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND)
                .setType(attachments.size() > 1 ? "*/*" : "application/json")
                .putExtra(Intent.EXTRA_EMAIL, new String[]{context.getString(R.string.report_email_address)})
                .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.report_subject, BuildConfig.VERSION_NAME))
                .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.report_body_prompt));
        if (!attachments.isEmpty()) {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File file : attachments) uris.add(ReportAttachmentProvider.uri(file));
            if (uris.size() > 1) intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            else intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            ClipData clip = new ClipData("Report", attachments.size() > 1
                    ? new String[]{"image/png", "application/json"} : new String[]{"application/json"}, new ClipData.Item(uris.get(0)));
            for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
            intent.setClipData(clip);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return intent;
    }

    // Filter attachment targets using mailto handlers so
    // a private screenshot is never offered to unrelated social/upload applications.
    static List<Intent> targets(Context context, Intent message) {
        PackageManager manager = context.getPackageManager();
        Set<String> packages = new HashSet<>();
        for (ResolveInfo info : manager.queryIntentActivities(
                new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), PackageManager.MATCH_DEFAULT_ONLY)) {
            if (info.activityInfo != null && info.activityInfo.exported && info.activityInfo.enabled) {
                packages.add(info.activityInfo.packageName);
            }
        }
        List<Intent> result = new ArrayList<>();
        Set<ComponentName> seen = new HashSet<>();
        for (String name : packages) {
            Intent scoped = new Intent(message).setPackage(name);
            for (ResolveInfo info : manager.queryIntentActivities(scoped, PackageManager.MATCH_DEFAULT_ONLY)) {
                if (info.activityInfo == null || !info.activityInfo.exported || !info.activityInfo.enabled) continue;
                ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
                if (seen.add(component)) result.add(new Intent(message).setComponent(component));
            }
        }
        return result;
    }

    static Intent chooser(Context context, List<Intent> targets) {
        if (targets.isEmpty()) throw new IllegalArgumentException("No email app");
        Intent chooser = Intent.createChooser(targets.get(0), context.getString(R.string.report_open_email));
        if (targets.size() > 1) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,
                targets.subList(1, targets.size()).toArray(new Intent[0]));
        return chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
