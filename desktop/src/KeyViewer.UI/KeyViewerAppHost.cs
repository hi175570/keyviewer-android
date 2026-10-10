using KeyViewer.Abstractions;
using KeyViewer.Core.Config;
using KeyViewer.Core.Input;
using KeyViewer.Core.Models;
using KeyViewer.ModFramework;
using System.Text.Json;

namespace KeyViewer.UI;

public sealed class KeyViewerAppHost : IDisposable
{
    private const string BundledPresetVersion = "2";
    private readonly object _saveGate = new();
    private readonly Timer _saveTimer;
    private readonly string _activeConfigPath;
    private readonly string _globalSettingsPath;
    private readonly string _configOrderPath;
    private readonly string _bundledPresetVersionPath;
    private bool _disposed;
    private string _currentConfigName = "default";

    public KeyViewerAppHost()
    {
        DataRoot = GetDataRoot();
        AssetRoot = Path.Combine(DataRoot, "Assets");
        PluginRoot = Path.Combine(DataRoot, "Plugins");
        FrameworkRoot = Path.Combine(AppContext.BaseDirectory, "BepInEx");
        FrameworkPatchRoot = Path.Combine(FrameworkRoot, "patchers");
        FrameworkPluginRoot = Path.Combine(FrameworkRoot, "plugins");
        ConfigRoot = Path.Combine(DataRoot, "Configs");
        ConfigPath = Path.Combine(DataRoot, "current.kv.json");
        _activeConfigPath = Path.Combine(DataRoot, "active-config.txt");
        _globalSettingsPath = Path.Combine(DataRoot, "global-settings.json");
        _configOrderPath = Path.Combine(DataRoot, "config-order.json");
        _bundledPresetVersionPath = Path.Combine(DataRoot, "bundled-presets.version");
        Directory.CreateDirectory(DataRoot);
        Directory.CreateDirectory(AssetRoot);
        Directory.CreateDirectory(PluginRoot);
        Directory.CreateDirectory(ConfigRoot);

        var freshConfigInstall = !Directory.EnumerateFiles(ConfigRoot, "*.json").Any();
        EnsureDefaultConfiguration();
        EnsureBundledPresets();
        _currentConfigName = freshConfigInstall
            ? ConfigSerializer.BundledPresets[0].Name
            : LoadActiveConfigurationName();
        if (freshConfigInstall) SetActiveConfiguration(_currentConfigName);
        Document = LoadInitialDocument();
        Document.GlobalSettings = ConfigSerializer.LoadGlobalSettings(
            _globalSettingsPath, Document.GlobalSettings);
        var initialGridAlignment = KeyViewerLayoutGrid.AlignDocument(Document);
        ExtractEmbeddedAssets(Document);
        Router = new InputRouter(Document);
        State = new KeyStateService(Document);
        Runtime = new KeyViewerRuntime(Router, State);
        Services = new ServiceRegistry();
        Services.Add(typeof(KeyViewerAppHost), this);
        Services.Add(typeof(KeyViewerDocument), Document);
        Services.Add(typeof(InputRouter), Router);
        Services.Add(typeof(KeyStateService), State);
        SurfaceEffects = new KeySurfaceEffectRegistry();
        Services.Add(typeof(IKeySurfaceEffectRegistry), SurfaceEffects);
        Services.Add(typeof(KeySurfaceEffectRegistry), SurfaceEffects);
        PluginHost = new PluginHost(Router, Services, Path.Combine(DataRoot, "PluginData"), Log);
        _saveTimer = new Timer(_ => SaveNowSafe(), null, Timeout.Infinite, Timeout.Infinite);
        // Loading and serializing a portable configuration can include embedded fonts and images.
        // Do not write the same large document twice on the UI thread before the first frame.
        // A normal shutdown still flushes synchronously, while this delayed save persists startup
        // normalization (grid alignment and the total-count recount) when the app stays open.
        ScheduleSave(initialGridAlignment.Changed
            ? TimeSpan.FromSeconds(2)
            : TimeSpan.FromSeconds(10));

        State.Changed += OnStateChanged;
        State.TransientChanged += OnTransientStateChanged;
        StartInputSources();
        if (Directory.Exists(FrameworkPatchRoot))
        {
            PluginHost.LoadDirectory(FrameworkPatchRoot);
        }
        if (Directory.Exists(FrameworkPluginRoot))
        {
            PluginHost.LoadDirectory(FrameworkPluginRoot);
        }
        PluginHost.LoadDirectory(PluginRoot);
    }

