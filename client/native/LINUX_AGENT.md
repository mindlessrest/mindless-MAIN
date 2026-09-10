# Linux Java agent

Build the release normally. The build produces `mindless-linux-agent.jar` and the Lunar payload.

Start-time injection:

```text
-javaagent:/absolute/path/mindless-linux-agent.jar=payload=/absolute/path/mindless-lunar-mcp-with-forge.jar;token=SESSION_TOKEN;hwid=HWID
```

The API URL defaults to `https://api.mindless.rest`. A loader can instead set `mindless.auth.token`, `mindless.auth.hwid`, and `mindless.auth.apiUrl` as JVM system properties and pass only the payload path to the agent.

The same agent manifest exposes `agentmain`, so Linux attach tools can load it into a running JVM with the same argument string.
