package com.KV;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.appcompat.widget.SwitchCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import rikka.shizuku.Shizuku;

public class GlobalSettingsPanel {

    private Activity activity;
    private View panelView;
    private KeyView keyView;
    private GlobalSettings settings;
    private RishInputListener inputListener;
    
    private FrameLayout darkModeContainer;
    private View darkModeSlider;
    private ImageView sunIcon;
    private ImageView moonIcon;
    private boolean darkModeAnimating = false;
    private float sliderStartX = 0;
    private float sliderEndX = 0;
    
    private TextView floatScaleText;
    private Button floatScaleUpBtn;
    private Button floatScaleDownBtn;
    private EditText rainHeightInput;
    private EditText rainReserveHeightInput;
    private EditText rainCornerRadiusInput;
    private SwitchCompat keyDebounceSwitch;
    private EditText keyDebounceMillisInput;
    private SwitchCompat keyRestrictionSwitch;
    private EditText keyRestrictionBlockedKeysInput;
    private Button backgroundImageBtn;
    private Button clearBackgroundImageBtn;
    private Button resetCountsBtn;
    private Button resetFloatPositionBtn;
    private Button exportConfigBtn;
    private Button importConfigBtn;
    private Spinner presetSpinner;
    private Button restorePresetBtn;
    private Button calibrateKeyboardBtn;
    private Button closeBtn;
    private volatile boolean calibratingKeyboard;
    
    private OnSettingsChangedListener listener;
    
    private static final int REQUEST_EXPORT = 1101;
    private static final int REQUEST_IMPORT = 1002;
    private static final int REQUEST_BACKGROUND_IMAGE = 1003;
    private static final String TAG = "KV_GlobalSettings";
    
    public interface OnSettingsChangedListener {
        void onFloatScaleChanged(float scale);
        void onCountsReset();
        void onFloatPositionReset();
        void onConfigImported();
        void onDarkModeChanged(boolean isDarkMode);
    }
    
    public GlobalSettingsPanel(Activity activity, View panelView, KeyView keyView) {
        this.activity = activity;
        this.panelView = panelView;
        this.keyView = keyView;
        this.settings = GlobalSettings.getInstance(activity);
        
        initViews();
        setupListeners();
        updateScaleText();
        updateDarkModeUI(settings.darkModeEnabled);
    }
    
    public void setInputListener(RishInputListener listener) {
        this.inputListener = listener;
    }
    
    public void setOnSettingsChangedListener(OnSettingsChangedListener listener) {
        this.listener = listener;
    }
    
    private void initViews() {
        darkModeContainer = panelView.findViewById(R.id.darkModeContainer);
        darkModeSlider = panelView.findViewById(R.id.darkModeSlider);
        sunIcon = panelView.findViewById(R.id.sunIcon);
        moonIcon = panelView.findViewById(R.id.moonIcon);
        closeBtn = panelView.findViewById(R.id.closeGlobalSettingsBtn);
        
        floatScaleText = panelView.findViewById(R.id.floatScaleText);
        floatScaleUpBtn = panelView.findViewById(R.id.floatScaleUpBtn);
        floatScaleDownBtn = panelView.findViewById(R.id.floatScaleDownBtn);
        rainHeightInput = panelView.findViewById(R.id.rainHeightInput);
        rainReserveHeightInput = panelView.findViewById(R.id.rainReserveHeightInput);
        rainCornerRadiusInput = panelView.findViewById(R.id.rainCornerRadiusInput);
        keyDebounceSwitch = panelView.findViewById(R.id.keyDebounceSwitch);
        keyDebounceMillisInput = panelView.findViewById(R.id.keyDebounceMillisInput);
        keyRestrictionSwitch = panelView.findViewById(R.id.keyRestrictionSwitch);
        keyRestrictionBlockedKeysInput = panelView.findViewById(
                R.id.keyRestrictionBlockedKeysInput);
        backgroundImageBtn = panelView.findViewById(R.id.backgroundImageBtn);
        clearBackgroundImageBtn = panelView.findViewById(R.id.clearBackgroundImageBtn);
        resetCountsBtn = panelView.findViewById(R.id.resetCountsBtn);
        resetFloatPositionBtn = panelView.findViewById(R.id.resetFloatPositionBtn);
        exportConfigBtn = panelView.findViewById(R.id.exportConfigBtn);
        importConfigBtn = panelView.findViewById(R.id.importConfigBtn);
        presetSpinner = panelView.findViewById(R.id.presetSpinner);
        restorePresetBtn = panelView.findViewById(R.id.restorePresetBtn);
        calibrateKeyboardBtn = panelView.findViewById(R.id.calibrateKeyboardBtn);
        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, ConfigManagerPanel.bundledPresetNames());
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(presetAdapter);
        
