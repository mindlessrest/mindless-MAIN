package mindless.script;

import mindless.utility.Utils;

import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import java.util.Locale;

public class ScriptDiagnosticListener implements DiagnosticListener<JavaFileObject> {

    @Override
    public void report(final javax.tools.Diagnostic<? extends JavaFileObject> diagnostic) {
        final String message = diagnostic.getMessage(null);
        if (message.contains("SpongePowered")) {
            return;
        }
        JavaFileObject source = diagnostic.getSource();
        String sourceName = "unknown";
        int extraLines = 0;
        boolean isJavaSource = source instanceof JavaSourceFromString;

        if (isJavaSource) {
            sourceName = ((JavaSourceFromString) source).name;
            extraLines = ((JavaSourceFromString) source).extraLines;
        } else if (source != null) {
            sourceName = source.getName();
        }

        long line = diagnostic.getLineNumber() - extraLines;
        System.out.println("[Scripts] " + diagnostic.getKind() + " in " + sourceName + " line " + line + ": " + message.split("\n")[0]);

        if (source != null) {
            Utils.sendDebugMessage("§cError loading script §b" + Utils.extractFileName(sourceName));
            int indentIndex = message.indexOf("\n");
            String error = diagnostic.getMessage(Locale.getDefault());
            Utils.sendDebugMessage(" §7err: §c" + (indentIndex == -1 ? error : error.substring(0, indentIndex)));
            Utils.sendDebugMessage(" §7line: §c" + line);

            if (isJavaSource) {
                try {
                    String sourceContent = ((JavaSourceFromString) source).getCharContent(true).toString();
                    int startPos = (int) diagnostic.getStartPosition();
                    int endPos = (int) diagnostic.getEndPosition();
                    if (startPos >= 0 && endPos >= startPos && endPos <= sourceContent.length()) {
                        int srcIndentIndex = sourceContent.indexOf("\n", startPos);
                        if (srcIndentIndex != -1) {
                            Utils.sendDebugMessage(" §7src: §c" + sourceContent.substring(startPos, srcIndentIndex));
                        } else {
                            Utils.sendDebugMessage(" §7src: §c" + sourceContent.substring(startPos, endPos));
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }
}
