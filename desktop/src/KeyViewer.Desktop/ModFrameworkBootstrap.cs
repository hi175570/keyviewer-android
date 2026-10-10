using System.Reflection;
using System.Runtime.Loader;
using System.Runtime.InteropServices;

namespace KeyViewer.Desktop;

/// <summary>
/// Installs the directory layout resolver before Avalonia or KeyViewer.UI is
/// initialized. Managed dependencies live in Managed, native dependencies in
/// Native, and loader-owned assemblies in BepInEx/core.
/// </summary>
internal static class ModFrameworkBootstrap
{
    private static int _initialized;

    public static void Initialize()
    {
        if (Interlocked.Exchange(ref _initialized, 1) != 0)
        {
            return;
        }

        AssemblyLoadContext.Default.Resolving += ResolveManagedAssembly;
        ConfigureNativeSearchPath();
        TouchDoorstopProxy();
    }

    private static Assembly? ResolveManagedAssembly(AssemblyLoadContext context, AssemblyName name)
    {
        if (string.IsNullOrWhiteSpace(name.Name)
            || name.Name.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0)
        {
            return null;
        }

        var roots = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "Managed"),
            Path.Combine(AppContext.BaseDirectory, "BepInEx", "core")
        };
        foreach (var root in roots)
        {
            var fullRoot = Path.GetFullPath(root);
            var candidate = Path.GetFullPath(Path.Combine(fullRoot, name.Name + ".dll"));
            if (candidate.StartsWith(fullRoot + Path.DirectorySeparatorChar,
                    StringComparison.OrdinalIgnoreCase)
                && File.Exists(candidate))
            {
                return context.LoadFromAssemblyPath(candidate);
            }
        }

        return null;
    }

    private static void ConfigureNativeSearchPath()
    {
        if (!OperatingSystem.IsWindows())
        {
            return;
        }

        var nativeRoot = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "Native"));
        if (Directory.Exists(nativeRoot))
        {
            _ = SetDllDirectory(nativeRoot);
        }
    }

    private static void TouchDoorstopProxy()
    {
        if (!OperatingSystem.IsWindows()
            || !File.Exists(Path.Combine(AppContext.BaseDirectory, "winhttp.dll")))
        {
            return;
        }

        try
        {
            // Doorstop is intentionally disabled in doorstop_config.ini for
            // this non-Unity CoreCLR host. Calling an exported WinHTTP method
            // still validates that the real proxy loads and forwards cleanly.
            _ = WinHttpCheckPlatform();
        }
        catch (DllNotFoundException)
        {
            // Development builds do not necessarily carry the publish asset.
        }
        catch (EntryPointNotFoundException)
        {
            // Keep source builds usable if a user replaces the compatibility
            // proxy with an incompatible build.
        }
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetDllDirectory(string pathName);

    [DllImport("winhttp.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool WinHttpCheckPlatform();
}