    public string DataRoot { get; }
    public string AssetRoot { get; }
    public string PluginRoot { get; }
    public string FrameworkRoot { get; }
    public string FrameworkPatchRoot { get; }
    public string FrameworkPluginRoot { get; }
    public string ConfigRoot { get; }
    public string ConfigPath { get; }
    public string CurrentConfigName => _currentConfigName;
    public KeyViewerDocument Document { get; private set; }
    public InputRouter Router { get; }
    public KeyStateService State { get; }
    public KeyViewerRuntime Runtime { get; }
    public ServiceRegistry Services { get; }
    public KeySurfaceEffectRegistry SurfaceEffects { get; }
    public PluginHost PluginHost { get; }
    public event Action? Changed;
    public event Action<string>? LogMessage;

    public void AddInputSource(IInputSource source) => TryAddSource(source);

    public void NotifyEdited()
    {
        ConfigSerializer.Validate(Document);
        ScheduleSave();
        Changed?.Invoke();
    }

    public GridAlignmentResult ReplaceDocument(KeyViewerDocument document)
    {
        ArgumentNullException.ThrowIfNull(document);
        var globalSettings = Document.GlobalSettings;
        document.GlobalSettings = globalSettings;
        ConfigSerializer.Validate(document);
        var gridAlignment = KeyViewerLayoutGrid.AlignDocument(document);
        ExtractEmbeddedAssets(document);
        Document = document;
        Router.Document = document;
        State.Document = document;
        Services.Add(typeof(KeyViewerDocument), document);
        ScheduleSave();
        Changed?.Invoke();
        return gridAlignment;
    }

    public string? ResolveAsset(string? storedName)
    {
        if (string.IsNullOrWhiteSpace(storedName))
        {
            return null;
        }

        var safeName = Path.GetFileName(storedName);
        if (!safeName.Equals(storedName, StringComparison.Ordinal))
        {
            return null;
        }

        var path = Path.Combine(AssetRoot, safeName);
        if (File.Exists(path))
        {
            return path;
        }

        var asset = Document.EmbeddedAssets.Items.FirstOrDefault(item =>
            item.Name.Equals(safeName, StringComparison.OrdinalIgnoreCase));
        return asset is null ? null : ConfigSerializer.ExtractAsset(asset, AssetRoot);
    }

    public async Task<string> ImportAssetAsync(Stream source, string extension, string type)
    {
        extension = extension.StartsWith('.') ? extension : "." + extension;
        var name = Guid.NewGuid().ToString("N") + extension.ToLowerInvariant();
        var path = Path.Combine(AssetRoot, name);
        await using (var target = File.Create(path))
        {
            await source.CopyToAsync(target).ConfigureAwait(false);
            if (target.Length > ConfigSerializer.MaxAssetBytes)
            {
                throw new InvalidDataException("单个素材不能超过 12 MiB");
            }
        }

        try
        {
            ConfigSerializer.EmbedAsset(Document, path, type);
            ScheduleSave();
            return name;
        }
        catch
        {
            File.Delete(path);
            throw;
        }
    }

    public PluginStatus InstallPlugin(string sourcePath)
    {
        var target = Path.Combine(PluginRoot, Path.GetFileName(sourcePath));
        File.Copy(sourcePath, target, true);
        return PluginHost.LoadAssembly(target);
    }

    public async Task<PluginStatus> InstallPluginAsync(Stream source, string fileName)
    {
        var target = Path.Combine(PluginRoot, Path.GetFileName(fileName));
        await using (var output = File.Create(target))
        {
            await source.CopyToAsync(output).ConfigureAwait(false);
        }
        return PluginHost.LoadAssembly(target);
    }

