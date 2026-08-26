#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

/*
 * RavenInjector (Linux) — injects RavenNative.so into a running JVM process.
 *
 * Strategy: use the JVM's built-in Attach API. The HotSpot JVM listens for
 * attach requests via a Unix socket at /tmp/.java_pidXXX. We create the
 * trigger file, send SIGQUIT to wake the attach listener, connect, and
 * issue a "load" command to load our agent .so via the JVMTI Agent_OnAttach
 * entry point.
 *
 * Fallback: if the attach socket doesn't exist, we create .attach_pid<pid>
 * in the target's cwd to trigger the attach listener thread.
 */

static int is_java_process(pid_t pid) {
    char path[256], buf[256];
    ssize_t len;
    snprintf(path, sizeof(path), "/proc/%d/exe", (int)pid);
    len = readlink(path, buf, sizeof(buf) - 1);
    if (len <= 0) return 0;
    buf[len] = '\0';
    return strstr(buf, "java") != NULL;
}

static int get_process_cmdline(pid_t pid, char *out, size_t capacity) {
    char path[256];
    FILE *f;
    size_t pos = 0;
    int ch;
    snprintf(path, sizeof(path), "/proc/%d/cmdline", (int)pid);
    f = fopen(path, "r");
    if (!f) return 0;
    while ((ch = fgetc(f)) != EOF && pos < capacity - 1) {
        out[pos++] = ch ? (char)ch : ' ';
    }
    fclose(f);
    out[pos] = '\0';
    return 1;
}

static int trigger_attach_mechanism(pid_t pid) {
    char trigger_path[256];
    char socket_path[256];
    FILE *f;
    struct stat st;
    int attempt;

    snprintf(socket_path, sizeof(socket_path), "/tmp/.java_pid%d", (int)pid);
    if (stat(socket_path, &st) == 0) return 1;

    snprintf(trigger_path, sizeof(trigger_path), "/proc/%d/cwd/.attach_pid%d",
            (int)pid, (int)pid);
    f = fopen(trigger_path, "w");
    if (f) fclose(f);

    kill(pid, SIGQUIT);

    for (attempt = 0; attempt < 50; ++attempt) {
        usleep(100000);
        if (stat(socket_path, &st) == 0) {
            unlink(trigger_path);
            return 1;
        }
    }
    unlink(trigger_path);
    fprintf(stderr, "Attach socket did not appear for PID %d\n", (int)pid);
    return 0;
}

static int inject_via_attach(pid_t pid, const char *so_path) {
    char socket_path[256];
    char cmd[4096];
    FILE *sock_f;
    int fd;
    struct sockaddr_un {
        unsigned short sun_family;
        char sun_path[108];
    } addr;

    if (!trigger_attach_mechanism(pid)) return 0;

    snprintf(socket_path, sizeof(socket_path), "/tmp/.java_pid%d", (int)pid);

    fd = socket(1 /* AF_UNIX */, 1 /* SOCK_STREAM */, 0);
    if (fd < 0) { perror("socket"); return 0; }

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = 1; /* AF_UNIX */
    strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("connect to JVM attach socket");
        close(fd);
        return 0;
    }

    /* Attach protocol: "1\0" (version), "load\0" (command),
     * "instrument\0" (arg0: ignored for agent), "false\0" (arg1),
     * path\0 (the .so to load) */
    {
        char proto_buf[4096];
        size_t offset = 0;
        #define WRITE_STR(s) do { \
            size_t l = strlen(s) + 1; \
            memcpy(proto_buf + offset, s, l); \
            offset += l; \
        } while(0)

        WRITE_STR("1");
        WRITE_STR("load");
        WRITE_STR("instrument");
        WRITE_STR("false");
        WRITE_STR(so_path);
        #undef WRITE_STR

        if (write(fd, proto_buf, offset) != (ssize_t)offset) {
            perror("write attach command");
            close(fd);
            return 0;
        }
    }

    /* Read response */
    {
        char resp[256];
        ssize_t n = read(fd, resp, sizeof(resp) - 1);
        close(fd);
        if (n > 0) {
            resp[n] = '\0';
            if (resp[0] == '0') return 1;
            fprintf(stderr, "JVM attach load returned: %s\n", resp);
            return 0;
        }
    }
    close(fd);
    return 0;
}

static pid_t select_process_interactive(void) {
    DIR *proc;
    struct dirent *ent;
    pid_t candidates[256];
    char labels[256][512];
    int count = 0;
    int i;
    char input[32];

    proc = opendir("/proc");
    if (!proc) { perror("opendir /proc"); return 0; }

    while ((ent = readdir(proc)) != NULL && count < 256) {
        pid_t pid = (pid_t)atoi(ent->d_name);
        if (pid <= 0) continue;
        if (!is_java_process(pid)) continue;
        candidates[count] = pid;
        get_process_cmdline(pid, labels[count], sizeof(labels[0]));
        count++;
    }
    closedir(proc);

    if (count == 0) {
        fprintf(stderr, "No Java processes found.\n");
        return 0;
    }

    printf("Raven Injector (Linux)\n\n");
    printf("Select a Java process:\n\n");
    for (i = 0; i < count; ++i) {
        printf("  %d) [%d] %.70s\n", i + 1, (int)candidates[i], labels[i]);
    }
    printf("\nChoice (1-%d): ", count);
    fflush(stdout);

    if (!fgets(input, sizeof(input), stdin)) return 0;
    i = atoi(input) - 1;
    if (i < 0 || i >= count) {
        fprintf(stderr, "Invalid selection.\n");
        return 0;
    }
    return candidates[i];
}

static void usage(const char *prog) {
    fprintf(stderr,
            "Usage: %s [RavenNative.so]\n"
            "       %s <pid> <RavenNative.so>\n"
            "Without a PID, lists Java processes for interactive selection.\n",
            prog, prog);
}

int main(int argc, char **argv) {
    char so_path[PATH_MAX];
    pid_t pid = 0;

    if (argc > 3) { usage(argv[0]); return 2; }

    if (argc == 3) {
        pid = (pid_t)atoi(argv[1]);
        if (pid <= 0) { fprintf(stderr, "Invalid PID: %s\n", argv[1]); return 2; }
        if (!realpath(argv[2], so_path)) {
            fprintf(stderr, "SO does not exist: %s\n", argv[2]); return 2;
        }
    } else if (argc == 2) {
        if (!realpath(argv[1], so_path)) {
            fprintf(stderr, "SO does not exist: %s\n", argv[1]); return 2;
        }
    } else {
        /* Default: look for RavenNative.so next to this binary */
        char self[PATH_MAX];
        ssize_t len = readlink("/proc/self/exe", self, sizeof(self) - 1);
        if (len <= 0) { fprintf(stderr, "Cannot resolve self path\n"); return 2; }
        self[len] = '\0';
        char *sep = strrchr(self, '/');
        if (sep) *(sep + 1) = '\0';
        snprintf(so_path, sizeof(so_path), "%sRavenNative.so", self);
        struct stat st;
        if (stat(so_path, &st) != 0) {
            fprintf(stderr, "RavenNative.so not found beside injector.\n");
            usage(argv[0]);
            return 2;
        }
    }

    if (pid == 0) {
        pid = select_process_interactive();
        if (pid == 0) return 1;
    }

    printf("Injecting %s into PID %d...\n", so_path, (int)pid);
    if (inject_via_attach(pid, so_path)) {
        printf("Success. Raven bootstrap is running.\n");
        return 0;
    } else {
        fprintf(stderr, "Injection failed.\n");
        return 3;
    }
}
