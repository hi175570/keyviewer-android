#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define LOG_TAG "KV_NativeInput"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static pthread_mutex_t server_lock = PTHREAD_MUTEX_INITIALIZER;
static int server_started;
static int stop_requested;
static int listen_fd = -1;
static int uinput_fd = -1;
static char server_name[sizeof(((struct sockaddr_un *)0)->sun_path) - 1] = "kv_socket";

struct input_packet {
    uint16_t type;
    uint16_t code;
    int32_t value;
};

struct input_monitor {
    int input_fd;
    int output_fd;
    int grabbed;
};

static int write_all(int fd, const void *data, size_t size) {
    const uint8_t *cursor = (const uint8_t *) data;
    while (size > 0) {
        ssize_t written = write(fd, cursor, size);
        if (written < 0) {
            if (errno == EINTR) continue;
            return -errno;
        }
        cursor += written;
        size -= (size_t) written;
    }
    return 0;
}

static int setup_uinput(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) {
        int error = -errno;
        close(fd);
        return error;
    }
    for (int code = 1; code <= KEY_MAX; code++) {
        if (ioctl(fd, UI_SET_KEYBIT, code) < 0) {
            int error = -errno;
            close(fd);
            return error;
        }
    }

    struct uinput_user_dev device;
    memset(&device, 0, sizeof(device));
    snprintf(device.name, UINPUT_MAX_NAME_SIZE, "KV Virtual Input");
    device.id.bustype = 0x30;
    device.id.version = 1;
    int result = write_all(fd, &device, sizeof(device));
    if (result != 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        int error = result != 0 ? result : -errno;
        close(fd);
        return error;
    }
    uinput_fd = fd;
    LOGI("uinput device created");
    return 0;
}

static int inject_event(uint16_t code, int down) {
    struct input_event event;
    memset(&event, 0, sizeof(event));
    event.type = EV_KEY;
    event.code = code;
    event.value = down;
    int result = write_all(uinput_fd, &event, sizeof(event));
    if (result != 0) return result;
    memset(&event, 0, sizeof(event));
    event.type = EV_SYN;
    event.code = SYN_REPORT;
    return write_all(uinput_fd, &event, sizeof(event));
}

static int is_valid_input_path(const char *path) {
    static const char prefix[] = "/dev/input/event";
    if (path == NULL || strncmp(path, prefix, sizeof(prefix) - 1) != 0) return 0;
    const char *cursor = path + sizeof(prefix) - 1;
    if (*cursor == '\0') return 0;
    while (*cursor != '\0') {
        if (*cursor < '0' || *cursor > '9') return 0;
        cursor++;
    }
    return 1;
}

static void *input_monitor_main(void *opaque) {
    struct input_monitor *monitor = (struct input_monitor *) opaque;
    struct input_event event;
    for (;;) {
        ssize_t received = read(monitor->input_fd, &event, sizeof(event));
        if (received < 0 && errno == EINTR) continue;
        if (received != (ssize_t) sizeof(event)) break;
        if (event.type != EV_KEY) continue;

        struct input_packet packet;
        packet.type = event.type;
        packet.code = event.code;
        packet.value = event.value;
        ssize_t sent = send(monitor->output_fd, &packet, sizeof(packet), MSG_NOSIGNAL);
        if (sent != (ssize_t) sizeof(packet)) break;
    }

    if (monitor->grabbed) ioctl(monitor->input_fd, EVIOCGRAB, 0);
    close(monitor->input_fd);
    close(monitor->output_fd);
    free(monitor);
    return NULL;
}

static int bind_server_socket(const char *name) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -errno;
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    size_t name_length = strnlen(name, sizeof(address.sun_path) - 1);
    memcpy(address.sun_path + 1, name, name_length);
    socklen_t address_length = (socklen_t) (offsetof(struct sockaddr_un, sun_path) + 1 + name_length);
    if (bind(fd, (struct sockaddr *) &address, address_length) < 0 || listen(fd, 2) < 0) {
        int error = -errno;
        close(fd);
        return error;
    }
    listen_fd = fd;
    return 0;
}

static void cleanup_server(void) {
    if (listen_fd >= 0) {
        close(listen_fd);
        listen_fd = -1;
    }
    if (uinput_fd >= 0) {
        ioctl(uinput_fd, UI_DEV_DESTROY);
        close(uinput_fd);
        uinput_fd = -1;
    }
    pthread_mutex_lock(&server_lock);
    server_started = 0;
    pthread_mutex_unlock(&server_lock);
}

