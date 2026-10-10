using System.Reflection;
using System.Runtime.Loader;
using KeyViewer.Abstractions;
using KeyViewer.Core.Input;

namespace KeyViewer.ModFramework;

public sealed class PluginHost : IDisposable
{
    public const string ApiVersion = "1.0";

    private readonly InputRouter _router;
    private readonly ServiceRegistry _services;
    private readonly string _dataRoot;
    private readonly Action<string> _log;
    private readonly List<LoadedPlugin> _plugins = [];
    private bool _disposed;

    public PluginHost(InputRouter router, ServiceRegistry services, string dataRoot, Action<string>? log = null)
    {
        _router = router ?? throw new ArgumentNullException(nameof(router));
        _services = services ?? throw new ArgumentNullException(nameof(services));
        _dataRoot = dataRoot ?? throw new ArgumentNullException(nameof(dataRoot));
        _log = log ?? (_ => { });
    }

    public IReadOnlyList<PluginStatus> Plugins => _plugins.Select(plugin => plugin.Status).ToArray();

    public void LoadDirectory(string pluginDirectory)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        Directory.CreateDirectory(pluginDirectory);
        foreach (var assemblyPath in Directory.EnumerateFiles(pluginDirectory, "*.dll", SearchOption.TopDirectoryOnly))
        {
            if (_plugins.Any(plugin => plugin.AssemblyPath.Equals(assemblyPath, StringComparison.OrdinalIgnoreCase)))
            {
                continue;
            }

            LoadAssembly(assemblyPath);
        }
    }

    public PluginStatus LoadAssembly(string assemblyPath)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        assemblyPath = Path.GetFullPath(assemblyPath);
        var context = new PluginLoadContext(assemblyPath);
        IKeyViewerPlugin? instance = null;
        try
        {
            var assembly = context.LoadFromAssemblyPath(assemblyPath);
            var pluginType = assembly.GetTypes().SingleOrDefault(type =>
                !type.IsAbstract && typeof(IKeyViewerPlugin).IsAssignableFrom(type));
            if (pluginType is null)
            {
                throw new InvalidDataException("程序集没有唯一的 IKeyViewerPlugin 实现");
            }

            instance = (IKeyViewerPlugin?)Activator.CreateInstance(pluginType)
                ?? throw new InvalidOperationException("无法创建插件实例");
            ValidateManifest(instance.Manifest);
            if (_plugins.Any(plugin => plugin.Status.Id.Equals(
                instance.Manifest.Id, StringComparison.OrdinalIgnoreCase)))
            {
                throw new InvalidOperationException($"插件 ID 已加载：{instance.Manifest.Id}");
            }

            var registrations = new List<IDisposable>();
            var dataDirectory = Path.Combine(_dataRoot, Sanitize(instance.Manifest.Id));
            Directory.CreateDirectory(dataDirectory);
            var pluginContext = new PluginContext(
                instance.Manifest.Id,
                dataDirectory,
                _router,
                _services,
                registrations,
                _log);
            instance.Initialize(pluginContext);
            var status = new PluginStatus(
                instance.Manifest.Id,
                instance.Manifest.Name,
                instance.Manifest.Version,
                true,
                string.Empty,
                assemblyPath);
            _plugins.Add(new LoadedPlugin(assemblyPath, context, instance, registrations, status));
            _log($"[插件] 已加载 {status.Name} {status.Version}");
            return status;
        }
        catch (Exception exception)
        {
            instance?.Dispose();
            context.Unload();
            var status = new PluginStatus(
                Path.GetFileNameWithoutExtension(assemblyPath),
                Path.GetFileNameWithoutExtension(assemblyPath),
                "?",
                false,
                exception.Message,
                assemblyPath);
            _log($"[插件] 加载失败 {assemblyPath}: {exception}");
            return status;
        }
    }

    public bool Unload(string id)
    {
        var plugin = _plugins.FirstOrDefault(item =>
            item.Status.Id.Equals(id, StringComparison.OrdinalIgnoreCase));
        if (plugin is null)
        {
            return false;
        }

        _plugins.Remove(plugin);
        foreach (var registration in plugin.Registrations.AsEnumerable().Reverse())
        {
            registration.Dispose();
        }

        plugin.Instance.Dispose();
        plugin.Context.Unload();
        return true;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        foreach (var plugin in _plugins.ToArray())
        {
            Unload(plugin.Status.Id);
        }
    }

    private static void ValidateManifest(KeyViewerPluginManifest manifest)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        if (string.IsNullOrWhiteSpace(manifest.Id) || manifest.Id.Length > 100)
        {
            throw new InvalidDataException("插件 ID 为空或过长");
        }

        if (!Version.TryParse(manifest.ApiVersion, out var requested)
            || !Version.TryParse(ApiVersion, out var current)
            || requested.Major != current.Major)
        {
            throw new InvalidDataException($"插件 API {manifest.ApiVersion} 与主程序 API {ApiVersion} 不兼容");
        }

        if (!Version.TryParse(manifest.Version, out _))
        {
            throw new InvalidDataException("插件版本必须是有效的 SemVer 数字版本");
        }
    }

    private static string Sanitize(string value)
        => string.Concat(value.Select(character =>
            Path.GetInvalidFileNameChars().Contains(character) ? '_' : character));

    private sealed record LoadedPlugin(
        string AssemblyPath,
        PluginLoadContext Context,
        IKeyViewerPlugin Instance,
        List<IDisposable> Registrations,
        PluginStatus Status);

    private sealed class PluginLoadContext(string pluginPath) : AssemblyLoadContext(isCollectible: true)
    {
        private readonly AssemblyDependencyResolver _resolver = new(pluginPath);

        protected override Assembly? Load(AssemblyName assemblyName)
        {
            if (assemblyName.Name is "KeyViewer.Abstractions" or "KeyViewer.Core" or "KeyViewer.ModFramework")
            {
                return null;
            }

            var path = _resolver.ResolveAssemblyToPath(assemblyName);
            return path is null ? null : LoadFromAssemblyPath(path);
        }

        protected override nint LoadUnmanagedDll(string unmanagedDllName)
        {
            var path = _resolver.ResolveUnmanagedDllToPath(unmanagedDllName);
            return path is null ? 0 : LoadUnmanagedDllFromPath(path);
        }
    }

    private sealed class PluginContext(
        string pluginId,
        string dataDirectory,
        InputRouter router,
        ServiceRegistry services,
        List<IDisposable> registrations,
        Action<string> log) : IPluginContext
    {
        public string DataDirectory { get; } = dataDirectory;

        public IDisposable RegisterInputFilter(IInputFilter filter)
        {
            var registration = router.RegisterFilter(filter);
            registrations.Add(registration);
            return registration;
        }

        public IDisposable RegisterKeySurfaceEffect(IKeySurfaceEffect effect)
        {
            var registry = services.Get(typeof(IKeySurfaceEffectRegistry)) as IKeySurfaceEffectRegistry
                ?? throw new InvalidOperationException("主程序没有提供按键表面渲染服务");
            var registration = registry.Register(effect);
            registrations.Add(registration);
            return registration;
        }

        public IDisposable SubscribeRoutedInput(Action<KeyRouteContext> handler)
        {
            router.Routed += handler;
            var registration = new CallbackDisposable(() => router.Routed -= handler);
            registrations.Add(registration);
            return registration;
        }

        public void LogInfo(string message) => log($"[{pluginId}] {message}");
        public void LogWarning(string message) => log($"[{pluginId}] WARNING: {message}");
        public void LogError(string message, Exception? exception = null)
            => log($"[{pluginId}] ERROR: {message}{Environment.NewLine}{exception}");
        public object? GetService(Type serviceType) => services.Get(serviceType);
    }

    private sealed class CallbackDisposable(Action callback) : IDisposable
    {
        private Action? _callback = callback;
        public void Dispose() => Interlocked.Exchange(ref _callback, null)?.Invoke();
    }
}

public sealed record PluginStatus(
    string Id,
    string Name,
    string Version,
    bool IsLoaded,
    string Error,
    string AssemblyPath);
