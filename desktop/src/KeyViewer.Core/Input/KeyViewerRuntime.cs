using KeyViewer.Abstractions;

namespace KeyViewer.Core.Input;

public sealed class KeyViewerRuntime : IDisposable
{
    private readonly object _gate = new();
    private readonly List<IInputSource> _sources = [];
    private bool _disposed;

    public KeyViewerRuntime(InputRouter router, KeyStateService state)
    {
        Router = router ?? throw new ArgumentNullException(nameof(router));
        State = state ?? throw new ArgumentNullException(nameof(state));
    }

    public InputRouter Router { get; }
    public KeyStateService State { get; }
    public IReadOnlyList<IInputSource> Sources
    {
        get
        {
            lock (_gate)
            {
                return _sources.ToArray();
            }
        }
    }

    public void AddSource(IInputSource source, bool start = true)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        ArgumentNullException.ThrowIfNull(source);
        lock (_gate)
        {
            if (_sources.Any(item => item.Id.Equals(source.Id, StringComparison.OrdinalIgnoreCase)))
            {
                throw new InvalidOperationException($"输入源 ID 已存在：{source.Id}");
            }

            _sources.Add(source);
            if (source is IRoutingInputSource routingSource)
            {
                routingSource.Router = Process;
            }
            else
            {
                source.Input += OnInput;
            }
        }

        if (start)
        {
            source.Start();
        }
    }

    public void RemoveSource(IInputSource source)
    {
        lock (_gate)
        {
            if (!_sources.Remove(source))
            {
                return;
            }

            if (source is IRoutingInputSource routingSource)
            {
                routingSource.Router = null;
            }
            else
            {
                source.Input -= OnInput;
            }
        }

        source.Stop();
        source.Dispose();
    }

    public KeyRouteContext Process(KeyInputEvent input)
    {
        var route = Router.Route(input);
        State.Apply(route);
        return route;
    }

    private void OnInput(object? sender, KeyInputEvent input) => Process(input);

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        foreach (var source in Sources)
        {
            RemoveSource(source);
        }
    }
}