static void *server_main(void *unused) {
    (void) unused;
    int result = setup_uinput();
    if (result == 0) result = bind_server_socket(server_name);
    if (result != 0) {
        LOGE("server startup failed: %d", result);
        cleanup_server();
        return NULL;
    }
    LOGI("listening on @%s", server_name);

    while (!stop_requested) {
        int client = accept4(listen_fd, NULL, NULL, SOCK_CLOEXEC);
        if (client < 0) {
            if (errno == EINTR) continue;
            if (!stop_requested) LOGE("accept failed: %d", errno);
            break;
        }
        for (;;) {
            uint8_t command[4];
            ssize_t received = recv(client, command, sizeof(command), MSG_WAITALL);
            if (received != (ssize_t) sizeof(command)) break;
            uint16_t state = (uint16_t) command[0] | ((uint16_t) command[1] << 8);
            uint16_t code = (uint16_t) command[2] | ((uint16_t) command[3] << 8);
            if (code > 0 && code <= KEY_MAX) {
                result = inject_event(code, state <= 2 ? state : 0);
                if (result != 0) LOGE("inject failed: %d", result);
            }
        }
        close(client);
    }
    cleanup_server();
    return NULL;
}

JNIEXPORT jint JNICALL
Java_com_KV_IpcService_nativeOpenInput(JNIEnv *env, jclass clazz, jstring device_path,
                                       jboolean grab) {
    (void) clazz;
    const char *path = (*env)->GetStringUTFChars(env, device_path, NULL);
    if (path == NULL) return -ENOMEM;
    if (!is_valid_input_path(path)) {
        (*env)->ReleaseStringUTFChars(env, device_path, path);
        return -EINVAL;
    }

    int input_fd = open(path, O_RDONLY | O_CLOEXEC);
    int open_error = errno;
    (*env)->ReleaseStringUTFChars(env, device_path, path);
    if (input_fd < 0) return -open_error;

    int grabbed = 0;
    if (grab) {
        if (ioctl(input_fd, EVIOCGRAB, 1) < 0) {
            int error = -errno;
            close(input_fd);
            return error;
        }
        grabbed = 1;
    }

    int sockets[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sockets) < 0) {
        int error = -errno;
        if (grabbed) ioctl(input_fd, EVIOCGRAB, 0);
        close(input_fd);
        return error;
    }

    struct input_monitor *monitor = calloc(1, sizeof(*monitor));
    if (monitor == NULL) {
        if (grabbed) ioctl(input_fd, EVIOCGRAB, 0);
        close(input_fd);
        close(sockets[0]);
        close(sockets[1]);
        return -ENOMEM;
    }
    monitor->input_fd = input_fd;
    monitor->output_fd = sockets[1];
    monitor->grabbed = grabbed;

    pthread_t thread;
    int result = pthread_create(&thread, NULL, input_monitor_main, monitor);
    if (result != 0) {
        if (grabbed) ioctl(input_fd, EVIOCGRAB, 0);
        close(input_fd);
        close(sockets[0]);
        close(sockets[1]);
        free(monitor);
        return -result;
    }
    pthread_detach(thread);
    LOGI("input monitor started, grab=%d", grabbed);
    return sockets[0];
}

JNIEXPORT jint JNICALL
Java_com_KV_IpcService_nativeStartServer(JNIEnv *env, jclass clazz, jstring socket_name) {
    (void) clazz;
    pthread_mutex_lock(&server_lock);
    if (server_started) {
        pthread_mutex_unlock(&server_lock);
        return 0;
    }
    const char *name = (*env)->GetStringUTFChars(env, socket_name, NULL);
    if (name == NULL) {
        pthread_mutex_unlock(&server_lock);
        return -ENOMEM;
    }
    snprintf(server_name, sizeof(server_name), "%s", name);
    (*env)->ReleaseStringUTFChars(env, socket_name, name);
    stop_requested = 0;
    server_started = 1;
    pthread_t thread;
    int result = pthread_create(&thread, NULL, server_main, NULL);
    if (result == 0) pthread_detach(thread); else server_started = 0;
    pthread_mutex_unlock(&server_lock);
    return result == 0 ? 0 : -result;
}

JNIEXPORT jint JNICALL
Java_com_KV_IpcService_nativeConnectToSocket(JNIEnv *env, jclass clazz, jstring socket_name) {
    (void) clazz;
    const char *name = (*env)->GetStringUTFChars(env, socket_name, NULL);
    if (name == NULL) return -ENOMEM;
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    size_t name_length = strnlen(name, sizeof(address.sun_path) - 1);
    memcpy(address.sun_path + 1, name, name_length);
    (*env)->ReleaseStringUTFChars(env, socket_name, name);
    socklen_t address_length = (socklen_t) (offsetof(struct sockaddr_un, sun_path) + 1 + name_length);

    int last_error = ECONNREFUSED;
    for (int attempt = 0; attempt < 100; attempt++) {
        int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) return -errno;
        if (connect(fd, (struct sockaddr *) &address, address_length) == 0) return fd;
        last_error = errno;
        close(fd);
        usleep(20000);
    }
    return -last_error;
}

JNIEXPORT void JNICALL
Java_com_KV_IpcService_nativeStopServer(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    stop_requested = 1;
    if (listen_fd >= 0) shutdown(listen_fd, SHUT_RDWR);
}
