using KeyViewer.Abstractions;

namespace KeyViewer.ModFramework;

public sealed class ServiceRegistry : IKeyViewerServiceRegistry
{
    private readonly Dictionary<Type, object> _services = [];
    private readonly object _gate = new();

    public void Add(Type serviceType, object service)
    {
        ArgumentNullException.ThrowIfNull(serviceType);
        ArgumentNullException.ThrowIfNull(service);
        if (!serviceType.IsInstanceOfType(service))
        {
            throw new ArgumentException($"服务实例不实现 {serviceType.FullName}", nameof(service));
        }

        lock (_gate)
        {
            _services[serviceType] = service;
        }
    }

    public object? Get(Type serviceType)
    {
        lock (_gate)
        {
            return _services.TryGetValue(serviceType, out var service) ? service : null;
        }
    }

    public IReadOnlyDictionary<Type, object> Snapshot()
    {
        lock (_gate)
        {
            return new Dictionary<Type, object>(_services);
        }
    }
}
