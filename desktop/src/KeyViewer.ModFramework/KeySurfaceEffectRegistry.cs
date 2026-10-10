using KeyViewer.Abstractions;

namespace KeyViewer.ModFramework;

public sealed class KeySurfaceEffectRegistry : IKeySurfaceEffectRegistry
{
    private readonly object _gate = new();
    private readonly List<IKeySurfaceEffect> _effects = [];

    public IReadOnlyList<string> EffectIds
    {
        get
        {
            lock (_gate)
            {
                return _effects
                    .OrderByDescending(effect => effect.Priority)
                    .Select(effect => effect.Id)
                    .ToArray();
            }
        }
    }

    public IDisposable Register(IKeySurfaceEffect effect)
    {
        ArgumentNullException.ThrowIfNull(effect);
        if (string.IsNullOrWhiteSpace(effect.Id))
        {
            throw new ArgumentException("Rendering effect ID cannot be empty.", nameof(effect));
        }

        lock (_gate)
        {
            if (_effects.Any(item => item.Id.Equals(effect.Id, StringComparison.OrdinalIgnoreCase)))
            {
                throw new InvalidOperationException($"Rendering effect ID is already registered: {effect.Id}");
            }

            _effects.Add(effect);
        }

        return new CallbackDisposable(() =>
        {
            lock (_gate)
            {
                _effects.Remove(effect);
            }
        });
    }

    public bool TryCreateStyle(KeySurfaceContext context, out KeySurfaceStyle style)
    {
        ArgumentNullException.ThrowIfNull(context);
        IKeySurfaceEffect[] snapshot;
        lock (_gate)
        {
            snapshot = _effects.OrderByDescending(effect => effect.Priority).ToArray();
        }

        foreach (var effect in snapshot)
        {
            if (effect.TryCreateStyle(context, out style))
            {
                return true;
            }
        }

        style = new KeySurfaceStyle();
        return false;
    }

    private sealed class CallbackDisposable(Action callback) : IDisposable
    {
        private Action? _callback = callback;
        public void Dispose() => Interlocked.Exchange(ref _callback, null)?.Invoke();
    }
}
