package mindless.utility;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import mindless.Mindless;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;

import java.awt.Canvas;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FileDropManager {
    private static final int GWL_WNDPROC = -4;
    private static final int WM_DROPFILES = 0x0233;
    private static final int WM_COPYGLOBALDATA = 0x0049;
    private static final int MSGFLT_ALLOW = 1;
    private static DropTarget awtDropTarget;
    private static Pointer window;
    private static Pointer previousWindowProc;
    private static WindowProc windowProc;

    private FileDropManager() {
    }

    public static synchronized void install() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        if (awtDropTarget != null || windowProc != null) return;
        if (installAwt()) return;
        installWindows();
    }

    public static synchronized void uninstall() {
        if (awtDropTarget != null) {
            try {
                awtDropTarget.setActive(false);
                awtDropTarget.getComponent().setDropTarget(null);
            }
            catch (Throwable ignored) {
            }
            awtDropTarget = null;
        }
        if (window != null && previousWindowProc != null) {
            try {
                Shell32.INSTANCE.DragAcceptFiles(window, false);
                if (Native.POINTER_SIZE == 8) {
                    User32.INSTANCE.SetWindowLongPtrW(window, GWL_WNDPROC, previousWindowProc);
                }
                else {
                    User32.INSTANCE.SetWindowLongW(window, GWL_WNDPROC, previousWindowProc);
                }
            }
            catch (Throwable ignored) {
            }
        }
        window = null;
        previousWindowProc = null;
        windowProc = null;
    }

    private static boolean installAwt() {
        try {
            Canvas parent = Display.getParent();
            if (parent == null) return false;
            awtDropTarget = new DropTarget(parent, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
                @Override
                public void drop(DropTargetDropEvent event) {
                    boolean accepted = false;
                    try {
                        if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            event.rejectDrop();
                            return;
                        }
                        event.acceptDrop(DnDConstants.ACTION_COPY);
                        Object value = event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                        if (value instanceof List) {
                            List<File> files = new ArrayList<>();
                            for (Object item : (List<?>) value) {
                                if (item instanceof File) files.add((File) item);
                            }
                            queueImport(files);
                            accepted = true;
                        }
                    }
                    catch (Throwable ignored) {
                    }
                    finally {
                        event.dropComplete(accepted);
                    }
                }
            }, true);
            return true;
        }
        catch (Throwable ignored) {
            return false;
        }
    }

    private static void installWindows() {
        try {
            int processId = Kernel32.INSTANCE.GetCurrentProcessId();
            final Pointer[] first = new Pointer[1];
            final Pointer[] lwjgl = new Pointer[1];
            User32.INSTANCE.EnumWindows((candidate, data) -> {
                IntByReference owner = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(candidate, owner);
                if (owner.getValue() != processId || !User32.INSTANCE.IsWindowVisible(candidate)) return true;
                if (first[0] == null) first[0] = candidate;
                char[] className = new char[128];
                User32.INSTANCE.GetClassNameW(candidate, className, className.length);
                if (Native.toString(className).toLowerCase(Locale.ROOT).contains("lwjgl")) {
                    lwjgl[0] = candidate;
                    return false;
                }
                return true;
            }, null);
            window = lwjgl[0] != null ? lwjgl[0] : first[0];
            if (window == null) return;
            windowProc = (handle, message, wParam, lParam) -> {
                if (message == WM_DROPFILES) {
                    receiveWindowsDrop(wParam);
                    return Pointer.NULL;
                }
                return User32.INSTANCE.CallWindowProcW(previousWindowProc, handle, message, wParam, lParam);
            };
            previousWindowProc = Native.POINTER_SIZE == 8
                    ? User32.INSTANCE.SetWindowLongPtrW(window, GWL_WNDPROC, windowProc)
                    : User32.INSTANCE.SetWindowLongW(window, GWL_WNDPROC, windowProc);
            if (previousWindowProc == null) {
                window = null;
                windowProc = null;
                return;
            }
            Shell32.INSTANCE.DragAcceptFiles(window, true);
            try {
                User32.INSTANCE.ChangeWindowMessageFilterEx(window, WM_DROPFILES, MSGFLT_ALLOW, null);
                User32.INSTANCE.ChangeWindowMessageFilterEx(window, WM_COPYGLOBALDATA, MSGFLT_ALLOW, null);
            }
            catch (Throwable ignored) {
            }
        }
        catch (Throwable throwable) {
            window = null;
            previousWindowProc = null;
            windowProc = null;
            System.err.println("[FileDrop] Failed to attach to the game window: " + throwable.getMessage());
        }
    }

    private static void receiveWindowsDrop(Pointer dropHandle) {
        List<File> files = new ArrayList<>();
        try {
            int count = Shell32.INSTANCE.DragQueryFileW(dropHandle, -1, null, 0);
            for (int i = 0; i < count; i++) {
                int length = Shell32.INSTANCE.DragQueryFileW(dropHandle, i, null, 0);
                char[] path = new char[length + 1];
                Shell32.INSTANCE.DragQueryFileW(dropHandle, i, path, path.length);
                files.add(new File(Native.toString(path)));
            }
        }
        catch (Throwable throwable) {
            System.err.println("[FileDrop] Failed to receive files: " + throwable.getMessage());
        }
        finally {
            Shell32.INSTANCE.DragFinish(dropHandle);
        }
        queueImport(files);
    }

    private static void queueImport(List<File> files) {
        if (files == null || files.isEmpty()) return;
        Minecraft.getMinecraft().addScheduledTask(() -> importFiles(files));
    }

    private static void importFiles(List<File> files) {
        int scriptCount = 0;
        int profileCount = 0;
        int failedCount = 0;
        for (File source : files) {
            if (source == null || !source.isFile()) continue;
            String lowerName = source.getName().toLowerCase(Locale.ROOT);
            File destinationDirectory;
            boolean script;
            if (lowerName.endsWith(".jar") || lowerName.endsWith(".java")) {
                destinationDirectory = Utils.getScriptDirectory();
                script = true;
            }
            else if (lowerName.endsWith(".json")) {
                if (Mindless.profileManager == null) continue;
                destinationDirectory = Mindless.profileManager.directory;
                script = false;
            }
            else {
                continue;
            }
            try {
                if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
                    failedCount++;
                    continue;
                }
                File destination = new File(destinationDirectory, source.getName());
                if (!source.getCanonicalFile().equals(destination.getCanonicalFile())) {
                    Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                if (script) scriptCount++; else profileCount++;
            }
            catch (Throwable throwable) {
                failedCount++;
                System.err.println("[FileDrop] Failed to import " + source.getName() + ": " + throwable.getMessage());
            }
        }
        if (scriptCount > 0 && Mindless.scriptManager != null) Mindless.scriptManager.loadScripts();
        if (profileCount > 0 && Mindless.profileManager != null) Mindless.profileManager.loadProfiles();
        String message = "&7Imported &b" + scriptCount + " &7script" + (scriptCount == 1 ? "" : "s")
                + " and &b" + profileCount + " &7profile" + (profileCount == 1 ? "" : "s") + ".";
        if (failedCount > 0) message += " &c" + failedCount + " failed.";
        if (Utils.nullCheck()) Utils.sendMessage(message); else System.out.println(message.replace("&7", "").replace("&b", "").replace("&c", ""));
    }

    private interface WindowProc extends StdCallLibrary.StdCallCallback {
        Pointer invoke(Pointer handle, int message, Pointer wParam, Pointer lParam);
    }

    private interface EnumWindowsProc extends StdCallLibrary.StdCallCallback {
        boolean invoke(Pointer handle, Pointer data);
    }

    private interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class);
        boolean EnumWindows(EnumWindowsProc callback, Pointer data);
        boolean IsWindowVisible(Pointer handle);
        int GetWindowThreadProcessId(Pointer handle, IntByReference processId);
        int GetClassNameW(Pointer handle, char[] className, int maxCount);
        Pointer SetWindowLongPtrW(Pointer handle, int index, Callback callback);
        Pointer SetWindowLongW(Pointer handle, int index, Callback callback);
        Pointer SetWindowLongPtrW(Pointer handle, int index, Pointer value);
        Pointer SetWindowLongW(Pointer handle, int index, Pointer value);
        Pointer CallWindowProcW(Pointer previous, Pointer handle, int message, Pointer wParam, Pointer lParam);
        boolean ChangeWindowMessageFilterEx(Pointer handle, int message, int action, Pointer changeFilterStruct);
    }

    private interface Shell32 extends StdCallLibrary {
        Shell32 INSTANCE = Native.load("shell32", Shell32.class);
        void DragAcceptFiles(Pointer handle, boolean accept);
        int DragQueryFileW(Pointer dropHandle, int index, char[] path, int pathLength);
        void DragFinish(Pointer dropHandle);
    }

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);
        int GetCurrentProcessId();
    }
}
