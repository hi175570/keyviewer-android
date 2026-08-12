package com.KV;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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

public class RishInputListener {

    private static final String TAG = "KV_Rish";
    private static final String DEVICE_PATH_PATTERN = "/dev/input/event\\d+";

    private final KeyView keyView;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Process> currentProcesses =
            Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Boolean> keyPressedState = new ConcurrentHashMap<>();
    private final Object mappingStateLock = new Object();
    private final Map<String, Integer> activeOutputs = new HashMap<>();
    private final Map<Integer, Integer> outputPressCounts = new HashMap<>();
    private final Map<String, String> activeVisualKeys = new HashMap<>();
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicInteger activeMonitors = new AtomicInteger(0);
    private final KeyDebounceFilter debounceFilter = new KeyDebounceFilter();

    private volatile boolean paused;
    private volatile long completedDiscoveryGeneration = -1;
    private volatile String inputHelperLibraryPath;

    public RishInputListener(Context context, KeyView keyView) {
        this.context = context.getApplicationContext();
        this.keyView = keyView;
    }

    public boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == 0;
        } catch (Exception e) {
            Log.e(TAG, "Unable to query Shizuku", e);
            return false;
        }
    }

    public void startListening() {
        if (!isShizukuAvailable() || !isListening.compareAndSet(false, true)) {
            return;
        }

        KeyInjector.getInstance(context).start();
        paused = false;
        completedDiscoveryGeneration = -1;
        final long listenerGeneration = generation.incrementAndGet();
        new Thread(() -> discoverAndStart(listenerGeneration), "KV-Input-Discovery").start();
    }

    private void discoverAndStart(long listenerGeneration) {
        int started = 0;
        if (!prepareInputHelper()) {
            Log.e(TAG, "Unable to prepare native input helper");
            isListening.compareAndSet(true, false);
            return;
        }
        GlobalSettings settings = GlobalSettings.getInstance(context);
        String calibratedDevice = settings.keyboardDevicePath;

        List<String> discoveredDevices = prioritizeCalibratedDevice(
                findAllKeyboardDevices(), calibratedDevice);
        Log.i(TAG, "Keyboard discovery completed: " + discoveredDevices.size() + " device(s)");
        for (String device : discoveredDevices) {
            if (!isCurrent(listenerGeneration)) {
                break;
            }
            startMonitoringAsync(device, listenerGeneration);
            started++;
        }

        completedDiscoveryGeneration = listenerGeneration;
        if (started == 0 || activeMonitors.get() == 0) {
            isListening.compareAndSet(true, false);
        }
    }

    private boolean prepareInputHelper() {
        String abi = android.os.Build.SUPPORTED_ABIS[0];
        if (!"x86_64".equals(abi) && !"arm64-v8a".equals(abi)) {
            Log.e(TAG, "Unsupported input helper ABI: " + abi);
            return false;
        }
        final String destination = "/data/local/tmp/kv_keyviewer/libjnidispatch.so";
        Process process = null;
        try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
            ZipEntry entry = apk.getEntry("lib/" + abi + "/libjnidispatch.so");
            if (entry == null) throw new IOException("JNA bridge missing for " + abi);
            Method method = getNewProcessMethod();
            process = (Process) method.invoke(null, new String[]{
                    "/system/bin/sh", "-c",
                    "umask 022; mkdir -p /data/local/tmp/kv_keyviewer; "
                            + "cat > " + destination + "; chmod 755 " + destination
            }, null, null);
            try (InputStream input = apk.getInputStream(entry);
                 OutputStream output = process.getOutputStream()) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            }
            if (process.waitFor() != 0) throw new IOException("Unable to stage JNA bridge");
            inputHelperLibraryPath = destination;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Input helper setup failed", e);
            return false;
        } finally {
            if (process != null) destroyProcess(process);
        }
    }

    public void pause() {
        paused = true;
        clearPressedState();
    }

    public void resume() {
        paused = false;
    }

    private void clearPressedState() {
        keyPressedState.clear();
        debounceFilter.clear();
        synchronized (mappingStateLock) {
            for (int outputCode : outputPressCounts.keySet()) {
                KeyInjector.getInstance(context).injectCode(outputCode, 0);
            }
            activeOutputs.clear();
            outputPressCounts.clear();
            activeVisualKeys.clear();
        }
        mainHandler.post(keyView::releaseAllPressedKeys);
    }

    private List<String> findAllKeyboardDevices() {
        List<String> devices = new ArrayList<>();
        try {
            Method method = getNewProcessMethod();
            for (int i = 0; i < 32 && isListening.get(); i++) {
                String devicePath = "/dev/input/event" + i;
                if (!deviceExists(devicePath, method)) {
                    continue;
                }
                String deviceInfo = getDeviceInfo(devicePath, method);
                if (deviceInfo != null && isKeyboardDevice(deviceInfo)) {
                    devices.add(devicePath);
                    Log.i(TAG, "Keyboard input: " + devicePath + " (" + extractDeviceName(deviceInfo) + ")");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Input device discovery failed", e);
        }
        return devices;
    }

    static List<String> prioritizeCalibratedDevice(List<String> discovered, String calibrated) {
        List<String> ordered = new ArrayList<>();
        if (calibrated != null && discovered.contains(calibrated)) {
            ordered.add(calibrated);
        }
        for (String device : discovered) {
            if (device != null && !ordered.contains(device)) ordered.add(device);
        }
        return ordered;
    }

    private Method getNewProcessMethod() throws Exception {
        Method method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        return method;
    }

    private boolean deviceExists(String devicePath, Method method) {
        if (!isValidDevicePath(devicePath)) {
            return false;
        }
        Process process = null;
        try {
            process = (Process) method.invoke(null,
                    new String[]{"sh", "-c", "test -e " + devicePath + " && echo ok"}, null, null);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                return "ok".equals(reader.readLine());
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) {
                destroyProcess(process);
            }
        }
    }

    private String getDeviceInfo(String devicePath, Method method) {
        if (!isValidDevicePath(devicePath)) {
            return null;
        }
        Process process = null;
        try {
            process = (Process) method.invoke(null,
                    new String[]{"sh", "-c", "getevent -p " + devicePath + " 2>/dev/null"}, null, null);
            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line).append('\n');
                }
            }
            return result.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                destroyProcess(process);
            }
        }
    }

    static boolean isKeyboardDevice(String deviceInfo) {
        if (!deviceInfo.contains("KEY (0001):")) {
            return false;
        }

        int keyCount = countKeyCodes(deviceInfo);
        String name = extractDeviceName(deviceInfo).toLowerCase(java.util.Locale.ROOT);
        if (name.contains("kv virtual input")) {
            return false;
        }
        String[] excluded = {"touch", "proximity", "hall", "grip", "accdet", "meta",
                "sensor", "headset", "button jack", "pon", "nav", "folio"};
        for (String pattern : excluded) {
            if (name.contains(pattern)) {
                return false;
            }
        }

        boolean pointer = deviceInfo.contains("REL (0002):") || deviceInfo.contains("EV_REL");
        boolean namedMouseOnly = pointer && name.contains("mouse") && !name.contains("keyboard");
        return keyCount > 50 && !namedMouseOnly;
    }

    private static int countKeyCodes(String deviceInfo) {
        int count = 0;
        boolean inKeySection = false;
        for (String line : deviceInfo.split("\\n")) {
            if (line.contains("KEY") && line.contains("(0001)")) {
                inKeySection = true;
                count += countHexNumbers(line);
            } else if (inKeySection) {
                if (line.trim().isEmpty() || (line.contains("(") && !line.contains("KEY"))) {
                    inKeySection = false;
                } else {
                    count += countHexNumbers(line);
                }
            }
        }
        return count;
    }

    private static int countHexNumbers(String line) {
        int count = 0;
        for (String part : line.split("\\s+")) {
            if (part.matches("[0-9a-fA-F]{4}")) {
                count++;
            }
        }
        return count;
    }

    private static String extractDeviceName(String deviceInfo) {
        for (String line : deviceInfo.split("\\n")) {
            if (line.contains("name:")) {
                int start = line.indexOf('"');
                int end = line.lastIndexOf('"');
                if (start >= 0 && end > start) {
                    return line.substring(start + 1, end);
                }
            }
        }
        return "unknown";
    }

    private void startMonitoringAsync(String devicePath, long listenerGeneration) {
        if (!isCurrent(listenerGeneration) || !isValidDevicePath(devicePath)) {
            return;
        }
        activeMonitors.incrementAndGet();
        Log.i(TAG, "Starting exclusive monitor for " + devicePath);
        new Thread(() -> {
            try {
                startMonitoring(devicePath, listenerGeneration);
            } finally {
                releaseDeviceState(devicePath);
                if (activeMonitors.decrementAndGet() == 0
                        && completedDiscoveryGeneration == listenerGeneration) {
                    isListening.compareAndSet(true, false);
                }
            }
        }, "KV-Monitor-" + devicePath.replace('/', '_')).start();
    }

    private void startMonitoring(String devicePath, long listenerGeneration) {
        Process process = null;
        try {
            Method method = getNewProcessMethod();
            String apkPath = context.getApplicationInfo().sourceDir;
            if (!apkPath.matches("/data/app/[A-Za-z0-9_~+=./-]+/base\\.apk")) {
                throw new IOException("Rejected unexpected APK path");
            }
            String command = "CLASSPATH=" + apkPath
                    + " TMPDIR=/data/local/tmp exec /system/bin/app_process"
                    + " -Djava.library.path=/data/local/tmp/kv_keyviewer"
                    + " /system/bin " + InputGrabberMain.class.getName()
                    + " " + devicePath + " " + inputHelperLibraryPath;
            process = (Process) method.invoke(null,
                    new String[]{"/system/bin/sh", "-c", command}, null, null);
            currentProcesses.add(process);
            Log.i(TAG, "Exclusive input helper started for " + devicePath);
            drainMonitorErrors(process, devicePath);
            try (InputStream input = process.getInputStream()) {
                // Both packaged ABIs are 64-bit: timeval (16 bytes) + type/code/value (8).
                byte[] packet = new byte[24];
                while (isCurrent(listenerGeneration) && readPacket(input, packet)) {
                    int type = unsignedShortLe(packet, 16);
                    int code = unsignedShortLe(packet, 18);
                    int value = intLe(packet, 20);
                    processEvent(devicePath, type, code, value, listenerGeneration);
                }
            }
        } catch (Exception e) {
            if (isCurrent(listenerGeneration)) {
                Log.e(TAG, "Input monitor stopped for " + devicePath, e);
            }
        } finally {
            if (process != null) {
                currentProcesses.remove(process);
                try {
                    Log.i(TAG, "Input helper exited for " + devicePath + ": " + process.exitValue());
                } catch (RuntimeException ignored) { }
                destroyProcess(process);
            }
        }
    }

    private void drainMonitorErrors(Process process, String devicePath) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.w(TAG, "Input helper " + devicePath + ": " + line);
                }
            } catch (IOException ignored) { }
        }, "KV-Monitor-Errors").start();
    }

    private boolean readPacket(InputStream input, byte[] packet) throws IOException {
        int offset = 0;
        while (offset < packet.length) {
            int count = input.read(packet, offset, packet.length - offset);
            if (count < 0) return false;
            offset += count;
        }
        return true;
    }

    private int unsignedShortLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private int intLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private void destroyProcess(Process process) {
        try {
            process.destroy();
        } catch (RuntimeException e) {
            // Shizuku can die before its Process proxy is cleaned up. Cleanup must not crash
            // the app just because the remote Binder has already disappeared.
            Log.w(TAG, "Remote process was already unavailable during cleanup", e);
        }
    }

    private void processEvent(String devicePath, int type, int code, int value,
                              long listenerGeneration) {
        if (!isCurrent(listenerGeneration) || type != 1 || value < 0 || value > 2) {
            return;
        }
        if (paused) {
            KeyInjector.getInstance(context).injectCode(code, value);
            return;
        }

        String originalKey = keyCodeToName(code);
        if (originalKey == null) {
            GlobalSettings settings = GlobalSettings.getInstance(context);
            // Restriction mode intentionally swallows unknown keyboard/OEM keys because they
            // cannot exist on the KV canvas. Safety-reserved system codes always pass through.
            if (!KeyRestrictionPolicy.shouldBlockUnknownCode(
                    code, settings.keyRestrictionEnabled)) {
                KeyInjector.getInstance(context).injectCode(code, value);
            }
            return;
        }
        String stateKey = devicePath + ':' + code;
        if (value == 2) {
            // Route repeats on the same queue as DOWN/UP. Otherwise a fast repeat can overtake
            // the mapped DOWN and leak the original key into the foreground application.
            mainHandler.post(() -> repeatOutput(stateKey, code));
            return;
        }

        boolean isDown = value == 1;
        boolean suppressOutput = false;
        if (isDown) {
            if (Boolean.TRUE.equals(keyPressedState.put(stateKey, true))) return;
            GlobalSettings settings = GlobalSettings.getInstance(context);
            suppressOutput = debounceFilter.shouldSuppressDown(
                    stateKey, android.os.SystemClock.elapsedRealtimeNanos(),
                    settings.keyDebounceEnabled, settings.keyDebounceMillis);
        } else if (!Boolean.TRUE.equals(keyPressedState.remove(stateKey))) {
            // Listening may begin while a key is already held. Preserve its release so the
            // foreground application cannot be left with a stuck key.
            KeyInjector.getInstance(context).injectCode(code, 0);
            return;
        } else {
            debounceFilter.recordRelease(stateKey, android.os.SystemClock.elapsedRealtimeNanos());
        }
        final boolean swallowThisPress = suppressOutput;
        mainHandler.post(() -> dispatchKey(
                originalKey, stateKey, code, isDown, swallowThisPress, listenerGeneration));
    }

    private void dispatchKey(String originalKey, String stateKey, int sourceCode, boolean isDown,
                             boolean suppressOutput, long listenerGeneration) {
        if (!isCurrent(listenerGeneration) || paused) {
            KeyInjector.getInstance(context).injectCode(sourceCode, isDown ? 1 : 0);
            return;
        }
        String visualKey;
        if (isDown) {
            boolean bound = keyView.isKeyBound(originalKey);
            GlobalSettings settings = GlobalSettings.getInstance(context);
            boolean restricted = KeyRestrictionPolicy.shouldBlock(
                    originalKey, bound, settings.keyRestrictionEnabled,
                    settings.keyRestrictionBlockedKeys);
            Integer outputCode;
            if (restricted) {
                outputCode = 0;
                visualKey = null;
            } else {
                boolean ghost = bound && keyView.isGhostKey(originalKey);
                String mapped = bound ? keyView.getMappedKey(originalKey) : originalKey;
                KeyDispatchRoute route = KeyDispatchRoute.resolve(originalKey, mapped, ghost);
                outputCode = suppressOutput || route.outputKey == null
                        ? 0 : KeyInjector.linuxKeyCode(route.outputKey);
                if (outputCode == null) {
                    outputCode = sourceCode;
                    Log.w(TAG, "Unsupported mapping target; passing source key: " + route.outputKey);
                }
                visualKey = bound ? originalKey : null;
            }
            pressOutput(stateKey, outputCode);
            synchronized (mappingStateLock) {
                if (visualKey == null) activeVisualKeys.remove(stateKey);
                else activeVisualKeys.put(stateKey, visualKey);
            }
        } else {
            releaseOutput(stateKey);
            synchronized (mappingStateLock) {
                visualKey = activeVisualKeys.remove(stateKey);
            }
        }
        if (visualKey == null) {
            return;
        }
        if (!isDown) {
            synchronized (mappingStateLock) {
                // Another keyboard may still be holding the same visual key. Keep it visibly
                // pressed until the final physical source releases it.
                if (activeVisualKeys.containsValue(visualKey)) return;
            }
        }
        keyView.notifyKeyPressed(visualKey);
        keyView.setKeyPressed(visualKey, isDown);
        if (isDown) {
            keyView.getKeyManager().recordKeyPress(visualKey);
            keyView.invalidate();
        }
    }

    private void pressOutput(String stateKey, int outputCode) {
        synchronized (mappingStateLock) {
            activeOutputs.put(stateKey, outputCode);
            if (outputCode == 0) return;
            int pressCount = outputPressCounts.getOrDefault(outputCode, 0) + 1;
            outputPressCounts.put(outputCode, pressCount);
            if (pressCount == 1) {
                KeyInjector.getInstance(context).injectCode(outputCode, 1);
            }
        }
    }

    private void repeatOutput(String stateKey, int sourceCode) {
        synchronized (mappingStateLock) {
            Integer outputCode = activeOutputs.get(stateKey);
            if (outputCode == null) outputCode = sourceCode;
            if (outputCode != 0) {
                KeyInjector.getInstance(context).injectCode(outputCode, 2);
            }
        }
    }

    private void releaseOutput(String stateKey) {
        synchronized (mappingStateLock) {
            Integer outputCode = activeOutputs.remove(stateKey);
            if (outputCode == null) return;
            releaseOutputCodeLocked(outputCode);
        }
    }

    private void releaseDeviceState(String devicePath) {
        String prefix = devicePath + ':';
        for (String stateKey : new ArrayList<>(keyPressedState.keySet())) {
            if (stateKey.startsWith(prefix)) keyPressedState.remove(stateKey);
        }

        List<String> visualCandidates = new ArrayList<>();
        synchronized (mappingStateLock) {
            for (String stateKey : new ArrayList<>(activeOutputs.keySet())) {
                if (!stateKey.startsWith(prefix)) continue;
                Integer outputCode = activeOutputs.remove(stateKey);
                if (outputCode != null) releaseOutputCodeLocked(outputCode);
            }
            for (String stateKey : new ArrayList<>(activeVisualKeys.keySet())) {
                if (!stateKey.startsWith(prefix)) continue;
                String visualKey = activeVisualKeys.remove(stateKey);
                if (visualKey != null && !visualCandidates.contains(visualKey)) {
                    visualCandidates.add(visualKey);
                }
            }
        }

        if (!visualCandidates.isEmpty()) {
            mainHandler.post(() -> {
                for (String visualKey : visualCandidates) {
                    synchronized (mappingStateLock) {
                        if (activeVisualKeys.containsValue(visualKey)) continue;
                    }
                    keyView.setKeyPressed(visualKey, false);
                }
                keyView.invalidate();
            });
        }
    }

    private void releaseOutputCodeLocked(int outputCode) {
        if (outputCode == 0) return;
        int pressCount = outputPressCounts.getOrDefault(outputCode, 1) - 1;
        if (pressCount <= 0) {
            outputPressCounts.remove(outputCode);
            KeyInjector.getInstance(context).injectCode(outputCode, 0);
        } else {
            outputPressCounts.put(outputCode, pressCount);
        }
    }

    private boolean isCurrent(long listenerGeneration) {
        return isListening.get() && generation.get() == listenerGeneration;
    }

    private boolean isValidDevicePath(String path) {
        return path != null && path.matches(DEVICE_PATH_PATTERN);
    }

    private String keyCodeToName(int code) {
        switch (code) {
            case 57: return "space";
            case 28: return "enter";
            case 15: return "tab";
            case 1: return "escape";
            case 14: return "backspace";
            case 111: return "del";
            case 125: return "lmeta";
            case 126: return "rmeta";
            case 42: return "lshift";
            case 54: return "rshift";
            case 29: return "lctrl";
            case 97: return "rctrl";
            case 56: return "lalt";
            case 100: return "ralt";
            case 103: return "up";
            case 108: return "down";
            case 105: return "left";
            case 106: return "right";
            case 51: return "comma";
            case 52: return "dot";
            case 39: return "semicolon";
            case 53: return "slash";
            case 43: return "backslash";
            case 40: return "quote";
            case 41: return "backquote";
            case 26: return "bracketleft";
            case 27: return "bracketright";
            case 12: return "minus";
            case 13: return "equal";
            case 104: return "pageup";
            case 109: return "pagedown";
            case 102: return "home";
            case 107: return "end";
            case 110: return "insert";
            case 58: return "capslock";
            case 69: return "numlock";
            case 78: return "plus";
            case 74: return "minus";
            case 98: return "slash";
            case 55: return "asterisk";
            case 83: return "dot";
            case 96: return "enter";
            case 59: return "f1"; case 60: return "f2";
            case 61: return "f3"; case 62: return "f4";
            case 63: return "f5"; case 64: return "f6";
            case 65: return "f7"; case 66: return "f8";
            case 67: return "f9"; case 68: return "f10";
            case 87: return "f11"; case 88: return "f12";
            case 30: return "a"; case 48: return "b"; case 46: return "c";
            case 32: return "d"; case 18: return "e"; case 33: return "f";
            case 34: return "g"; case 35: return "h"; case 23: return "i";
            case 36: return "j"; case 37: return "k"; case 38: return "l";
            case 50: return "m"; case 49: return "n"; case 24: return "o";
            case 25: return "p"; case 16: return "q"; case 19: return "r";
            case 31: return "s"; case 20: return "t"; case 22: return "u";
            case 47: return "v"; case 17: return "w"; case 45: return "x";
            case 21: return "y"; case 44: return "z";
            case 2: return "1"; case 3: return "2"; case 4: return "3";
            case 5: return "4"; case 6: return "5"; case 7: return "6";
            case 8: return "7"; case 9: return "8"; case 10: return "9";
            case 11: return "0";
            default: return null;
        }
    }

    public void stopListening() {
        if (!isListening.getAndSet(false)) {
            clearPressedState();
            return;
        }
        generation.incrementAndGet();
        clearPressedState();
        synchronized (currentProcesses) {
            for (Process process : currentProcesses) {
                if (process != null) {
                    destroyProcess(process);
                }
            }
            currentProcesses.clear();
        }
    }
}
