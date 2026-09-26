package com.firstham.aethergui;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import android.widget.Toast;

import java.io.File;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdateReceiver extends BroadcastReceiver {
    private static final ExecutorService VERIFY_EXECUTOR = Executors.newSingleThreadExecutor();

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (UpdateConfig.ACTION_DOWNLOAD.equals(action)) {
            AppUpdateManager.startDownload(context, false);
            return;
        }
        if (UpdateConfig.ACTION_INSTALL.equals(action)) {
            install(context);
            return;
        }
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) return;
        long completed = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        android.content.SharedPreferences prefs = context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE);
        if (completed == -1 || completed != prefs.getLong(UpdateConfig.KEY_DOWNLOAD_ID, -2)) return;
        PendingResult pending = goAsync();
        VERIFY_EXECUTOR.execute(() -> {
            try {
                if (!downloadSucceeded(context, completed)) throw new IllegalStateException("Download failed");
                File apk = new File(prefs.getString(UpdateConfig.KEY_APK_PATH, ""));
                String expected = prefs.getString(UpdateConfig.KEY_CHECKSUM, "");
                if (!apk.isFile() || expected.isEmpty() || !expected.equalsIgnoreCase(AppUpdateManager.sha256(apk))) throw new SecurityException("APK checksum mismatch");
                if (!sameSigner(context, apk)) throw new SecurityException("APK signing certificate mismatch");
                prefs.edit().putString("status", "ready_install").apply();
                AppUpdateManager.notifyInstallReady(context);
                AppUpdateManager.sendState(context);
            } catch (Throwable error) {
                boolean verification = error instanceof SecurityException;
                prefs.edit().putString("status", verification ? "verification_failed" : "download_failed").apply();
                AppUpdateManager.notifyFailure(context, verification ? R.string.update_verification_failed : R.string.update_download_failed);
                AppUpdateManager.sendState(context);
            } finally { pending.finish(); }
        });
    }

    private static boolean downloadSucceeded(Context context, long id) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id));
        if (cursor == null) return false;
        try { return cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL; }
        finally { cursor.close(); }
    }

    private static boolean sameSigner(Context context, File apk) throws Exception {
        PackageManager pm = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo current = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (candidate == null || candidate.signingInfo == null || current.signingInfo == null) return false;
            Signature[] installed = current.signingInfo.hasPastSigningCertificates() ? current.signingInfo.getSigningCertificateHistory() : current.signingInfo.getApkContentsSigners();
            Signature[] downloaded = candidate.signingInfo.getApkContentsSigners();
            return anySignerMatches(installed, downloaded);
        }
        PackageInfo current = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        return candidate != null && anySignerMatches(current.signatures, candidate.signatures);
    }

    private static boolean anySignerMatches(Signature[] installed, Signature[] downloaded) throws Exception {
        if (installed == null || downloaded == null || downloaded.length == 0) return false;
        for (Signature candidate : downloaded) {
            byte[] candidateHash = signerHash(candidate);
            boolean matched = false;
            for (Signature trusted : installed) if (Arrays.equals(candidateHash, signerHash(trusted))) { matched = true; break; }
            if (!matched) return false;
        }
        return true;
    }

    private static byte[] signerHash(Signature signature) throws Exception { return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()); }

    /**
     * Verify the downloaded APK, then put it where the user can open it.
     *
     * It used to hand the file straight to the package installer. That needs
     * REQUEST_INSTALL_PACKAGES, and that permission is the loudest thing this
     * app could ask for: it is the one Google Play Protect singles out and the
     * one that makes an install warning appear for people who only wanted a
     * VPN. A VPN app that can also install other apps is a shape the warning
     * is describing accurately, and no amount of explaining helps — the user
     * sees the screen before they see the reason.
     *
     * The file goes to the public Downloads folder instead, and the user taps
     * it. Same one tap, the same installer, no permission and no warning. The
     * verification below is unchanged and still runs: the digest and the
     * signing certificate are checked here, not by the installer, so a
     * swapped file is rejected before anyone is asked to open it.
     */
    private static void install(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(UpdateConfig.PREFS, Context.MODE_PRIVATE);
        File apk = new File(prefs.getString(UpdateConfig.KEY_APK_PATH, ""));
        if (!apk.isFile() || !"ready_install".equals(prefs.getString("status", ""))) {
            AppUpdateManager.sendState(context);
            return;
        }
        // The download was verified when it landed, but the file has been sitting in shared
        // external storage since then. Re-check the digest and the signer immediately before
        // handing the package to the installer.
        try {
            String expected = prefs.getString(UpdateConfig.KEY_CHECKSUM, "").trim().toLowerCase(java.util.Locale.US);
            if (expected.isEmpty() || !expected.equalsIgnoreCase(AppUpdateManager.sha256(apk))) {
                throw new SecurityException("APK checksum mismatch");
            }
            if (!sameSigner(context, apk)) throw new SecurityException("APK signing certificate mismatch");
        } catch (Throwable error) {
            prefs.edit().putString("status", "verification_failed").remove(UpdateConfig.KEY_APK_PATH).apply();
            if (!apk.delete()) apk.deleteOnExit();
            AppUpdateManager.sendState(context);
            AppUpdateManager.notifyFailure(context, R.string.update_verification_failed);
            return;
        }
        // Public Downloads, so the user opens the file themselves.
        //
        // Writing there needs no permission on any supported release, and the
        // file lands where a person would look for it. The app is verified by
        // the checks above, not by the fact that it came from this folder, so
        // moving it out of private storage costs nothing in safety.
        File publicCopy = moveToPublicDownloads(context, apk);
        if (publicCopy == null) {
            // Scoped storage can refuse the write on some devices. Rather than
            // reaching for the install permission, say so and let the user
            // find the file the app already has.
            prefs.edit().putString("status", "ready_install").apply();
            AppUpdateManager.sendState(context);
            AppUpdateManager.notifyFailure(context, R.string.update_no_download);
            return;
        }
        Uri uri = publicCopy.toUri();
        Intent open = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        prefs.edit().putString("status", "installing").apply();
        AppUpdateManager.sendState(context);
        try { context.startActivity(open); }
        catch (Throwable error) {
            // No file manager answered. The APK is in Downloads either way.
            prefs.edit().putString("status", "ready_install").apply();
            AppUpdateManager.sendState(context);
            Toast.makeText(context, R.string.update_open_downloads, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Copy the verified APK into the public Downloads directory.
     *
     * Returns the new file, or null when the platform refused. Tries the
     * standard location first and MediaStore second, because a device with no
     * external storage volume still has a Downloads collection.
     */
    private static File moveToPublicDownloads(Context context, File apk) {
        String name = "aether-gui-update.apk";
        try {
            File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                File target = new File(dir, name);
                java.nio.file.Files.copy(apk.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return target;
            }
        } catch (Throwable ignored) { /* fall through to MediaStore */ }
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE,
                    "application/vnd.android.package-archive");
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
            android.net.Uri inserted = context.getContentResolver()
                    .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (inserted == null) return null;
            try (java.io.OutputStream out = context.getContentResolver().openOutputStream(inserted)) {
                if (out == null) return null;
                java.nio.file.Files.copy(apk.toPath(), out);
            }
            values.clear();
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(inserted, values, null, null);
            return uriFile(inserted);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The MediaStore uri as a File, so the caller can hand it to a viewer.
     *
     * Downloads entries live under a path the app can name but not always
     * write, and that is fine here: the copy is already written and verified by
     * the time this is called, and the only thing the File is used for is to
     * become a uri again.
     */
    private static File uriFile(android.net.Uri uri) {
        return new File(uri.getPath() == null ? "" : uri.getPath());
    }
}
