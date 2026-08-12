using KeyViewer.Abstractions;

namespace KeyViewer.SamplePlugin;

public sealed class SamplePlugin : IKeyViewerPlugin
{
    private IDisposable? _subscription;

    public KeyViewerPluginManifest Manifest { get; } = new(
        "io.keyviewer.sample",
        "KeyViewer 示例模组",
        "1.0.0")
    {
        Author = "KeyViewer",
        Description = "演示稳定插件 API、事件订阅和安全卸载，不会修改按键。"
    };

    public void Initialize(IPluginContext context)
    {
        context.LogInfo("示例模组已加载；它只观察路由，不会拦截或映射按键。");
        _subscription = context.SubscribeRoutedInput(route =>
        {
            if (route.Input.State == KeyEventState.Down)
            {
                context.LogInfo($"按下 {route.Input.Key}，路由结果：{route.Reason}");
            }
        });
    }

    public void Dispose()
    {
        _subscription?.Dispose();
        _subscription = null;
    }
}
