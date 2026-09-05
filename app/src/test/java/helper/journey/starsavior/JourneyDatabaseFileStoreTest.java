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
        Files.write(current.toPath(), "old database".getBytes(StandardCharsets.UTF_8));

        JourneyDatabaseFileStore.FileOperations failNewDatabaseRename =
                new JourneyDatabaseFileStore.FileOperations() {
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
                directory, "new database", failNewDatabaseRename));

        assertTrue(current.isFile());
        assertEquals("old database", new String(
                Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8));
        assertFalse(JourneyDatabaseFileStore.previous(directory).exists());
    }

    @Test
    public void failedBackupLeavesCurrentDatabaseUntouched() throws Exception {
        File directory = temporary.newFolder("database");
        File current = JourneyDatabaseFileStore.updated(directory);
        Files.write(current.toPath(), "old database".getBytes(StandardCharsets.UTF_8));

        JourneyDatabaseFileStore.FileOperations failBackup =
                new JourneyDatabaseFileStore.FileOperations() {
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
                directory, "new database", failBackup));

        assertEquals("old database", new String(
                Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8));
    }
}
