using System.Diagnostics;

namespace KeyViewer.Core.Input;

/// <summary>
/// Counts presses in a half-open monotonic time window: (now - window, now].
/// Input-event wall-clock timestamps are deliberately ignored because bridged
/// sources can arrive late, out of order, or use a different system clock.
/// </summary>
internal sealed class KpsCounter
{
    private readonly Queue<long> _presses = new();
    private readonly Func<long> _getTimestamp;
    private readonly long _timestampFrequency;
    private readonly long _windowTicks;

    public KpsCounter(
        TimeSpan window,
        Func<long>? getTimestamp = null,
        long timestampFrequency = 0)
    {
        if (window <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(window));
        }

        _getTimestamp = getTimestamp ?? Stopwatch.GetTimestamp;
        _timestampFrequency = timestampFrequency > 0 ? timestampFrequency : Stopwatch.Frequency;
        _windowTicks = Math.Max(1, (long)Math.Ceiling(window.TotalSeconds * _timestampFrequency));
    }

    public int Count
    {
        get
        {
            RemoveExpired(_getTimestamp());
            return _presses.Count;
        }
    }

    public void RecordPress()
    {
        var now = _getTimestamp();
        RemoveExpired(now);
        _presses.Enqueue(now);
    }

    public TimeSpan? TimeUntilNextExpiry
    {
        get
        {
            var now = _getTimestamp();
            RemoveExpired(now);
            if (!_presses.TryPeek(out var oldest))
            {
                return null;
            }

            var elapsed = Math.Max(0, now - oldest);
            var remaining = _windowTicks - elapsed;
            return remaining <= 0
                ? TimeSpan.Zero
                : TimeSpan.FromSeconds((double)remaining / _timestampFrequency);
        }
    }

    public void Clear() => _presses.Clear();

    private void RemoveExpired(long now)
    {
        while (_presses.TryPeek(out var timestamp)
               && now - timestamp >= _windowTicks)
        {
            _presses.Dequeue();
        }
    }
}
