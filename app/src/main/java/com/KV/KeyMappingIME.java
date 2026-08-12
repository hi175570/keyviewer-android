package com.KV;

import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

public class KeyMappingIME extends InputMethodService {
    
    private static final String TAG = "KeyMappingIME";
    private static KeyMappingIME instance;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "IME created");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }
    
    public static KeyMappingIME getInstance() {
        return instance;
    }
    
    @Override
    public View onCreateInputView() {
        // 返回 null，使用系统默认输入法界面
        return null;
    }
    
    @Override
    public View onCreateCandidatesView() {
        return null;
    }
    
    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }
    
    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        Log.d(TAG, "onStartInput");
    }
    
    @Override
    public void onFinishInput() {
        super.onFinishInput();
        Log.d(TAG, "onFinishInput");
    }
}