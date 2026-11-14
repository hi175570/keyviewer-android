using KeyViewer.Abstractions;
using KeyViewer.Core.Input;
using KeyViewer.Core.Models;
using KeyViewer.ModFramework;
using Sample = KeyViewer.SamplePlugin.SamplePlugin;

namespace KeyViewer.Core.Tests;

public sealed class ModFrameworkTests
{
    [Fact]
    public void Sample_plugin_loads_observes_input_and_unloads()
    {
        var router = new InputRouter(new KeyViewerDocument());
        var services = new ServiceRegistry();
        var messages = new List<string>();
        var dataRoot = Path.Combine(Path.GetTempPath(), $"keyviewer-plugin-test-{Guid.NewGuid():N}");
        Directory.CreateDirectory(dataRoot);
        try
        {
            using var host = new PluginHost(router, services, dataRoot, messages.Add);
            var status = host.LoadAssembly(typeof(Sample).Assembly.Location);

            Assert.True(status.IsLoaded, status.Error);
            Assert.Equal("io.keyviewer.sample", status.Id);
            router.Route(new KeyInputEvent("a", KeyEventState.Down, "test", DateTimeOffset.UtcNow));
            Assert.Contains(messages, message => message.Contains("按下 a", StringComparison.Ordinal));

            Assert.True(host.Unload(status.Id));
            Assert.Empty(host.Plugins);
        }
        finally
        {
            Directory.Delete(dataRoot, true);
        }
    }
}
