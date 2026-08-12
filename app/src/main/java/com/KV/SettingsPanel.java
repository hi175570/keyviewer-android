package com.KV;

import android.app.Activity;
import android.app.AlertDialog;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SettingsPanel {

    private static final String TAG = "KV_Settings";
    private static final int REQUEST_KEY_FONT = 2101;
    private static final int REQUEST_KEY_IMAGE = 2102;
    
    private Activity activity;
    private View panelView;
    private KeyView keyView;
    private RishInputListener inputListener;
    private GlobalSettings globalSettings;

    // 基础控件
    private Button keyBindBtn;
    private TextView settingsPanelTitle;
    private EditText labelInput;
    private EditText widthInput;
    private EditText heightInput;
    private EditText cornerRadiusInput;
    private EditText borderWidthInput;
    private EditText textSizeInput;
    private EditText countTextSizeInput;
    private Switch autoFitTextSwitch;
    private Switch textShadowEnabledSwitch;
    private LinearLayout textShadowSettingsContainer;
    private EditText textShadowColorInput;
    private EditText textShadowOffsetXInput;
    private EditText textShadowOffsetYInput;
    private EditText textShadowBlurInput;
    private Switch showCountSwitch;
    private Button fontAssetBtn;
    private Button clearFontAssetBtn;
    private Button imageAssetBtn;
    private Button clearImageAssetBtn;
    private Switch imageReplacesLabelSwitch;
    private EditText zIndexInput;

    // 映射
    private Switch mappingEnabledSwitch;
    private Switch ghostKeySwitch;
    private LinearLayout mappingSettingsContainer;
    private Button mappingBindBtn;

    // 键雨
    private Switch rainEnabledSwitch;
    private LinearLayout rainSettingsContainer;
    private EditText rainOffsetXInput;
    private EditText rainOffsetYInput;
    private EditText rainWidthInput;
    private EditText rainColorInput;
    private View rainColorPreview;
    private EditText rainSpeedInput;
    private Switch rainGlowEnabledSwitch;
    private EditText rainGlowRadiusInput;

    // KPS/TOTAL 特殊控制
    private LinearLayout kpsTotalContainer;
    private Switch kpsTotalNoWrapSwitch;
    private Spinner counterLabelCaseSpinner;
    private EditText textOffsetXInput;
    private EditText textOffsetYInput;
    private EditText countOffsetXInput;
    private EditText countOffsetYInput;

    // 颜色设置（可展开）
    private LinearLayout colorSectionHeader;
    private View colorSectionDivider;
    private LinearLayout colorSectionContent;
    private boolean colorSectionExpanded = false;
    private boolean colorSectionAnimating = false;
    
    // 普通格子颜色控件（两套）
    private LinearLayout normalColorContainer;
    
    // 放开时颜色控件（Up）
    private EditText fillColorUpInput;
    private View fillColorUpPreview;
    private EditText borderColorUpInput;
    private View borderColorUpPreview;
    private EditText textColorUpInput;
    private View textColorUpPreview;
    private EditText countColorUpInput;
    private View countColorUpPreview;
    
    // 按下时颜色控件（Down）
    private EditText fillColorDownInput;
    private View fillColorDownPreview;
    private EditText borderColorDownInput;
    private View borderColorDownPreview;
    private EditText textColorDownInput;
    private View textColorDownPreview;
    private EditText countColorDownInput;
    private View countColorDownPreview;
    
    // 特殊格子简化版颜色控件（一套）
    private LinearLayout simpleColorContainer;
    private EditText simpleFillColorInput;
    private View simpleFillColorPreview;
    private EditText simpleBorderColorInput;
    private View simpleBorderColorPreview;
    private EditText simpleTextColorInput;
    private View simpleTextColorPreview;
    private EditText simpleCountColorInput;
    private View simpleCountColorPreview;

    // 状态
    private boolean isListeningForKey = false;
    private boolean isListeningForMapping = false;
    private boolean syncingUi = false;
    private final List<String> pendingAssetKeyIds = new ArrayList<>();

    public SettingsPanel(Activity activity, View panelView, KeyView keyView) {
        this.activity = activity;
        this.panelView = panelView;
        this.keyView = keyView;
        this.globalSettings = GlobalSettings.getInstance(activity);

        initViews();
        setupListeners();
        syncFromKeyView();
        applyDarkModeToPanel();
        applyDarkModeToInputs();
    }
    
    private void applyDarkModeToPanel() {
        boolean isDarkMode = globalSettings.darkModeEnabled;
        int panelBgColor = isDarkMode ? 0xFF2C2C2C : 0xFFF5F5F5;
        panelView.setBackgroundColor(panelBgColor);
        
        // 更新所有 TextView 的文字颜色
        updateTextViewColors(panelView, isDarkMode);
    }
    
    private void updateTextViewColors(View view, boolean isDarkMode) {
        int textColor = isDarkMode ? 0xFFFFFFFF : 0xFF333333;
        int subTextColor = isDarkMode ? 0xFFAAAAAA : 0xFF666666;
        
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            String text = tv.getText().toString();
            if (text.equals("按键设置") || text.startsWith("批量设置") || text.equals("映射按键") || text.equals("键雨效果") ||
                text.equals("KPS/TOTAL 显示") || text.equals("颜色设置")) {
                tv.setTextColor(textColor);
            } else if (text.equals("绑定按键") || text.equals("显示文字") || text.equals("文字大小 (sp)") ||
                       text.equals("图层顺序") || text.equals("尺寸") || text.equals("圆角半径") ||
                       text.equals("边框宽度") || text.equals("放开时") || text.equals("按下时") ||
                       text.equals("颜色") || text.equals("X 偏移") || text.equals("Y 偏移") ||
                       text.equals("宽度 (%)") || text.equals("键雨颜色") || text.equals("速度") ||
                       text.equals("标签 X 偏移")) {
                tv.setTextColor(subTextColor);
            } else {
                tv.setTextColor(subTextColor);
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                updateTextViewColors(vg.getChildAt(i), isDarkMode);
            }
        }
    }
    
    private void applyDarkModeToInputs() {
        boolean isDarkMode = globalSettings.darkModeEnabled;
        int bgColor = isDarkMode ? 0xFF3C3C3C : 0xFFFFFFFF;
        int textColor = isDarkMode ? 0xFFFFFFFF : 0xFF333333;
        
        applyToEditText(labelInput, bgColor, textColor);
        applyToEditText(widthInput, bgColor, textColor);
        applyToEditText(heightInput, bgColor, textColor);
        applyToEditText(cornerRadiusInput, bgColor, textColor);
        applyToEditText(borderWidthInput, bgColor, textColor);
        applyToEditText(textSizeInput, bgColor, textColor);
        applyToEditText(countTextSizeInput, bgColor, textColor);
        applyToEditText(textShadowColorInput, bgColor, textColor);
        applyToEditText(textShadowOffsetXInput, bgColor, textColor);
        applyToEditText(textShadowOffsetYInput, bgColor, textColor);
        applyToEditText(textShadowBlurInput, bgColor, textColor);
        applyToEditText(zIndexInput, bgColor, textColor);
        applyToEditText(rainOffsetXInput, bgColor, textColor);
        applyToEditText(rainOffsetYInput, bgColor, textColor);
        applyToEditText(rainWidthInput, bgColor, textColor);
        applyToEditText(rainColorInput, bgColor, textColor);
        applyToEditText(rainSpeedInput, bgColor, textColor);
        applyToEditText(rainGlowRadiusInput, bgColor, textColor);
        applyToEditText(textOffsetXInput, bgColor, textColor);
        applyToEditText(textOffsetYInput, bgColor, textColor);
        applyToEditText(countOffsetXInput, bgColor, textColor);
        applyToEditText(countOffsetYInput, bgColor, textColor);
        applyToEditText(fillColorUpInput, bgColor, textColor);
        applyToEditText(borderColorUpInput, bgColor, textColor);
        applyToEditText(textColorUpInput, bgColor, textColor);
        applyToEditText(countColorUpInput, bgColor, textColor);
        applyToEditText(fillColorDownInput, bgColor, textColor);
        applyToEditText(borderColorDownInput, bgColor, textColor);
        applyToEditText(textColorDownInput, bgColor, textColor);
        applyToEditText(countColorDownInput, bgColor, textColor);
        applyToEditText(simpleFillColorInput, bgColor, textColor);
        applyToEditText(simpleBorderColorInput, bgColor, textColor);
        applyToEditText(simpleTextColorInput, bgColor, textColor);
        applyToEditText(simpleCountColorInput, bgColor, textColor);
        
        // 更新 Switch 的文字颜色
        int switchTextColor = isDarkMode ? 0xFFFFFFFF : 0xFF333333;
        if (showCountSwitch != null) {
            showCountSwitch.setTextColor(switchTextColor);
        }
        if (autoFitTextSwitch != null) autoFitTextSwitch.setTextColor(switchTextColor);
        if (textShadowEnabledSwitch != null) textShadowEnabledSwitch.setTextColor(switchTextColor);
        if (mappingEnabledSwitch != null) {
            mappingEnabledSwitch.setTextColor(switchTextColor);
        }
        if (ghostKeySwitch != null) {
            ghostKeySwitch.setTextColor(switchTextColor);
        }
        if (rainEnabledSwitch != null) {
            rainEnabledSwitch.setTextColor(switchTextColor);
        }
        if (rainGlowEnabledSwitch != null) {
            rainGlowEnabledSwitch.setTextColor(switchTextColor);
        }
        if (imageReplacesLabelSwitch != null) {
            imageReplacesLabelSwitch.setTextColor(switchTextColor);
        }
        if (kpsTotalNoWrapSwitch != null) {
            kpsTotalNoWrapSwitch.setTextColor(switchTextColor);
        }
        
        // 更新按钮的文字颜色
        int btnTextColor = isDarkMode ? 0xFFFFFFFF : 0xFF333333;
        if (keyBindBtn != null) {
            keyBindBtn.setTextColor(btnTextColor);
        }
        if (mappingBindBtn != null) {
            mappingBindBtn.setTextColor(btnTextColor);
        }
    }
    
    private void applyToEditText(EditText editText, int bgColor, int textColor) {
        if (editText != null) {
            editText.setBackgroundColor(bgColor);
            editText.setTextColor(textColor);
        }
    }
    
    public void onDarkModeChanged() {
        applyDarkModeToPanel();
        applyDarkModeToInputs();
    }
    
    public void setInputListener(RishInputListener listener) {
        this.inputListener = listener;
    }
    
    public boolean isListeningForKey() {
        return isListeningForKey || isListeningForMapping;
    }

    private void initViews() {
        // 基础控件
        keyBindBtn = panelView.findViewById(R.id.keyBindBtn);
        settingsPanelTitle = panelView.findViewById(R.id.settingsPanelTitle);
        labelInput = panelView.findViewById(R.id.labelInput);
        widthInput = panelView.findViewById(R.id.widthInput);
        heightInput = panelView.findViewById(R.id.heightInput);
        cornerRadiusInput = panelView.findViewById(R.id.cornerRadiusInput);
        borderWidthInput = panelView.findViewById(R.id.borderWidthInput);
        textSizeInput = panelView.findViewById(R.id.textSizeInput);
        countTextSizeInput = panelView.findViewById(R.id.countTextSizeInput);
        autoFitTextSwitch = panelView.findViewById(R.id.autoFitTextSwitch);
        textShadowEnabledSwitch = panelView.findViewById(R.id.textShadowEnabledSwitch);
        textShadowSettingsContainer = panelView.findViewById(R.id.textShadowSettingsContainer);
        textShadowColorInput = panelView.findViewById(R.id.textShadowColorInput);
        textShadowOffsetXInput = panelView.findViewById(R.id.textShadowOffsetXInput);
        textShadowOffsetYInput = panelView.findViewById(R.id.textShadowOffsetYInput);
        textShadowBlurInput = panelView.findViewById(R.id.textShadowBlurInput);
        showCountSwitch = panelView.findViewById(R.id.showCountSwitch);
        fontAssetBtn = panelView.findViewById(R.id.fontAssetBtn);
        clearFontAssetBtn = panelView.findViewById(R.id.clearFontAssetBtn);
        imageAssetBtn = panelView.findViewById(R.id.imageAssetBtn);
        clearImageAssetBtn = panelView.findViewById(R.id.clearImageAssetBtn);
        imageReplacesLabelSwitch = panelView.findViewById(R.id.imageReplacesLabelSwitch);
        zIndexInput = panelView.findViewById(R.id.zIndexInput);

        // 映射
        mappingEnabledSwitch = panelView.findViewById(R.id.mappingEnabledSwitch);
        ghostKeySwitch = panelView.findViewById(R.id.ghostKeySwitch);
        mappingSettingsContainer = panelView.findViewById(R.id.mappingSettingsContainer);
        mappingBindBtn = panelView.findViewById(R.id.mappingBindBtn);

        // 键雨
        rainEnabledSwitch = panelView.findViewById(R.id.rainEnabledSwitch);
        rainSettingsContainer = panelView.findViewById(R.id.rainSettingsContainer);
        rainOffsetXInput = panelView.findViewById(R.id.rainOffsetXInput);
        rainOffsetYInput = panelView.findViewById(R.id.rainOffsetYInput);
        rainWidthInput = panelView.findViewById(R.id.rainWidthInput);
        rainColorInput = panelView.findViewById(R.id.rainColorInput);
        rainColorPreview = panelView.findViewById(R.id.rainColorPreview);
        rainSpeedInput = panelView.findViewById(R.id.rainSpeedInput);
        rainGlowEnabledSwitch = panelView.findViewById(R.id.rainGlowEnabledSwitch);
        rainGlowRadiusInput = panelView.findViewById(R.id.rainGlowRadiusInput);

        // KPS/TOTAL 特殊控制
        kpsTotalContainer = panelView.findViewById(R.id.kpsTotalContainer);
        kpsTotalNoWrapSwitch = panelView.findViewById(R.id.kpsTotalNoWrapSwitch);
        counterLabelCaseSpinner = panelView.findViewById(R.id.counterLabelCaseSpinner);
        textOffsetXInput = panelView.findViewById(R.id.textOffsetXInput);
        textOffsetYInput = panelView.findViewById(R.id.textOffsetYInput);
        countOffsetXInput = panelView.findViewById(R.id.countOffsetXInput);
        countOffsetYInput = panelView.findViewById(R.id.countOffsetYInput);

        // 颜色设置区域
        colorSectionHeader = panelView.findViewById(R.id.colorSectionHeader);
        colorSectionDivider = panelView.findViewById(R.id.colorSectionDivider);
        colorSectionContent = panelView.findViewById(R.id.colorSectionContent);
        
        // 普通格子颜色容器
        normalColorContainer = panelView.findViewById(R.id.normalColorContainer);
        
        // 放开时颜色
        fillColorUpInput = panelView.findViewById(R.id.fillColorUpInput);
        fillColorUpPreview = panelView.findViewById(R.id.fillColorUpPreview);
        borderColorUpInput = panelView.findViewById(R.id.borderColorUpInput);
        borderColorUpPreview = panelView.findViewById(R.id.borderColorUpPreview);
        textColorUpInput = panelView.findViewById(R.id.textColorUpInput);
        textColorUpPreview = panelView.findViewById(R.id.textColorUpPreview);
        countColorUpInput = panelView.findViewById(R.id.countColorUpInput);
        countColorUpPreview = panelView.findViewById(R.id.countColorUpPreview);
        
        // 按下时颜色
        fillColorDownInput = panelView.findViewById(R.id.fillColorDownInput);
        fillColorDownPreview = panelView.findViewById(R.id.fillColorDownPreview);
        borderColorDownInput = panelView.findViewById(R.id.borderColorDownInput);
        borderColorDownPreview = panelView.findViewById(R.id.borderColorDownPreview);
        textColorDownInput = panelView.findViewById(R.id.textColorDownInput);
        textColorDownPreview = panelView.findViewById(R.id.textColorDownPreview);
        countColorDownInput = panelView.findViewById(R.id.countColorDownInput);
        countColorDownPreview = panelView.findViewById(R.id.countColorDownPreview);
        
        // 特殊格子简化颜色
        simpleColorContainer = panelView.findViewById(R.id.simpleColorContainer);
        simpleFillColorInput = panelView.findViewById(R.id.simpleFillColorInput);
        simpleFillColorPreview = panelView.findViewById(R.id.simpleFillColorPreview);
        simpleBorderColorInput = panelView.findViewById(R.id.simpleBorderColorInput);
        simpleBorderColorPreview = panelView.findViewById(R.id.simpleBorderColorPreview);
        simpleTextColorInput = panelView.findViewById(R.id.simpleTextColorInput);
        simpleTextColorPreview = panelView.findViewById(R.id.simpleTextColorPreview);
        simpleCountColorInput = panelView.findViewById(R.id.simpleCountColorInput);
        simpleCountColorPreview = panelView.findViewById(R.id.simpleCountColorPreview);
        
        // 初始收起颜色区域
        colorSectionContent.setVisibility(View.GONE);
        if (colorSectionDivider != null) {
            colorSectionDivider.setVisibility(View.GONE);
        }
    }

    private void setupListeners() {
        // 按键绑定
        keyBindBtn.setOnClickListener(v -> {
            if (!isListeningForKey && !isListeningForMapping) {
                isListeningForKey = true;
                keyBindBtn.setText("按下按键...");
                pauseActiveInput();
            }
        });

        // 映射绑定
        mappingBindBtn.setOnClickListener(v -> {
            if (!isListeningForKey && !isListeningForMapping) {
                isListeningForMapping = true;
                mappingBindBtn.setText("按下映射目标按键...");
                pauseActiveInput();
            }
        });

        // 映射开关
        mappingEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            mappingSettingsContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (syncingUi) return;
            for (KeyData key : getTargetKeys()) {
                key.mappingEnabled = isChecked;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
        });

        fontAssetBtn.setOnClickListener(v -> showFontSourceDialog());
        imageAssetBtn.setOnClickListener(v -> chooseAsset(REQUEST_KEY_IMAGE));
        clearFontAssetBtn.setOnClickListener(v -> clearAsset(true));
        clearImageAssetBtn.setOnClickListener(v -> clearAsset(false));
        imageReplacesLabelSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncingUi) return;
            for (KeyData key : getTargetKeys()) key.imageReplacesLabel = isChecked;
            saveAndRedrawAssets();
        });

        ghostKeySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncingUi) return;
            for (KeyData key : getTargetKeys()) {
                key.ghostKey = isChecked;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
        });

        // 显示次数开关
        showCountSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            if (syncingUi) return;
            for (KeyData key : getTargetKeys()) {
                key.showCount = isChecked;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
        });

        autoFitTextSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            if (syncingUi) return;
            for (KeyData key : getTargetKeys()) key.autoFitText = isChecked;
            saveTextAppearance();
        });

        textShadowEnabledSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            textShadowSettingsContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (syncingUi) return;
            for (KeyData key : getTargetKeys()) key.textShadowEnabled = isChecked;
            saveTextAppearance();
        });

        // 键雨开关
        rainEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            rainSettingsContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (syncingUi) return;
            for (KeyData key : getTargetKeys()) {
                key.rainEnabled = isChecked;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
        });

        rainGlowEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncingUi) return;
            for (KeyData key : getTargetKeys()) key.rainGlowEnabled = isChecked;
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
        });

        // KPS/TOTAL 换行开关
        kpsTotalNoWrapSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (syncingUi) return;
            for (KeyData key : getTargetKeys()) {
                key.kpsTotalNoWrap = isChecked;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
        });
        String[] counterCases = {"全大写", "首字母大写", "全小写"};
        ArrayAdapter<String> counterCaseAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, counterCases);
        counterCaseAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        counterLabelCaseSpinner.setAdapter(counterCaseAdapter);
        counterLabelCaseSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (syncingUi) return;
                String mode = position == 1 ? "title" : position == 2 ? "lower" : "upper";
                for (KeyData key : getTargetKeys()) {
                    if (key.isSpecialKey()) key.counterLabelCase = mode;
                }
                keyView.getKeyManager().saveToPreferences();
                keyView.getKeyManager().notifyDataChanged();
                keyView.invalidate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        // 颜色区域展开/收起
        colorSectionHeader.setOnClickListener(v -> toggleColorSection());

        // 设置所有输入框的 EditorAction
        setupEditorAction(labelInput, this::updateLabel);
        setupEditorAction(widthInput, this::updateSize);
        setupEditorAction(heightInput, this::updateSize);
        setupEditorAction(cornerRadiusInput, this::updateCornerRadius);
        setupEditorAction(borderWidthInput, this::updateBorderWidth);
        setupEditorAction(textSizeInput, this::updateTextSize);
        setupEditorAction(countTextSizeInput, this::updateCountTextSize);
        setupEditorAction(textShadowColorInput, this::updateTextShadow);
        setupEditorAction(textShadowOffsetXInput, this::updateTextShadow);
        setupEditorAction(textShadowOffsetYInput, this::updateTextShadow);
        setupEditorAction(textShadowBlurInput, this::updateTextShadow);
        setupEditorAction(zIndexInput, this::updateZIndex);
        setupEditorAction(rainOffsetXInput, this::updateRainOffsetX);
        setupEditorAction(rainOffsetYInput, this::updateRainOffsetY);
        setupEditorAction(rainWidthInput, this::updateRainWidth);
        setupEditorAction(rainSpeedInput, this::updateRainSpeed);
        setupEditorAction(rainGlowRadiusInput, this::updateRainGlowRadius);
        setupEditorAction(textOffsetXInput, this::updateTextAndCountOffsets);
        setupEditorAction(textOffsetYInput, this::updateTextAndCountOffsets);
        setupEditorAction(countOffsetXInput, this::updateTextAndCountOffsets);
        setupEditorAction(countOffsetYInput, this::updateTextAndCountOffsets);
        
        // 颜色输入框的设置
        setupColorInput(fillColorUpInput, fillColorUpPreview, this::updateFillColorUp);
        setupColorInput(borderColorUpInput, borderColorUpPreview, this::updateBorderColorUp);
        setupColorInput(textColorUpInput, textColorUpPreview, this::updateTextColorUp);
        setupColorInput(countColorUpInput, countColorUpPreview, this::updateCountColorUp);
        setupColorInput(fillColorDownInput, fillColorDownPreview, this::updateFillColorDown);
        setupColorInput(borderColorDownInput, borderColorDownPreview, this::updateBorderColorDown);
        setupColorInput(textColorDownInput, textColorDownPreview, this::updateTextColorDown);
        setupColorInput(countColorDownInput, countColorDownPreview, this::updateCountColorDown);
        
        // 特殊格子简化颜色
        setupColorInput(simpleFillColorInput, simpleFillColorPreview, this::updateSimpleFillColor);
        setupColorInput(simpleBorderColorInput, simpleBorderColorPreview, this::updateSimpleBorderColor);
        setupColorInput(simpleTextColorInput, simpleTextColorPreview, this::updateSimpleTextColor);
        setupColorInput(simpleCountColorInput, simpleCountColorPreview, this::updateSimpleCountColor);
        
        // 键雨颜色
        setupColorInput(rainColorInput, rainColorPreview, this::updateRainColor);
    }

    private void setupEditorAction(EditText editText, Runnable action) {
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                action.run();
                editText.clearFocus();
                return true;
            }
            return false;
        });
    }

    private void setupColorInput(EditText input, View preview, Runnable action) {
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                action.run();
                input.clearFocus();
                return true;
            }
            return false;
        });
        
        preview.setOnClickListener(v -> {
            String currentHex = input.getText().toString();
            showColorPicker(currentHex, preview, hex -> {
                input.setText(hex);
                action.run();
            });
        });
    }

    private void showColorPicker(String currentHex, View anchorView, OnColorSelectedListener listener) {
        int currentColor = ColorUtils.hexToInt(currentHex);
        ColorPickerDialog dialog = new ColorPickerDialog(activity, currentColor, anchorView);
        dialog.setOnColorSelectedListener(listener::onColorSelected);
        dialog.show();
    }

    private interface OnColorSelectedListener {
        void onColorSelected(String hex);
    }

    private List<KeyData> getTargetKeys() {
        return keyView.getSelectedKeys();
    }

    private void chooseAsset(int requestCode) {
        pendingAssetKeyIds.clear();
        for (KeyData key : getTargetKeys()) pendingAssetKeyIds.add(key.id);
        if (pendingAssetKeyIds.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (requestCode == REQUEST_KEY_FONT) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf"});
        } else {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "text/xml", "application/xml"});
        }
        activity.startActivityForResult(intent, requestCode);
    }

    private void showFontSourceDialog() {
        String[] labels = {
                "Maplestory OTF Bold（默认内建）",
                "ADOFAI / godoMaum（内建）",
                "安卓系统无衬线",
                "安卓系统衬线",
                "安卓系统等宽",
                "导入 TTF / OTF 文件"
        };
        new AlertDialog.Builder(activity)
                .setTitle("选择字体")
                .setItems(labels, (dialog, which) -> {
                    switch (which) {
                        case 0: applyFontReference(""); break;
                        case 1: applyFontReference(FontReference.ADOFAI); break;
                        case 2: applyFontReference(FontReference.SANS); break;
                        case 3: applyFontReference(FontReference.SERIF); break;
                        case 4: applyFontReference(FontReference.MONOSPACE); break;
                        default: chooseAsset(REQUEST_KEY_FONT); break;
                    }
                })
                .show();
    }

    private void applyFontReference(String reference) {
        for (KeyData key : getTargetKeys()) key.fontAsset = reference;
        saveAndRedrawAssets();
        syncFromKeyView();
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_KEY_FONT && requestCode != REQUEST_KEY_IMAGE) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        boolean font = requestCode == REQUEST_KEY_FONT;
        try {
            String storedName = KeyAssetStore.importAsset(activity, uri,
                    font ? KeyAssetStore.TYPE_FONT : KeyAssetStore.TYPE_IMAGE);
            for (KeyData key : keyView.getKeyManager().getKeys()) {
                if (!pendingAssetKeyIds.contains(key.id)) continue;
                if (font) key.fontAsset = storedName; else key.imageAsset = storedName;
            }
            saveAndRedrawAssets();
            syncFromKeyView();
            Toast.makeText(activity, font ? "字体已导入" : "图片已导入", Toast.LENGTH_SHORT).show();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Unable to import key asset", e);
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            pendingAssetKeyIds.clear();
        }
        return true;
    }

    private void clearAsset(boolean font) {
        for (KeyData key : getTargetKeys()) {
            if (font) key.fontAsset = ""; else key.imageAsset = "";
        }
        saveAndRedrawAssets();
        syncFromKeyView();
    }

    private void saveAndRedrawAssets() {
        keyView.clearAssetCaches();
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
    }

    private void toggleColorSection() {
        if (colorSectionAnimating) return;

        colorSectionExpanded = !colorSectionExpanded;
        
        if (colorSectionExpanded) {
            expandColorSection();
        } else {
            collapseColorSection();
        }
    }

    private void expandColorSection() {
        if (colorSectionDivider != null) {
            colorSectionDivider.setVisibility(View.VISIBLE);
        }

        colorSectionContent.clearAnimation();
        colorSectionContent.setVisibility(View.VISIBLE);
        colorSectionContent.setAlpha(0f);

        colorSectionContent.measure(
                View.MeasureSpec.makeMeasureSpec(((View) colorSectionContent.getParent()).getWidth(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        int targetHeight = colorSectionContent.getMeasuredHeight();
        ViewGroup.LayoutParams layoutParams = colorSectionContent.getLayoutParams();
        layoutParams.height = 0;
        colorSectionContent.setLayoutParams(layoutParams);

        ValueAnimator animator = ValueAnimator.ofInt(0, targetHeight);
        animator.setDuration(220);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        colorSectionAnimating = true;
        animator.addUpdateListener(animation -> {
            ViewGroup.LayoutParams params = colorSectionContent.getLayoutParams();
            params.height = (int) animation.getAnimatedValue();
            colorSectionContent.setLayoutParams(params);
            colorSectionContent.setAlpha(animation.getAnimatedFraction());
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ViewGroup.LayoutParams params = colorSectionContent.getLayoutParams();
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                colorSectionContent.setLayoutParams(params);
                colorSectionContent.setAlpha(1f);
                colorSectionAnimating = false;
            }
        });
        animator.start();
    }

    private void collapseColorSection() {
        int startHeight = colorSectionContent.getHeight();
        if (startHeight == 0) {
            colorSectionContent.setVisibility(View.GONE);
            if (colorSectionDivider != null) {
                colorSectionDivider.setVisibility(View.GONE);
            }
            return;
        }

        colorSectionContent.clearAnimation();
        ValueAnimator animator = ValueAnimator.ofInt(startHeight, 0);
        animator.setDuration(180);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        colorSectionAnimating = true;
        animator.addUpdateListener(animation -> {
            ViewGroup.LayoutParams params = colorSectionContent.getLayoutParams();
            params.height = (int) animation.getAnimatedValue();
            colorSectionContent.setLayoutParams(params);
            colorSectionContent.setAlpha(1f - animation.getAnimatedFraction());
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ViewGroup.LayoutParams params = colorSectionContent.getLayoutParams();
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                colorSectionContent.setLayoutParams(params);
                colorSectionContent.setAlpha(1f);
                colorSectionContent.setVisibility(View.GONE);
                if (colorSectionDivider != null) {
                    colorSectionDivider.setVisibility(View.GONE);
                }
                colorSectionAnimating = false;
            }
        });
        animator.start();
    }

    // ==================== UI 动态切换 ====================

    private void updateUIBasedOnKeyType() {
        List<KeyData> keys = getTargetKeys();
        if (keys.isEmpty()) return;
        
        KeyData firstKey = keys.get(0);
        if (keyView.isBatchEditMode()) {
            String baselineName = firstKey.getDisplayText();
            if (baselineName == null || baselineName.trim().isEmpty()) baselineName = "未绑定";
            settingsPanelTitle.setText("批量设置（基准：" + baselineName + "）");
        } else {
            settingsPanelTitle.setText("按键设置");
        }
        boolean isSpecialKey = firstKey.isSpecialKey();
        
        // 映射设置（特殊格子隐藏）
        int mappingVisibility = isSpecialKey ? View.GONE : View.VISIBLE;
        if (mappingEnabledSwitch != null) {
            mappingEnabledSwitch.setVisibility(mappingVisibility);
        }
        if (ghostKeySwitch != null) {
            ghostKeySwitch.setVisibility(mappingVisibility);
        }
        if (mappingSettingsContainer != null) {
            mappingSettingsContainer.setVisibility(
                (!isSpecialKey && firstKey.mappingEnabled) ? View.VISIBLE : 
                (isSpecialKey ? View.GONE : (firstKey.mappingEnabled ? View.VISIBLE : View.GONE))
            );
        }
        
        // 键雨设置（特殊格子隐藏）
        int rainVisibility = isSpecialKey ? View.GONE : View.VISIBLE;
        if (rainEnabledSwitch != null) {
            rainEnabledSwitch.setVisibility(rainVisibility);
        }
        if (rainSettingsContainer != null) {
            rainSettingsContainer.setVisibility(
                (!isSpecialKey && firstKey.rainEnabled) ? View.VISIBLE : 
                (isSpecialKey ? View.GONE : (firstKey.rainEnabled ? View.VISIBLE : View.GONE))
            );
        }
        
        // 显示次数开关（特殊格子隐藏）
        if (showCountSwitch != null) {
            showCountSwitch.setVisibility(isSpecialKey ? View.GONE : View.VISIBLE);
        }
        
        // KPS/TOTAL 特殊控制（仅特殊格子显示）
        if (kpsTotalContainer != null) {
            kpsTotalContainer.setVisibility(isSpecialKey ? View.VISIBLE : View.GONE);
        }
        
        // 颜色设置 UI 切换
        if (normalColorContainer != null && simpleColorContainer != null) {
            if (isSpecialKey) {
                normalColorContainer.setVisibility(View.GONE);
                simpleColorContainer.setVisibility(View.VISIBLE);
            } else {
                normalColorContainer.setVisibility(View.VISIBLE);
                simpleColorContainer.setVisibility(View.GONE);
            }
        }
    }

    // ==================== 更新方法 ====================

    private void updateLabel() {
        String label = labelInput.getText().toString();
        for (KeyData key : getTargetKeys()) {
            key.displayLabel = label;
        }
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        syncFromKeyView();
    }

    private void updateSize() {
        try {
            float w = parseClamped(widthInput, 8f, 5000f);
            float h = parseClamped(heightInput, 8f, 5000f);
            float density = activity.getResources().getDisplayMetrics().density;
            for (KeyData key : getTargetKeys()) {
                key.width = w * density;
                key.height = h * density;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
        } catch (NumberFormatException ignored) {}
    }

    private void updateCornerRadius() {
        try {
            float r = parseClamped(cornerRadiusInput, 0f, 2500f);
            for (KeyData key : getTargetKeys()) {
                key.cornerRadiusDp = r;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
        } catch (NumberFormatException ignored) {}
    }

    private void updateBorderWidth() {
        try {
            float w = parseClamped(borderWidthInput, 0f, 500f);
            for (KeyData key : getTargetKeys()) {
                key.borderWidthDp = w;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
        } catch (NumberFormatException ignored) {}
    }

    private void updateTextSize() {
        try {
            float size = parseClamped(textSizeInput, 4f, 300f);
            for (KeyData key : getTargetKeys()) {
                key.textSizeSp = size;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
        } catch (NumberFormatException ignored) {}
    }

    private void updateCountTextSize() {
        try {
            float size = parseClamped(countTextSizeInput, 4f, 300f);
            for (KeyData key : getTargetKeys()) key.countTextSizeSp = size;
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
        } catch (NumberFormatException ignored) {}
    }

    private void updateTextShadow() {
        try {
            int color = ColorUtils.hexToInt(textShadowColorInput.getText().toString());
            float offsetX = parseClamped(textShadowOffsetXInput, -500f, 500f);
            float offsetY = parseClamped(textShadowOffsetYInput, -500f, 500f);
            float blur = parseClamped(textShadowBlurInput, 0f, 100f);
            for (KeyData key : getTargetKeys()) {
                key.textShadowColor = color;
                key.textShadowOffsetXDp = offsetX;
                key.textShadowOffsetYDp = offsetY;
                key.textShadowBlurRadiusDp = blur;
            }
            textShadowColorInput.setText(ColorUtils.intToHex(color));
            saveTextAppearance();
        } catch (NumberFormatException ignored) {}
    }

    private void saveTextAppearance() {
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
    }

    private void updateZIndex() {
        try {
            int zIndex = Integer.parseInt(zIndexInput.getText().toString());
            for (KeyData key : getTargetKeys()) {
                key.zIndex = zIndex;
            }
            keyView.getKeyManager().normalizeZIndexes();
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
        } catch (NumberFormatException ignored) {}
    }

    private void updateRainOffsetX() {
        try {
            float x = parseClamped(rainOffsetXInput, -5000f, 5000f);
            for (KeyData key : getTargetKeys()) {
                key.rainOffsetXDp = x;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
        } catch (NumberFormatException ignored) {}
    }

    private void updateRainOffsetY() {
        try {
            float y = parseClamped(rainOffsetYInput, -5000f, 5000f);
            for (KeyData key : getTargetKeys()) {
                key.rainOffsetYDp = y;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
        } catch (NumberFormatException ignored) {}
    }

    private void updateRainWidth() {
        try {
            float width = parseClamped(rainWidthInput, 1f, 1000f);
            for (KeyData key : getTargetKeys()) {
                key.rainWidthPercent = width;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
        } catch (NumberFormatException ignored) {}
    }

    private void updateRainSpeed() {
        try {
            float speed = parseClamped(rainSpeedInput, 0f, 1000f);
            for (KeyData key : getTargetKeys()) {
                key.rainSpeedDp = speed;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
        } catch (NumberFormatException ignored) {}
    }

    private void updateRainGlowRadius() {
        try {
            float radius = parseClamped(rainGlowRadiusInput, 0f, 200f);
            for (KeyData key : getTargetKeys()) key.rainGlowRadiusDp = radius;
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
        } catch (NumberFormatException ignored) { }
    }

    private void updateRainColor() {
        String hex = rainColorInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.rainColor = color;
        }
        rainColorPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        rainColorInput.setText(ColorUtils.intToHex(color));
    }

    private void updateTextAndCountOffsets() {
        try {
            float labelX = parseClamped(textOffsetXInput, -5000f, 5000f);
            float labelY = parseClamped(textOffsetYInput, -5000f, 5000f);
            float valueX = parseClamped(countOffsetXInput, -5000f, 5000f);
            float valueY = parseClamped(countOffsetYInput, -5000f, 5000f);
            for (KeyData key : getTargetKeys()) {
                key.textOffsetXDp = labelX;
                key.textOffsetYDp = labelY;
                key.countOffsetXDp = valueX;
                key.countOffsetYDp = valueY;
            }
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
        } catch (NumberFormatException ignored) {}
    }

    private float parseClamped(EditText input, float min, float max) throws NumberFormatException {
        float value = Float.parseFloat(input.getText().toString());
        if (!Float.isFinite(value)) {
            throw new NumberFormatException("Value must be finite");
        }
        return Math.max(min, Math.min(max, value));
    }

    // ==================== 颜色更新方法（普通格子）====================

    private void updateFillColorUp() {
        String hex = fillColorUpInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.fillColorUp = color;
        }
        fillColorUpPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        fillColorUpInput.setText(ColorUtils.intToHex(color));
    }

    private void updateBorderColorUp() {
        String hex = borderColorUpInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.borderColorUp = color;
        }
        borderColorUpPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        borderColorUpInput.setText(ColorUtils.intToHex(color));
    }

    private void updateTextColorUp() {
        String hex = textColorUpInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.textColorUp = color;
        }
        textColorUpPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        textColorUpInput.setText(ColorUtils.intToHex(color));
    }

    private void updateCountColorUp() {
        String hex = countColorUpInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.countColorUp = color;
        }
        countColorUpPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        countColorUpInput.setText(ColorUtils.intToHex(color));
    }

    private void updateFillColorDown() {
        String hex = fillColorDownInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.fillColorDown = color;
        }
        fillColorDownPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        fillColorDownInput.setText(ColorUtils.intToHex(color));
    }

    private void updateBorderColorDown() {
        String hex = borderColorDownInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.borderColorDown = color;
        }
        borderColorDownPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        borderColorDownInput.setText(ColorUtils.intToHex(color));
    }

    private void updateTextColorDown() {
        String hex = textColorDownInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.textColorDown = color;
        }
        textColorDownPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        textColorDownInput.setText(ColorUtils.intToHex(color));
    }

    private void updateCountColorDown() {
        String hex = countColorDownInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.countColorDown = color;
        }
        countColorDownPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        countColorDownInput.setText(ColorUtils.intToHex(color));
    }

    // ==================== 颜色更新方法（特殊格子）====================

    private void updateSimpleFillColor() {
        String hex = simpleFillColorInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.fillColorUp = color;
            key.fillColorDown = color;
        }
        simpleFillColorPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        simpleFillColorInput.setText(ColorUtils.intToHex(color));
    }

    private void updateSimpleBorderColor() {
        String hex = simpleBorderColorInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.borderColorUp = color;
            key.borderColorDown = color;
        }
        simpleBorderColorPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        simpleBorderColorInput.setText(ColorUtils.intToHex(color));
    }

    private void updateSimpleTextColor() {
        String hex = simpleTextColorInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.textColorUp = color;
            key.textColorDown = color;
        }
        simpleTextColorPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        simpleTextColorInput.setText(ColorUtils.intToHex(color));
    }

    private void updateSimpleCountColor() {
        String hex = simpleCountColorInput.getText().toString();
        int color = ColorUtils.hexToInt(hex);
        for (KeyData key : getTargetKeys()) {
            key.countColorUp = color;
            key.countColorDown = color;
        }
        simpleCountColorPreview.setBackgroundColor(color);
        keyView.getKeyManager().saveToPreferences();
        keyView.getKeyManager().notifyDataChanged();
        keyView.invalidate();
        simpleCountColorInput.setText(ColorUtils.intToHex(color));
    }

    // ==================== 同步 ====================

    public void syncFromKeyView() {
        List<KeyData> keys = getTargetKeys();
        if (keys.isEmpty()) return;
        syncingUi = true;
        
        KeyData firstKey = keys.get(0);
        boolean isSpecialKey = firstKey.isSpecialKey();
        float density = activity.getResources().getDisplayMetrics().density;

        // 基础信息
        String displayName = getDisplayNameForInternalKey(firstKey.boundKey);
        keyBindBtn.setText(displayName);
        labelInput.setText(firstKey.displayLabel);
        widthInput.setText(String.valueOf((int) (firstKey.width / density)));
        heightInput.setText(String.valueOf((int) (firstKey.height / density)));
        cornerRadiusInput.setText(String.valueOf((int) firstKey.cornerRadiusDp));
        borderWidthInput.setText(String.valueOf((int) firstKey.borderWidthDp));
        textSizeInput.setText(String.valueOf((int) firstKey.textSizeSp));
        countTextSizeInput.setText(String.valueOf((int) (firstKey.countTextSizeSp > 0f
                ? firstKey.countTextSizeSp : firstKey.textSizeSp)));
        autoFitTextSwitch.setChecked(firstKey.autoFitText);
        textShadowEnabledSwitch.setChecked(firstKey.textShadowEnabled);
        textShadowSettingsContainer.setVisibility(
                firstKey.textShadowEnabled ? View.VISIBLE : View.GONE);
        textShadowColorInput.setText(ColorUtils.intToHex(firstKey.textShadowColor));
        textShadowOffsetXInput.setText(String.valueOf(firstKey.textShadowOffsetXDp));
        textShadowOffsetYInput.setText(String.valueOf(firstKey.textShadowOffsetYDp));
        textShadowBlurInput.setText(String.valueOf(firstKey.textShadowBlurRadiusDp));
        showCountSwitch.setChecked(firstKey.showCount);
        fontAssetBtn.setText(FontReference.displayName(firstKey.fontAsset));
        imageAssetBtn.setText(firstKey.imageAsset.isEmpty() ? "选择图片 / XML" : "图片已设置");
        imageReplacesLabelSwitch.setChecked(firstKey.imageReplacesLabel);
        zIndexInput.setText(String.valueOf(firstKey.zIndex));

        // 映射
        mappingEnabledSwitch.setChecked(firstKey.mappingEnabled);
        ghostKeySwitch.setChecked(firstKey.ghostKey);
        String mappedDisplayName = getDisplayNameForInternalKey(firstKey.mappedKey);
        mappingBindBtn.setText(mappedDisplayName.isEmpty() ? "点击设置映射按键" : mappedDisplayName);

        // 键雨
        rainEnabledSwitch.setChecked(firstKey.rainEnabled);
        rainOffsetXInput.setText(String.valueOf((int) firstKey.rainOffsetXDp));
        rainOffsetYInput.setText(String.valueOf((int) firstKey.rainOffsetYDp));
        rainWidthInput.setText(String.valueOf((int) firstKey.rainWidthPercent));
        rainColorInput.setText(ColorUtils.intToHex(firstKey.rainColor));
        rainColorPreview.setBackgroundColor(firstKey.rainColor);
        rainSpeedInput.setText(String.valueOf((int) firstKey.rainSpeedDp));
        rainGlowEnabledSwitch.setChecked(firstKey.rainGlowEnabled);
        rainGlowRadiusInput.setText(String.valueOf(firstKey.rainGlowRadiusDp));

        // KPS/TOTAL 特殊控制
        kpsTotalNoWrapSwitch.setChecked(firstKey.kpsTotalNoWrap);
        counterLabelCaseSpinner.setSelection("title".equals(firstKey.counterLabelCase) ? 1
                : "lower".equals(firstKey.counterLabelCase) ? 2 : 0);
        textOffsetXInput.setText(String.valueOf((int) firstKey.textOffsetXDp));
        textOffsetYInput.setText(String.valueOf((int) firstKey.textOffsetYDp));
        countOffsetXInput.setText(String.valueOf((int) firstKey.countOffsetXDp));
        countOffsetYInput.setText(String.valueOf((int) firstKey.countOffsetYDp));

        // 颜色设置
        if (isSpecialKey) {
            simpleFillColorInput.setText(ColorUtils.intToHex(firstKey.fillColorUp));
            simpleFillColorPreview.setBackgroundColor(firstKey.fillColorUp);
            simpleBorderColorInput.setText(ColorUtils.intToHex(firstKey.borderColorUp));
            simpleBorderColorPreview.setBackgroundColor(firstKey.borderColorUp);
            simpleTextColorInput.setText(ColorUtils.intToHex(firstKey.textColorUp));
            simpleTextColorPreview.setBackgroundColor(firstKey.textColorUp);
            simpleCountColorInput.setText(ColorUtils.intToHex(firstKey.countColorUp));
            simpleCountColorPreview.setBackgroundColor(firstKey.countColorUp);
        } else {
            fillColorUpInput.setText(ColorUtils.intToHex(firstKey.fillColorUp));
            fillColorUpPreview.setBackgroundColor(firstKey.fillColorUp);
            borderColorUpInput.setText(ColorUtils.intToHex(firstKey.borderColorUp));
            borderColorUpPreview.setBackgroundColor(firstKey.borderColorUp);
            textColorUpInput.setText(ColorUtils.intToHex(firstKey.textColorUp));
            textColorUpPreview.setBackgroundColor(firstKey.textColorUp);
            countColorUpInput.setText(ColorUtils.intToHex(firstKey.countColorUp));
            countColorUpPreview.setBackgroundColor(firstKey.countColorUp);
            
            fillColorDownInput.setText(ColorUtils.intToHex(firstKey.fillColorDown));
            fillColorDownPreview.setBackgroundColor(firstKey.fillColorDown);
            borderColorDownInput.setText(ColorUtils.intToHex(firstKey.borderColorDown));
            borderColorDownPreview.setBackgroundColor(firstKey.borderColorDown);
            textColorDownInput.setText(ColorUtils.intToHex(firstKey.textColorDown));
            textColorDownPreview.setBackgroundColor(firstKey.textColorDown);
            countColorDownInput.setText(ColorUtils.intToHex(firstKey.countColorDown));
            countColorDownPreview.setBackgroundColor(firstKey.countColorDown);
        }
        
        updateUIBasedOnKeyType();
        applyDarkModeToPanel();
        applyDarkModeToInputs();
        syncingUi = false;
    }

    // ==================== 按键事件处理 ====================

    public boolean handleKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        if (isListeningForKey) {
            String internalName = getInternalKeyName(event);
            for (KeyData key : getTargetKeys()) {
                key.boundKey = internalName;
            }
            String displayName = getDisplayNameForInternalKey(internalName);
            keyBindBtn.setText(displayName);
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
            isListeningForKey = false;
            resumeActiveInput();
            return true;
        }
        
        if (isListeningForMapping) {
            String internalName = getInternalKeyName(event);
            for (KeyData key : getTargetKeys()) {
                key.mappedKey = internalName;
            }
            String displayName = getDisplayNameForInternalKey(internalName);
            mappingBindBtn.setText(displayName);
            keyView.getKeyManager().saveToPreferences();
            keyView.getKeyManager().notifyDataChanged();
            keyView.invalidate();
            isListeningForMapping = false;
            resumeActiveInput();
            return true;
        }
        
        return false;
    }

    private String getInternalKeyName(KeyEvent event) {
        int keyCode = event.getKeyCode();
        
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: return "space";
            case KeyEvent.KEYCODE_ENTER: return "enter";
            case KeyEvent.KEYCODE_DEL: return "backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "del";
            case KeyEvent.KEYCODE_TAB: return "tab";
            case KeyEvent.KEYCODE_ESCAPE: return "escape";
            case KeyEvent.KEYCODE_SHIFT_LEFT: return "lshift";
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return "rshift";
            case KeyEvent.KEYCODE_CTRL_LEFT: return "lctrl";
            case KeyEvent.KEYCODE_CTRL_RIGHT: return "rctrl";
            case KeyEvent.KEYCODE_ALT_LEFT: return "lalt";
            case KeyEvent.KEYCODE_ALT_RIGHT: return "ralt";
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_COMMA: return "comma";
            case KeyEvent.KEYCODE_PERIOD: return "dot";
            case KeyEvent.KEYCODE_SEMICOLON: return "semicolon";
            case KeyEvent.KEYCODE_SLASH: return "slash";
            case KeyEvent.KEYCODE_BACKSLASH: return "backslash";
            case KeyEvent.KEYCODE_APOSTROPHE: return "quote";
            case KeyEvent.KEYCODE_GRAVE: return "backquote";
            case KeyEvent.KEYCODE_LEFT_BRACKET: return "bracketleft";
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return "bracketright";
            case KeyEvent.KEYCODE_MINUS: return "minus";
            case KeyEvent.KEYCODE_EQUALS: return "equal";
            case KeyEvent.KEYCODE_PAGE_UP: return "pageup";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "pagedown";
            case KeyEvent.KEYCODE_MOVE_HOME: return "home";
            case KeyEvent.KEYCODE_MOVE_END: return "end";
            case KeyEvent.KEYCODE_CAPS_LOCK: return "capslock";
            default:
                if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
                    return String.valueOf((char) ('a' + (keyCode - KeyEvent.KEYCODE_A)));
                }
                if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                    return String.valueOf((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
                }
                return KeyEvent.keyCodeToString(keyCode);
        }
    }

    private String getDisplayNameForInternalKey(String internalName) {
        if (internalName == null || internalName.equals("未绑定") || internalName.isEmpty()) {
            return "未绑定";
        }
        
        switch (internalName) {
            case "space": return "空格";
            case "enter": return "回车";
            case "del": return "Del";
            case "tab": return "Tab";
            case "escape": return "Esc";
            case "lshift": return "左Shift";
            case "rshift": return "右Shift";
            case "lctrl": return "左Ctrl";
            case "rctrl": return "右Ctrl";
            case "lalt": return "左Alt";
            case "ralt": return "右Alt";
            case "up": return "↑";
            case "down": return "↓";
            case "left": return "←";
            case "right": return "→";
            case "comma": return ",";
            case "dot": return ".";
            case "semicolon": return ";";
            case "slash": return "/";
            case "backslash": return "\\";
            case "quote": return "'";
            case "backquote": return "`";
            case "bracketleft": return "[";
            case "bracketright": return "]";
            case "minus": return "-";
            case "equal": return "=";
            case "pageup": return "PgUp";
            case "pagedown": return "PgDn";
            case "home": return "Home";
            case "end": return "End";
            case "capslock": return "Caps";
            case "backspace": return "Back";
            default:
                if (internalName.length() == 1) return internalName.toUpperCase(java.util.Locale.ROOT);
                return internalName;
        }
    }

    public void stopKeyListening() {
        isListeningForKey = false;
        isListeningForMapping = false;
        List<KeyData> keys = getTargetKeys();
        if (!keys.isEmpty()) {
            KeyData firstKey = keys.get(0);
            keyBindBtn.setText(getDisplayNameForInternalKey(firstKey.boundKey));
            mappingBindBtn.setText(firstKey.mappedKey.isEmpty() ? "点击设置映射按键" : getDisplayNameForInternalKey(firstKey.mappedKey));
        }
        resumeActiveInput();
    }

    private void pauseActiveInput() {
        if (inputListener != null) inputListener.pause();
        FloatService service = FloatService.getInstance();
        if (service != null) service.pauseInputListening();
    }

    private void resumeActiveInput() {
        if (inputListener != null) inputListener.resume();
        FloatService service = FloatService.getInstance();
        if (service != null) service.resumeInputListening();
    }
}
