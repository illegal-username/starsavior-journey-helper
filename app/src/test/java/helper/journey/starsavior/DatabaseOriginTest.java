package helper.journey.starsavior;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = DatabaseOriginTest.DirectorySync.class)
public class DatabaseOriginTest {
    /** Only the OS directory-sync/rename adapter is replaced; parsing and file installation are real. */
    @org.robolectric.annotation.Implements(android.system.Os.class)
    public static class DirectorySync {
        @org.robolectric.annotation.Implementation
        protected static java.io.FileDescriptor open(String path, int flags, int mode) {
            assertTrue("Only directory sync is emulated", new File(path).isDirectory());
            return new java.io.FileDescriptor();
        }
        @org.robolectric.annotation.Implementation
        protected static void fsync(java.io.FileDescriptor descriptor) {}
        @org.robolectric.annotation.Implementation
        protected static void close(java.io.FileDescriptor descriptor) {}
        @org.robolectric.annotation.Implementation
        protected static void rename(String source, String target) throws android.system.ErrnoException {
            try {
                Files.move(java.nio.file.Paths.get(source), java.nio.file.Paths.get(target),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException error) {
                throw new android.system.ErrnoException("rename", android.system.OsConstants.EIO, error);
            }
        }
    }

    @Test public void loadedFallbackAndInstalledReturnValuesHaveRuntimeOrigin() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        GameLanguage language = GameLanguage.ENGLISH;
        JourneyModels.Data example = JourneyRepository.load(context, language);
        assertEquals(JourneyModels.DatabaseOrigin.EXAMPLE, example.origin);
        assertTrue(JourneyRepository.isExampleDatabase(example));
        String json = DatabaseTestData.json(language, "origin-test", 20);
        JourneyModels.Data raw = JourneyRepository.parseValidated(json, language);
        assertEquals(JourneyModels.DatabaseOrigin.UNLOADED, raw.origin);
        JourneyModels.Data installed = JourneyRepository.installUpdated(context, json, language);
        assertEquals(JourneyModels.DatabaseOrigin.DOWNLOADED, installed.origin);
        assertEquals(raw.contentSha256, installed.contentSha256);
        assertEquals(raw.source, installed.source);
        assertEquals(raw.upstreamRevision, installed.upstreamRevision);
        assertEquals(JourneyModels.DatabaseOrigin.DOWNLOADED, JourneyRepository.load(context, language).origin);
        File directory = JourneyDatabaseFileStore.directory(context.getFilesDir(), language);
        Files.write(JourneyDatabaseFileStore.previous(directory).toPath(), json.getBytes(StandardCharsets.UTF_8));
        Files.write(JourneyDatabaseFileStore.updated(directory).toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        JourneyModels.Data recovered = JourneyRepository.load(context, language);
        assertEquals(JourneyModels.DatabaseOrigin.DOWNLOADED, recovered.origin);
        assertEquals(raw.contentSha256, recovered.contentSha256);
        assertEquals(JourneyModels.DatabaseOrigin.EXAMPLE,
                JourneyRepository.load(context, GameLanguage.JAPANESE).origin);
    }
}
