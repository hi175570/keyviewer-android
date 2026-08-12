using KeyViewer.Abstractions;
using KeyViewer.Core.Input;

namespace KeyViewer.Core.Tests;

public sealed class BridgePipeInputSourceTests
{
    [Fact]
    public void Parses_BepInEx_bridge_message()
    {
        const string json = "{\"key\":\"SpaceBar\",\"state\":\"down\",\"deviceId\":\"game:test\",\"timestampUnixMilliseconds\":1234567890000}";

        var parsed = BridgePipeInputSource.TryParse(json, out var input);

        Assert.True(parsed);
        Assert.Equal("space", input.Key);
        Assert.Equal(KeyEventState.Down, input.State);
        Assert.Equal("game:test", input.DeviceId);
    }
}