        rainHeightInput.setText(String.valueOf(settings.globalRainHeightDp));
        rainReserveHeightInput.setText(String.valueOf(settings.globalRainReserveHeightDp));
        rainCornerRadiusInput.setText(formatFloat(settings.globalRainCornerRadiusDp));
        keyDebounceSwitch.setChecked(settings.keyDebounceEnabled);
        keyDebounceMillisInput.setText(String.valueOf(settings.keyDebounceMillis));
        keyDebounceMillisInput.setEnabled(settings.keyDebounceEnabled);
        keyRestrictionSwitch.setChecked(settings.keyRestrictionEnabled);
        keyRestrictionBlockedKeysInput.setText(settings.keyRestrictionBlockedKeys);
        keyRestrictionBlockedKeysInput.setEnabled(settings.keyRestrictionEnabled);
        updateBackgroundImageUi();
        
        panelView.post(() -> calculateSliderRange());
    }
    
    private void calculateSliderRange() {
        if (darkModeContainer == null || darkModeSlider == null) return;
        int containerWidth = darkModeContainer.getWidth();
        int sliderWidth = darkModeSlider.getWidth();
        sliderStartX = 0;
        sliderEndX = containerWidth - sliderWidth;
    }
    
    private void setupListeners() {
        darkModeContainer.setOnClickListener(v -> {
            if (darkModeAnimating) return;
            toggleDarkMode();
        });
        
        closeBtn.setOnClickListener(v -> {
            panelView.setVisibility(View.GONE);
        });
        
        floatScaleUpBtn.setOnClickListener(v -> {
            settings.increaseScale();
            updateScaleText();
            if (listener != null) listener.onFloatScaleChanged(settings.floatWindowScale);
            FloatService service = FloatService.getInstance();
            if (service != null) service.updateScale();
        });
        
        floatScaleDownBtn.setOnClickListener(v -> {
            settings.decreaseScale();
            updateScaleText();
            if (listener != null) listener.onFloatScaleChanged(settings.floatWindowScale);
            FloatService service = FloatService.getInstance();
            if (service != null) service.updateScale();
        });
        
        rainHeightInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                try {
                    int height = Math.max(0, Math.min(5000,
                            Integer.parseInt(rainHeightInput.getText().toString())));
                    settings.globalRainHeightDp = height;
                    settings.globalRainReserveHeightDp = Math.min(
                            settings.globalRainReserveHeightDp, height);
                    settings.save();
                    refreshFromSettings();
                    keyView.refreshGlobalSettings();
                    FloatService service = FloatService.getInstance();
                    if (service != null && service.getFloatView() != null) {
                        service.getFloatView().updateScale();
                    }
                } catch (NumberFormatException ignored) {}
                return true;
            }
            return false;
        });
        
        rainReserveHeightInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                try {
                    int reserve = Math.max(0, Math.min(settings.globalRainHeightDp,
                            Integer.parseInt(rainReserveHeightInput.getText().toString())));
                    settings.globalRainReserveHeightDp = reserve;
                    settings.save();
                    rainReserveHeightInput.setText(String.valueOf(reserve));
                } catch (NumberFormatException ignored) {}
                return true;
            }
            return false;
        });

        keyDebounceSwitch.setOnCheckedChangeListener((button, enabled) -> {
            settings.keyDebounceEnabled = enabled;
            settings.save();
            keyDebounceMillisInput.setEnabled(enabled);
        });
        keyDebounceMillisInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveDebounceMillis();
                return true;
            }
            return false;
        });
        keyDebounceMillisInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveDebounceMillis();
        });

        keyRestrictionSwitch.setOnCheckedChangeListener((button, enabled) -> {
            settings.keyRestrictionEnabled = enabled;
            settings.save();
            keyRestrictionBlockedKeysInput.setEnabled(enabled);
            keyView.getKeyManager().notifyDataChanged();
        });
        keyRestrictionBlockedKeysInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveRestrictedKeys();
                return true;
            }
            return false;
        });
        keyRestrictionBlockedKeysInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveRestrictedKeys();
        });

        rainCornerRadiusInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveRainCornerRadius();
                return true;
            }
            return false;
        });
        rainCornerRadiusInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveRainCornerRadius();
        });

        backgroundImageBtn.setOnClickListener(v -> chooseBackgroundImage());
        clearBackgroundImageBtn.setOnClickListener(v -> clearBackgroundImage());
        
        resetCountsBtn.setOnClickListener(v -> {
            keyView.getKeyManager().resetAllCounts();
            keyView.invalidate();
            if (listener != null) listener.onCountsReset();
        });
        
        resetFloatPositionBtn.setOnClickListener(v -> {
            settings.resetFloatPosition();
            if (listener != null) listener.onFloatPositionReset();
            FloatService service = FloatService.getInstance();
            if (service != null && service.getFloatView() != null) {
                service.getFloatView().updatePositionFromSettings();
            }
        });
        
        calibrateKeyboardBtn.setOnClickListener(v -> startCalibration());
        
        exportConfigBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, currentConfigurationName() + ".json");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            activity.startActivityForResult(intent, REQUEST_EXPORT);
        });
        
        exportConfigBtn.setOnLongClickListener(v -> {
            shareConfig();
            return true;
        });
        
        importConfigBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(intent, REQUEST_IMPORT);
        });

        restorePresetBtn.setOnClickListener(v -> {
            String presetName = String.valueOf(presetSpinner.getSelectedItem());
            new AlertDialog.Builder(activity)
                    .setTitle("恢复预设")
                    .setMessage("使用“" + presetName + "”覆盖当前配置？其他配置不会改变。")
                    .setPositiveButton("恢复", (dialog, which) -> {
                        if (activity instanceof MainActivity) {
                            ((MainActivity) activity).getConfigManagerPanel()
                                    .restoreCurrentFromBundledPreset(presetName);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }
    
    private void toggleDarkMode() {
        boolean newMode = !settings.darkModeEnabled;
        animateSliderToTarget(newMode);
        applyDarkMode(newMode);
    }
    
    private void animateSliderToTarget(boolean toDark) {
        if (darkModeAnimating) return;
        darkModeAnimating = true;
        
        float targetX = toDark ? sliderEndX : sliderStartX;
        ObjectAnimator animator = ObjectAnimator.ofFloat(darkModeSlider, "translationX", targetX);
        animator.setDuration(300);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                darkModeAnimating = false;
            }
        });
        animator.start();
    }
    
    private void applyDarkMode(boolean isDarkMode) {
        settings.darkModeEnabled = isDarkMode;
        settings.save();
        
        int iconColor = isDarkMode ? 0xFFFFFFFF : 0xFF757575;
        sunIcon.setColorFilter(iconColor);
        moonIcon.setColorFilter(iconColor);
        
        animateThemeTransition(isDarkMode);
        
        if (listener != null) {
            listener.onDarkModeChanged(isDarkMode);
        }
    }
    
    private void animateThemeTransition(boolean isDarkMode) {
        int panelBgEnd = isDarkMode ? 0xFF2C2C2C : 0xFFF5F5F5;
        int textColorEnd = isDarkMode ? 0xFFFFFFFF : 0xFF333333;
        
        animateBackgroundColor(panelView, panelBgEnd);
        
        TextView title = panelView.findViewById(R.id.closeGlobalSettingsBtn);
        if (title != null) {
            animateTextColor(title, textColorEnd);
        }
        
        updateAllChildViews(panelView, isDarkMode);
    }
    
    private void updateAllChildViews(View view, boolean isDarkMode) {
        if (view instanceof EditText) {
            EditText et = (EditText) view;
            int bgColor = isDarkMode ? 0xFF3C3C3C : 0xFFFFFFFF;
            int textColor = isDarkMode ? 0xFFFFFFFF : 0xFF333333;
            animateBackgroundColor(et, bgColor);
            animateTextColor(et, textColor);
        } else if (view instanceof TextView) {
            TextView tv = (TextView) view;
            int textColor = isDarkMode ? 0xFFFFFFFF : 0xFF333333;
            animateTextColor(tv, textColor);
        } else if (view instanceof Button) {
            Button btn = (Button) view;
            int textColor = isDarkMode ? 0xFFFFFFFF : 0xFF333333;
            animateTextColor(btn, textColor);
        } else if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                updateAllChildViews(vg.getChildAt(i), isDarkMode);
            }
        }
    }
    
    private void animateBackgroundColor(View target, int toColor) {
        ValueAnimator animator = ValueAnimator.ofArgb(target.getSolidColor(), toColor);
        animator.setDuration(300);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            target.setBackgroundColor((int) animation.getAnimatedValue());
        });
        animator.start();
    }
    
    private void animateTextColor(TextView target, int toColor) {
        ValueAnimator animator = ValueAnimator.ofArgb(target.getCurrentTextColor(), toColor);
        animator.setDuration(300);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            target.setTextColor((int) animation.getAnimatedValue());
        });
        animator.start();
    }
    
    private void updateDarkModeUI(boolean isDarkMode) {
        int iconColor = isDarkMode ? 0xFFFFFFFF : 0xFF757575;
        sunIcon.setColorFilter(iconColor);
        moonIcon.setColorFilter(iconColor);
        
        float targetX = isDarkMode ? sliderEndX : sliderStartX;
        darkModeSlider.setTranslationX(targetX);
        
        if (isDarkMode) {
            panelView.setBackgroundColor(0xFF2C2C2C);
            closeBtn.setTextColor(0xFFFFFFFF);
            floatScaleText.setTextColor(0xFFFFFFFF);
            rainHeightInput.setBackgroundColor(0xFF3C3C3C);
            rainHeightInput.setTextColor(0xFFFFFFFF);
            rainReserveHeightInput.setBackgroundColor(0xFF3C3C3C);
            rainReserveHeightInput.setTextColor(0xFFFFFFFF);
        } else {
            panelView.setBackgroundColor(0xFFF5F5F5);
            closeBtn.setTextColor(0xFF666666);
            floatScaleText.setTextColor(0xFF333333);
            rainHeightInput.setBackgroundColor(0xFFFFFFFF);
            rainHeightInput.setTextColor(0xFF333333);
            rainReserveHeightInput.setBackgroundColor(0xFFFFFFFF);
            rainReserveHeightInput.setTextColor(0xFF333333);
        }
    }
    
    private void startCalibration() {
        if (calibratingKeyboard) {
            Toast.makeText(activity, "键盘校准正在进行", Toast.LENGTH_SHORT).show();
            return;
        }
        calibratingKeyboard = true;
        calibrateKeyboardBtn.setEnabled(false);
        Toast.makeText(activity, "请按键盘上的任意键（如空格）", Toast.LENGTH_LONG).show();

        if (inputListener != null) inputListener.stopListening();
        FloatService floatService = FloatService.getInstance();
        if (floatService != null) floatService.stopInputListening();

        new Thread(() -> {
            // Give the Shizuku helper processes time to release EVIOCGRAB before probing.
            SystemClock.sleep(250);
            String device = findKeyboardDeviceByUserInput();
            activity.runOnUiThread(() -> {
                calibratingKeyboard = false;
                calibrateKeyboardBtn.setEnabled(true);
                if (device != null) {
                    settings.keyboardDevicePath = device;
                    settings.keyboardCalibrated = true;
                    settings.save();
                }

                FloatService activeFloatService = FloatService.getInstance();
                if (settings.floatWindowEnabled && activeFloatService != null) {
                    activeFloatService.startInputListening();
                } else if (inputListener != null) {
                    inputListener.startListening();
                }

                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (device != null) {
                    Toast.makeText(activity, "已校准: " + device, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity, "未检测到按键，请重试", Toast.LENGTH_SHORT).show();
                }
            });
        }, "KV-Keyboard-Calibration").start();
    }
    
    private String findKeyboardDeviceByUserInput() {
        Process eventProcess = null;
        try {
            Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");
            Method method = shizukuClass.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            method.setAccessible(true);

            eventProcess = (Process) method.invoke(null,
                    new String[]{"sh", "-c", "timeout 10 getevent"}, null, null);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    eventProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String devicePath = pressedKeyDeviceFromLine(line);
                    if (devicePath != null && isKeyboardDevice(devicePath, method)) {
                        return devicePath;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "findKeyboardDeviceByUserInput error", e);
        } finally {
            destroyCalibrationProcess(eventProcess);
        }
        return null;
    }

    static String pressedKeyDeviceFromLine(String line) {
        if (line == null) return null;
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 4 || !parts[0].matches("/dev/input/event\\d+:")) return null;
        try {
            int type = Integer.parseInt(parts[1], 16);
            int value = (int) Long.parseLong(parts[3], 16);
            return type == 1 && value == 1
                    ? parts[0].substring(0, parts[0].length() - 1) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isKeyboardDevice(String devicePath, Method method) {
        Process infoProcess = null;
        try {
            infoProcess = (Process) method.invoke(null,
                    new String[]{"getevent", "-p", devicePath}, null, null);
            StringBuilder info = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    infoProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) info.append(line).append('\n');
            }
            return RishInputListener.isKeyboardDevice(info.toString());
        } catch (Exception e) {
            Log.w(TAG, "Unable to inspect calibration device " + devicePath, e);
            return false;
        } finally {
            destroyCalibrationProcess(infoProcess);
        }
    }

    private void destroyCalibrationProcess(Process process) {
        if (process == null) return;
        try {
            process.destroy();
        } catch (RuntimeException e) {
            Log.w(TAG, "Calibration process already stopped", e);
        }
    }
    
    private void updateScaleText() {
        floatScaleText.setText(settings.getScalePercent());
    }

    private void saveRainCornerRadius() {
        try {
            float radius = Math.max(0f, Math.min(1000f,
                    Float.parseFloat(rainCornerRadiusInput.getText().toString())));
            settings.globalRainCornerRadiusDp = radius;
            settings.save();
            rainCornerRadiusInput.setText(formatFloat(radius));
            keyView.refreshGlobalSettings();
            FloatService service = FloatService.getInstance();
            if (service != null && service.getFloatView() != null) {
                service.getFloatView().updateScale();
            }
        } catch (NumberFormatException ignored) {
            rainCornerRadiusInput.setText(formatFloat(settings.globalRainCornerRadiusDp));
        }
    }

    private static String formatFloat(float value) {
        return value == Math.round(value) ? String.valueOf(Math.round(value)) : String.valueOf(value);
    }

    private void saveDebounceMillis() {
        try {
            int millis = Math.max(1, Math.min(200,
                    Integer.parseInt(keyDebounceMillisInput.getText().toString())));
            settings.keyDebounceMillis = millis;
            settings.save();
            keyDebounceMillisInput.setText(String.valueOf(millis));
        } catch (NumberFormatException ignored) {
            keyDebounceMillisInput.setText(String.valueOf(settings.keyDebounceMillis));
        }
    }

    private void saveRestrictedKeys() {
        settings.keyRestrictionBlockedKeys = KeyRestrictionPolicy.normalizeBlockedKeys(
                keyRestrictionBlockedKeysInput.getText().toString());
        settings.save();
        keyRestrictionBlockedKeysInput.setText(settings.keyRestrictionBlockedKeys);
        keyView.getKeyManager().notifyDataChanged();
    }

    private void chooseBackgroundImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        activity.startActivityForResult(intent, REQUEST_BACKGROUND_IMAGE);
    }

    private void importBackgroundImage(Uri uri) {
        try {
            settings.backgroundImageAsset = KeyAssetStore.importAsset(
                    activity, uri, KeyAssetStore.TYPE_IMAGE);
            settings.save();
            keyView.clearAssetCaches();
            keyView.refreshGlobalSettings();
            keyView.getKeyManager().notifyDataChanged();
            updateBackgroundImageUi();
            Toast.makeText(activity, "背景图片已导入", Toast.LENGTH_SHORT).show();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Unable to import background image", e);
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void clearBackgroundImage() {
        settings.backgroundImageAsset = "";
        settings.save();
        keyView.clearAssetCaches();
        keyView.refreshGlobalSettings();
        keyView.getKeyManager().notifyDataChanged();
        updateBackgroundImageUi();
    }

    private void updateBackgroundImageUi() {
        boolean configured = settings.backgroundImageAsset != null
                && !settings.backgroundImageAsset.isEmpty();
        backgroundImageBtn.setText(configured
                ? R.string.background_image_configured
                : R.string.background_image_choose);
        clearBackgroundImageBtn.setEnabled(configured);
    }

    public void refreshFromSettings() {
        rainHeightInput.setText(String.valueOf(settings.globalRainHeightDp));
        rainReserveHeightInput.setText(String.valueOf(settings.globalRainReserveHeightDp));
        rainCornerRadiusInput.setText(formatFloat(settings.globalRainCornerRadiusDp));
        keyDebounceSwitch.setChecked(settings.keyDebounceEnabled);
        keyDebounceMillisInput.setText(String.valueOf(settings.keyDebounceMillis));
        keyDebounceMillisInput.setEnabled(settings.keyDebounceEnabled);
        keyRestrictionSwitch.setChecked(settings.keyRestrictionEnabled);
        keyRestrictionBlockedKeysInput.setText(settings.keyRestrictionBlockedKeys);
        keyRestrictionBlockedKeysInput.setEnabled(settings.keyRestrictionEnabled);
        updateBackgroundImageUi();
        updateScaleText();
        updateDarkModeUI(settings.darkModeEnabled);
    }
    
    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_BACKGROUND_IMAGE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                importBackgroundImage(data.getData());
            }
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null) return;
        
        Uri uri = data.getData();
        if (uri == null) return;
        
        if (requestCode == REQUEST_EXPORT) {
            writeConfigToUri(uri);
        } else if (requestCode == REQUEST_IMPORT) {
            importConfigFromUri(uri);
        }
    }
    
    private JSONObject buildConfigRoot() throws Exception {
        KeyManager km = keyView.getKeyManager();
        float density = activity.getResources().getDisplayMetrics().density;
        return ConfigCodec.encode(km, density);
    }

    private String buildPortableConfigJson(JSONObject root) throws Exception {
        PortableConfigAssets.embed(activity, root);
        return root.toString(2);
    }
    
    private void shareConfig() {
        final JSONObject snapshot;
        try {
            snapshot = buildConfigRoot();
        } catch (Exception e) {
            showPortableConfigError("分享失败", e);
            return;
        }
        Toast.makeText(activity, "正在打包配置与素材...", Toast.LENGTH_SHORT).show();
        startPortableConfigTask(() -> {
            try {
                String json = buildPortableConfigJson(snapshot);
                File directory = new File(activity.getCacheDir(), "shared_configs");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IllegalStateException("Unable to create share cache");
                }
                cleanupOldSharedConfigs(directory);
                File sharedFile = new File(directory, currentConfigurationName() + ".json");
                try (FileOutputStream output = new FileOutputStream(sharedFile)) {
                    output.write(json.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    output.getFD().sync();
                }
                Uri uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", sharedFile);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("application/json");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.setClipData(ClipData.newRawUri("KeyViewer config", uri));
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(shareIntent, "导出配置"));
                });
            } catch (Exception e) {
                showPortableConfigError("分享失败", e);
            }
        });
    }
    
    private void writeConfigToUri(Uri uri) {
        final JSONObject snapshot;
        try {
            snapshot = buildConfigRoot();
        } catch (Exception e) {
            showPortableConfigError("导出失败", e);
            return;
        }
        Toast.makeText(activity, "正在打包配置与素材...", Toast.LENGTH_SHORT).show();
        startPortableConfigTask(() -> {
            try {
                String json = buildPortableConfigJson(snapshot);
                try {
                    activity.getContentResolver().takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException e) {
                    Log.w(TAG, "takePersistableUriPermission failed: " + e.getMessage());
                }

                OutputStream os = openExportOutputStream(uri);
                if (os == null) throw new IllegalStateException("无法写入文件");
                try (OutputStream outputStream = os) {
                    outputStream.write(json.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        Toast.makeText(activity, "配置与素材导出成功", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                showPortableConfigError("导出失败", e);
            }
        });
    }

    private void startPortableConfigTask(Runnable task) {
        Thread thread = new Thread(task, "KV-Portable-Config");
        thread.setDaemon(true);
        thread.start();
    }

    private void showPortableConfigError(String prefix, Exception error) {
        Log.e(TAG, prefix, error);
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                Toast.makeText(activity, prefix + ": " + error.getMessage(), Toast.LENGTH_LONG)
                        .show();
            }
        });
    }

    private void cleanupOldSharedConfigs(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File file : files) {
            if (file.lastModified() < cutoff) {
                // Files are private cache entries created only by shareConfig().
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private String currentConfigurationName() {
        if (activity instanceof MainActivity) {
            ConfigManagerPanel panel = ((MainActivity) activity).getConfigManagerPanel();
            if (panel != null) {
                String name = panel.getCurrentConfigName();
                if (name != null && !name.isEmpty()) return name;
            }
        }
        return "default";
    }

    private OutputStream openExportOutputStream(Uri uri) {
        try {
            return activity.getContentResolver().openOutputStream(uri, "wt");
        } catch (Exception e) {
            Log.w(TAG, "openOutputStream wt failed, falling back: " + e.getMessage());
        }

        try {
            return activity.getContentResolver().openOutputStream(uri);
        } catch (Exception e) {
            Log.e(TAG, "openOutputStream default failed", e);
            return null;
        }
    }
    
    // ==================== 导入配置（复用 ConfigManagerPanel）====================
    
    private void importConfigFromUri(Uri uri) {
        if (activity instanceof MainActivity) {
            ConfigManagerPanel configPanel = ((MainActivity) activity).getConfigManagerPanel();
            if (configPanel != null) {
                configPanel.importConfigToCurrent(uri);
                return;
            }
        }
        Toast.makeText(activity, "Config manager is unavailable", Toast.LENGTH_LONG).show();
    }
    
    private void importConfigFromUriFallback(Uri uri) {
        activity.runOnUiThread(() -> Toast.makeText(activity, "正在导入配置...", Toast.LENGTH_SHORT).show());
        
        new Thread(() -> {
            try {
                try {
                    activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    Log.e(TAG, "takePersistableUriPermission failed: " + e.getMessage());
                }
                
                InputStream is = activity.getContentResolver().openInputStream(uri);
                if (is == null) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, "无法读取文件", Toast.LENGTH_SHORT).show());
                    return;
                }
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                String json = sb.toString();
                if (json.isEmpty()) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, "文件内容为空", Toast.LENGTH_SHORT).show());
                    return;
                }
                
                JSONObject root = new JSONObject(json);
                applyConfigToCurrent(root);
                
                activity.runOnUiThread(() -> {
                    keyView.invalidate();
                    if (listener != null) {
                        listener.onConfigImported();
                        listener.onDarkModeChanged(settings.darkModeEnabled);
                    }
                    Toast.makeText(activity, "配置导入成功", Toast.LENGTH_SHORT).show();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "importConfigFromUri failed", e);
                activity.runOnUiThread(() -> Toast.makeText(activity, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }
    
    /**
     * 应用配置到当前界面（覆盖模式，替换所有按键，不累加）
     */
    private void applyConfigToCurrent(JSONObject root) throws Exception {
        // Global settings are application state. Ignore the legacy field when importing a KV.
        root.remove("globalSettings");
        
        // 1. 应用按键数据（完全替换，不累加）
        KeyManager km = keyView.getKeyManager();
        List<KeyData> newKeys = new ArrayList<>();
        
        if (root.has("keys")) {
            JSONArray keysArray = root.getJSONArray("keys");
            float density = activity.getResources().getDisplayMetrics().density;
            
            for (int i = 0; i < keysArray.length(); i++) {
                JSONObject keyJson = keysArray.getJSONObject(i);
                KeyData key = importKeyFromJson(keyJson, density);
                newKeys.add(key);
            }
        }
        
        // 关键：先清空，再添加（覆盖，不是累加）
        km.getKeys().clear();
        km.getKeys().addAll(newKeys);
        
        // 2. 应用按键计数（先重置再累加导入的计数）
        km.resetAllCounts();
        if (root.has("keyCounts")) {
            JSONObject countsJson = root.getJSONObject("keyCounts");
            java.util.Iterator<String> keys = countsJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int count = countsJson.optInt(key, 0);
                for (int i = 0; i < count; i++) {
                    km.recordKeyPress(key);
                }
            }
        }
        
        km.saveToPreferences();
        keyView.clearSelection();
    }
    
    private KeyData importKeyFromJson(JSONObject keyJson, float density) throws Exception {
        String id = keyJson.optString("id", java.util.UUID.randomUUID().toString());
        float cx = (float) keyJson.optDouble("centerX", 100) * density;
        float cy = (float) keyJson.optDouble("centerY", 100) * density;
        float w = (float) keyJson.optDouble("width", 50) * density;
        float h = (float) keyJson.optDouble("height", 50) * density;
        
        KeyData key = new KeyData(id, cx, cy, w, h);
        
        key.boundKey = keyJson.optString("boundKey", "未绑定");
        key.displayLabel = keyJson.optString("displayLabel", "");
        
        int oldFillColor = keyJson.optInt("fillColor", 0xFF999999);
        int oldBorderColor = keyJson.optInt("borderColor", 0xFF666666);
        
        key.fillColorUp = keyJson.optInt("fillColorUp", oldFillColor);
        key.borderColorUp = keyJson.optInt("borderColorUp", oldBorderColor);
        key.textColorUp = keyJson.optInt("textColorUp", 0xFFFFFFFF);
        key.countColorUp = keyJson.optInt("countColorUp", key.textColorUp);
        key.fillColorDown = keyJson.optInt("fillColorDown", 0xFFFFFFFF);
        key.borderColorDown = keyJson.optInt("borderColorDown", key.borderColorUp);
        key.textColorDown = keyJson.optInt("textColorDown", key.textColorUp);
        key.countColorDown = keyJson.optInt("countColorDown", key.countColorUp);
        
        key.textSizeSp = (float) keyJson.optDouble("textSizeSp", 14);
        key.autoFitText = keyJson.optBoolean("autoFitText", true);
        key.textShadowEnabled = keyJson.optBoolean("textShadowEnabled", false);
        key.textShadowColor = keyJson.optInt("textShadowColor", 0x80000000);
        key.textShadowOffsetXDp = (float) keyJson.optDouble("textShadowOffsetXDp", 1);
        key.textShadowOffsetYDp = (float) keyJson.optDouble("textShadowOffsetYDp", 1);
        key.textShadowBlurRadiusDp = (float) keyJson.optDouble("textShadowBlurRadiusDp", 0);
        key.showCount = keyJson.optBoolean("showCount", true);
        key.fontAsset = keyJson.optString("fontAsset", "");
        key.imageAsset = keyJson.optString("imageAsset", "");
        key.imageReplacesLabel = keyJson.optBoolean("imageReplacesLabel", true);
        key.mappingEnabled = keyJson.optBoolean("mappingEnabled", false);
        key.mappedKey = keyJson.optString("mappedKey", "");
        key.ghostKey = keyJson.optBoolean("ghostKey", false);
        key.rainEnabled = keyJson.optBoolean("rainEnabled", false);
        key.rainOffsetXDp = (float) keyJson.optDouble("rainOffsetXDp", 0);
        key.rainOffsetYDp = (float) keyJson.optDouble("rainOffsetYDp", 0);
        key.rainWidthPercent = (float) keyJson.optDouble("rainWidthPercent", 100);
        key.rainColor = keyJson.optInt("rainColor", 0xFFFFFFFF);
        key.rainSpeedDp = (float) keyJson.optDouble("rainSpeedDp", 5);
        key.rainGlowEnabled = keyJson.optBoolean("rainGlowEnabled", false);
        key.rainGlowRadiusDp = (float) keyJson.optDouble("rainGlowRadiusDp", 12);
        key.kpsTotalNoWrap = keyJson.optBoolean("kpsTotalNoWrap", false);
        key.countTextSizeSp = (float) keyJson.optDouble("countTextSizeSp", -1);
        key.counterLabelCase = keyJson.optString("counterLabelCase", "upper");
        key.textOffsetXDp = (float) keyJson.optDouble("textOffsetXDp", keyJson.optDouble("kpsTotalLabelOffsetX", 0));
        key.textOffsetYDp = (float) keyJson.optDouble("textOffsetYDp", keyJson.optDouble("kpsTotalLabelOffsetY", 0));
        key.countOffsetXDp = (float) keyJson.optDouble("countOffsetXDp", keyJson.optDouble("kpsTotalValueOffsetX", 0));
        key.countOffsetYDp = (float) keyJson.optDouble("countOffsetYDp", keyJson.optDouble("kpsTotalValueOffsetY", 0));
        key.zIndex = keyJson.optInt("zIndex", 0);
        
        return key;
    }
}
