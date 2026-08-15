package keystrokesmod.alt;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps an in-memory session override for the Minecraft#getSession hook.
 *
 * <p>The override is the authoritative path because some Lunar builds keep
 * Minecraft.session final. A best-effort reflective write is also performed
 * for vanilla/Forge code which reads the field directly. No session is ever
 * serialized by this class.</p>
 */
public final class AltSessionController {
    private static final AtomicReference<Session> OVERRIDE = new AtomicReference<>();
    private static final AtomicReference<Session> ORIGINAL = new AtomicReference<>();
    private static volatile Field sessionField;
    private static volatile boolean sessionFieldResolved;
    private static volatile Field profilePropertiesField;
    private static volatile boolean profilePropertiesFieldResolved;

    private AltSessionController() {
    }

    public interface ApplyCallback {
        void complete(boolean success, String message);
    }

    /**
     * Called from a RETURN hook in Minecraft#getSession.
     */
    public static Session resolveSession(Session vanillaSession) {
        if (vanillaSession != null && OVERRIDE.get() == null) {
            ORIGINAL.compareAndSet(null, vanillaSession);
        }
        Session replacement = OVERRIDE.get();
        return replacement == null ? vanillaSession : replacement;
    }

    /**
     * Exposed for a HEAD hook or compatibility diagnostics.
     */
    public static Session getOverride() {
        return OVERRIDE.get();
    }

    public static void captureOriginal(Minecraft minecraft) {
        if (minecraft == null || ORIGINAL.get() != null) {
            return;
        }

        Session raw = readSessionField(minecraft);
        if (raw == null) {
            try {
                raw = minecraft.getSession();
            } catch (Throwable ignored) {
                // The caller will receive a controlled failure if restore is requested.
            }
        }
        if (raw != null && raw != OVERRIDE.get()) {
            ORIGINAL.compareAndSet(null, raw);
        }
    }

    public static void applySession(final Minecraft minecraft, final Session session,
                                    final ApplyCallback callback) {
        if (minecraft == null || session == null) {
            notifyCallback(callback, false, "Sessao invalida.");
            return;
        }

        runOnClientThread(minecraft, new Runnable() {
            @Override
            public void run() {
                captureOriginal(minecraft);
                try {
                    disconnectCurrentWorld(minecraft);
                    OVERRIDE.set(session);
                    writeSessionField(minecraft, session);
                    clearProfileProperties(minecraft);
                    notifyCallback(callback, true, "Sessao ativa: " + session.getUsername());
                } catch (Throwable ignored) {
                    OVERRIDE.set(null);
                    notifyCallback(callback, false, "Nao foi possivel trocar a sessao com seguranca.");
                }
            }
        });
    }

    public static void restoreOriginal(final Minecraft minecraft, final ApplyCallback callback) {
        if (minecraft == null) {
            notifyCallback(callback, false, "Minecraft indisponivel.");
            return;
        }

        runOnClientThread(minecraft, new Runnable() {
            @Override
            public void run() {
                captureOriginal(minecraft);
                Session original = ORIGINAL.get();
                if (original == null) {
                    notifyCallback(callback, false, "A sessao original nao esta disponivel.");
                    return;
                }

                try {
                    disconnectCurrentWorld(minecraft);
                    OVERRIDE.set(null);
                    writeSessionField(minecraft, original);
                    clearProfileProperties(minecraft);
                    notifyCallback(callback, true, "Sessao original restaurada: " + original.getUsername());
                } catch (Throwable ignored) {
                    OVERRIDE.set(original);
                    notifyCallback(callback, false, "Nao foi possivel restaurar a sessao original.");
                }
            }
        });
    }

    private static void runOnClientThread(Minecraft minecraft, Runnable action) {
        try {
            if (minecraft.isCallingFromMinecraftThread()) {
                action.run();
            } else {
                minecraft.addScheduledTask(action);
            }
        } catch (Throwable ignored) {
            // A stopped client cannot safely accept a session change.
        }
    }

    private static void clearProfileProperties(Minecraft minecraft) {
        Field field = findProfilePropertiesField();
        if (field == null) {
            return;
        }
        try {
            Object properties = field.get(minecraft);
            if (properties != null) {
                properties.getClass().getMethod("clear").invoke(properties);
            }
        } catch (Throwable ignored) {
            // Cosmetic profile data may remain cached, but auth still uses the
            // getSession override and must not fail because of that cache.
        }
    }

    private static void disconnectCurrentWorld(Minecraft minecraft) {
        if (minecraft.theWorld == null) {
            return;
        }

        try {
            minecraft.theWorld.sendQuittingDisconnectingPacket();
        } catch (Throwable ignored) {
            // loadWorld(null) is still required to prevent an in-place account swap.
        }
        minecraft.loadWorld(null);
    }

    private static Session readSessionField(Minecraft minecraft) {
        Field field = findSessionField();
        if (field == null) {
            return null;
        }
        try {
            Object value = field.get(minecraft);
            return value instanceof Session ? (Session) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean writeSessionField(Minecraft minecraft, Session session) {
        Field field = findSessionField();
        if (field == null) {
            return false;
        }
        try {
            field.set(minecraft, session);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field findSessionField() {
        if (sessionFieldResolved) {
            return sessionField;
        }

        synchronized (AltSessionController.class) {
            if (sessionFieldResolved) {
                return sessionField;
            }

            Field resolved = findNamedField("session");
            if (resolved == null) {
                resolved = findNamedField("field_71449_j");
            }
            if (resolved == null) {
                Field[] fields = Minecraft.class.getDeclaredFields();
                for (Field field : fields) {
                    if (Session.class.isAssignableFrom(field.getType())) {
                        resolved = field;
                        break;
                    }
                }
            }

            if (resolved != null) {
                try {
                    resolved.setAccessible(true);
                    removeFinalModifier(resolved);
                    sessionField = resolved;
                } catch (Throwable ignored) {
                    sessionField = null;
                }
            }
            sessionFieldResolved = true;
            return sessionField;
        }
    }

    private static Field findProfilePropertiesField() {
        if (profilePropertiesFieldResolved) {
            return profilePropertiesField;
        }
        synchronized (AltSessionController.class) {
            if (profilePropertiesFieldResolved) {
                return profilePropertiesField;
            }
            Field resolved = findAnyField("profileProperties");
            if (resolved == null) {
                resolved = findAnyField("field_181038_N");
            }
            if (resolved != null) {
                try {
                    resolved.setAccessible(true);
                    profilePropertiesField = resolved;
                } catch (Throwable ignored) {
                    profilePropertiesField = null;
                }
            }
            profilePropertiesFieldResolved = true;
            return profilePropertiesField;
        }
    }

    private static Field findNamedField(String name) {
        try {
            Field field = Minecraft.class.getDeclaredField(name);
            return Session.class.isAssignableFrom(field.getType()) ? field : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findAnyField(String name) {
        try {
            return Minecraft.class.getDeclaredField(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void removeFinalModifier(Field field) {
        if ((field.getModifiers() & Modifier.FINAL) == 0) {
            return;
        }
        try {
            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (Throwable ignored) {
            // Modern JVMs may block this; the getSession hook remains authoritative.
        }
    }

    private static void notifyCallback(ApplyCallback callback, boolean success, String message) {
        if (callback != null) {
            callback.complete(success, message);
        }
    }
}
