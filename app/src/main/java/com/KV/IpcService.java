package com.KV;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.util.Log;

/** Runs inside Shizuku's shell process and owns the privileged uinput device. */
public final class IpcService extends IKeyInjectorService.Stub {

    private static final String TAG = "KV_IpcService";
    private static final String SOCKET_NAME = "kv_socket";

    static {
        System.loadLibrary("kvsocket");
    }

    public IpcService() {
        startServer();
    }

    public IpcService(Context ignored) {
        startServer();
    }

    private void startServer() {
        int result = nativeStartServer(SOCKET_NAME);
        if (result != 0) {
            Log.e(TAG, "Unable to start native input server: " + result);
        }
    }

    @Override
    public ParcelFileDescriptor connect() {
        int fd = nativeConnectToSocket(SOCKET_NAME);
        if (fd < 0) {
            Log.e(TAG, "Unable to connect to native input server: " + fd);
            return null;
        }
        return ParcelFileDescriptor.adoptFd(fd);
    }

    @Override
    public void destroy() {
        nativeStopServer();
        System.exit(0);
    }

    private static native int nativeStartServer(String socketName);
    private static native int nativeConnectToSocket(String socketName);
    private static native void nativeStopServer();
}
