package helper.journey.starsavior;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Durable current/previous database rotation with injectable file operations for recovery tests. */
final class JourneyDatabaseFileStore {
    static final String UPDATED_NAME = "journey_choices_updated.json";
    static final String PREVIOUS_NAME = "journey_choices_previous.json";
    static final String TEMP_NAME = "journey_choices_update.tmp";

    interface FileOperations {
        boolean delete(File file);
        boolean rename(File source, File target);
    }

    private static final FileOperations SYSTEM = new FileOperations() {
        @Override
        public boolean delete(File file) {
            return file.delete();
        }

        @Override
        public boolean rename(File source, File target) {
            return source.renameTo(target);
        }
    };

    private JourneyDatabaseFileStore() {}

    static File updated(File directory) {
        return new File(directory, UPDATED_NAME);
    }

    static File previous(File directory) {
        return new File(directory, PREVIOUS_NAME);
    }

    static void install(File directory, String json) throws IOException {
        install(directory, json, SYSTEM);
    }

    static void install(File directory, String json, FileOperations operations) throws IOException {
        File current = updated(directory);
        File previous = previous(directory);
        File temporary = new File(directory, TEMP_NAME);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }

        if (previous.exists() && !operations.delete(previous)) {
            throw new IOException("이전 DB 백업을 정리하지 못했습니다.");
        }
        if (current.exists() && !operations.rename(current, previous)) {
            throw new IOException("현재 DB를 백업하지 못했습니다.");
        }
        if (!operations.rename(temporary, current)) {
            boolean restored = !previous.exists() || operations.rename(previous, current);
            if (!restored) {
                throw new IOException("새 DB 적용과 기존 DB 복구에 실패했습니다.");
            }
            throw new IOException("새 DB를 적용하지 못했습니다.");
        }
    }
}
