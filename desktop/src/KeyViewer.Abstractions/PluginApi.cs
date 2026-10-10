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
        : this(key, state, deviceId, timestamp, isInjected, false)
    {
    }

    public KeyInputEvent(
        string key,
        KeyEventState state,
        string deviceId,
        DateTimeOffset timestamp,
        bool isInjected,
        bool isObservationOnly)
    {
        Key = key ?? throw new ArgumentNullException(nameof(key));
        State = state;
        DeviceId = deviceId ?? string.Empty;
        Timestamp = timestamp;
        IsInjected = isInjected;
        IsObservationOnly = isObservationOnly;
    }

    public string Key { get; }
    public KeyEventState State { get; }
    public string DeviceId { get; }
    public DateTimeOffset Timestamp { get; }
    public bool IsInjected { get; }
    public bool IsObservationOnly { get; }
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
    IDisposable RegisterKeySurfaceEffect(IKeySurfaceEffect effect);
    IDisposable SubscribeRoutedInput(Action<KeyRouteContext> handler);
    void LogInfo(string message);
    void LogWarning(string message);
    void LogError(string message, Exception? exception = null);
    object? GetService(Type serviceType);
}

/// <summary>
/// Rendering-neutral information about one key surface. The contract deliberately
/// avoids framework types so a visual plugin can run in the shared Windows/Android UI.
/// </summary>
public sealed class KeySurfaceContext
{
    public KeySurfaceContext(
        string keyId,
        bool isPressed,
        bool darkMode,
        double width,
        double height,
        double cornerRadius,
        int fillColor,
        int borderColor)
    {
        KeyId = keyId ?? string.Empty;
        IsPressed = isPressed;
        DarkMode = darkMode;
        Width = width;
        Height = height;
        CornerRadius = cornerRadius;
        FillColor = fillColor;
        BorderColor = borderColor;
    }

    public string KeyId { get; }
    public bool IsPressed { get; }
    public bool DarkMode { get; }
    public double Width { get; }
    public double Height { get; }
    public double CornerRadius { get; }
    public int FillColor { get; }
    public int BorderColor { get; }
}

/// <summary>
/// Declarative glass material returned by a rendering plugin and interpreted by
/// the host renderer. Colors use ARGB32, matching KeyViewer configuration colors.
/// </summary>
public sealed class KeySurfaceStyle
{
    public int BaseColor { get; set; }
    public int MidColor { get; set; }
    public int DepthColor { get; set; }
    public int SpecularColor { get; set; }
    public int RimLightColor { get; set; }
    public int RimShadeColor { get; set; }
    public int ShadowColor { get; set; }
    public int GlowColor { get; set; }
    public double SurfaceOpacity { get; set; } = 0.72;
    public double RefractionStrength { get; set; } = 1;
    public double BackdropBlurRadius { get; set; } = 1.4;
    public double ChromaticAberration { get; set; } = 0.8;
    public double SpecularStrength { get; set; } = 1;
    public double ShadowDepth { get; set; } = 5;
    public double PressInset { get; set; } = 1.2;
    public double CornerRadiusBoost { get; set; } = 4;
}

public interface IKeySurfaceEffect
{
    string Id { get; }
    int Priority { get; }
    bool TryCreateStyle(KeySurfaceContext context, out KeySurfaceStyle style);
}

public interface IKeySurfaceEffectRegistry
{
    IReadOnlyList<string> EffectIds { get; }
    IDisposable Register(IKeySurfaceEffect effect);
    bool TryCreateStyle(KeySurfaceContext context, out KeySurfaceStyle style);
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
