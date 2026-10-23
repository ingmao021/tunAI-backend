import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hook PreToolUse (Bash) de TunAI.
 *
 * <ul>
 *   <li>Bloquea comandos que impriman la clave de Tripo.
 *   <li>Bloquea git push --force y borrados masivos (rm -rf de raíz, home o directorio actual).
 *   <li>Pide confirmación antes de llamar a la API real de Tripo (cada generación gasta créditos).
 * </ul>
 */
public class GuardBash {

    private static final Pattern COMMAND =
            Pattern.compile("\"command\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PRINTS_SECRET = Pattern.compile(
            "\\b(echo|printf|printenv|cat|env|set|export)\\b[^\\n]*TRIPO_API_KEY");
    private static final Pattern FORCE_PUSH =
            Pattern.compile("\\bgit\\s+push\\b[^\\n]*(\\s--force\\b|\\s-f\\b|\\s--force-with-lease\\b)");
    private static final Pattern MASS_DELETE =
            Pattern.compile("\\brm\\s+-[a-zA-Z]*[rR][a-zA-Z]*\\s+(/|~|\\.|\\*|\\$HOME)(\\s|$|/\\s|/$)");
    private static final Pattern REAL_PROVIDER_CALL = Pattern.compile("openapi\\.tripo3d\\.ai");

    public static void main(String[] args) throws IOException {
        useUtf8Output();
        String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        String command = extractCommand(input);

        if (PRINTS_SECRET.matcher(command).find()) {
            block("este comando mostraría la clave de Tripo. Nunca imprimas secretos.");
        }
        if (FORCE_PUSH.matcher(command).find()) {
            block("git push --force no está permitido; crea un commit nuevo en su lugar.");
        }
        if (MASS_DELETE.matcher(command).find()) {
            block("borrado masivo detectado (rm -rf sobre /, ~, . o *). Borra rutas concretas.");
        }
        if (REAL_PROVIDER_CALL.matcher(command).find()) {
            ask("Este comando llama a la API real de Tripo y puede gastar créditos (presupuesto: 2"
                    + " generaciones de 40 créditos). Confirma si lo autorizas.");
        }
        System.exit(0);
    }

    private static String extractCommand(String json) {
        Matcher m = COMMAND.matcher(json);
        if (!m.find()) {
            return "";
        }
        return m.group(1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static void block(String reason) {
        System.err.println("Bloqueado por hook de TunAI: " + reason);
        System.exit(2);
    }

    private static void ask(String reason) {
        System.out.println("{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\","
                + "\"permissionDecision\":\"ask\",\"permissionDecisionReason\":\""
                + reason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}}");
        System.exit(0);
    }

    /** Fuerza UTF-8 en stdout/stderr para que las tildes lleguen bien en Windows, Linux y macOS. */
    private static void useUtf8Output() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }
}
