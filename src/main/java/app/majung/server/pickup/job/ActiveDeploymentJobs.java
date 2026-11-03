package app.majung.server.pickup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Candidate servers serve health checks, while only the selected slot runs periodic jobs. */
final class ActiveDeploymentJobs {
    private ActiveDeploymentJobs() { }

    static boolean allowed() {
        String slot = System.getenv("MAJUNG_SLOT");
        if (slot == null || slot.isBlank()) return true;
        String file = System.getenv("MAJUNG_ACTIVE_SLOT_FILE");
        if (file == null || file.isBlank()) return false;
        return allowed(slot, Path.of(file));
    }

    static boolean allowed(String slot, Path file) {
        if (!slot.equals("blue") && !slot.equals("green")) return false;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 16) return false;
            return Files.readString(file).strip().equals(slot);
        } catch (IOException failure) {
            return false;
        }
    }
}
