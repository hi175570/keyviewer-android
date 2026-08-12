package com.KV;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.AtomicFile;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ConfigManagerPanel {

    private static final String TAG = "KV_ConfigManager";
    private static final String CONFIG_DIR = "kv_configs";
    private static final String CONFIG_EXTENSION = ".json";
    private static final String CURRENT_CONFIG_KEY = "current_config_name";
    private static final String DEFAULT_CONFIG_NAME = "default";
    private static final String BUNDLED_DEFAULT_ASSET = "default_config.json";
    private static final String CONFIG_ORDER_KEY = "config_order_v1";
    private static final String PRESET_VERSION_KEY = "bundled_preset_version";
    private static final int BUNDLED_PRESET_VERSION = 1;
    private static final String[][] BUNDLED_PRESETS = {
            {"Jipper 16K", "preset_jipper_16k.json"},
            {"Jipper 12K", "preset_jipper_12k.json"},
            {"Jipper 10K", "preset_jipper_10k.json"}
    };
    private static final int MAX_CONFIG_BYTES = 72 * 1024 * 1024;
    private static final long AUTO_SAVE_DELAY_MS = 500;

    private final Activity activity;
    private final View panelView;
    private final KeyView keyView;
    private final GlobalSettings globalSettings;
    private final File configDir;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final List<ConfigItem> configList = new ArrayList<>();
    private final List<PortableConfigAssets.RestoreSession> pendingAssetRestores =
            Collections.synchronizedList(new ArrayList<>());

    private final RecyclerView recyclerView;
    private final ConfigAdapter adapter;
    private final Button closeBtn;
    private final Button newConfigBtn;

    private String currentConfigName;
    private Runnable pendingAutoSave;
    private boolean suppressAutoSave;
    private boolean destroyed;
    private OnConfigChangedListener listener;

    public interface OnConfigChangedListener {
        void onConfigLoaded(String configName);
        void onConfigDeleted(String configName);
        void onConfigRenamed(String oldName, String newName);
    }

    public ConfigManagerPanel(Activity activity, View panelView, KeyView keyView) {
        this.activity = activity;
        this.panelView = panelView;
        this.keyView = keyView;
        this.globalSettings = GlobalSettings.getInstance(activity);
        this.configDir = new File(activity.getFilesDir(), CONFIG_DIR);
        if (!configDir.exists() && !configDir.mkdirs()) {
            Log.e(TAG, "Unable to create config directory");
        }

        recyclerView = panelView.findViewById(R.id.configRecyclerView);
        closeBtn = panelView.findViewById(R.id.closeConfigPanelBtn);
        newConfigBtn = panelView.findViewById(R.id.newConfigBtn);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new ConfigAdapter();
        recyclerView.setAdapter(adapter);

        closeBtn.setOnClickListener(v -> hidePanel());
        newConfigBtn.setOnClickListener(v -> showCreateConfigDialog());
        currentConfigName = activity.getSharedPreferences("kv_config_prefs", Context.MODE_PRIVATE)
                .getString(CURRENT_CONFIG_KEY, DEFAULT_CONFIG_NAME);
        if (!isValidConfigName(currentConfigName)) {
            currentConfigName = DEFAULT_CONFIG_NAME;
        }

        File[] existingConfigs = configDir.listFiles((dir, name) -> name.endsWith(CONFIG_EXTENSION));
        boolean freshConfigInstall = existingConfigs == null || existingConfigs.length == 0;
        String requestedConfigName = currentConfigName;
        ensureDefaultExists();
        ensureBundledPresets();
        if (freshConfigInstall) {
            requestedConfigName = BUNDLED_PRESETS[0][0];
        }
        if (!configFile(requestedConfigName).exists()) {
            requestedConfigName = DEFAULT_CONFIG_NAME;
        }
        saveCurrentConfigName(requestedConfigName);
        loadConfigFromFile(requestedConfigName);
        refreshConfigList();
        applyDarkMode();
        keyView.getKeyManager().setOnDataChangedCallback(() -> mainHandler.post(this::scheduleAutoSave));
    }

    public void setOnConfigChangedListener(OnConfigChangedListener listener) {
        this.listener = listener;
    }

    public String getCurrentConfigName() {
        return currentConfigName;
    }

    private void scheduleAutoSave() {
        if (destroyed || suppressAutoSave) {
            return;
        }
        if (pendingAutoSave != null) {
            mainHandler.removeCallbacks(pendingAutoSave);
        }
        pendingAutoSave = () -> {
            pendingAutoSave = null;
            saveSnapshot(currentConfigName);
        };
        mainHandler.postDelayed(pendingAutoSave, AUTO_SAVE_DELAY_MS);
    }

    private String createSnapshot() {
        try {
            float density = activity.getResources().getDisplayMetrics().density;
            return ConfigCodec.encode(keyView.getKeyManager(), density).toString(2);
        } catch (Exception e) {
            Log.e(TAG, "Unable to serialize config", e);
            return null;
        }
    }

    private Future<?> saveSnapshot(String name) {
        if (!isValidConfigName(name) || destroyed) {
            return null;
        }
        String snapshot = createSnapshot();
        if (snapshot == null) {
            return null;
        }
        File target = configFile(name);
        return fileExecutor.submit(() -> writeAtomic(target, snapshot));
    }

    private void writeAtomic(File target, String content) {
        AtomicFile atomicFile = new AtomicFile(target);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
            atomicFile.finishWrite(output);
        } catch (Exception e) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            Log.e(TAG, "Unable to save " + target.getName(), e);
        }
    }

    public void flushAutoSave() {
        if (destroyed) {
            return;
        }
        if (pendingAutoSave != null) {
            mainHandler.removeCallbacks(pendingAutoSave);
            pendingAutoSave = null;
        }
        Future<?> save = saveSnapshot(currentConfigName);
        if (save != null) {
            try {
                save.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.e(TAG, "Timed out flushing config", e);
            }
        }
    }

    public void destroy() {
        flushAutoSave();
        destroyed = true;
        keyView.getKeyManager().setOnDataChangedCallback(null);
        mainHandler.removeCallbacksAndMessages(null);
        synchronized (pendingAssetRestores) {
            for (PortableConfigAssets.RestoreSession session : pendingAssetRestores) {
                session.rollback();
            }
            pendingAssetRestores.clear();
        }
        fileExecutor.shutdown();
    }

    private void ensureDefaultExists() {
        File file = configFile(DEFAULT_CONFIG_NAME);
        if (file.exists()) {
            return;
        }

        PortableConfigAssets.RestoreSession restoreSession = null;
        try (InputStream input = activity.getAssets().open(BUNDLED_DEFAULT_ASSET)) {
            JSONObject root = new JSONObject(readLimited(input));
            restoreSession = PortableConfigAssets.restore(activity, root);
            writeAtomic(file, root.toString(2));
            if (!file.exists() || !applyConfig(root, DEFAULT_CONFIG_NAME, false)) {
                throw new IllegalStateException("Unable to apply bundled default config");
            }
            restoreSession.commit();
            return;
        } catch (Exception e) {
            if (restoreSession != null) {
                restoreSession.rollback();
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            Log.e(TAG, "Unable to install bundled default config", e);
        }

        String snapshot = createSnapshot();
        if (snapshot != null) {
            writeAtomic(file, snapshot);
        }
    }

    private void ensureBundledPresets() {
        SharedPreferences prefs = activity.getSharedPreferences("kv_config_prefs", Context.MODE_PRIVATE);
        if (prefs.getInt(PRESET_VERSION_KEY, 0) >= BUNDLED_PRESET_VERSION) return;

        boolean complete = true;
        for (String[] preset : BUNDLED_PRESETS) {
            File target = configFile(preset[0]);
            if (target.exists()) continue;
            PortableConfigAssets.RestoreSession restoreSession = null;
            try (InputStream input = activity.getAssets().open(preset[1])) {
                JSONObject root = new JSONObject(readLimited(input));
                root.remove("globalSettings");
                restoreSession = PortableConfigAssets.restore(activity, root);
                writeAtomic(target, root.toString(2));
                if (!target.isFile()) throw new IllegalStateException("Unable to install " + preset[0]);
                restoreSession.commit();
            } catch (Exception e) {
                complete = false;
                if (restoreSession != null) restoreSession.rollback();
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                Log.e(TAG, "Unable to install bundled preset " + preset[0], e);
            }
        }
        if (complete) prefs.edit().putInt(PRESET_VERSION_KEY, BUNDLED_PRESET_VERSION).apply();
    }

    private static String bundledPresetAsset(String name) {
        for (String[] preset : BUNDLED_PRESETS) {
            if (preset[0].equals(name)) return preset[1];
        }
        return null;
    }

    public static String[] bundledPresetNames() {
        String[] names = new String[BUNDLED_PRESETS.length];
        for (int index = 0; index < BUNDLED_PRESETS.length; index++) {
            names[index] = BUNDLED_PRESETS[index][0];
        }
        return names;
    }

    private void saveCurrentConfigName(String name) {
        currentConfigName = name;
        SharedPreferences prefs = activity.getSharedPreferences("kv_config_prefs", Context.MODE_PRIVATE);
        prefs.edit().putString(CURRENT_CONFIG_KEY, name).apply();
    }

    private File configFile(String name) {
        return new File(configDir, name + CONFIG_EXTENSION);
    }

    private boolean isValidConfigName(String name) {
        return name != null
                && !name.trim().isEmpty()
                && name.length() <= 64
                && !name.equals(".")
                && !name.equals("..")
                && !name.matches(".*[\\\\/:*?\"<>|].*")
                && name.equals(name.trim());
    }

    private void refreshConfigList() {
        configList.clear();
        File[] files = configDir.listFiles((dir, name) -> name.endsWith(CONFIG_EXTENSION));
        if (files != null) {
            List<String> names = new ArrayList<>();
            for (File file : files) {
                String fileName = file.getName();
                String name = fileName.substring(0, fileName.length() - CONFIG_EXTENSION.length());
                if (isValidConfigName(name)) {
                    names.add(name);
                }
            }
            List<String> orderedNames = orderedConfigNames(names);
            for (String name : orderedNames) {
                configList.add(new ConfigItem(name, name.equals(currentConfigName)));
            }
        }
        adapter.notifyDataSetChanged();
    }

    private List<String> orderedConfigNames(List<String> availableNames) {
        List<String> available = new ArrayList<>(availableNames);
        List<String> result = new ArrayList<>();
        SharedPreferences prefs = activity.getSharedPreferences("kv_config_prefs", Context.MODE_PRIVATE);
        String stored = prefs.getString(CONFIG_ORDER_KEY, "");
        try {
            JSONArray order = new JSONArray(stored);
            for (int index = 0; index < order.length(); index++) {
                String name = order.optString(index, "");
                if (available.remove(name)) result.add(name);
            }
        } catch (Exception ignored) { }

        if (stored == null || stored.isEmpty()) {
            for (String[] preset : BUNDLED_PRESETS) {
                if (available.remove(preset[0])) result.add(preset[0]);
            }
            if (available.remove(DEFAULT_CONFIG_NAME)) result.add(DEFAULT_CONFIG_NAME);
        }
        Collections.sort(available, String.CASE_INSENSITIVE_ORDER);
        result.addAll(available);
        return result;
    }

    private void saveConfigOrder(List<String> names) {
        JSONArray order = new JSONArray();
        for (String name : names) order.put(name);
        activity.getSharedPreferences("kv_config_prefs", Context.MODE_PRIVATE)
                .edit().putString(CONFIG_ORDER_KEY, order.toString()).apply();
    }

    private void moveConfig(String name, int delta) {
        List<String> names = new ArrayList<>();
        for (ConfigItem item : configList) names.add(item.name);
        int index = names.indexOf(name);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= names.size()) return;
        Collections.swap(names, index, target);
        saveConfigOrder(names);
        refreshConfigList();
    }

    private void loadConfigFromFile(String name) {
        if (!isValidConfigName(name)) {
            return;
        }
        fileExecutor.submit(() -> {
            try {
                JSONObject root = new JSONObject(readLimited(new FileInputStream(configFile(name))));
                mainHandler.post(() -> {
                    if (!destroyed) applyConfig(root, name, false);
                });
            } catch (Exception e) {
                showError("Unable to load config", e);
            }
        });
    }

    private boolean applyConfig(JSONObject root, String name, boolean saveNormalized) {
        if (destroyed) return false;
        try {
            suppressAutoSave = true;
            root.remove("globalSettings");
            float density = activity.getResources().getDisplayMetrics().density;
            ConfigCodec.GridAlignmentResult gridAlignment =
                    ConfigCodec.apply(root, keyView.getKeyManager(), density);
            keyView.clearSelection();
            keyView.refreshGlobalSettings();
            saveCurrentConfigName(name);
            refreshConfigList();
            if (gridAlignment.changed()) {
                Log.i(TAG, "Aligned loaded config to grid: offsetX=" + gridAlignment.offsetX
                        + ", offsetY=" + gridAlignment.offsetY
                        + ", residualKeys=" + gridAlignment.individuallyAdjustedKeyCount);
            }
            if (saveNormalized || gridAlignment.changed()) {
                saveSnapshot(name);
            }
            if (listener != null) {
                try {
                    listener.onConfigLoaded(name);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Config loaded callback failed", e);
                }
            }
            return true;
        } catch (Exception e) {
            Toast.makeText(activity, "Invalid config: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        } finally {
            suppressAutoSave = false;
        }
    }

    public void importConfigToCurrent(Uri uri) {
        if (uri == null) {
            return;
        }
        // The user can switch configs while the document provider is still being read.
        // Keep the destination selected when the import began instead of overwriting whichever
        // config happens to be current when the background task completes.
        final String targetConfigName = currentConfigName;
        fileExecutor.submit(() -> {
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalArgumentException("Cannot open selected file");
                }
                JSONObject root = new JSONObject(readLimited(input));
                root.remove("globalSettings");
                PortableConfigAssets.RestoreSession restoreSession =
                        PortableConfigAssets.restore(activity, root);
                pendingAssetRestores.add(restoreSession);
                if (destroyed) {
                    pendingAssetRestores.remove(restoreSession);
                    restoreSession.rollback();
                    return;
                }
                mainHandler.post(() -> {
                    try {
                        if (destroyed) {
                            restoreSession.rollback();
                            return;
                        }
                        if (!targetConfigName.equals(currentConfigName)) {
                            restoreSession.rollback();
                            Toast.makeText(activity,
                                    "Import canceled because the active config changed",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (applyConfig(root, targetConfigName, true)) {
                            restoreSession.commit();
                        } else {
                            restoreSession.rollback();
                        }
                    } finally {
                        pendingAssetRestores.remove(restoreSession);
                    }
                });
            } catch (Exception e) {
                showError("Unable to import config", e);
            }
        });
    }

    public void importConfigFromUri(Uri uri) {
        importConfigToCurrent(uri);
    }

    private String readLimited(InputStream input) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > MAX_CONFIG_BYTES) {
                    throw new IllegalArgumentException("Config is larger than 72 MiB");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void showError(String prefix, Exception error) {
        Log.e(TAG, prefix, error);
        mainHandler.post(() -> {
            if (!destroyed) {
                Toast.makeText(activity, prefix + ": " + error.getMessage(), Toast.LENGTH_LONG)
                        .show();
            }
        });
    }

    private void showCreateConfigDialog() {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Config name");
        new AlertDialog.Builder(activity)
                .setTitle("New config")
                .setView(input)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!isValidConfigName(name)) {
                        Toast.makeText(activity, "Use a 1-64 character file-safe name", Toast.LENGTH_SHORT).show();
                    } else if (configFile(name).exists()) {
                        Toast.makeText(activity, "That config already exists", Toast.LENGTH_SHORT).show();
                    } else {
                        String snapshot = createSnapshot();
                        if (snapshot == null) {
                            Toast.makeText(activity, "Unable to create config", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        fileExecutor.submit(() -> {
                            File target = configFile(name);
                            writeAtomic(target, snapshot);
                            mainHandler.post(() -> {
                                if (destroyed || !target.isFile()) {
                                    if (!destroyed) Toast.makeText(activity,
                                            "Unable to create config", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                saveCurrentConfigName(name);
                                refreshConfigList();
                                if (listener != null) listener.onConfigLoaded(name);
                            });
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRenameDialog(String oldName) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(oldName);
        input.selectAll();
        new AlertDialog.Builder(activity)
                .setTitle("Rename config")
                .setView(input)
                .setPositiveButton("Rename", (dialog, which) -> renameConfig(oldName,
                        input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renameConfig(String oldName, String newName) {
        if (oldName.equals(DEFAULT_CONFIG_NAME)) {
            Toast.makeText(activity, "The default config cannot be renamed", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isValidConfigName(newName) || configFile(newName).exists()) {
            Toast.makeText(activity, "Invalid or duplicate config name", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!configFile(oldName).renameTo(configFile(newName))) {
            Toast.makeText(activity, "Rename failed", Toast.LENGTH_SHORT).show();
            return;
        }
        if (oldName.equals(currentConfigName)) {
            saveCurrentConfigName(newName);
        }
        List<String> order = new ArrayList<>();
        for (ConfigItem item : configList) order.add(item.name.equals(oldName) ? newName : item.name);
        saveConfigOrder(order);
        refreshConfigList();
        if (listener != null) {
            listener.onConfigRenamed(oldName, newName);
        }
    }

    private void showDeleteConfirmDialog(String name) {
        new AlertDialog.Builder(activity)
                .setTitle("Delete config")
                .setMessage("Delete ‘" + name + "’?")
                .setPositiveButton("Delete", (dialog, which) -> deleteConfig(name))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteConfig(String name) {
        if (name.equals(DEFAULT_CONFIG_NAME)) {
            Toast.makeText(activity, "The default config cannot be deleted", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!configFile(name).delete()) {
            Toast.makeText(activity, "Delete failed", Toast.LENGTH_SHORT).show();
            return;
        }
        if (name.equals(currentConfigName)) {
            loadConfigFromFile(DEFAULT_CONFIG_NAME);
        }
        List<String> order = new ArrayList<>();
        for (ConfigItem item : configList) if (!item.name.equals(name)) order.add(item.name);
        saveConfigOrder(order);
        refreshConfigList();
        if (listener != null) {
            listener.onConfigDeleted(name);
        }
    }

    public void restoreCurrentFromBundledPreset(String presetName) {
        String assetName = bundledPresetAsset(presetName);
        if (assetName == null) return;
        String targetConfigName = currentConfigName;
        flushAutoSave();
        fileExecutor.submit(() -> {
            PortableConfigAssets.RestoreSession restoreSession = null;
            try (InputStream input = activity.getAssets().open(assetName)) {
                JSONObject root = new JSONObject(readLimited(input));
                root.remove("globalSettings");
                restoreSession = PortableConfigAssets.restore(activity, root);
                writeAtomic(configFile(targetConfigName), root.toString(2));
                PortableConfigAssets.RestoreSession completedSession = restoreSession;
                mainHandler.post(() -> {
                    if (destroyed) {
                        completedSession.rollback();
                        return;
                    }
                    if (targetConfigName.equals(currentConfigName)) {
                        applyConfig(root, targetConfigName, false);
                    } else {
                        refreshConfigList();
                    }
                    completedSession.commit();
                    Toast.makeText(activity, "当前配置已恢复为：" + presetName,
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                if (restoreSession != null) restoreSession.rollback();
                showError("Unable to restore preset", e);
            }
        });
    }

    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        // Import is owned by GlobalSettingsPanel. Kept for API compatibility.
    }

    public void showPanel() {
        refreshConfigList();
        panelView.setVisibility(View.VISIBLE);
    }

    private void hidePanel() {
        panelView.setVisibility(View.GONE);
    }

    public void togglePanel() {
        if (panelView.getVisibility() == View.VISIBLE) {
            hidePanel();
        } else {
            showPanel();
        }
    }

    public void refresh() {
        refreshConfigList();
    }

    private void applyDarkMode() {
        boolean dark = globalSettings.darkModeEnabled;
        panelView.setBackgroundColor(dark ? 0xFF2C2C2C : 0xFFF5F5F5);
        recyclerView.setBackgroundColor(dark ? 0xFF3C3C3C : 0xFFFFFFFF);
        updateTextColors(panelView, dark ? 0xFFFFFFFF : 0xFF333333);
    }

    public void onDarkModeChanged() {
        animateDarkMode(globalSettings.darkModeEnabled);
        adapter.notifyDataSetChanged();
    }

    private void animateDarkMode(boolean dark) {
        int panelStart = dark ? 0xFFF5F5F5 : 0xFF2C2C2C;
        int panelEnd = dark ? 0xFF2C2C2C : 0xFFF5F5F5;
        int listStart = dark ? 0xFFFFFFFF : 0xFF3C3C3C;
        int listEnd = dark ? 0xFF3C3C3C : 0xFFFFFFFF;
        int textStart = dark ? 0xFF333333 : 0xFFFFFFFF;
        int textEnd = dark ? 0xFFFFFFFF : 0xFF333333;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(300);
        animator.addUpdateListener(value -> {
            float fraction = (float) value.getAnimatedValue();
            panelView.setBackgroundColor(blend(panelStart, panelEnd, fraction));
            recyclerView.setBackgroundColor(blend(listStart, listEnd, fraction));
            updateTextColors(panelView, blend(textStart, textEnd, fraction));
        });
        animator.start();
    }

    private static int blend(int from, int to, float fraction) {
        int a = Math.round(android.graphics.Color.alpha(from)
                + (android.graphics.Color.alpha(to) - android.graphics.Color.alpha(from)) * fraction);
        int r = Math.round(android.graphics.Color.red(from)
                + (android.graphics.Color.red(to) - android.graphics.Color.red(from)) * fraction);
        int g = Math.round(android.graphics.Color.green(from)
                + (android.graphics.Color.green(to) - android.graphics.Color.green(from)) * fraction);
        int b = Math.round(android.graphics.Color.blue(from)
                + (android.graphics.Color.blue(to) - android.graphics.Color.blue(from)) * fraction);
        return android.graphics.Color.argb(a, r, g, b);
    }

    private static void updateTextColors(View view, int color) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(color);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                updateTextColors(group.getChildAt(index), color);
            }
        }
    }

    private final class ConfigAdapter extends RecyclerView.Adapter<ConfigViewHolder> {
        @Override
        public ConfigViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.config_list_item, parent, false);
            return new ConfigViewHolder(view);
        }

        @Override
        public void onBindViewHolder(ConfigViewHolder holder, int position) {
            ConfigItem item = configList.get(position);
            holder.name.setText(item.name.equals(DEFAULT_CONFIG_NAME)
                    ? activity.getString(R.string.default_config_name)
                    : item.name);
            holder.name.setTextColor(globalSettings.darkModeEnabled ? 0xFFFFFFFF : 0xFF333333);
            holder.itemView.setBackgroundColor(globalSettings.darkModeEnabled
                    ? 0xFF3C3C3C : 0xFFFFFFFF);
            holder.more.setColorFilter(globalSettings.darkModeEnabled
                    ? 0xFFFFFFFF : 0xFF666666);
            holder.indicator.setVisibility(item.current ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(v -> {
                if (!item.current) {
                    flushAutoSave();
                    loadConfigFromFile(item.name);
                }
            });
            holder.more.setOnClickListener(v -> {
                PopupMenu menu = new PopupMenu(activity, holder.more);
                menu.getMenu().add("上移");
                menu.getMenu().add("下移");
                menu.getMenu().add("重命名");
                menu.getMenu().add("删除");
                menu.setOnMenuItemClickListener(menuItem -> {
                    switch (menuItem.getTitle().toString()) {
                        case "上移": moveConfig(item.name, -1); break;
                        case "下移": moveConfig(item.name, 1); break;
                        case "重命名": showRenameDialog(item.name); break;
                        case "删除": showDeleteConfirmDialog(item.name); break;
                    }
                    return true;
                });
                menu.show();
            });
        }

        @Override
        public int getItemCount() {
            return configList.size();
        }
    }

    private static final class ConfigViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final View indicator;
        final ImageButton more;

        ConfigViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.configNameText);
            indicator = itemView.findViewById(R.id.currentIndicator);
            more = itemView.findViewById(R.id.configMoreBtn);
            View edit = itemView.findViewById(R.id.configNameEdit);
            edit.setVisibility(View.GONE);
        }
    }

    private static final class ConfigItem {
        final String name;
        final boolean current;

        ConfigItem(String name, boolean current) {
            this.name = name;
            this.current = current;
        }
    }
}
