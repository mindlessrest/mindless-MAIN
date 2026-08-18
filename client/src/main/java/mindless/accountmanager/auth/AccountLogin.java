package mindless.accountmanager.auth;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import mindless.accountmanager.AccountAuthStatus;
import mindless.accountmanager.AccountManager;
import mindless.accountmanager.auth.Account;
import mindless.accountmanager.auth.AccountType;
import mindless.accountmanager.auth.CookieAuth;
import mindless.accountmanager.auth.MicrosoftAuth;
import mindless.accountmanager.auth.RefreshTokenAuth;
import mindless.accountmanager.auth.SessionManager;
import mindless.accountmanager.gui.GuiAccountManager;
import mindless.accountmanager.utils.Notification;
import mindless.accountmanager.utils.TextFormatting;
import net.minecraft.util.Session;
import org.apache.commons.lang3.StringUtils;

public final class AccountLogin {
    private AccountLogin() {}

    public static CompletableFuture<Void> login(Account account, Executor executor) {
        String username = StringUtils.isBlank((CharSequence) account.getUsername()) ? "???" : account.getUsername();
        GuiAccountManager.notification = new Notification(
                TextFormatting.translate(String.format("&7Fetching your Minecraft profile... (%s)&r", username)), -1L);

        CompletableFuture<Session> profileFuture = MicrosoftAuth.login(account.getAccessToken(), executor);

        CompletableFuture<CompletableFuture<Void>> handledFuture = profileFuture.handle(
                (Session session, Throwable error) -> {
                    if (session != null) {
                        AccountLogin.applySession(account, session);
                        GuiAccountManager.notification = new Notification(
                                TextFormatting.translate(String.format("&aSuccessful login! (%s)&r", account.getUsername())), 5000L);
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    return AccountLogin.fallbackLogin(account, executor, username);
                });

        return handledFuture.thenComposeAsync(f -> f, executor)
                .exceptionally((Throwable error) -> {
                    String message = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
                    account.authStatus = AccountAuthStatus.FAILED;
                    GuiAccountManager.notification = new Notification(
                            TextFormatting.translate(String.format("&c%s (%s)&r", message, username)), 5000L);
                    return null;
                });
    }

    private static CompletableFuture<Void> fallbackLogin(Account account, Executor executor, String username) {
        AccountType type = account.getType();
        if (type == AccountType.PREMIUM) type = AccountType.MICROSOFT;
        switch (type) {
            case COOKIE:
                if (StringUtils.isBlank((CharSequence) account.getRefreshToken()))
                    return AccountLogin.failed("No saved cookies for this account");
                GuiAccountManager.notification = new Notification(
                        TextFormatting.translate(String.format("&7Re-authenticating with cookies... (%s)&r", username)), -1L);
                return CookieAuth.loginWithStoredCookies(account.getRefreshToken(), executor)
                        .thenAccept(refreshed -> AccountLogin.mergeRefreshedAccount(account, refreshed));

            case REFRESH:
                if (StringUtils.isBlank((CharSequence) account.getRefreshToken()))
                    return AccountLogin.failed("No saved refresh token for this account");
                GuiAccountManager.notification = new Notification(
                        TextFormatting.translate(String.format("&7Refreshing Microsoft tokens... (%s)&r", username)), -1L);
                return RefreshTokenAuth.authenticate(account.getRefreshToken(), executor)
                        .thenAccept(refreshed -> AccountLogin.mergeRefreshedAccount(account, refreshed));

            case MICROSOFT:
                if (StringUtils.isBlank((CharSequence) account.getRefreshToken()))
                    return AccountLogin.failed("No saved Microsoft refresh token for this account");
                return AccountLogin.microsoftOAuthFallback(account, executor, username);

            case TOKEN:
                return AccountLogin.failed("Minecraft access token expired and cannot be refreshed");

            default:
                return AccountLogin.failed("Unsupported account type for login");
        }
    }

    private static CompletableFuture<Void> microsoftOAuthFallback(Account account, Executor executor, String username) {
        GuiAccountManager.notification = new Notification(
                TextFormatting.translate(String.format("&7Refreshing Microsoft access tokens... (%s)&r", username)), -1L);

        CompletableFuture<Map<String, String>> step1 =
                MicrosoftAuth.refreshMSAccessTokens(account.getRefreshToken(), executor);

        CompletableFuture<String> step2 = step1.thenComposeAsync((Map<String, String> msAccessTokens) -> {
            account.setRefreshToken(msAccessTokens.get("refresh_token"));
            GuiAccountManager.notification = new Notification(
                    TextFormatting.translate(String.format("&7Acquiring Xbox access token... (%s)&r", username)), -1L);
            return MicrosoftAuth.acquireXboxAccessToken(msAccessTokens.get("access_token"), executor);
        }, executor);

        CompletableFuture<Map<String, String>> step3 = step2.thenComposeAsync((String xboxAccessToken) -> {
            GuiAccountManager.notification = new Notification(
                    TextFormatting.translate(String.format("&7Acquiring Xbox XSTS token... (%s)&r", username)), -1L);
            return MicrosoftAuth.acquireXboxXstsToken(xboxAccessToken, executor);
        }, executor);

        CompletableFuture<String> step4 = step3.thenComposeAsync((Map<String, String> xboxXstsData) -> {
            GuiAccountManager.notification = new Notification(
                    TextFormatting.translate(String.format("&7Acquiring Minecraft access token... (%s)&r", username)), -1L);
            return MicrosoftAuth.acquireMCAccessToken(xboxXstsData.get("Token"), xboxXstsData.get("uhs"), executor);
        }, executor);

        CompletableFuture<Session> step5 = step4.thenComposeAsync((String mcToken) -> {
            account.setAccessToken(mcToken);
            GuiAccountManager.notification = new Notification(
                    TextFormatting.translate(String.format("&7Fetching your Minecraft profile... (%s)&r", username)), -1L);
            return MicrosoftAuth.login(mcToken, executor);
        }, executor);

        return step5.thenAccept((Session session) -> {
            AccountLogin.applySession(account, session);
            GuiAccountManager.notification = new Notification(
                    TextFormatting.translate(String.format("&aSuccessful login! (%s)&r", account.getUsername())), 5000L);
        });
    }

    private static void mergeRefreshedAccount(Account account, Account refreshed) {
        account.setRefreshToken(refreshed.getRefreshToken());
        account.setAccessToken(refreshed.getAccessToken());
        account.setUsername(refreshed.getUsername());
        account.setUuid(refreshed.getUuid());
        account.setType(refreshed.getType());
        account.authStatus = AccountAuthStatus.AUTHED;
        Session session = new Session(refreshed.getUsername(), refreshed.getUuid(), refreshed.getAccessToken(), "mojang");
        SessionManager.set(session);
        AccountManager.save();
        GuiAccountManager.notification = new Notification(
                TextFormatting.translate(String.format("&aSuccessful login! (%s)&r", account.getUsername())), 5000L);
    }

    private static void applySession(Account account, Session session) {
        account.setUsername(session.getUsername());
        account.setUuid(session.getPlayerID());
        account.setAccessToken(session.getToken());
        account.authStatus = AccountAuthStatus.AUTHED;
        SessionManager.set(session);
        AccountManager.save();
    }

    private static CompletableFuture<Void> failed(String message) {
        return CompletableFuture.supplyAsync(() -> { throw new RuntimeException(message); });
    }
}
