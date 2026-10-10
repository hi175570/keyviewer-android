using System.Numerics;
using System.Runtime.InteropServices;
using KeyViewer.UI.Rendering;
using Windows.System;
using Windows.UI.Composition;
using Windows.UI.Composition.Desktop;
using WinRT;

namespace KeyViewer.Desktop;

/// <summary>
/// Places a Windows compositor visual beneath Avalonia's transparent overlay.
/// The source visual is filled by HostBackdrop, so the pixels come from the
/// desktop/game behind the HWND. A visual surface then makes that live source
/// transformable without recursively sampling KeyViewer's own content.
/// </summary>
internal sealed class WindowsLiquidGlassCompositionHost : IDisposable
{
    private const int DwmwaUseHostBackdropBrush = 17;
    private const int LayerCount = 9;

    private readonly nint _dispatcherQueueController;
    private readonly Compositor _compositor;
    private readonly DesktopWindowTarget _target;
    private readonly ContainerVisual _root;
    private readonly SpriteVisual _backdropSource;
    private readonly CompositionBackdropBrush _backdropBrush;
    private readonly CompositionVisualSurface _backdropSurface;
    private readonly List<IDisposable> _keyResources = [];
    private int? _lastSignature;
    private bool _disposed;

    private WindowsLiquidGlassCompositionHost(nint window)
    {
        if (DispatcherQueue.GetForCurrentThread() is null)
        {
            var options = new DispatcherQueueOptions
            {
                Size = Marshal.SizeOf<DispatcherQueueOptions>(),
                ThreadType = 2, // DQTYPE_THREAD_CURRENT
                ApartmentType = 0 // DQTAT_COM_NONE; Avalonia owns COM setup.
            };
            Marshal.ThrowExceptionForHR(CreateDispatcherQueueController(options, out _dispatcherQueueController));
        }

        var useHostBackdrop = 1;
        Marshal.ThrowExceptionForHR(DwmSetWindowAttribute(
            window,
            DwmwaUseHostBackdropBrush,
            ref useHostBackdrop,
            sizeof(int)));

        _compositor = new Compositor();
        _compositor.As<ICompositorDesktopInterop>()
            .CreateDesktopWindowTarget(window, false, out var targetPointer);
        try
        {
            _target = MarshalInterface<DesktopWindowTarget>.FromAbi(targetPointer);
        }
        finally
        {
            Marshal.Release(targetPointer);
        }

        _root = _compositor.CreateContainerVisual();
        _target.Root = _root;

        _backdropBrush = _compositor.CreateHostBackdropBrush();
        _backdropSource = _compositor.CreateSpriteVisual();
        _backdropSource.Brush = _backdropBrush;
        _root.Children.InsertAtBottom(_backdropSource);

        _backdropSurface = _compositor.CreateVisualSurface();
        _backdropSurface.SourceVisual = _backdropSource;
    }

    public static bool TryCreate(nint window, out WindowsLiquidGlassCompositionHost? host, out string error)
    {
        host = null;
        error = string.Empty;
        if (!OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000))
        {
            error = "真实背景折射需要 Windows 11（Build 22000 或更新版本）。";
            return false;
        }

