using System.Diagnostics;
using KeyViewer.Abstractions;
using KeyViewer.Core.Input;
using KeyViewer.Core.Models;

namespace KeyViewer.Core.Tests;

public sealed class KpsCounterTests
{
    [Fact]
    public void Sliding_window_expires_each_press_at_exactly_one_second()
    {
        long now = 0;
        var counter = new KpsCounter(TimeSpan.FromSeconds(1), () => now, 1_000);

        counter.RecordPress();
        now = 250;
        counter.RecordPress();

        now = 999;
        Assert.Equal(2, counter.Count);
        now = 1_000;
        Assert.Equal(1, counter.Count);
        now = 1_250;
        Assert.Equal(0, counter.Count);
        Assert.Null(counter.TimeUntilNextExpiry);
    }

    [Fact]
    public async Task Kps_publishes_zero_without_requiring_another_input_event()
    {
        var document = DocumentWith("a");
        using var state = new KeyStateService(
            document,
            TimeSpan.FromMilliseconds(40),
            Stopwatch.GetTimestamp,
            Stopwatch.Frequency);
        var router = new InputRouter(document);
        var reachedZero = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        state.TransientChanged += () =>
        {
            if (state.Kps == 0)
            {
                reachedZero.TrySetResult();
            }
        };

        state.Apply(router.Route(new KeyInputEvent(
            "a", KeyEventState.Down, "test", DateTimeOffset.MaxValue)));

        Assert.Equal(1, state.Kps);
        await reachedZero.Task.WaitAsync(TimeSpan.FromSeconds(2));
        Assert.Equal(0, state.Kps);
    }

    [Fact]
    public void Kps_uses_arrival_time_instead_of_untrusted_event_wall_clock()
    {
        long now = 42;
        var document = DocumentWith("a");
        using var state = new KeyStateService(
            document,
            TimeSpan.FromSeconds(1),
            () => now,
            1_000);
        var router = new InputRouter(document);

        state.Apply(router.Route(new KeyInputEvent(
            "a", KeyEventState.Down, "test", DateTimeOffset.MaxValue)));

        Assert.Equal(1, state.Kps);
        now += 1_000;
        Assert.Equal(0, state.Kps);
    }

    private static KeyViewerDocument DocumentWith(string key)
        => new()
        {
            Keys =
            [
                new KeyDefinition
                {
                    BoundKey = key,
                    CenterX = 100,
                    CenterY = 100
                }
            ]
        };
}
