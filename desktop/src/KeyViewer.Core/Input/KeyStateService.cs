using KeyViewer.Abstractions;
using KeyViewer.Core.Models;

namespace KeyViewer.Core.Input;

public sealed class KeyStateService
{
    private readonly object _gate = new();
    private readonly HashSet<string> _pressed = new(StringComparer.OrdinalIgnoreCase);
    private readonly Queue<DateTimeOffset> _recentPresses = new();
    private KeyViewerDocument _document;

    public KeyStateService(KeyViewerDocument document)
    {
        _document = document ?? throw new ArgumentNullException(nameof(document));
        _document.RecalculateTotalPressCount();
    }

    public event Action? Changed;

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
                _document.RecalculateTotalPressCount();
                _pressed.Clear();
                _recentPresses.Clear();
            }

            Changed?.Invoke();
        }
    }

    public void Apply(KeyRouteContext route)
    {
        if (!route.ShowInViewer)
        {
            return;
        }

        var keyName = KeyNames.Normalize(route.Input.Key);
        lock (_gate)
        {
            if (route.Input.State == KeyEventState.Down)
            {
                _pressed.Add(keyName);
            }
            else if (route.Input.State == KeyEventState.Up)
            {
                _pressed.Remove(keyName);
            }

            if (route.CountPress && route.Input.State == KeyEventState.Down)
            {
                _document.KeyCounts.TryGetValue(keyName, out var count);
                _document.KeyCounts[keyName] = count == int.MaxValue ? count : count + 1;
                if (_document.TotalPressCount < int.MaxValue)
                {
                    _document.TotalPressCount++;
                }

                _recentPresses.Enqueue(route.Input.Timestamp);
            }

            RemoveExpired(route.Input.Timestamp);
        }

        Changed?.Invoke();
    }

    public bool IsPressed(string key)
    {
        lock (_gate)
        {
            return _pressed.Contains(KeyNames.Normalize(key));
        }
    }

    public int GetCount(string key)
    {
        lock (_gate)
        {
            return _document.KeyCounts.TryGetValue(KeyNames.Normalize(key), out var count) ? count : 0;
        }
    }

    public void SetCount(string key, int count)
    {
        var keyName = KeyNames.Normalize(key);
        count = Math.Max(0, count);
        lock (_gate)
        {
            if (count == 0)
            {
                _document.KeyCounts.Remove(keyName);
            }
            else
            {
                _document.KeyCounts[keyName] = count;
            }
            _document.RecalculateTotalPressCount();
        }

        Changed?.Invoke();
    }

    public int Kps
    {
        get
        {
            lock (_gate)
            {
                RemoveExpired(DateTimeOffset.UtcNow);
                return _recentPresses.Count;
            }
        }
    }

    public void ResetCounts()
    {
        lock (_gate)
        {
            _document.KeyCounts.Clear();
            _document.TotalPressCount = 0;
            _recentPresses.Clear();
        }

        Changed?.Invoke();
    }

    public void ReleaseAll()
    {
        lock (_gate)
        {
            _pressed.Clear();
        }

        Changed?.Invoke();
    }

    private void RemoveExpired(DateTimeOffset now)
    {
        var threshold = now - TimeSpan.FromSeconds(1);
        while (_recentPresses.TryPeek(out var timestamp) && timestamp < threshold)
        {
            _recentPresses.Dequeue();
        }
    }
}
