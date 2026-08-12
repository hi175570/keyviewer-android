package com.KV.bridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.KV.InputGrabberMain;
import com.KV.KeyInjector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import rikka.shizuku.Shizuku;

/** Android transport: Shizuku owns EVIOCGRAB/uinput, while C# owns all routing policy. */
public final class AndroidInputBridge implements AutoCloseable {
    private static final String TAG = "KV_DotNetInput";
    private static final int REQUEST_CODE = 0x4b56;
    private static final String DEVICE_PATTERN = "/dev/input/event\\d+";

    private final Context context;
    private final AndroidInputCallback callback;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicInteger monitors = new AtomicInteger();
    private final List<Process> processes = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Boolean> pressed = new ConcurrentHashMap<>();
    private final Object outputGate = new Object();
    private final Map<String, Integer> activeOutputs = new HashMap<>();
    private final Map<Integer, Integer> outputReferences = new HashMap<>();
    private volatile String jnaPath;
    private volatile boolean listenersRegistered;

    private final Shizuku.OnBinderReceivedListener binderReceived = this::ensureStarted;
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        stop();
        status("shizuku_disconnected");
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResult =
            (requestCode, grantResult) -> {
                if (requestCode != REQUEST_CODE) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) ensureStarted();
                else status("permission_denied");
            };

    public AndroidInputBridge(Context context, AndroidInputCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public void start() {
        registerListeners();
        ensureStarted();
    }

    public void requestPermission() {
        registerListeners();
        try {
            if (!Shizuku.pingBinder()) {
                status("shizuku_unavailable");
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ensureStarted();
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                status("permission_denied");
            } else {
                status("permission_required");
                Shizuku.requestPermission(REQUEST_CODE);
            }
        } catch (RuntimeException error) {
            status("shizuku_error:" + error.getClass().getSimpleName());
        }
    }

    private void ensureStarted() {
        try {
            if (!Shizuku.pingBinder()) {
                status("shizuku_unavailable");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                requestPermission();
                return;
            }
        } catch (RuntimeException error) {
            status("shizuku_error:" + error.getClass().getSimpleName());
            return;
        }

        if (!running.compareAndSet(false, true)) return;
        KeyInjector.getInstance(context).start();
        long currentGeneration = generation.incrementAndGet();
        status("starting");
        new Thread(() -> discover(currentGeneration), "KV-Android-Discovery").start();
    }

    private void discover(long currentGeneration) {
        if (!prepareJna()) {
            running.compareAndSet(true, false);
            status("native_helper_failed");
            return;
        }

        List<String> devices = findKeyboards();
        if (devices.isEmpty()) {
            running.compareAndSet(true, false);
            status("no_keyboard");
            return;
        }

        for (String device : devices) {
            if (!isCurrent(currentGeneration)) break;
            monitors.incrementAndGet();
            new Thread(() -> monitor(device, currentGeneration),
                    "KV-Android-" + device.replace('/', '_')).start();
        }
        status("listening:" + devices.size());
    }

    private boolean prepareJna() {
        String abi = Build.SUPPORTED_ABIS[0];
        if (!"x86_64".equals(abi) && !"arm64-v8a".equals(abi)) return false;
        String destination = "/data/local/tmp/kv_keyviewer/libjnidispatch.so";
        Process process = null;
        try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
            ZipEntry entry = apk.getEntry("lib/" + abi + "/libjnidispatch.so");
            if (entry == null) throw new IOException("JNA bridge missing for " + abi);
            process = newProcess(new String[]{"/system/bin/sh", "-c",
                    "umask 022; mkdir -p /data/local/tmp/kv_keyviewer; cat > "
                            + destination + "; chmod 755 " + destination});
            try (InputStream input = apk.getInputStream(entry);
                 OutputStream output = process.getOutputStream()) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
            if (process.waitFor() != 0) throw new IOException("Unable to stage JNA bridge");
            jnaPath = destination;
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Native helper setup failed", error);
            return false;
        } finally {
            destroy(process);
        }
    }

    private List<String> findKeyboards() {
        List<String> devices = new ArrayList<>();
        for (int index = 0; index < 32 && running.get(); index++) {
            String path = "/dev/input/event" + index;
            Process process = null;
            try {
                process = newProcess(new String[]{"sh", "-c", "getevent -p " + path + " 2>/dev/null"});
                StringBuilder info = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) info.append(line).append('\n');
                }
                if (isKeyboard(info.toString())) devices.add(path);
            } catch (Exception ignored) {
            } finally {
                destroy(process);
            }
        }
        return devices;
    }

    private static boolean isKeyboard(String info) {
        if (!info.contains("KEY (0001):")) return false;
        String lower = info.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("kv virtual input")) return false;
        String[] excluded = {"touch", "proximity", "hall", "grip", "sensor",
                "headset", "button jack", "mouse"};
        for (String item : excluded) if (lower.contains("name:") && lower.contains(item)) return false;
        int codes = 0;
        for (String token : info.split("\\s+")) if (token.matches("[0-9a-fA-F]{4}")) codes++;
        return codes > 50;
    }

    private void monitor(String device, long currentGeneration) {
        Process process = null;
        try {
            String apk = context.getApplicationInfo().sourceDir;
            String command = "CLASSPATH=" + apk
                    + " TMPDIR=/data/local/tmp exec /system/bin/app_process"
                    + " -Djava.library.path=/data/local/tmp/kv_keyviewer"
                    + " /system/bin " + InputGrabberMain.class.getName()
                    + " " + device + " " + jnaPath;
            process = newProcess(new String[]{"/system/bin/sh", "-c", command});
            processes.add(process);
            drainErrors(process, device);
            try (InputStream input = process.getInputStream()) {
                byte[] packet = new byte[24];
                while (isCurrent(currentGeneration) && readPacket(input, packet)) {
                    int type = ushort(packet, 16);
                    int code = ushort(packet, 18);
                    int value = integer(packet, 20);
                    if (type == 1 && value >= 0 && value <= 2) route(device, code, value);
                }
            }
        } catch (Exception error) {
            if (isCurrent(currentGeneration)) Log.e(TAG, "Monitor stopped: " + device, error);
        } finally {
            if (process != null) processes.remove(process);
            releaseDevice(device);
            destroy(process);
            if (monitors.decrementAndGet() == 0 && isCurrent(currentGeneration)) {
                running.compareAndSet(true, false);
                status("stopped");
            }
        }
    }

    private void route(String device, int sourceCode, int linuxValue) {
        String stateKey = device + ':' + sourceCode;
        int state;
        if (linuxValue == 0) {
            if (!Boolean.TRUE.equals(pressed.remove(stateKey))) {
                KeyInjector.getInstance(context).injectCode(sourceCode, 0);
                return;
            }
            state = 2;
        } else if (linuxValue == 2) {
            state = 1;
        } else {
            if (Boolean.TRUE.equals(pressed.put(stateKey, true))) return;
            state = 0;
        }

        String sourceName = keyName(sourceCode);
        if (state == 1) {
            repeatOutput(stateKey, sourceCode);
            safeCallback(sourceName, state, device);
            return;
        }
        if (state == 2) {
            safeCallback(sourceName, state, device);
            releaseOutput(stateKey);
            return;
        }

        String target = safeCallback(sourceName, state, device);
        int outputCode = 0;
        if (target == null) {
            outputCode = sourceCode;
        } else if (!target.isEmpty()) {
            Integer mapped = KeyInjector.linuxKeyCode(target);
            outputCode = mapped == null && normalize(target).equals(normalize(sourceName))
                    ? sourceCode : mapped == null ? sourceCode : mapped;
        }
        pressOutput(stateKey, outputCode);
    }

    private String safeCallback(String key, int state, String device) {
        try {
            return callback == null ? null : callback.route(key, state, "android:" + device);
        } catch (Throwable error) {
            Log.e(TAG, "C# route callback failed; passing physical input", error);
            return null;
        }
    }

    private void pressOutput(String stateKey, int outputCode) {
        synchronized (outputGate) {
            activeOutputs.put(stateKey, outputCode);
            if (outputCode == 0) return;
            int count = outputReferences.getOrDefault(outputCode, 0) + 1;
            outputReferences.put(outputCode, count);
            if (count == 1) KeyInjector.getInstance(context).injectCode(outputCode, 1);
        }
    }

    private void repeatOutput(String stateKey, int sourceCode) {
        synchronized (outputGate) {
            int outputCode = activeOutputs.getOrDefault(stateKey, sourceCode);
            if (outputCode != 0) KeyInjector.getInstance(context).injectCode(outputCode, 2);
        }
    }

    private void releaseOutput(String stateKey) {
        synchronized (outputGate) {
            Integer outputCode = activeOutputs.remove(stateKey);
            if (outputCode == null || outputCode == 0) return;
            int count = outputReferences.getOrDefault(outputCode, 1) - 1;
            if (count <= 0) {
                outputReferences.remove(outputCode);
                KeyInjector.getInstance(context).injectCode(outputCode, 0);
            } else outputReferences.put(outputCode, count);
        }
    }

    private void releaseDevice(String device) {
        String prefix = device + ':';
        for (String key : new ArrayList<>(pressed.keySet())) if (key.startsWith(prefix)) pressed.remove(key);
        for (String key : new ArrayList<>(activeOutputs.keySet())) if (key.startsWith(prefix)) releaseOutput(key);
    }

    public void stop() {
        if (!running.getAndSet(false)) return;
        generation.incrementAndGet();
        synchronized (processes) {
            for (Process process : processes) destroy(process);
            processes.clear();
        }
        for (Integer code : new ArrayList<>(outputReferences.keySet())) {
            KeyInjector.getInstance(context).injectCode(code, 0);
        }
        pressed.clear();
        synchronized (outputGate) {
            activeOutputs.clear();
            outputReferences.clear();
        }
        status("stopped");
    }

    @Override public void close() {
        stop();
        if (listenersRegistered) {
            Shizuku.removeBinderReceivedListener(binderReceived);
            Shizuku.removeBinderDeadListener(binderDead);
            Shizuku.removeRequestPermissionResultListener(permissionResult);
            listenersRegistered = false;
        }
    }

    private void registerListeners() {
        if (listenersRegistered) return;
        Shizuku.addBinderReceivedListener(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        listenersRegistered = true;
    }

    private void status(String value) {
        try { if (callback != null) callback.onStatus(value); }
        catch (Throwable ignored) { }
    }

    private boolean isCurrent(long value) { return running.get() && generation.get() == value; }

    private Process newProcess(String[] command) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        return (Process) method.invoke(null, command, null, null);
    }

    private static void destroy(Process process) {
        if (process == null) return;
        try { process.destroy(); } catch (RuntimeException ignored) { }
    }

    private static boolean readPacket(InputStream input, byte[] packet) throws IOException {
        int offset = 0;
        while (offset < packet.length) {
            int count = input.read(packet, offset, packet.length - offset);
            if (count < 0) return false;
            offset += count;
        }
        return true;
    }

    private static int ushort(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static int integer(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8)
                | ((bytes[offset + 2] & 255) << 16) | ((bytes[offset + 3] & 255) << 24);
    }

    private static void drainErrors(Process process, String device) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) Log.w(TAG, device + ": " + line);
            } catch (IOException ignored) { }
        }, "KV-Android-Errors").start();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String keyName(int code) {
        switch (code) {
            case 1: return "esc"; case 14: return "backspace"; case 15: return "tab";
            case 28: return "enter"; case 29: return "lctrl"; case 42: return "lshift";
            case 54: return "rshift"; case 56: return "lalt"; case 57: return "space";
            case 58: return "capslock"; case 69: return "numlock"; case 97: return "rctrl";
            case 100: return "ralt"; case 102: return "home"; case 103: return "up";
            case 104: return "pageup"; case 105: return "left"; case 106: return "right";
            case 107: return "end"; case 108: return "down"; case 109: return "pagedown";
            case 110: return "insert"; case 111: return "del"; case 125: return "lmeta";
            case 126: return "rmeta"; case 12: return "minus"; case 13: return "equal";
            case 26: return "bracketleft"; case 27: return "bracketright";
            case 39: return "semicolon"; case 40: return "quote"; case 41: return "backquote";
            case 43: return "backslash"; case 51: return "comma"; case 52: return "dot";
            case 53: return "slash";
            case 59: return "f1"; case 60: return "f2"; case 61: return "f3";
            case 62: return "f4"; case 63: return "f5"; case 64: return "f6";
            case 65: return "f7"; case 66: return "f8"; case 67: return "f9";
            case 68: return "f10"; case 87: return "f11"; case 88: return "f12";
        }
        if (code >= 2 && code <= 11) return "1234567890".substring(code - 2, code - 1);
        int[] codes = {30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44};
        String letters = "abcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < codes.length; i++) if (codes[i] == code) return letters.substring(i, i + 1);
        return "linux_" + code;
    }
}
