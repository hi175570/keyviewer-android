#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

static int emit_event(int fd, unsigned short type, unsigned short code, int value) {
    struct input_event event;
    memset(&event, 0, sizeof(event));
    event.type = type;
    event.code = code;
    event.value = value;
    return write(fd, &event, sizeof(event)) == sizeof(event) ? 0 : -1;
}

int main(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        perror("open /dev/uinput");
        return 2;
    }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    for (int code = 1; code <= 126; ++code) ioctl(fd, UI_SET_KEYBIT, code);

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "KeyViewer Smoke Keyboard");
    setup.id.bustype = BUS_USB;
    setup.id.vendor = 0x4b56;
    setup.id.product = 0x0001;
    setup.id.version = 1;
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        perror("create uinput");
        close(fd);
        return 3;
    }

    /* Allow Android and KeyViewer discovery to open and grab the new device. */
    sleep(12);
    emit_event(fd, EV_KEY, KEY_1, 1);
    emit_event(fd, EV_SYN, SYN_REPORT, 0);
    usleep(150000);
    emit_event(fd, EV_KEY, KEY_1, 0);
    emit_event(fd, EV_SYN, SYN_REPORT, 0);
    sleep(3);

    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}
