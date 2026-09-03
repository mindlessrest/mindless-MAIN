package mindless.command.impl;

import mindless.Mindless;
import mindless.command.Command;
import mindless.command.CommandInput;
import mindless.helper.DebugHelper;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class Debug extends Command {
    private static final String RECORDING_NAME = "mindless";
    private static final SimpleDateFormat FILE_FORMAT = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    private static volatile boolean profiling;
    private static String activeFilePath;

    public Debug() {
        super("debug");
    }

    @Override
    public void execute(CommandInput input) {
        if (input.argumentCount() == 0) {
            Mindless.DEBUG = !Mindless.DEBUG;
            replyWithHeader("&7Debug " + (Mindless.DEBUG ? "&aenabled" : "&cdisabled") + "&7.");
            return;
        }

        String option = input.getArgument(0).toLowerCase();

        if ("mixin".equals(option)) {
            DebugHelper.MIXIN = !DebugHelper.MIXIN;
            replyWithHeader("&dMixin &7debug " + (DebugHelper.MIXIN ? "&aenabled" : "&cdisabled") + "&7.");
            return;
        }

        if ("bg".equals(option) || "background".equals(option)) {
            DebugHelper.BACKGROUND = !DebugHelper.BACKGROUND;
            replyWithHeader("&6Background &7debug " + (DebugHelper.BACKGROUND ? "&aenabled" : "&cdisabled") + "&7.");
            return;
        }

        if ("profile".equals(option)) {
            handleProfile(input);
            return;
        }

        replyWithHeader("&7Usage:");
        replyWithHeader("&b  debug &7— toggle debug mode");
        replyWithHeader("&b  debug mixin &7— toggle mixin debug");
        replyWithHeader("&b  debug bg &7— toggle background debug");
        replyWithHeader("&b  debug profile start <seconds> [alloc true/false]");
        replyWithHeader("&b  debug profile stop");
    }

    private void handleProfile(CommandInput input) {
        if (input.argumentCount() < 2) {
            replyWithHeader("&7Usage: &bdebug profile start <seconds> [alloc true/false]");
            replyWithHeader("&7       &bdebug profile stop");
            if (profiling) {
                replyWithHeader("&aRecording active &7→ " + activeFilePath);
            }
            return;
        }

        String action = input.getArgument(1).toLowerCase();

        if ("stop".equals(action)) {
            if (!profiling) {
                replyWithHeader("&cNo active recording.");
                return;
            }
            try {
                stopRecording();
                profiling = false;
                replyWithHeader("&aStopped. &7Saved: &b" + activeFilePath);
            } catch (Throwable t) {
                replyWithHeader("&cFailed to stop: " + t.getMessage());
            }
            return;
        }

        if ("start".equals(action)) {
            if (profiling) {
                replyWithHeader("&cAlready recording. Stop first with &bdebug profile stop");
                return;
            }

            int durationSec = 120;
            boolean alloc = true;

            if (input.argumentCount() >= 3) {
                try {
                    durationSec = Integer.parseInt(input.getArgument(2));
                    durationSec = Math.max(10, Math.min(1800, durationSec));
                } catch (NumberFormatException e) {
                    replyWithHeader("&cInvalid duration. Use seconds (10-1800).");
                    return;
                }
            }

            if (input.argumentCount() >= 4) {
                alloc = "true".equalsIgnoreCase(input.getArgument(3));
            }

            try {
                startRecording(durationSec, alloc);
                profiling = true;
                replyWithHeader("&aRecording started &7(" + durationSec + "s, alloc=" + alloc + ")");
                replyWithHeader("&7File: &b" + activeFilePath);
                replyWithHeader("&7Writing continuously — safe to end task.");
            } catch (Throwable t) {
                replyWithHeader("&cFailed to start: " + t.getMessage());
            }
            return;
        }

        replyWithHeader("&cUnknown action. Use &bstart &cor &bstop&c.");
    }

    private static void startRecording(int durationSec, boolean alloc) throws Exception {
        File dir = new File(net.minecraft.client.Minecraft.getMinecraft().mcDataDir, "profiler");
        if (!dir.exists()) dir.mkdirs();
        activeFilePath = new File(dir, "mindless_" + FILE_FORMAT.format(new Date()) + ".jfr")
                .getAbsolutePath();

        String settings = alloc ? "profile" : "default";

        java.util.List<String> args = new java.util.ArrayList<String>();
        args.add("name=" + RECORDING_NAME);
        args.add("settings=" + settings);
        args.add("duration=" + durationSec + "s");
        args.add("maxsize=1g");
        args.add("disk=true");
        args.add("dumponexit=true");
        args.add("filename=" + activeFilePath);
        if (alloc) {
            // Neither profile nor default records thrown exceptions, so a path that throws and
            // swallows hundreds a second shows up only as an unattributed count in
            // ExceptionStatistics. With these on, the recording names the throw site.
            args.add("jdk.JavaExceptionThrow#enabled=true");
            args.add("jdk.JavaExceptionThrow#stackTrace=true");
        }

        invokeDiagnosticCommand("jfrStart", args.toArray(new String[0]));
    }

    private static void stopRecording() throws Exception {
        invokeDiagnosticCommand("jfrStop", new String[]{"name=" + RECORDING_NAME});
    }

    private static void invokeDiagnosticCommand(String command, String[] args) throws Exception {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName diagCmd = new ObjectName("com.sun.management:type=DiagnosticCommand");
        server.invoke(diagCmd, command,
                new Object[]{args},
                new String[]{"[Ljava.lang.String;"});
    }

    @Override
    public List<String> suggest(CommandInput input) {
        if (input.argumentCount() <= 1) {
            return filterSuggestions(input, "mixin", "bg", "profile");
        }
        if (input.argumentCount() == 2 && "profile".equalsIgnoreCase(input.getArgument(0))) {
            return filterSuggestions(input, "start", "stop");
        }
        if (input.argumentCount() == 4 && "profile".equalsIgnoreCase(input.getArgument(0))
                && "start".equalsIgnoreCase(input.getArgument(1))) {
            return filterSuggestions(input, "true", "false");
        }
        return super.suggest(input);
    }

    @Override
    public int getSuggestionArgumentStart(CommandInput input) {
        return Math.max(0, input.argumentCount() - 1);
    }
}
