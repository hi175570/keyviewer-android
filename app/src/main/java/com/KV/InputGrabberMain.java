package com.KV;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;

/** Entry point launched by Shizuku as the shell UID; stdout is raw struct input_event data. */
public final class InputGrabberMain {
    private static final String DEVICE_PATH_PATTERN = "/dev/input/event\\d+";

    private InputGrabberMain() {}

    public static void main(String[] args) {
        if (args.length != 2 || !args[0].matches(DEVICE_PATH_PATTERN)
                || !args[1].matches("/data/local/tmp/kv_keyviewer/libjnidispatch\\.so")) {
            System.err.println("Invalid input device path");
            System.exit(2);
        }
        System.setProperty("jna.tmpdir", "/data/local/tmp");
        System.setProperty("jna.nosys", "false");
        System.setProperty("jna.boot.library.path", "/data/local/tmp/kv_keyviewer");

        int fd = -1;
        try (FileOutputStream output = new FileOutputStream(FileDescriptor.out)) {
            System.load(args[1]);
            fd = NativeInput.open(args[0], true);
            if (fd < 0) throw new IOException("open/grab failed: " + fd);
            byte[] event = new byte[24];
            int offset = 0;
            for (;;) {
                byte[] target = offset == 0 ? event : new byte[event.length - offset];
                int count = NativeInput.read(fd, target);
                if (count <= 0) break;
                if (offset == 0 && count == event.length) {
                    output.write(event);
                    continue;
                }
                System.arraycopy(target, 0, event, offset, count);
                offset += count;
                if (offset == event.length) {
                    output.write(event);
                    offset = 0;
                }
            }
        } catch (Throwable e) {
            System.err.println("KV input grabber failed: " + e);
            System.exit(1);
        } finally {
            NativeInput.close(fd, true);
        }
    }
}
