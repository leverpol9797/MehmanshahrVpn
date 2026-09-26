package com.firstham.aethergui;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The updater no longer asks for permission to install, so it has to put the
 * verified APK somewhere the user can open themselves — and the uri it hands
 * over has to be one another app is actually allowed to read.
 *
 * Both of those were wrong once. The first version called File.toUri(), which
 * does not exist, and the build only found out on the runner. The second would
 * have used Uri.fromFile(), which exists but throws FileUriExposedException the
 * moment the installer is a different process. Neither is visible from a unit
 * test on a real device, so they are pinned here by reading the source.
 */
public final class UpdaterWithoutInstallPermissionTest {

    private static String source() throws Exception {
        Path path = Path.of("src", "main", "java", "com", "firstham", "aethergui", "AppUpdateReceiver.java");
        assertTrue("AppUpdateReceiver.java not found at " + path.toAbsolutePath(), Files.exists(path));
        return Files.readString(path);
    }

    @Test public void theInstallPermissionIsNotRequested() throws Exception {
        String manifest = Files.readString(Path.of("src", "main", "AndroidManifest.xml"));
        assertTrue("REQUEST_INSTALL_PACKAGES is the permission Play Protect flags, "
                        + "and the updater no longer needs it",
                !manifest.contains("REQUEST_INSTALL_PACKAGES"));
    }

    @Test public void theUpdaterNeverAsksToBeAnInstaller() throws Exception {
        String source = source();
        assertTrue("canRequestPackageInstalls is gone with the permission",
                !source.contains("canRequestPackageInstalls"));
        assertTrue("MANAGE_UNKNOWN_APP_SOURCES is gone with the permission",
                !source.contains("MANAGE_UNKNOWN_APP_SOURCES"));
    }

    @Test public void theApkIsHandedOverAsAContentUriNotAFileUri() throws Exception {
        String source = source();
        assertTrue("Uri.fromFile is refused across process boundaries on Android 7+",
                !source.contains("Uri.fromFile"));
        // MediaStore on Android 10+, FileProvider before it. Both are content://.
        assertTrue("the MediaStore path should be the modern route",
                source.contains("MediaStore.Downloads.EXTERNAL_CONTENT_URI"));
        assertTrue("the pre-Android-10 path should go through the FileProvider",
                source.contains("FileProvider.getUriForFile"));
    }

    @Test public void theIntentThatOpensTheApkCarriesTheApkMimeType() throws Exception {
        String source = source();
        assertTrue("the viewer picks its handler from the mime type",
                source.contains("setDataAndType"));
        assertTrue("the apk mime type should be a constant, not a literal at the call site",
                source.contains("APK_MIME"));
    }

    @Test public void thePublisherIsDeclaredToReturnAUriNotAFile() throws Exception {
        Method publish = AppUpdateReceiver.class.getDeclaredMethod("publishVerifiedApk", android.content.Context.class, File.class);
        assertEquals("the caller passes this straight into setDataAndType, so a File here is what broke the build",
                android.net.Uri.class, publish.getReturnType());
        assertTrue(Modifier.isPrivate(publish.getModifiers()));
    }

    @Test public void everyFileProviderPathIsScopedToADownloadsFolder() throws Exception {
        String paths = Files.readString(Path.of("src", "main", "res", "xml", "update_file_paths.xml"));
        List<String> roots = new ArrayList<>();
        for (String line : paths.split("\n")) {
            if (line.contains("<external-path") || line.contains("<external-files-path")
                    || line.contains("<root-path") || line.contains("<files-path")) {
                int at = line.indexOf("path=\"");
                if (at >= 0) roots.add(line.substring(at + 6, line.indexOf('"', at + 6)));
            }
        }
        assertTrue("no FileProvider path is declared, so the pre-Android-10 route cannot work", !roots.isEmpty());
        for (String root : roots) {
            assertTrue("FileProvider path \"" + root + "\" reaches outside Downloads",
                    root.contains("Download"));
        }
        assertTrue("a root path would hand out the entire external volume", !paths.contains("<root-path"));
    }
}
