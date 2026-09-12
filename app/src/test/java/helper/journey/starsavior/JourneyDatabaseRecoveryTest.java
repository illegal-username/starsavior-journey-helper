package helper.journey.starsavior;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class JourneyDatabaseRecoveryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    enum Start { CURRENT_AND_PREVIOUS, CURRENT_ONLY, PREVIOUS_ONLY, CORRUPT_CURRENT, EMPTY, LEGACY_CURRENT, LEGACY_PREVIOUS }
    static final class ProcessStopped extends Error {}

    static class LocalOperations extends JourneyDatabaseFileStore.FileOperations {
        @Override void syncDirectory(File directory) throws IOException { /* No Android calls in the desktop fixture. */ }
    }

    final class Fixture {
        final File files;
        final File directory;
        final File other;
        final GameLanguage language;
        final String candidate;
        final List<String> oldHashes = new ArrayList<>();
        final String otherJson;

        Fixture(Start start, GameLanguage language) throws Exception {
            this.language = language;
            files = temporary.newFolder();
            directory = JourneyDatabaseFileStore.directory(files, language);
            assertTrue(directory.mkdirs());
            GameLanguage otherLanguage = language == GameLanguage.ENGLISH ? GameLanguage.JAPANESE : GameLanguage.ENGLISH;
            other = JourneyDatabaseFileStore.directory(files, otherLanguage);
            assertTrue(other.mkdirs());
            otherJson = DatabaseTestData.json(otherLanguage, "other-language", 20);
            put(JourneyDatabaseFileStore.updated(other), otherJson);
            put(JourneyDatabaseFileStore.previous(other), otherJson);
            candidate = DatabaseTestData.json(language, "new", 20);
            String current = DatabaseTestData.json(language, "old-current", 20);
            String previous = DatabaseTestData.json(language, "old-previous", 20);
            if (start == Start.CURRENT_AND_PREVIOUS || start == Start.CURRENT_ONLY) {
                put(JourneyDatabaseFileStore.updated(directory), current);
                oldHashes.add(JourneyRepository.parse(current).contentSha256);
            }
            if (start == Start.CURRENT_AND_PREVIOUS || start == Start.PREVIOUS_ONLY || start == Start.CORRUPT_CURRENT) {
                put(JourneyDatabaseFileStore.previous(directory), previous);
                oldHashes.add(JourneyRepository.parse(previous).contentSha256);
            }
            if (start == Start.CORRUPT_CURRENT) put(JourneyDatabaseFileStore.updated(directory), "{broken");
            if (start == Start.LEGACY_CURRENT || start == Start.LEGACY_PREVIOUS) {
                JSONObject legacy = new JSONObject(current).put("schema", 4).put("source", GameLanguage.LEGACY_DATABASE_URL);
                legacy.remove("language");
                String json = legacy.toString();
                put(start == Start.LEGACY_CURRENT ? JourneyDatabaseFileStore.updated(files)
                        : JourneyDatabaseFileStore.previous(files), json);
                oldHashes.add(JourneyRepository.parse(json).contentSha256);
            }
            // A stale temporary file must never take precedence over downloaded or legacy data.
            put(new File(directory, JourneyDatabaseFileStore.TEMP_NAME), "{stale");
        }

        void assertInvariant() throws Exception {
            JourneyModels.Data loaded = JourneyRepository.loadDownloaded(files, language);
            if (!oldHashes.isEmpty()) {
                assertNotNull("The normal loader must still find a valid DB", loaded);
                boolean oldSurvives = false;
                for (File file : Arrays.asList(JourneyDatabaseFileStore.updated(directory),
                        JourneyDatabaseFileStore.previous(directory), JourneyDatabaseFileStore.updated(files),
                        JourneyDatabaseFileStore.previous(files))) {
                    if (!file.isFile()) continue;
                    try {
                        JourneyModels.Data data = JourneyRepository.parseValidated(get(file), language);
                        oldSurvives |= oldHashes.contains(data.contentSha256);
                    } catch (JSONException invalid) { /* A corrupt current is not a surviving normal DB. */ }
                }
                assertTrue("At least one pre-existing valid DB must survive", oldSurvives);
            } else if (loaded != null) {
                assertEquals(JourneyRepository.parse(candidate).contentSha256, loaded.contentSha256);
            }
            assertEquals(otherJson, get(JourneyDatabaseFileStore.updated(other)));
            assertEquals(otherJson, get(JourneyDatabaseFileStore.previous(other)));
        }
    }

    final class FaultOperations extends LocalOperations {
        final Fixture fixture;
        final int failAt;
        final boolean stop;
        final List<String> points = new ArrayList<>();
        boolean injected;
        FaultOperations(Fixture fixture, int failAt, boolean stop) {
            this.fixture = fixture; this.failAt = failAt; this.stop = stop;
        }
        void point(String point) throws IOException {
            points.add(point);
            try { fixture.assertInvariant(); }
            catch (Exception error) { throw new AssertionError(point, error); }
            // Observe before catch/finally recovery can run: process-stop safety is not rollback safety.
            if (!injected && points.size() == failAt) {
                injected = true;
                if (stop) throw new ProcessStopped();
                throw new IOException("Injected " + point);
            }
        }
        @Override byte[] read(File file) throws IOException {
            point("read-before:" + file.getName());
            byte[] result = super.read(file);
            point("read-after:" + file.getName());
            return result;
        }
        @Override boolean delete(File file) throws IOException {
            point("delete-before:" + file.getName());
            boolean result = super.delete(file);
            point("delete-after:" + file.getName());
            return result;
        }
        @Override boolean rename(File from, File to) throws IOException {
            point("rename-before:" + from.getName());
            boolean result = super.rename(from, to);
            point("rename-after:" + from.getName());
            return result;
        }
        @Override void syncDirectory(File directory) throws IOException {
            point("directory-sync-before");
            point("directory-sync-after");
        }
        @Override JourneyDatabaseFileStore.WriteHandle openWrite(File file) throws IOException {
            point("open-before");
            JourneyDatabaseFileStore.WriteHandle output = super.openWrite(file);
            try { point("open-after"); }
            catch (IOException | ProcessStopped error) { output.close(); throw error; }
            return new JourneyDatabaseFileStore.WriteHandle() {
                public void write(byte[] bytes) throws IOException { point("write-before"); output.write(bytes); point("write-after"); }
                public void flush() throws IOException { point("flush-before"); output.flush(); point("flush-after"); }
                public void sync() throws IOException { point("file-sync-before"); output.sync(); point("file-sync-after"); }
                public void close() throws IOException {
                    try { point("close-before"); }
                    finally { output.close(); }
                    point("close-after");
                }
            };
        }
    }

    @Test public void everyIoBoundaryAndProcessStopPreservesAnOldValidatedDatabase() throws Exception {
        int scenarios = 0;
        for (Start start : Start.values()) {
            Fixture baseline = new Fixture(start, GameLanguage.KOREAN);
            FaultOperations trace = new FaultOperations(baseline, -1, false);
            JourneyDatabaseFileStore.install(baseline.directory, baseline.candidate, baseline.language, trace);
            for (int point = 1; point <= trace.points.size(); point++) {
                for (boolean stopped : new boolean[] {false, true}) {
                    Fixture fixture = new Fixture(start, GameLanguage.KOREAN);
                    FaultOperations operations = new FaultOperations(fixture, point, stopped);
                    try {
                        JourneyDatabaseFileStore.install(fixture.directory, fixture.candidate, fixture.language, operations);
                        fail("Expected injected failure at " + start + " " + point);
                    } catch (IOException | ProcessStopped expected) {
                        assertTrue(operations.injected);
                    }
                    fixture.assertInvariant();
                    // Same on-disk state can be used by a new loader and then updated successfully.
                    JourneyDatabaseFileStore.install(fixture.directory, fixture.candidate, fixture.language, new LocalOperations());
                    assertEquals(JourneyRepository.parse(fixture.candidate).contentSha256,
                            JourneyRepository.loadDownloaded(fixture.files, fixture.language).contentSha256);
                    scenarios++;
                }
            }
        }
        assertTrue(scenarios > 200);
        System.out.println("Recovery failure/process-stop scenarios: " + scenarios);
    }

    @Test public void successfulRotationAndLanguageIsolationWorkForAllLanguages() throws Exception {
        for (GameLanguage language : GameLanguage.values()) {
            Fixture fixture = new Fixture(Start.CURRENT_AND_PREVIOUS, language);
            String oldCurrent = get(JourneyDatabaseFileStore.updated(fixture.directory));
            JourneyDatabaseFileStore.install(fixture.directory, fixture.candidate, language, new LocalOperations());
            assertEquals(fixture.candidate, get(JourneyDatabaseFileStore.updated(fixture.directory)));
            assertEquals(oldCurrent, get(JourneyDatabaseFileStore.previous(fixture.directory)));
            fixture.assertInvariant();
        }
    }

    @Test public void partialWriteAndCleanupFailureKeepThePrimaryExceptionAndOldDatabase() throws Exception {
        Fixture fixture = new Fixture(Start.CORRUPT_CURRENT, GameLanguage.KOREAN);
        LocalOperations operations = new LocalOperations() {
            @Override boolean delete(File file) { return false; }
            @Override JourneyDatabaseFileStore.WriteHandle openWrite(File file) throws IOException {
                JourneyDatabaseFileStore.WriteHandle output = super.openWrite(file);
                return new JourneyDatabaseFileStore.WriteHandle() {
                    public void write(byte[] bytes) throws IOException {
                        output.write(Arrays.copyOf(bytes, bytes.length / 2));
                        throw new IOException("Partial write");
                    }
                    public void flush() throws IOException { output.flush(); }
                    public void sync() throws IOException { output.sync(); }
                    public void close() throws IOException { output.close(); }
                };
            }
        };
        IOException failure = assertThrows(IOException.class, () -> JourneyDatabaseFileStore.install(
                fixture.directory, fixture.candidate, fixture.language, operations));
        assertEquals("Partial write", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        fixture.assertInvariant();
    }

    @Test public void failedInstallationAndRestorationLeaveTheBackupReadable() throws Exception {
        Fixture fixture = new Fixture(Start.CURRENT_ONLY, GameLanguage.KOREAN);
        IOException error = assertThrows(IOException.class, () -> JourneyDatabaseFileStore.install(
                fixture.directory, fixture.candidate, fixture.language, new LocalOperations() {
                    @Override boolean rename(File from, File to) throws IOException {
                        return from.equals(JourneyDatabaseFileStore.updated(fixture.directory)) && super.rename(from, to);
                    }
                }));
        assertEquals(1, error.getSuppressed().length);
        assertFalse(JourneyDatabaseFileStore.updated(fixture.directory).exists());
        fixture.assertInvariant();
    }

    @Test public void backupDeletionFailureLeavesBothOldFilesUntouched() throws Exception {
        Fixture fixture = new Fixture(Start.CURRENT_AND_PREVIOUS, GameLanguage.KOREAN);
        String current = get(JourneyDatabaseFileStore.updated(fixture.directory));
        String previous = get(JourneyDatabaseFileStore.previous(fixture.directory));
        assertThrows(IOException.class, () -> JourneyDatabaseFileStore.install(
                fixture.directory, fixture.candidate, fixture.language, new LocalOperations() {
                    @Override boolean delete(File file) throws IOException {
                        return !file.equals(JourneyDatabaseFileStore.previous(fixture.directory)) && super.delete(file);
                    }
                }));
        assertEquals(current, get(JourneyDatabaseFileStore.updated(fixture.directory)));
        assertEquals(previous, get(JourneyDatabaseFileStore.previous(fixture.directory)));
        fixture.assertInvariant();
    }

    @Test public void damagedTemporaryBytesAreRejectedBeforeRotation() throws Exception {
        Fixture fixture = new Fixture(Start.CURRENT_AND_PREVIOUS, GameLanguage.KOREAN);
        assertThrows(IOException.class, () -> JourneyDatabaseFileStore.install(
                fixture.directory, fixture.candidate, fixture.language, new LocalOperations() {
                    @Override byte[] read(File file) throws IOException {
                        return file.getName().equals(JourneyDatabaseFileStore.TEMP_NAME)
                                ? "{damaged".getBytes(StandardCharsets.UTF_8) : super.read(file);
                    }
                }));
        fixture.assertInvariant();
    }

    @Test public void badOrWrongLanguageCandidatesNeverReachFileOperations() throws Exception {
        Fixture fixture = new Fixture(Start.PREVIOUS_ONLY, GameLanguage.KOREAN);
        FaultOperations operations = new FaultOperations(fixture, -1, false);
        for (String json : Arrays.asList("{broken", DatabaseTestData.json(GameLanguage.ENGLISH, "new", 20))) {
            assertThrows(JSONException.class, () -> JourneyDatabaseFileStore.install(fixture.directory, json,
                    fixture.language, operations));
        }
        assertTrue(operations.points.isEmpty());
        fixture.assertInvariant();
    }

    @Test public void loaderUsesPreviousLegacyAndNoTemporaryAsFallback() throws Exception {
        for (Start start : Start.values()) {
            Fixture fixture = new Fixture(start, GameLanguage.KOREAN);
            JourneyModels.Data data = JourneyRepository.loadDownloaded(fixture.files, fixture.language);
            if (start == Start.EMPTY) assertNull(data);
            else { assertNotNull(data); assertTrue(fixture.oldHashes.contains(data.contentSha256)); }
            if (start == Start.LEGACY_CURRENT || start == Start.LEGACY_PREVIOUS) {
                assertNull(JourneyRepository.loadDownloaded(fixture.files, GameLanguage.FRENCH));
            }
        }
    }

    private static void put(File file, String json) throws IOException {
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
    }
    private static String get(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
