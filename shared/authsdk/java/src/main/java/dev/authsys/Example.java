package dev.authsys;

import dev.authsys.model.*;

/**
 * Example demonstrating the full loader → software auth handoff flow.
 *
 * <p>Run as loader:  {@code java dev.authsys.Example loader <username> <password> <server_url>}
 * <p>Run as software: {@code java dev.authsys.Example software <channel_name> <server_url>}
 *
 * <p>The loader authenticates, creates an IPC channel, launches the software process,
 * and securely passes the session token. The software receives the token, validates it,
 * and starts a heartbeat.
 */
public class Example {

    private static final String DEFAULT_SERVER = "http://localhost:8080";

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage:");
            System.err.println("  Loader:   java dev.authsys.Example loader <username> <password> [server_url]");
            System.err.println("  Software: java dev.authsys.Example software <channel_name> [server_url]");
            System.exit(1);
        }

        try {
            if ("loader".equalsIgnoreCase(args[0])) {
                runAsLoader(args);
            } else if ("software".equalsIgnoreCase(args[0])) {
                runAsSoftware(args);
            } else {
                System.err.println("Unknown mode: " + args[0] + ". Use 'loader' or 'software'.");
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Loader flow:
     * 1. Ping server for health check
     * 2. Fetch public config to see what's enabled
     * 3. Compute HWID
     * 4. Login with provided credentials
     * 5. Create IPC server
     * 6. Launch the software process with the channel name
     * 7. Wait for software to connect and send the token
     * 8. Exit
     */
    private static void runAsLoader(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Loader usage: java dev.authsys.Example loader <username> <password> [server_url]");
            System.exit(1);
        }

        String username = args[1];
        String password = args[2];
        String serverUrl = args.length > 3 ? args[3] : DEFAULT_SERVER;

        AuthClient client = new AuthClient(serverUrl);

        // Step 1: Ping server
        System.out.println("Pinging server...");
        PingResult ping = client.ping();
        System.out.println("Server is " + (ping.isOk() ? "healthy" : "unhealthy")
                + ", server time: " + ping.getServerTime());

        // Step 2: Check public config
        PublicConfig config = client.getPublicConfig();
        System.out.println("App: " + config.getAppName());
        System.out.println("HWID locking: " + config.isHwidLocking());
        System.out.println("IP locking: " + config.isIpLocking());

        // Step 3: Compute HWID (show first 8 chars only — never log the full value)
        String hwid = HwidUtil.getHWID();
        System.out.println("HWID: " + hwid.substring(0, 8) + "...");
        client.setHwid(hwid);

        // Step 4: Login
        System.out.println("Logging in as '" + username + "'...");
        LoginResult login = client.login(username, password);
        System.out.println("Logged in. Session expires at: " + login.getExpiresAt());

        // Step 5: Create IPC server
        try (IPCServer ipcServer = new IPCServer()) {
            String channelName = ipcServer.getChannelName();
            System.out.println("IPC channel: " + channelName);

            // Step 6: Launch software process with channel name as argument
            String javaPath = ProcessHandle.current().info().command().orElse("java");
            ProcessBuilder pb = new ProcessBuilder(
                    javaPath, "-cp", System.getProperty("java.class.path"),
                    "dev.authsys.Example", "software", channelName, serverUrl
            );
            pb.inheritIO();
            Process softwareProcess = pb.start();
            System.out.println("Launched software process (PID: " + softwareProcess.pid() + ")");

            // Step 7: Wait for software to connect and send the encrypted token
            ipcServer.waitAndSend(login.getToken(), hwid);
            System.out.println("Handoff complete. Token securely sent to software.");
        }

        System.out.println("Loader exiting.");
    }

    /**
     * Software flow:
     * 1. Receive token from loader via IPC
     * 2. Validate the session
     * 3. Start heartbeat
     * 4. Wait for user input, then stop
     */
    private static void runAsSoftware(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Software usage: java dev.authsys.Example software <channel_name> [server_url]");
            System.exit(1);
        }

        String channelName = args[1];
        String serverUrl = args.length > 2 ? args[2] : DEFAULT_SERVER;

        // Step 1: Receive token from loader via IPC
        System.out.println("Connecting to IPC channel: " + channelName);
        String hwid = HwidUtil.getHWID();
        String token;

        try (IPCClient ipcClient = new IPCClient(channelName)) {
            token = ipcClient.receiveToken(hwid);
        }
        System.out.println("Token received from loader.");

        // Step 2: Create client and validate the session
        AuthClient client = new AuthClient(serverUrl);
        client.setToken(token);
        client.setHwid(hwid);

        SessionInfo session = client.validateSession();
        System.out.println("Session valid: " + session.isValid());
        System.out.println("Username: " + session.getUsername());
        System.out.println("Expires at: " + session.getExpiresAt());

        // Step 3: Start heartbeat with 30s interval for demo purposes
        HeartbeatConfig heartbeatConfig = new HeartbeatConfig(30, 3, 10);
        Heartbeat heartbeat = new Heartbeat(client, heartbeatConfig);

        heartbeat.setOnInvalid(reason -> {
            System.err.println("Session dead: " + reason);
            System.exit(1);
        });
        heartbeat.setOnSuccess(() -> System.out.println("Heartbeat OK"));

        heartbeat.start();
        System.out.println("Heartbeat started (30s interval).");

        // Step 4: Wait for user input, then stop
        System.out.println("Software running. Press Enter to exit.");
        System.in.read();

        heartbeat.stop();
        System.out.println("Heartbeat stopped. Software exiting.");
    }
}
