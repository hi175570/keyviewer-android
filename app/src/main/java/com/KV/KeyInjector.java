package com.KV;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/** Owns the ordered, non-blocking connection to the privileged input service. */
public final class KeyInjector {

    private static final String TAG = "KV_KeyInjector";
    private static final Map<String, Integer> LINUX_KEY_CODES = createKeyCodeMap();
    private static volatile KeyInjector instance;

    private final Context context;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "KV-Key-Injector");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean binding = new AtomicBoolean(false);
    private final Object streamLock = new Object();
    private final Object serviceLock = new Object();

    private volatile IKeyInjectorService service;
    private ParcelFileDescriptor socketDescriptor;
    private FileOutputStream socketOutput;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IKeyInjectorService.Stub.asInterface(binder);
            binding.set(false);
            synchronized (serviceLock) {
                serviceLock.notifyAll();
            }
            writer.execute(KeyInjector.this::connectSocket);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding.set(false);
            synchronized (serviceLock) {
                serviceLock.notifyAll();
            }
            closeSocket();
        }
    };

    private KeyInjector(Context context) {
        this.context = context.getApplicationContext();
    }

    public static KeyInjector getInstance(Context context) {
        KeyInjector result = instance;
        if (result == null) {
            synchronized (KeyInjector.class) {
                result = instance;
                if (result == null) {
                    result = new KeyInjector(context);
                    instance = result;
                }
            }
        }
        return result;
    }

    public void start() {
        if (service != null || !binding.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                binding.set(false);
                return;
            }
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(context, IpcService.class))
                    .processNameSuffix("key_injector")
                    .daemon(false)
                    .debuggable(false)
                    .version(1);
            Shizuku.bindUserService(args, connection);
        } catch (RuntimeException e) {
            binding.set(false);
            Log.e(TAG, "Unable to bind key injector UserService", e);
        }
    }

    public void inject(String keyName, boolean down) {
        Integer code = LINUX_KEY_CODES.get(normalize(keyName));
        if (code == null) {
            Log.w(TAG, "Unsupported mapped key: " + keyName);
            return;
        }
        injectCode(code, down ? 1 : 0);
    }

    public void inject(String keyName, int value) {
        Integer code = linuxKeyCode(keyName);
        if (code == null) {
            Log.w(TAG, "Unsupported mapped key: " + keyName);
            return;
        }
        injectCode(code, value);
    }

    public static Integer linuxKeyCode(String keyName) {
        return LINUX_KEY_CODES.get(normalize(keyName));
    }

    public void injectCode(int code, int value) {
        if (code <= 0 || code > 0xffff || value < 0 || value > 2) return;
        writer.execute(() -> writeEvent(code, value));
    }

    private void connectSocket() {
        IKeyInjectorService currentService = service;
        if (currentService == null) return;
        synchronized (streamLock) {
            closeSocketLocked();
            try {
                socketDescriptor = currentService.connect();
                if (socketDescriptor == null) {
                    Log.e(TAG, "Native input server returned no socket");
                    return;
                }
                socketOutput = new FileOutputStream(socketDescriptor.getFileDescriptor());
                Log.i(TAG, "Key injection socket connected");
            } catch (RemoteException | RuntimeException e) {
                Log.e(TAG, "Unable to obtain injection socket", e);
                closeSocketLocked();
            }
        }
    }

    private void writeEvent(int code, int value) {
        synchronized (streamLock) {
            if (socketOutput == null) connectSocket();
            if (socketOutput == null) return;
            byte[] command = {
                    (byte) (value & 0xff), (byte) ((value >>> 8) & 0xff),
                    (byte) (code & 0xff), (byte) ((code >>> 8) & 0xff)
            };
            try {
                socketOutput.write(command);
            } catch (IOException e) {
                Log.e(TAG, "Key injection failed", e);
                closeSocketLocked();
            }
        }
    }

    private void closeSocket() {
        synchronized (streamLock) {
            closeSocketLocked();
        }
    }

    private void closeSocketLocked() {
        if (socketOutput != null) {
            try { socketOutput.close(); } catch (IOException ignored) { }
        }
        socketOutput = null;
        if (socketDescriptor != null) {
            try { socketDescriptor.close(); } catch (IOException ignored) { }
        }
        socketDescriptor = null;
    }

    private static String normalize(String keyName) {
        return keyName == null ? "" : keyName.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<String, Integer> createKeyCodeMap() {
        Map<String, Integer> codes = new HashMap<>();
        String letters = "abcdefghijklmnopqrstuvwxyz";
        int[] letterCodes = {30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44};
        for (int i = 0; i < letters.length(); i++) codes.put(String.valueOf(letters.charAt(i)), letterCodes[i]);
        int[] digitCodes = {11,2,3,4,5,6,7,8,9,10};
        for (int i = 0; i <= 9; i++) codes.put(String.valueOf(i), digitCodes[i]);
        codes.put("escape", 1); codes.put("backspace", 14); codes.put("tab", 15);
        codes.put("esc", 1);
        codes.put("enter", 28); codes.put("lctrl", 29); codes.put("lshift", 42);
        codes.put("backslash", 43); codes.put("rshift", 54); codes.put("lalt", 56);
        codes.put("space", 57); codes.put("capslock", 58); codes.put("numlock", 69);
        codes.put("rctrl", 97); codes.put("ralt", 100); codes.put("home", 102);
        codes.put("lmeta", 125); codes.put("rmeta", 126);
        codes.put("up", 103); codes.put("pageup", 104); codes.put("left", 105);
        codes.put("right", 106); codes.put("end", 107); codes.put("down", 108);
        codes.put("pagedown", 109); codes.put("insert", 110); codes.put("del", 111);
        codes.put("minus", 12); codes.put("equal", 13); codes.put("bracketleft", 26);
        codes.put("bracketright", 27); codes.put("semicolon", 39); codes.put("quote", 40);
        codes.put("backquote", 41); codes.put("comma", 51); codes.put("dot", 52);
        codes.put("slash", 53);
        for (int i = 1; i <= 10; i++) codes.put("f" + i, 58 + i);
        codes.put("f11", 87); codes.put("f12", 88);
        return Collections.unmodifiableMap(codes);
    }
}
