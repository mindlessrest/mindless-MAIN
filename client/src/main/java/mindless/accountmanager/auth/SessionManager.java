package mindless.accountmanager.auth;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;
import org.apache.commons.lang3.StringUtils;

public class SessionManager {
    private static Field field = null;
    private static Session launchSession = null;

    private static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    private static Field getField() {
        if (field == null) {
            try {
                for (Field f : Minecraft.class.getDeclaredFields()) {
                    if (!f.getType().isAssignableFrom(Session.class)) continue;
                    field = f;
                    field.setAccessible(true);
                    break;
                }
            }
            catch (Exception e) {
                field = null;
            }
        }
        return field;
    }

    public static void captureLaunchSession() {
        if (launchSession != null) {
            return;
        }
        Minecraft minecraft = SessionManager.mc();
        if (minecraft == null) {
            return;
        }
        Session current = minecraft.getSession();
        if (current == null || StringUtils.isBlank((CharSequence)current.getUsername())) {
            return;
        }
        launchSession = SessionManager.copySession(current);
    }

    public static Session getLaunchSession() {
        return launchSession;
    }

    public static boolean isUsingLaunchSession() {
        if (launchSession == null) {
            return true;
        }
        Session current = SessionManager.get();
        if (current == null) {
            return true;
        }
        return SessionManager.safe(launchSession.getUsername()).equals(SessionManager.safe(current.getUsername())) && SessionManager.safe(launchSession.getPlayerID()).equals(SessionManager.safe(current.getPlayerID())) && SessionManager.safe(launchSession.getToken()).equals(SessionManager.safe(current.getToken()));
    }

    public static void restoreLaunchSession() {
        if (launchSession == null) {
            return;
        }
        SessionManager.set(SessionManager.copySession(launchSession));
    }

    public static Session get() {
        Minecraft minecraft = SessionManager.mc();
        return minecraft != null ? minecraft.getSession() : null;
    }

    public static void set(Session session) {
        Minecraft minecraft = SessionManager.mc();
        if (minecraft == null || session == null) {
            return;
        }
        try {
            Field sessionField = SessionManager.getField();
            if (sessionField != null) {
                sessionField.set(minecraft, session);
            }
        }
        catch (Exception exception) {
        }
    }

    private static Session copySession(Session session) {
        return new Session(SessionManager.safe(session.getUsername()), SessionManager.safe(session.getPlayerID()), SessionManager.safe(session.getToken()), SessionManager.sessionType(session));
    }

    private static String sessionType(Session session) {
        if (session.getSessionType() != null) {
            return session.getSessionType().toString();
        }
        return Session.Type.MOJANG.toString();
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}

