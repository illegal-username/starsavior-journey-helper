package helper.journey.starsavior;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JourneyDatabaseFileStoreTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void failedFinalReplacementRestoresThePreviouslyUsableDatabase() throws Exception {
        File directory = temporary.newFolder("database");
        File current = JourneyDatabaseFileStore.updated(directory);
        Files.write(current.toPath(), DatabaseTestData.json(GameLanguage.KOREAN, "old", 20).getBytes(StandardCharsets.UTF_8));

        JourneyDatabaseFileStore.FileOperations failNewDatabaseRename =
                new JourneyDatabaseFileStore.FileOperations() {
                    @Override public void syncDirectory(File directory) { /* Android adapter tested separately. */ }
                    @Override
                    public boolean delete(File file) {
                        return file.delete();
                    }

                    @Override
                    public boolean rename(File source, File target) {
                        if (source.getName().equals(JourneyDatabaseFileStore.TEMP_NAME)) return false;
                        return source.renameTo(target);
                    }
                };

        assertThrows(IOException.class, () -> JourneyDatabaseFileStore.install(
                directory, DatabaseTestData.json(GameLanguage.KOREAN, "new", 20), failNewDatabaseRename));

        assertTrue(current.isFile());
        assertEquals(DatabaseTestData.json(GameLanguage.KOREAN, "old", 20), new String(
                Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8));
        assertFalse(JourneyDatabaseFileStore.previous(directory).exists());
    }

    @Test
    public void missingCurrentMustNotDeleteTheOnlyPreviousOnInstallFailure() throws Exception {
        File directory = temporary.newFolder("missing-current");
        File previous = JourneyDatabaseFileStore.previous(directory);
        String old = DatabaseTestData.json(GameLanguage.KOREAN, "old", 20);
        Files.write(previous.toPath(), old.getBytes(StandardCharsets.UTF_8));
        JourneyDatabaseFileStore.FileOperations operations = new JourneyDatabaseFileStore.FileOperations() {
                    @Override public void syncDirectory(File directory) { /* Android adapter tested separately. */ }
            public boolean delete(File file) { return file.delete(); }
            public boolean rename(File from, File to) {
                return !from.getName().equals(JourneyDatabaseFileStore.TEMP_NAME) && from.renameTo(to);
            }
        };
        assertThrows(IOException.class, () -> JourneyDatabaseFileStore.install(
                directory, DatabaseTestData.json(GameLanguage.KOREAN, "new", 20), operations));
        File survivor = JourneyDatabaseFileStore.updated(directory).isFile()
                ? JourneyDatabaseFileStore.updated(directory) : previous;
        assertTrue("A pre-existing valid DB must survive", survivor.isFile());
        assertEquals(old, new String(Files.readAllBytes(survivor.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void corruptCurrentMustNotReplaceTheValidPreviousOnInstallFailure() throws Exception {
        File directory = temporary.newFolder("corrupt-current");
        File previous = JourneyDatabaseFileStore.previous(directory);
        String old = DatabaseTestData.json(GameLanguage.KOREAN, "old", 20);
        Files.write(previous.toPath(), old.getBytes(StandardCharsets.UTF_8));
        Files.write(JourneyDatabaseFileStore.updated(directory).toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        JourneyDatabaseFileStore.FileOperations operations = new JourneyDatabaseFileStore.FileOperations() {
                    @Override public void syncDirectory(File directory) { /* Android adapter tested separately. */ }
            public boolean delete(File file) { return file.delete(); }
            public boolean rename(File from, File to) {
                return !from.getName().equals(JourneyDatabaseFileStore.TEMP_NAME) && from.renameTo(to);
            }
        };
        assertThrows(IOException.class, () -> JourneyDatabaseFileStore.install(
                directory, DatabaseTestData.json(GameLanguage.KOREAN, "new", 20), operations));
        File survivor = JourneyDatabaseFileStore.updated(directory).isFile()
                ? JourneyDatabaseFileStore.updated(directory) : previous;
        assertTrue(survivor.isFile());
        assertEquals(old, new String(Files.readAllBytes(survivor.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void failedBackupLeavesCurrentDatabaseUntouched() throws Exception {
        File directory = temporary.newFolder("database");
        File current = JourneyDatabaseFileStore.updated(directory);
        Files.write(current.toPath(), DatabaseTestData.json(GameLanguage.KOREAN, "old", 20).getBytes(StandardCharsets.UTF_8));

        JourneyDatabaseFileStore.FileOperations failBackup =
                new JourneyDatabaseFileStore.FileOperations() {
                    @Override public void syncDirectory(File directory) { /* Android adapter tested separately. */ }
                    @Override
                    public boolean delete(File file) {
                        return file.delete();
                    }

                    @Override
                    public boolean rename(File source, File target) {
                        return !source.equals(current) && source.renameTo(target);
                    }
                };

        assertThrows(IOException.class, () -> JourneyDatabaseFileStore.install(
                directory, DatabaseTestData.json(GameLanguage.KOREAN, "new", 20), failBackup));

        assertEquals(DatabaseTestData.json(GameLanguage.KOREAN, "old", 20), new String(
                Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8));
    }
}
