using System;
using System.Collections.Generic;

namespace KeyViewer.Abstractions;

public enum KeyEventState
{
    Down,
    Repeat,
    Up
}

public sealed class KeyInputEvent
{
    public KeyInputEvent(
        string key,
        KeyEventState state,
        string deviceId,
        DateTimeOffset timestamp,
        bool isInjected = false)
    {
        Key = key ?? throw new ArgumentNullException(nameof(key));
        State = state;
        DeviceId = deviceId ?? string.Empty;
        Timestamp = timestamp;
        IsInjected = isInjected;
    }

    public string Key { get; }
    public KeyEventState State { get; }
    public string DeviceId { get; }
    public DateTimeOffset Timestamp { get; }
    public bool IsInjected { get; }
}

public sealed class KeyRouteContext
{
    public KeyRouteContext(KeyInputEvent input)
    {
        Input = input ?? throw new ArgumentNullException(nameof(input));
        OutputKey = input.Key;
    }

    public KeyInputEvent Input { get; }
    public string OutputKey { get; set; }
    public bool ShowInViewer { get; set; } = true;
    public bool CountPress { get; set; } = true;
    public bool ForwardToSystem { get; set; } = true;
    public bool StopProcessing { get; set; }
    public string Reason { get; set; } = "passthrough";
}

public interface IInputFilter
{
    string Id { get; }
    int Priority { get; }
    void Process(KeyRouteContext context);
}

public sealed class KeyViewerPluginManifest
{
    public KeyViewerPluginManifest(string id, string name, string version, string apiVersion = "1.0")
    {
        Id = id ?? throw new ArgumentNullException(nameof(id));
        Name = name ?? throw new ArgumentNullException(nameof(name));
        Version = version ?? throw new ArgumentNullException(nameof(version));
        ApiVersion = apiVersion ?? throw new ArgumentNullException(nameof(apiVersion));
    }

    public string Id { get; }
    public string Name { get; }
    public string Version { get; }
    public string ApiVersion { get; }
    public string Author { get; set; } = string.Empty;
    public string Description { get; set; } = string.Empty;
}

public interface IPluginContext
{
    string DataDirectory { get; }
    IDisposable RegisterInputFilter(IInputFilter filter);
    IDisposable SubscribeRoutedInput(Action<KeyRouteContext> handler);
    void LogInfo(string message);
    void LogWarning(string message);
    void LogError(string message, Exception? exception = null);
    object? GetService(Type serviceType);
}

public interface IKeyViewerPlugin : IDisposable
{
    KeyViewerPluginManifest Manifest { get; }
    void Initialize(IPluginContext context);
}

public interface IInputSource : IDisposable
{
    string Id { get; }
    string DisplayName { get; }
    bool IsRunning { get; }
    event EventHandler<KeyInputEvent>? Input;
    void Start();
    void Stop();
}

public interface IRoutingInputSource : IInputSource
{
    Func<KeyInputEvent, KeyRouteContext>? Router { get; set; }
}

public interface IKeyViewerServiceRegistry
{
    void Add(Type serviceType, object service);
    object? Get(Type serviceType);
    IReadOnlyDictionary<Type, object> Snapshot();
}
