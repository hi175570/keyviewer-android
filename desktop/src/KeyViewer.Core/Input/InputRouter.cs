using KeyViewer.Abstractions;
using KeyViewer.Core.Models;

namespace KeyViewer.Core.Input;

public sealed class InputRouter
{
    private readonly object _gate = new();
    private readonly List<IInputFilter> _filters = [];
    private readonly Dictionary<string, DateTimeOffset> _lastDown = new(StringComparer.OrdinalIgnoreCase);
    private readonly HashSet<string> _down = new(StringComparer.OrdinalIgnoreCase);
    private KeyViewerDocument _document;

    public InputRouter(KeyViewerDocument document)
    {
        _document = document ?? throw new ArgumentNullException(nameof(document));
    }

    public event Action<KeyRouteContext>? Routed;

    public KeyViewerDocument Document
    {
        get
        {
            lock (_gate)
            {
                return _document;
            }
        }
        set
        {
            ArgumentNullException.ThrowIfNull(value);
            lock (_gate)
            {
                _document = value;
                _lastDown.Clear();
                _down.Clear();
            }
        }
    }

    public IDisposable RegisterFilter(IInputFilter filter)
    {
        ArgumentNullException.ThrowIfNull(filter);
        lock (_gate)
        {
            if (_filters.Any(item => item.Id.Equals(filter.Id, StringComparison.OrdinalIgnoreCase)))
            {
                throw new InvalidOperationException($"输入过滤器 ID 已存在：{filter.Id}");
            }

            _filters.Add(filter);
            _filters.Sort((left, right) => right.Priority.CompareTo(left.Priority));
        }

        return new CallbackDisposable(() =>
        {
            lock (_gate)
            {
                _filters.Remove(filter);
            }
        });
    }

    public KeyRouteContext Route(KeyInputEvent input)
    {
        KeyRouteContext context;
        Action<KeyRouteContext>? routed;
        lock (_gate)
        {
            context = RouteLocked(input);
            routed = Routed;
        }

        routed?.Invoke(context);
        return context;
    }

    private KeyRouteContext RouteLocked(KeyInputEvent input)
    {
        var context = new KeyRouteContext(input)
        {
            OutputKey = KeyNames.Normalize(input.Key),
            CountPress = input.State == KeyEventState.Down
        };
        var sourceKey = KeyNames.Normalize(input.Key);
        var sourceIdentity = $"{input.DeviceId}\0{sourceKey}";
        var key = _document.FindKey(sourceKey);

        if (ApplyRestriction(context, sourceKey, key))
        {
            UpdateDownState(input.State, sourceIdentity);
            return context;
        }

        if (KeyNames.IsProtected(sourceKey))
        {
            context.Reason = "protected-system-key";
            if (key is null)
            {
                context.ShowInViewer = false;
                context.CountPress = false;
            }
            UpdateDownState(input.State, sourceIdentity);
            return context;
        }

        foreach (var filter in _filters.ToArray())
        {
            try
            {
                filter.Process(context);
            }
            catch (Exception exception)
            {
                context.Reason = $"filter-error:{filter.Id}:{exception.GetType().Name}";
            }

            if (context.StopProcessing)
            {
                UpdateDownState(input.State, sourceIdentity);
                return context;
            }
        }

        if (key is null)
        {
            context.ShowInViewer = false;
            context.CountPress = false;
            context.Reason = "not-on-canvas";
            UpdateDownState(input.State, sourceIdentity);
            return context;
        }

        if (input.State == KeyEventState.Repeat)
        {
            context.CountPress = false;
        }
        else if (input.State == KeyEventState.Down && _down.Contains(sourceIdentity))
        {
            context.CountPress = false;
        }

        if (key.GhostKey)
        {
            context.ForwardToSystem = false;
            context.Reason = "ghost-key";
        }
        else if (IsDebounced(input, sourceIdentity))
        {
            context.ForwardToSystem = false;
            context.Reason = "debounced";
        }
        else if (key.MappingEnabled && !string.IsNullOrWhiteSpace(key.MappedKey))
        {
            context.ForwardToSystem = false;
            context.OutputKey = KeyNames.Normalize(key.MappedKey);
            context.Reason = "mapped";
        }

        UpdateDownState(input.State, sourceIdentity);
        return context;
    }

    private bool ApplyRestriction(KeyRouteContext context, string sourceKey, KeyDefinition? key)
    {
        var settings = _document.GlobalSettings;
        if (!settings.KeyRestrictionEnabled || KeyNames.IsProtected(sourceKey))
        {
            return false;
        }

        var blocked = KeyNames.ParseList(settings.KeyRestrictionBlockedKeys);
        if (key is not null && !blocked.Contains(sourceKey))
        {
            return false;
        }

        context.ForwardToSystem = false;
        context.ShowInViewer = false;
        context.CountPress = false;
        context.StopProcessing = true;
        context.Reason = key is null ? "restricted-not-on-canvas" : "restricted-explicit";
        return true;
    }

    private bool IsDebounced(KeyInputEvent input, string sourceIdentity)
    {
        var settings = _document.GlobalSettings;
        if (!settings.KeyDebounceEnabled || input.State != KeyEventState.Down)
        {
            return false;
        }

        var debounced = _lastDown.TryGetValue(sourceIdentity, out var previous)
            && input.Timestamp - previous < TimeSpan.FromMilliseconds(settings.KeyDebounceMillis);
        _lastDown[sourceIdentity] = input.Timestamp;
        return debounced;
    }

    private void UpdateDownState(KeyEventState state, string identity)
    {
        if (state == KeyEventState.Down)
        {
            _down.Add(identity);
        }
        else if (state == KeyEventState.Up)
        {
            _down.Remove(identity);
        }
    }

    private sealed class CallbackDisposable(Action callback) : IDisposable
    {
        private Action? _callback = callback;
        public void Dispose() => Interlocked.Exchange(ref _callback, null)?.Invoke();
    }
}
