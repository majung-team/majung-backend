package app.majung.server.pickup.common;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RestoreEntrypointTest {
    @Test
    void configuredRestoreLauncherExistsAndRejectsUnexpectedArguments() throws Exception {
        String script = Files.readString(Path.of("deploy/operations.py"));
        var configured = Pattern.compile("-Dloader\\.main=([A-Za-z0-9_.$]+)").matcher(script);
        assertTrue(configured.find(), "The restore command must select an application entry point");
        Class<?> entrypoint = Class.forName(configured.group(1));
        var main = entrypoint.getMethod("main", String[].class);
        assertTrue(Modifier.isPublic(main.getModifiers()));
        assertTrue(Modifier.isStatic(main.getModifiers()));
        var failure = assertThrows(InvocationTargetException.class,
                () -> main.invoke(null, (Object) new String[]{"unexpected"}));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
}
