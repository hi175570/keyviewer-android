package com.KV;

import com.sun.jna.LastErrorException;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;

/** Minimal libc bridge used inside the Shizuku UserService. */
final class NativeInput {
    private static final int O_RDONLY = 0;
    private static final int O_CLOEXEC = 0x80000;
    private static final long EVIOCGRAB = 0x40044590L;

    private interface LibC extends Library {
        int open(String path, int flags, int mode) throws LastErrorException;
        int ioctl(int fd, NativeLong request, int value) throws LastErrorException;
        int read(int fd, byte[] buffer, int count) throws LastErrorException;
        int close(int fd);
    }

    private static final LibC LIBC = Native.load("c", LibC.class);

    private NativeInput() {}

    static int open(String devicePath, boolean grab) {
        int fd = LIBC.open(devicePath, O_RDONLY | O_CLOEXEC, 0);
        if (fd < 0) return -Math.max(1, Native.getLastError());
        if (grab && LIBC.ioctl(fd, new NativeLong(EVIOCGRAB), 1) < 0) {
            int error = Math.max(1, Native.getLastError());
            LIBC.close(fd);
            return -error;
        }
        return fd;
    }

    static int read(int fd, byte[] buffer) {
        return LIBC.read(fd, buffer, buffer.length);
    }

    static void close(int fd, boolean grabbed) {
        if (fd < 0) return;
        if (grabbed) {
            try { LIBC.ioctl(fd, new NativeLong(EVIOCGRAB), 0); } catch (RuntimeException ignored) { }
        }
        LIBC.close(fd);
    }
}
