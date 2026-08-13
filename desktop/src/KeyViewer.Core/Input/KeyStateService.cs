using System.Diagnostics;
using KeyViewer.Abstractions;
using KeyViewer.Core.Models;

namespace KeyViewer.Core.Input;

public sealed class KeyStateService : IDisposable
{
    private readonly object _gate = new();
    private readonly HashSet<string> _pressed = new(StringComparer.OrdinalIgnoreCase);
    private readonly KpsCounter _kpsCounter;
    private readonly Timer _kpsExpiryTimer;
    private KeyViewerDocument _document;
    private int _lastPublishedKps;
    private bool _disposed;

    public KeyStateService(KeyViewerDocument document)
        : this(document, TimeSpan.FromSeconds(1), Stopwatch.GetTimestamp, Stopwatch.Frequency)
    {
    }

    internal KeyStateService(
        KeyViewerDocument document,
        TimeSpan kpsWindow,
        Func<long> getTimestamp,
        long timestampFrequency)
    {
        _document = document ?? throw new ArgumentNullException(nameof(document));
        _kpsCounter = new KpsCounter(kpsWindow, getTimestamp, timestampFrequency);
        _kpsExpiryTimer = new Timer(OnKpsExpiry, null, Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
        _document.RecalculateTotalPressCount();
    }

    public event Action? Changed;
    public event Action? TransientChanged;

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
                ClearKpsLocked();
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

                _kpsCounter.RecordPress();
                _lastPublishedKps = _kpsCounter.Count;
                ScheduleNextKpsExpiryLocked();
            }
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
                return _kpsCounter.Count;
            }
        }
    }

    public void ResetCounts()
    {
        lock (_gate)
        {
            _document.KeyCounts.Clear();
            _document.TotalPressCount = 0;
            ClearKpsLocked();
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

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed)
            {
                return;
            }

            _disposed = true;
            _kpsExpiryTimer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
        }

        _kpsExpiryTimer.Dispose();
    }

    private void OnKpsExpiry(object? state)
    {
        var changed = false;
        lock (_gate)
        {
            if (_disposed)
            {
                return;
            }

            var current = _kpsCounter.Count;
            changed = current != _lastPublishedKps;
            _lastPublishedKps = current;
            ScheduleNextKpsExpiryLocked();
        }

        if (changed)
        {
            // KPS decay is visual-only. Do not schedule a configuration save just
            // because an item naturally left the one-second sliding window.
            TransientChanged?.Invoke();
        }
    }

    private void ScheduleNextKpsExpiryLocked()
    {
        var delay = _kpsCounter.TimeUntilNextExpiry;
        if (delay is null)
        {
            _kpsExpiryTimer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
            return;
        }

        // Keep a small floor for timer-resolution rounding; the callback rechecks
        // the monotonic window before publishing a value.
        _kpsExpiryTimer.Change(
            delay.Value < TimeSpan.FromMilliseconds(1) ? TimeSpan.FromMilliseconds(1) : delay.Value,
            Timeout.InfiniteTimeSpan);
    }

    private void ClearKpsLocked()
    {
        _kpsCounter.Clear();
        _lastPublishedKps = 0;
        _kpsExpiryTimer.Change(Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
    }
}
