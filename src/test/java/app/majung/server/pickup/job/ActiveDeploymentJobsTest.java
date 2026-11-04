package app.majung.server.pickup.job;

import app.majung.server.pickup.api.*;
import app.majung.server.pickup.common.*;
import app.majung.server.pickup.domain.*;
import app.majung.server.pickup.job.*;
import app.majung.server.pickup.push.*;
import app.majung.server.pickup.repository.*;
import app.majung.server.pickup.security.*;
import app.majung.server.pickup.service.*;
import app.majung.server.pickup.api.PickupApiTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class ActiveDeploymentJobsTest {
    @TempDir Path directory;
    @Test void onlyTheSelectedSlotRunsJobsAndSelectionCanChange() throws Exception {
        Path file = directory.resolve("active-slot");
        assertFalse(ActiveDeploymentJobs.allowed("blue", file));
        Files.writeString(file, "blue\n");
        assertTrue(ActiveDeploymentJobs.allowed("blue", file));
        assertFalse(ActiveDeploymentJobs.allowed("green", file));
        Files.writeString(file, "green\n");
        assertFalse(ActiveDeploymentJobs.allowed("blue", file));
        assertTrue(ActiveDeploymentJobs.allowed("green", file));
        Files.writeString(file, "invalid");
        assertFalse(ActiveDeploymentJobs.allowed("green", file));
        assertFalse(ActiveDeploymentJobs.allowed("other", file));
    }
}
