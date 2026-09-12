package helper.journey.starsavior;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONException;

import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.Arrays;

/** Keeps a validated old database readable throughout an installation. Caller holds FILE_LOCK. */
final class JourneyDatabaseFileStore {
    static final String UPDATED_NAME = "journey_choices_updated.json";
    static final String PREVIOUS_NAME = "journey_choices_previous.json";
    static final String TEMP_NAME = "journey_choices_update.tmp";

    interface WriteHandle extends Closeable {
        void write(byte[] bytes) throws IOException;
        void flush() throws IOException;
        void sync() throws IOException;
    }

    /** Each operation is a separate failure boundary; tests use real temporary files. */
    static class FileOperations {
        WriteHandle openWrite(File file) throws IOException {
            FileOutputStream output = new FileOutputStream(file, false);
            return new WriteHandle() {
                public void write(byte[] bytes) throws IOException { output.write(bytes); }
                public void flush() throws IOException { output.flush(); }
                public void sync() throws IOException { output.getFD().sync(); }
                public void close() throws IOException { output.close(); }
            };
        }

        byte[] read(File file) throws IOException { return Files.readAllBytes(file.toPath()); }
        boolean delete(File file) throws IOException { return file.delete(); }
        boolean rename(File source, File target) throws IOException { return source.renameTo(target); }

        void syncDirectory(File directory) throws IOException {
            FileDescriptor descriptor = null;
            IOException failure = null;
            try {
                descriptor = Os.open(directory.getPath() + File.separator, OsConstants.O_RDONLY, 0);
                Os.fsync(descriptor);
            } catch (ErrnoException error) {
                failure = new IOException("Cannot sync the database directory.", error);
                throw failure;
            } finally {
                if (descriptor != null) {
                    try {
                        Os.close(descriptor);
                    } catch (ErrnoException error) {
                        IOException close = new IOException("Cannot close the database directory.", error);
                        if (failure != null) failure.addSuppressed(close);
                        else throw close;
                    }
                }
            }
        }
    }

    private static final FileOperations SYSTEM = new FileOperations() {
        @Override boolean rename(File source, File target) throws IOException {
            try {
                Os.rename(source.getPath(), target.getPath());
                return true;
            } catch (ErrnoException error) {
                throw new IOException("Cannot rename the database file.", error);
            }
        }
    };
    private JourneyDatabaseFileStore() {}

    static File directory(File files, GameLanguage language) {
        return new File(new File(files, "databases-v5"), language.tag);
    }

    static File updated(File directory) { return new File(directory, UPDATED_NAME); }
    static File previous(File directory) { return new File(directory, PREVIOUS_NAME); }

    static void install(File directory, String json, GameLanguage language) throws IOException, JSONException {
        install(directory, json, language, SYSTEM);
    }

    static void install(File directory, String json, FileOperations operations) throws IOException, JSONException {
        install(directory, json, GameLanguage.require(JourneyRepository.parse(json).language), operations);
    }

    static void install(File directory, String json, GameLanguage language, FileOperations operations)
            throws IOException, JSONException {
        JourneyRepository.parseValidated(json, language);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create database directory.");
        }
        File current = updated(directory);
        File previous = previous(directory);
        File temporary = new File(directory, TEMP_NAME);
        // Parsing failures mean invalid data; an I/O failure must stop destructive rotation.
        boolean currentIsValid = validExisting(current, language, operations);
        boolean previousIsValid = validExisting(previous, language, operations);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        try {
            try (WriteHandle output = operations.openWrite(temporary)) {
                output.write(bytes);
                output.flush();
                output.sync();
            }
            verifyBytes(temporary, bytes, operations);

            if (currentIsValid) {
                remove(previous, operations, "Cannot remove the previous database backup.");
                if (!operations.rename(current, previous)) {
                    throw new IOException("Cannot back up the current database.");
                }
                previousIsValid = true;
            } else {
                // A usable previous is left in place; corrupt current is never promoted over it.
                remove(current, operations, "Cannot remove the invalid current database.");
            }
            operations.syncDirectory(directory);
            if (!operations.rename(temporary, current)) {
                throw new IOException("Cannot install the new database.");
            }
            operations.syncDirectory(directory);
            verifyBytes(current, bytes, operations);
            // Commit: no backup deletion or interrupt check after the installed bytes are confirmed.
        } catch (IOException failure) {
            if (!current.exists() && previousIsValid && previous.isFile()) {
                try {
                    if (!operations.rename(previous, current)) {
                        throw new IOException("Cannot restore the database; the previous copy is retained.");
                    }
                    operations.syncDirectory(directory);
                } catch (IOException recovery) {
                    failure.addSuppressed(recovery);
                }
            }
            try {
                remove(temporary, operations, "Cannot remove the incomplete database temporary file.");
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static boolean validExisting(File file, GameLanguage language, FileOperations operations)
            throws IOException {
        byte[] bytes;
        try {
            bytes = operations.read(file);
        } catch (NoSuchFileException absent) {
            return false;
        }
        try {
            JourneyRepository.parseValidated(new String(bytes, StandardCharsets.UTF_8), language);
            return true;
        } catch (JSONException invalid) {
            return false;
        }
    }

    private static void verifyBytes(File file, byte[] expected, FileOperations operations) throws IOException {
        if (!Arrays.equals(expected, operations.read(file))) {
            throw new IOException("Database file verification failed.");
        }
    }

    private static void remove(File file, FileOperations operations, String message) throws IOException {
        if (file.exists() && !operations.delete(file)) throw new IOException(message);
    }
}