        try
        {
            host = new WindowsLiquidGlassCompositionHost(window);
            return true;
        }
        catch (Exception exception)
        {
            host?.Dispose();
            host = null;
            error = exception.Message;
            return false;
        }
    }

    public void Update(
        float clientWidth,
        float clientHeight,
        double renderScaling,
        IReadOnlyList<LiquidGlassVisualGeometry> keys)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        var width = Math.Max(1, clientWidth);
        var height = Math.Max(1, clientHeight);
        var size = new Vector2(width, height);
        _root.Size = size;
        _backdropSource.Size = size;
        _backdropSurface.SourceSize = size;

        var signature = CalculateSignature(width, height, renderScaling, keys);
        if (signature == _lastSignature)
        {
            return;
        }

        _lastSignature = signature;
        RebuildKeyVisuals(renderScaling, keys);
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _root.Children.RemoveAll();
        DisposeKeyResources();
        _backdropSurface.Dispose();
        _backdropSource.Dispose();
        _backdropBrush.Dispose();
        _root.Dispose();
        _target.Dispose();
        _compositor.Dispose();
        if (_dispatcherQueueController != 0)
        {
            Marshal.Release(_dispatcherQueueController);
        }
    }

    private void RebuildKeyVisuals(double renderScaling, IReadOnlyList<LiquidGlassVisualGeometry> keys)
    {
        _root.Children.RemoveAll();
        DisposeKeyResources();
        _root.Children.InsertAtBottom(_backdropSource);

        if (keys.Count == 0)
        {
            _backdropSource.IsVisible = false;
            return;
        }

        _backdropSource.IsVisible = true;
        var scaleFactor = (float)Math.Max(0.01, renderScaling);
        foreach (var key in keys)
        {
            AddKeyVisual(key, scaleFactor);
        }
    }

    private void AddKeyVisual(LiquidGlassVisualGeometry key, float renderScaling)
    {
        var keyX = (float)key.Surface.X * renderScaling;
        var keyY = (float)key.Surface.Y * renderScaling;
        var keyWidth = Math.Max(1, (float)key.Surface.Width * renderScaling);
        var keyHeight = Math.Max(1, (float)key.Surface.Height * renderScaling);
        var minDimension = Math.Min(keyWidth, keyHeight);
        var centerGlobal = new Vector2(keyX + keyWidth / 2, keyY + keyHeight / 2);
        var container = Track(_compositor.CreateContainerVisual());
        container.Offset = new Vector3(keyX, keyY, 0);
        container.Size = new Vector2(keyWidth, keyHeight);

        // Concentric transformed samples approximate a smooth convex lens.
        // Every layer still samples the live HostBackdrop surface, not a copy
        // of KeyViewer's own canvas.
        for (var index = 0; index < LayerCount; index++)
        {
            var progress = (index + 1f) / LayerCount;
            var inset = minDimension * 0.105f * index / (LayerCount - 1);
            var layerWidth = Math.Max(1, keyWidth - inset * 2);
            var layerHeight = Math.Max(1, keyHeight - inset * 2);
            var layer = Track(_compositor.CreateSpriteVisual());
            layer.Offset = new Vector3(inset, inset, index * 0.001f);
            layer.Size = new Vector2(layerWidth, layerHeight);

            var geometry = Track(_compositor.CreateRoundedRectangleGeometry());
            geometry.Size = layer.Size;
            var layerRadius = Math.Max(0, (float)key.CornerRadius * renderScaling - inset);
            geometry.CornerRadius = new Vector2(Math.Min(layerRadius, Math.Min(layerWidth, layerHeight) / 2));
            layer.Clip = Track(_compositor.CreateGeometricClip(geometry));

            var brush = Track(_compositor.CreateSurfaceBrush(_backdropSurface));
            brush.Stretch = CompositionStretch.None;
            var lensScale = 1f + (float)(key.Style.RefractionStrength * renderScaling / minDimension)
                * (0.18f + 0.82f * progress * progress);
            var centerLocal = new Vector2(layerWidth / 2, layerHeight / 2);
            brush.TransformMatrix = new Matrix3x2(
                lensScale,
                0,
                0,
                lensScale,
                centerLocal.X - centerGlobal.X * lensScale,
                centerLocal.Y - centerGlobal.Y * lensScale);
            layer.Brush = brush;
            container.Children.InsertAtTop(layer);
        }

        _root.Children.InsertAtTop(container);
    }

    private T Track<T>(T resource) where T : class, IDisposable
    {
        _keyResources.Add(resource);
        return resource;
    }

    private void DisposeKeyResources()
    {
        for (var index = _keyResources.Count - 1; index >= 0; index--)
        {
            _keyResources[index].Dispose();
        }
        _keyResources.Clear();
    }

    private static int CalculateSignature(
        float width,
        float height,
        double renderScaling,
        IReadOnlyList<LiquidGlassVisualGeometry> keys)
    {
        var hash = new HashCode();
        hash.Add(width);
        hash.Add(height);
        hash.Add(renderScaling);
        hash.Add(keys.Count);
        foreach (var key in keys)
        {
            hash.Add(key.KeyId, StringComparer.Ordinal);
            hash.Add(key.Surface);
            hash.Add(key.CornerRadius);
            hash.Add(key.Pressed);
            hash.Add(key.Style.RefractionStrength);
        }
        return hash.ToHashCode();
    }

    [DllImport("CoreMessaging.dll")]
    private static extern int CreateDispatcherQueueController(
        DispatcherQueueOptions options,
        out nint dispatcherQueueController);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(
        nint window,
        int attribute,
        ref int value,
        int valueSize);

    [StructLayout(LayoutKind.Sequential)]
    private struct DispatcherQueueOptions
    {
        public int Size;
        public int ThreadType;
        public int ApartmentType;
    }

    [ComImport]
    [Guid("29E691FA-4567-4DCA-B319-D0F207EB6807")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ICompositorDesktopInterop
    {
        void CreateDesktopWindowTarget(nint hwndTarget, bool isTopmost, out nint result);

        void EnsureOnThread(uint threadId);
    }
}