    public void SaveNow()
    {
        lock (_saveGate)
        {
            ConfigSerializer.SaveGlobalSettings(_globalSettingsPath, Document.GlobalSettings);
            ConfigSerializer.SaveConfiguration(ConfigPath, Document);
            ConfigSerializer.SaveConfiguration(NamedConfigurationPath(_currentConfigName), Document);
        }
    }

    public IReadOnlyList<string> ListNamedConfigurations()
    {
        var available = Directory.EnumerateFiles(ConfigRoot, "*.json", SearchOption.TopDirectoryOnly)
            .Select(Path.GetFileNameWithoutExtension)
            .Where(name => !string.IsNullOrWhiteSpace(name))
            .Cast<string>()
            .ToList();
        var result = new List<string>();
        foreach (var name in LoadConfigurationOrder())
        {
            var actual = available.FirstOrDefault(item => item.Equals(name, StringComparison.OrdinalIgnoreCase));
            if (actual is not null)
            {
                result.Add(actual);
                available.Remove(actual);
            }
        }
        if (!File.Exists(_configOrderPath))
        {
            foreach (var preset in ConfigSerializer.BundledPresets)
            {
                var actual = available.FirstOrDefault(item => item.Equals(preset.Name, StringComparison.OrdinalIgnoreCase));
                if (actual is not null) { result.Add(actual); available.Remove(actual); }
            }
            var defaultName = available.FirstOrDefault(item => item.Equals("default", StringComparison.OrdinalIgnoreCase));
            if (defaultName is not null) { result.Add(defaultName); available.Remove(defaultName); }
        }
        result.AddRange(available.OrderBy(name => name, StringComparer.CurrentCultureIgnoreCase));
        return result;
    }

    public void MoveNamedConfiguration(string name, int delta)
    {
        var names = ListNamedConfigurations().ToList();
        var index = names.FindIndex(item => item.Equals(name, StringComparison.OrdinalIgnoreCase));
        var target = index + delta;
        if (index < 0 || target < 0 || target >= names.Count) return;
        (names[index], names[target]) = (names[target], names[index]);
        SaveConfigurationOrder(names);
    }

    public void RestoreCurrentFromBundledPreset(string presetName)
    {
        SaveNow();
        var document = ConfigSerializer.LoadEmbeddedPreset(presetName);
        KeyViewerLayoutGrid.AlignDocument(document);
        ConfigSerializer.SaveConfiguration(NamedConfigurationPath(_currentConfigName), document);
        ReplaceDocument(document);
    }

    public void CreateNamedConfiguration(string name)
    {
        var path = NamedConfigurationPath(name);
        if (File.Exists(path))
        {
            throw new IOException($"配置“{name}”已经存在");
        }
        lock (_saveGate)
        {
            ConfigSerializer.SaveConfiguration(path, Document);
            SetActiveConfiguration(name);
            SaveConfigurationOrder(ListNamedConfigurations().Append(name).Distinct(StringComparer.OrdinalIgnoreCase));
        }
    }

    public GridAlignmentResult LoadNamedConfiguration(string name)
    {
        SaveNow();
        var normalized = NormalizeConfigurationName(name);
        var document = ConfigSerializer.Load(NamedConfigurationPath(normalized));
        SetActiveConfiguration(normalized);
        return ReplaceDocument(document);
    }

