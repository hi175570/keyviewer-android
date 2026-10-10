Unity Doorstop 4.4.1 compatibility files
========================================

winhttp.dll is the unmodified x64 Doorstop 4.4.1 proxy used by the local
Nine Sols and Hollow Knight: Silksong installations. SHA-256:
C8D4F45CA81979ACDA889AAA1B4334E010B70E4C3960B15D323D2829AB424257

Doorstop remains disabled because KeyViewer is a native .NET 10/CoreCLR host,
not Unity Mono or IL2CPP. KeyViewer's own early bootstrap resolves Managed,
Native and BepInEx/core before starting Avalonia. The proxy is loaded once and
its WinHTTP forwarder is exercised during Windows startup.

Upstream: https://github.com/NeighTools/UnityDoorstop
License: LGPL-2.1
