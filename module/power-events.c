#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/netlink.h>
#include <poll.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

static bool node_equals(const char *path, const char *value)
{
    char buffer[64];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t length = read(fd, buffer, sizeof(buffer) - 1);
    close(fd);
    if (length <= 0) return false;
    buffer[length] = '\0';
    buffer[strcspn(buffer, "\n")] = '\0';
    return strcmp(buffer, value) == 0;
}

static bool powered(void)
{
    if (node_equals("/sys/class/power_supply/usb/online", "1") ||
        node_equals("/sys/class/power_supply/ac/online", "1") ||
        node_equals("/sys/class/power_supply/wireless/online", "1")) return true;
    return node_equals("/sys/class/power_supply/battery/status", "Charging") ||
           node_equals("/sys/class/power_supply/battery/status", "Full");
}

static bool power_event(int fd)
{
    struct sockaddr_nl sender = {0};
    socklen_t length = sizeof(sender);
    char buffer[8192];
    ssize_t size = recvfrom(fd, buffer, sizeof(buffer) - 1, MSG_DONTWAIT,
                            (struct sockaddr *)&sender, &length);
    if (size < 0) return errno == ENOBUFS;
    if (sender.nl_pid != 0) return false;
    buffer[size] = '\0';
    for (const char *field = buffer; field < buffer + size; field += strlen(field) + 1)
        if (strcmp(field, "SUBSYSTEM=power_supply") == 0) return true;
    return false;
}

static long long milliseconds(void)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return now.tv_sec * 1000LL + now.tv_nsec / 1000000;
}

int main(int argc, char **argv)
{
    if (argc != 2 || chdir(argv[1]) != 0) return 1;
    int socket_fd = socket(AF_NETLINK, SOCK_DGRAM | SOCK_CLOEXEC, NETLINK_KOBJECT_UEVENT);
    struct sockaddr_nl address = {.nl_family = AF_NETLINK, .nl_groups = 1};
    if (socket_fd < 0 || bind(socket_fd, (struct sockaddr *)&address, sizeof(address)) != 0) {
        perror("power-events netlink");
        return 1;
    }
    int watch = inotify_init1(IN_CLOEXEC);
    if (watch < 0 || inotify_add_watch(watch, ".", IN_CREATE | IN_MOVED_TO |
                                                   IN_DELETE_SELF | IN_MOVE_SELF) < 0) {
        perror("power-events module watch");
        return 1;
    }
    struct pollfd fds[] = {{.fd = socket_fd, .events = POLLIN},
                           {.fd = watch, .events = POLLIN}};
    bool active = false;
    bool check_power = true;
    long long next = 0;
    setvbuf(stdout, NULL, _IOLBF, 0);
    while (access("disable", F_OK) != 0 && access("remove", F_OK) != 0) {
        long long now = milliseconds();
        if (check_power || (active && now >= next)) {
            bool online = powered();
            if (online && (!active || now >= next)) {
                if (puts("sample") < 0) return 1;
                next = now + 5000;
            } else if (active && !online) {
                if (puts("idle") < 0) return 1;
            }
            active = online;
        }
        now = milliseconds();
        int timeout = active ? (next > now ? (int)(next - now) : 0) : -1;
        int ready = poll(fds, 2, timeout);
        if (ready < 0) {
            if (errno == EINTR) continue;
            return 1;
        }
        if (fds[1].revents) {
            char events[4096];
            if (read(watch, events, sizeof(events)) <= 0 || access(argv[1], F_OK) != 0)
                return 1;
        }
        if (fds[0].revents & (POLLHUP | POLLNVAL)) return 1;
        check_power = fds[0].revents && power_event(socket_fd);
    }
    return 0;
}
