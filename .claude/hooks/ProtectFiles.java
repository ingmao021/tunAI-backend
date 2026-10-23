import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hook PreToolUse (Edit|Write) de TunAI.
 *
 * <ul>
 *   <li>Bloquea cualquier archivo que contenga algo con forma de clave de API (sk-... o tsk_...).
 *   <li>Bloquea la edición de archivos .env.
 *   <li>En el frontend, bloquea que el código fuente mencione TRIPO_API_KEY.
 *   <li>Pide confirmación antes de modificar los PRD (fuente de verdad).
 * </ul>
 *
 * <p>Salida: exit 2 + stderr = bloquear; JSON en stdout = pedir confirmación; exit 0 = continuar.
 */
public class ProtectFiles {

    private static final Pattern FILE_PATH =
            Pattern.compile("\"file_path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern API_KEY = Pattern.compile("sk-[A-Za-z0-9]{32,}|tsk_[A-Za-z0-9_-]{32,}");
    private static final Pattern PRD = Pattern.compile(".*/docs/PRD-TunAI-[^/]*\\.md");

    public static void main(String[] args) throws IOException {
        useUtf8Output();
        String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        String path = extractFilePath(input);
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        boolean frontend = "frontend".equals(System.getProperty("tunai.role"));

        if (API_KEY.matcher(input).find()) {
            block("El contenido parece una clave de API (sk-... o tsk_...). Las claves solo se configuran"
                    + " como variables de entorno, nunca en archivos del repositorio.");
        }
        if (fileName.equals(".env") || fileName.startsWith(".env.")) {
            block("No se editan archivos .env: los secretos van en variables de entorno (Render o"
                    + " la terminal local).");
        }
        if (frontend && path.contains("/src/") && input.contains("TRIPO_API_KEY")) {
            block("El cliente de escritorio nunca debe conocer la clave de Tripo: solo habla"
                    + " con el backend de TunAI (PRD frontend §18).");
        }
        if (PRD.matcher(path).matches()) {
            ask("El PRD es la fuente de verdad del proyecto. Confirma si autorizas modificarlo.");
        }
        System.exit(0);
    }

    private static String extractFilePath(String json) {
        Matcher m = FILE_PATH.matcher(json);
        if (!m.find()) {
            return "";
        }
        return m.group(1).replace("\\\\", "\\").replace("\\/", "/").replace('\\', '/');
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