    public void DeleteNamedConfiguration(string name)
    {
        var normalized = NormalizeConfigurationName(name);
        if (normalized.Equals("default", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("默认配置不能删除");
        }
        var deletingCurrent = normalized.Equals(_currentConfigName, StringComparison.OrdinalIgnoreCase);
        File.Delete(NamedConfigurationPath(normalized));
        SaveConfigurationOrder(ListNamedConfigurations().Where(item =>
            !item.Equals(normalized, StringComparison.OrdinalIgnoreCase)));
        if (deletingCurrent)
        {
            SetActiveConfiguration("default");
            ReplaceDocument(ConfigSerializer.Load(NamedConfigurationPath("default")));
        }
    }

    public void RenameNamedConfiguration(string oldName, string newName)
    {
        var normalizedOld = NormalizeConfigurationName(oldName);
        var normalizedNew = NormalizeConfigurationName(newName);
        if (normalizedOld.Equals("default", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("默认配置不能重命名");
        }
        var source = NamedConfigurationPath(normalizedOld);
        var target = NamedConfigurationPath(normalizedNew);
        if (File.Exists(target))
        {
            throw new IOException($"配置“{newName}”已经存在");
        }
        var order = ListNamedConfigurations().ToList();
        File.Move(source, target);
        SaveConfigurationOrder(order.Select(item =>
            item.Equals(normalizedOld, StringComparison.OrdinalIgnoreCase) ? normalizedNew : item));
        if (_currentConfigName.Equals(normalizedOld, StringComparison.OrdinalIgnoreCase))
        {
            SetActiveConfiguration(normalizedNew);
        }
    }

    private string NamedConfigurationPath(string name)
        => Path.Combine(ConfigRoot, NormalizeConfigurationName(name) + ".json");

    private static string NormalizeConfigurationName(string name)
    {
        name = name.Trim();
        if (name.EndsWith(".json", StringComparison.OrdinalIgnoreCase))
        {
            name = name[..^5];
        }
        if (string.IsNullOrWhiteSpace(name)
            || name.Length > 64
            || name is "." or ".."
            || name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0
            || name.IndexOfAny(['\\', '/', ':', '*', '?', '"', '<', '>', '|']) >= 0
            || !Path.GetFileName(name).Equals(name, StringComparison.Ordinal))
        {
            throw new ArgumentException("配置名称不能为空，也不能包含路径或特殊字符", nameof(name));
        }
        return name;
    }

    private KeyViewerDocument LoadInitialDocument()
    {
        try
        {
            var activePath = NamedConfigurationPath(_currentConfigName);
            if (File.Exists(activePath))
            {
                return ConfigSerializer.Load(activePath);
            }
            if (File.Exists(ConfigPath))
            {
                return ConfigSerializer.Load(ConfigPath);
            }
            return ConfigSerializer.LoadEmbeddedDefault();
        }
        catch (Exception exception)
        {
            Log($"现有配置无法读取，已回退到内置默认配置：{exception.Message}");
            return ConfigSerializer.LoadEmbeddedDefault();
        }
    }

    private void EnsureDefaultConfiguration()
    {
        var defaultPath = NamedConfigurationPath("default");
        if (File.Exists(defaultPath))
        {
            return;
        }

        KeyViewerDocument initial;
        try
        {
            initial = File.Exists(ConfigPath)
                ? ConfigSerializer.Load(ConfigPath)
                : ConfigSerializer.LoadEmbeddedDefault();
        }
        catch
        {
            initial = ConfigSerializer.LoadEmbeddedDefault();
        }
        KeyViewerLayoutGrid.AlignDocument(initial);
        ConfigSerializer.SaveConfiguration(defaultPath, initial);
    }

    private void EnsureBundledPresets()
    {
        if (File.Exists(_bundledPresetVersionPath)
            && File.ReadAllText(_bundledPresetVersionPath).Trim() == BundledPresetVersion)
        {
            return;
        }
        foreach (var preset in ConfigSerializer.BundledPresets)
        {
            var path = NamedConfigurationPath(preset.Name);
            var canonicalPreset = ConfigSerializer.LoadEmbeddedPreset(preset.Name);
            if (!File.Exists(path))
            {
                KeyViewerLayoutGrid.AlignDocument(canonicalPreset);
                ConfigSerializer.SaveConfiguration(path, canonicalPreset);
                continue;
            }

            try
            {
                var existing = ConfigSerializer.Load(path);
                if (KeyViewerLayoutGrid.RepairBundledPresetGridMigration(existing, canonicalPreset) > 0)
                {
                    ConfigSerializer.SaveConfiguration(path, existing);
                }
            }
            catch (Exception exception)
            {
                Log($"Bundled preset migration skipped for {preset.Name}: {exception.Message}");
            }
        }
        File.WriteAllText(_bundledPresetVersionPath, BundledPresetVersion);
    }

    private IReadOnlyList<string> LoadConfigurationOrder()
    {
        try
        {
            return File.Exists(_configOrderPath)
                ? JsonSerializer.Deserialize<string[]>(File.ReadAllText(_configOrderPath)) ?? []
                : [];
        }
        catch
        {
            return [];
        }
    }

    private void SaveConfigurationOrder(IEnumerable<string> names)
    {
        File.WriteAllText(_configOrderPath, JsonSerializer.Serialize(
            names.Distinct(StringComparer.OrdinalIgnoreCase).ToArray()));
    }

    private string LoadActiveConfigurationName()
    {
        try
        {
            if (File.Exists(_activeConfigPath))
            {
                var name = NormalizeConfigurationName(File.ReadAllText(_activeConfigPath));
                if (File.Exists(NamedConfigurationPath(name)))
                {
                    return name;
                }
            }
        }
        catch
        {
        }
        return "default";
    }

    private void SetActiveConfiguration(string name)
    {
        _currentConfigName = NormalizeConfigurationName(name);
        File.WriteAllText(_activeConfigPath, _currentConfigName);
    }

    private void StartInputSources()
    {
        if (OperatingSystem.IsWindows())
        {
            TryAddSource(new BridgePipeInputSource(Document.GlobalSettings.BridgePipeName));
            TryAddSource(new WindowsGlobalKeyboardSource());
        }
        else if (OperatingSystem.IsLinux())
        {
            TryAddSource(new BridgePipeInputSource(Document.GlobalSettings.BridgePipeName));
            var configured = Document.GlobalSettings.LinuxInputDevice
                .Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            TryAddSource(new LinuxEvdevInputSource(configured));
        }
    }

    private void TryAddSource(IInputSource source)
    {
        try
        {
            Runtime.AddSource(source);
            Log($"输入源：{source.DisplayName} {(source.IsRunning ? "已启动" : "等待权限/平台")}");
        }
        catch (Exception exception)
        {
            source.Dispose();
            Log($"输入源启动失败 {source.DisplayName}：{exception.Message}");
        }
    }

    private void ExtractEmbeddedAssets(KeyViewerDocument document)
    {
        foreach (var asset in document.EmbeddedAssets.Items)
        {
            var path = Path.Combine(AssetRoot, Path.GetFileName(asset.Name));
            if (!File.Exists(path))
            {
                ConfigSerializer.ExtractAsset(asset, AssetRoot);
            }
        }
    }

    private void OnStateChanged()
    {
        ScheduleSave();
        Changed?.Invoke();
    }

    private void OnTransientStateChanged() => Changed?.Invoke();

    private void ScheduleSave() => ScheduleSave(TimeSpan.FromMilliseconds(500));

    private void ScheduleSave(TimeSpan delay) => _saveTimer.Change(delay, Timeout.InfiniteTimeSpan);

    private void SaveNowSafe()
    {
        try
        {
            SaveNow();
        }
        catch (Exception exception)
        {
            Log($"自动保存失败：{exception.Message}");
        }
    }

    private void Log(string message)
    {
        System.Diagnostics.Trace.WriteLine($"[KeyViewer] {message}");
        LogMessage?.Invoke(message);
    }

    private static string GetDataRoot()
    {
        var overridePath = Environment.GetEnvironmentVariable("KEYVIEWER_DATA_ROOT");
        if (!string.IsNullOrWhiteSpace(overridePath))
        {
            return Path.GetFullPath(overridePath);
        }

        if (OperatingSystem.IsLinux())
        {
            var xdg = Environment.GetEnvironmentVariable("XDG_CONFIG_HOME");
            return Path.Combine(
                string.IsNullOrWhiteSpace(xdg)
                    ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".config")
                    : xdg,
                "KeyViewer");
        }

        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "KeyViewer");
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        State.Changed -= OnStateChanged;
        State.TransientChanged -= OnTransientStateChanged;
        _saveTimer.Change(Timeout.Infinite, Timeout.Infinite);
        SaveNowSafe();
        PluginHost.Dispose();
        Runtime.Dispose();
        State.Dispose();
        _saveTimer.Dispose();
    }
}
