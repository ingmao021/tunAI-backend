import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Hook Stop de TunAI: no deja que Claude dé una tarea por terminada si {@code ./gradlew check}
 * falla (tests, ArchUnit, Checkstyle, Spotless y cobertura del 70 %, según la configuración del
 * build).
 *
 * <p>Solo se ejecuta si hay cambios sin commit en código o en archivos de build. Si la verificación
 * ya forzó un reintento en este turno ({@code stop_hook_active}), deja terminar para no entrar en
 * un ciclo.
 */
public class QualityGate {

    private static final Pattern STOP_HOOK_ACTIVE =
            Pattern.compile("\"stop_hook_active\"\\s*:\\s*true");
    private static final int TAIL_LINES = 60;
    private static final long TIMEOUT_MINUTES = 9;

    public static void main(String[] args) throws Exception {
        useUtf8Output();
        String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        if (STOP_HOOK_ACTIVE.matcher(input).find()) {
            System.exit(0);
        }

        Path root = Path.of(System.getenv().getOrDefault("CLAUDE_PROJECT_DIR", "."));
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (!Files.exists(root.resolve(windows ? "gradlew.bat" : "gradlew"))) {
            System.exit(0); // El proyecto Gradle todavía no existe.
        }
        if (!hasRelevantChanges(root)) {
            System.exit(0);
        }

        List<String> command = windows
                ? List.of("cmd", "/c", "gradlew.bat", "check", "--console=plain")
                : List.of("./gradlew", "check", "--console=plain");
        Path log = Files.createTempFile("tunai-quality-gate", ".log");
        Process process = new ProcessBuilder(command)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        String output = Files.readString(log, StandardCharsets.UTF_8);
        Files.deleteIfExists(log);

        if (!finished) {
            block("./gradlew check superó el tiempo límite.", output);
        }
        if (process.exitValue() != 0) {
            block("./gradlew check falló. Según la Definición de terminado, corrige el problema"
                    + " antes de terminar (si es formato, ejecuta ./gradlew spotlessApply).", output);
        }
        System.exit(0);
    }

    private static boolean hasRelevantChanges(Path root) {
        try {
            Process git = new ProcessBuilder("git", "status", "--porcelain")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String status = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (git.waitFor() != 0) {
                return true; // Sin git no se puede saber: verificar igualmente.
            }
            for (String line : status.split("\\R")) {
                if (line.length() < 4) {
                    continue;
                }
                String file = line.substring(3).replace('"', ' ').trim();
                if (file.startsWith("src/") || file.contains("/src/")
                        || file.endsWith(".gradle") || file.endsWith(".gradle.kts")
                        || file.endsWith("gradle.properties") || file.startsWith("config/")) {
                    return true;
                }
            }
            return false;
        } catch (IOException | InterruptedException e) {
            return true;
        }
    }

    private static void block(String reason, String output) {
        String[] lines = output.split("\\R");
        List<String> tail = new ArrayList<>();
        for (int i = Math.max(0, lines.length - TAIL_LINES); i < lines.length; i++) {
            tail.add(lines[i]);
        }
        System.err.println("Bloqueado por hook de TunAI: " + reason);
        System.err.println("Últimas líneas de Gradle:");
        System.err.println(String.join(System.lineSeparator(), tail));
        System.exit(2);
    }

    /** Fuerza UTF-8 en stdout/stderr para que las tildes lleguen bien en Windows, Linux y macOS. */
    private static void useUtf8Output() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }
}
