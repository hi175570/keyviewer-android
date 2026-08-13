using System.Globalization;
using System.Reflection;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Styling;
using Avalonia.Threading;
using Avalonia.VisualTree;
using KeyViewer.Abstractions;
using KeyViewer.Core.Config;
using KeyViewer.Core.Models;
using KeyViewer.UI.Controls;

namespace KeyViewer.UI;

public sealed partial class KeyViewerMainView : UserControl
{
    private static readonly Color ToolbarBlue = Color.Parse("#1976D2");
    private readonly KeyViewerAppHost _host;
    private readonly KeyCanvasControl _canvas;
    private readonly Border _sidePanel;
    private readonly ContentControl _sideContent;
    private readonly TextBlock _statusText;
    private readonly Button _overlayButton;
    private readonly Button _listenButton;
    private readonly Border _normalEditToolbar;
    private readonly Border _pasteToolbar;
    private readonly Button _batchEditButton;
    private readonly Button _batchPropertiesButton;
    private readonly Button _selectAllButton;
    private readonly Border _themeTransitionOverlay;
    private readonly Border _dialogOverlay;
    private readonly ContentControl _dialogContent;
    private readonly IKeyViewerPlatformServices _platform;
    private KeyDefinition? _currentKey;
    private string _panelMode = string.Empty;
    private Button? _captureButton;
    private Action<string>? _captureAction;
    private string _captureOriginal = string.Empty;
    private bool _ownsHost;
    private IStorageProvider StorageProvider => TopLevel.GetTopLevel(this)?.StorageProvider
        ?? throw new InvalidOperationException("当前平台没有可用的文件选择器");

    public KeyViewerMainView()
        : this(new KeyViewerAppHost(), new NullKeyViewerPlatformServices())
    {
        _ownsHost = true;
    }

    public KeyViewerMainView(KeyViewerAppHost host, IKeyViewerPlatformServices platform)
    {
        _host = host;
        _platform = platform;
        AvaloniaXamlLoader.Load(this);

        _canvas = this.FindControl<KeyCanvasControl>("KeyCanvas")
            ?? throw new InvalidOperationException("AXAML 中缺少 KeyCanvas");
        _canvas.Attach(_host);
        _canvas.SelectedKeyChanged += OnSelectedKeyChanged;
        _canvas.SelectionChanged += OnSelectionChanged;
        _canvas.EditModeChanged += UpdateEditModeUi;

        _statusText = this.FindControl<TextBlock>("StatusText")
            ?? throw new InvalidOperationException("AXAML 中缺少 StatusText");
        _statusText.Text = BuildStatus();
        _overlayButton = this.FindControl<Button>("OverlayButton")
            ?? throw new InvalidOperationException("AXAML 中缺少 OverlayButton");
        _overlayButton.Click += (_, _) => ToggleOverlay();
        _listenButton = this.FindControl<Button>("ListenButton")
            ?? throw new InvalidOperationException("AXAML 中缺少 ListenButton");
        _listenButton.Click += (_, _) => ToggleListening();

        _sideContent = this.FindControl<ContentControl>("SideContent")
            ?? throw new InvalidOperationException("AXAML 中缺少 SideContent");
        _sidePanel = this.FindControl<Border>("SidePanel")
            ?? throw new InvalidOperationException("AXAML 中缺少 SidePanel");
        _themeTransitionOverlay = this.FindControl<Border>("ThemeTransitionOverlay")
            ?? throw new InvalidOperationException("AXAML 中缺少 ThemeTransitionOverlay");
        _dialogOverlay = this.FindControl<Border>("DialogOverlay")
            ?? throw new InvalidOperationException("AXAML 中缺少 DialogOverlay");
        _dialogContent = this.FindControl<ContentControl>("DialogContent")
            ?? throw new InvalidOperationException("AXAML 中缺少 DialogContent");
        _normalEditToolbar = this.FindControl<Border>("NormalEditToolbar")
            ?? throw new InvalidOperationException("AXAML 中缺少 NormalEditToolbar");
        _pasteToolbar = this.FindControl<Border>("PasteToolbar")
            ?? throw new InvalidOperationException("AXAML 中缺少 PasteToolbar");
        _batchEditButton = RequireButton("BatchEditButton");
        _batchPropertiesButton = RequireButton("BatchPropertiesButton");
        _selectAllButton = RequireButton("SelectAllButton");
        RequireButton("ZoomOutButton").Click += (_, _) => _canvas.ZoomOut();
        RequireButton("ZoomResetButton").Click += (_, _) => _canvas.ResetViewport();
        RequireButton("ZoomInButton").Click += (_, _) => _canvas.ZoomIn();
        RequireButton("GlobalSettingsButton").Click += (_, _) => ShowGlobalPanel();
        RequireButton("LayersButton").Click += (_, _) => ShowLayersPanel();
        RequireButton("ConfigManagerButton").Click += (_, _) => ShowConfigPanel();
        RequireButton("InputSourcesButton").Click += (_, _) => ShowInputPanel();
        RequireButton("AddKeyButton").Click += (_, _) => AddKey();
        RequireButton("DeleteKeyButton").Click += (_, _) => DeleteSelected();
        RequireButton("CopyButton").Click += (_, _) => CopySelected();
        RequireButton("PasteButton").Click += (_, _) => StartPaste();
        RequireButton("ConfirmPasteButton").Click += (_, _) => ConfirmPaste();
        RequireButton("CancelPasteButton").Click += (_, _) => CancelPaste();
        _batchEditButton.Click += (_, _) => ToggleBatchEdit();
        _batchPropertiesButton.Click += (_, _) => ShowBatchKeyPanel();
        _selectAllButton.Click += (_, _) => _canvas.SelectAllKeys();

        _host.Changed += OnHostChanged;
        _host.LogMessage += OnLogMessage;
        _host.Router.Routed += OnRoutedForBinding;
        DetachedFromVisualTree += OnDetachedFromVisualTree;
        _platform.OverlayVisibilityChanged += OnOverlayVisibilityChanged;
        if (_platform is IInputBackendPlatformServices inputBackend)
        {
            inputBackend.InputBackendChanged += OnInputBackendChanged;
        }
        UpdateEditModeUi();
    }

    private Button RequireButton(string name)
        => this.FindControl<Button>(name)
           ?? throw new InvalidOperationException($"AXAML 中缺少 {name}");

    private Control BuildRoot()
    {
        var root = new Grid();
        root.Children.Add(_canvas);
        root.Children.Add(BuildTopBar());
        root.Children.Add(BuildZoomControls());
        root.Children.Add(BuildRightControls());
        root.Children.Add(BuildBottomLeftControls());
        root.Children.Add(BuildBottomCenterControls());
        root.Children.Add(_sidePanel);
        return root;
    }

    private Control BuildTopBar()
    {
        var grid = new Grid
        {
            Height = 38,
            VerticalAlignment = VerticalAlignment.Top,
            Background = new SolidColorBrush(ToolbarBlue),
            ColumnDefinitions = new ColumnDefinitions("Auto,*")
        };
        var title = new TextBlock
        {
            Text = "KEYVIEWER",
            FontSize = 15,
            FontWeight = FontWeight.Bold,
            Foreground = Brushes.White,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(16, 0)
        };
        grid.Children.Add(title);
        Grid.SetColumn(_statusText, 1);
        grid.Children.Add(_statusText);
        return grid;
    }

    private Control BuildZoomControls()
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 0 };
        row.Children.Add(PillButton("−", (_, _) => _canvas.ZoomOut()));
        row.Children.Add(PillButton("=", (_, _) => _canvas.ResetViewport()));
        row.Children.Add(PillButton("+", (_, _) => _canvas.ZoomIn()));
        return new Border
        {
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(18, 54, 0, 0),
            CornerRadius = new CornerRadius(12),
            Background = new SolidColorBrush(Color.Parse("#B3333333")),
            BoxShadow = new BoxShadows(new BoxShadow { Blur = 8, OffsetY = 3, Color = Color.Parse("#44000000") }),
            Child = row
        };
    }

    private Control BuildRightControls()
    {
        var stack = new StackPanel
        {
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 54, 18, 0),
            Spacing = 10
        };
        stack.Children.Add(SquareButton("⚙", (_, _) => ShowGlobalPanel(), "全局设置"));
        stack.Children.Add(SquareButton("◆", (_, _) => ShowLayersPanel(), "图层管理"));
        return stack;
    }

    private Control BuildBottomLeftControls()
    {
        var stack = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(18, 0, 0, 18),
            Spacing = 16
        };
        stack.Children.Add(_overlayButton);
        stack.Children.Add(_listenButton);
        var inputButton = ColoredButton("输入来源", "#FF9800", 120);
        inputButton.Click += (_, _) => ShowInputPanel();
        stack.Children.Add(inputButton);
        return stack;
    }

    private Control BuildBottomCenterControls()
    {
        var stack = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 0 };
        stack.Children.Add(PillButton("+", (_, _) => AddKey()));
        stack.Children.Add(PillButton("▣", (_, _) => DeleteSelected()));
        stack.Children.Add(PillButton("☷", (_, _) => ShowKeyPanel(_currentKey)));
        return new Border
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(0, 0, 0, 18),
            CornerRadius = new CornerRadius(32),
            Background = new SolidColorBrush(Color.Parse("#D9212121")),
            BoxShadow = new BoxShadows(new BoxShadow { Blur = 10, OffsetY = 3, Color = Color.Parse("#55000000") }),
            Child = stack
        };
    }

    private static Button PillButton(string text, EventHandler<RoutedEventArgs> handler)
    {
        var button = new Button
        {
            Content = text,
            Width = 72,
            Height = 64,
            FontSize = 30,
            FontWeight = FontWeight.Bold,
            Foreground = Brushes.White,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            HorizontalContentAlignment = HorizontalAlignment.Center,
            VerticalContentAlignment = VerticalAlignment.Center
        };
        button.Click += handler;
        return button;
    }

    private static Button ColoredButton(string text, string color, double width)
    {
        return new Button
        {
            Content = text,
            Width = width,
            Height = 54,
            FontSize = 16,
            Foreground = Brushes.White,
            Background = new SolidColorBrush(Color.Parse(color)),
            BorderThickness = new Thickness(0),
            HorizontalContentAlignment = HorizontalAlignment.Center,
            VerticalContentAlignment = VerticalAlignment.Center
        };
    }

    private static Button SquareButton(string text, EventHandler<RoutedEventArgs> handler, string tip)
    {
        var button = new Button
        {
            Content = text,
            Width = 64,
            Height = 64,
            FontSize = 30,
            Foreground = Brushes.White,
            Background = new SolidColorBrush(Color.Parse("#B3333333")),
            BorderThickness = new Thickness(0),
            CornerRadius = new CornerRadius(10),
            HorizontalContentAlignment = HorizontalAlignment.Center,
            VerticalContentAlignment = VerticalAlignment.Center
        };
        ToolTip.SetTip(button, tip);
        button.Click += handler;
        return button;
    }

    private void ShowPanel(string title, Control content, string mode)
    {
        _panelMode = mode;
        var close = new Button
        {
            Content = "×",
            Width = 42,
            Height = 38,
            FontSize = 24,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0)
        };
        close.Click += (_, _) => ClosePanel();
        var header = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Height = 54 };
        header.Children.Add(new TextBlock
        {
            Text = title,
            FontSize = 18,
            FontWeight = FontWeight.SemiBold,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(18, 0)
        });
        Grid.SetColumn(close, 1);
        header.Children.Add(close);
        var body = new Grid { RowDefinitions = new RowDefinitions("Auto,*") };
        body.Children.Add(header);
        Grid.SetRow(content, 1);
        body.Children.Add(content);
        _sideContent.Content = body;
        _sidePanel.Background = new SolidColorBrush(
            _host.Document.GlobalSettings.DarkModeEnabled ? Color.Parse("#252525") : Color.Parse("#F7F7F7"));
        _sidePanel.IsVisible = true;
    }

    private void ClosePanel()
    {
        CancelKeyCapture();
        _panelMode = string.Empty;
        _sidePanel.IsVisible = false;
        _sideContent.Content = null;
    }

    private void BeginThemeTransition(bool oldDarkMode, bool newDarkMode)
    {
        if (oldDarkMode == newDarkMode)
        {
            return;
        }

        _themeTransitionOverlay.Transitions = null;
        _themeTransitionOverlay.Background = new SolidColorBrush(
            Color.Parse(oldDarkMode ? "#252525" : "#F7F7F7"));
        _themeTransitionOverlay.Opacity = 0.88;
        Dispatcher.UIThread.Post(() =>
        {
            _themeTransitionOverlay.Transitions = new Transitions
            {
                new DoubleTransition
                {
                    Property = OpacityProperty,
                    Duration = TimeSpan.FromMilliseconds(260),
                    Easing = new CubicEaseOut()
                }
            };
            _themeTransitionOverlay.Opacity = 0;
        }, DispatcherPriority.Render);
    }

    private void ShowGlobalPanel()
    {
        var settings = _host.Document.GlobalSettings;
        var stack = PanelStack();
        stack.Children.Add(Section("显示"));
        var dark = new ToggleSwitch { Content = "深色模式", IsChecked = settings.DarkModeEnabled };
        dark.IsCheckedChanged += (_, _) =>
        {
            var enabled = dark.IsChecked == true;
            BeginThemeTransition(settings.DarkModeEnabled, enabled);
            settings.DarkModeEnabled = enabled;
            if (Application.Current is not null)
            {
                Application.Current.RequestedThemeVariant = settings.DarkModeEnabled
                    ? ThemeVariant.Dark
                    : ThemeVariant.Light;
            }
            _host.NotifyEdited();
            ShowGlobalPanel();
        };
        stack.Children.Add(dark);

        stack.Children.Add(Section("应用背景图片"));
        var backgroundRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var chooseBackground = new Button { Content = string.IsNullOrEmpty(settings.BackgroundImageAsset) ? "选择背景图" : "背景图已设置" };
        chooseBackground.Click += async (_, _) => await ChooseBackgroundAsync();
        var clearBackground = new Button { Content = "清除", IsEnabled = !string.IsNullOrEmpty(settings.BackgroundImageAsset) };
        clearBackground.Click += (_, _) =>
        {
            settings.BackgroundImageAsset = string.Empty;
            _canvas.ClearAssetCache();
            _host.NotifyEdited();
            ShowGlobalPanel();
        };
        backgroundRow.Children.Add(chooseBackground);
        backgroundRow.Children.Add(clearBackground);
        stack.Children.Add(backgroundRow);
        stack.Children.Add(Hint("替换主编辑画布背景；悬浮窗保持透明。背景图属于全局设置，不随 KV 配置导入导出。"));

        if (_platform is IInputBackendPlatformServices globalBackend)
        {
            stack.Children.Add(Section("Android 输入模式"));
            foreach (var option in globalBackend.InputBackendOptions.Where(option => option.IsEnabled))
            {
                var selected = option.Id.Equals(
                    globalBackend.ActiveInputBackend, StringComparison.OrdinalIgnoreCase);
                var mode = new Button
                {
                    Content = $"{(selected ? "●" : "○")} {option.DisplayName}",
                    HorizontalAlignment = HorizontalAlignment.Stretch,
                    HorizontalContentAlignment = HorizontalAlignment.Left
                };
                mode.Click += (_, _) =>
                {
                    globalBackend.SetInputBackend(option.Id);
                    ShowGlobalPanel();
                };
                stack.Children.Add(mode);
                stack.Children.Add(Hint(option.Description));
            }
            stack.Children.Add(Hint("状态：" + globalBackend.InputBackendStatus));
            var configureInput = new Button
            {
                Content = globalBackend.ActiveInputBackend.Equals(
                    "accessibility", StringComparison.OrdinalIgnoreCase)
                    ? "打开系统无障碍设置"
                    : globalBackend.ActiveInputBackend.Equals("root", StringComparison.OrdinalIgnoreCase)
                        ? "检查 Root / Sui 授权"
                        : "检查 / 授予 Shizuku 权限"
            };
            configureInput.Click += (_, _) => globalBackend.OpenInputBackendSettings();
            stack.Children.Add(configureInput);
        }

        stack.Children.Add(Section("输入保护"));
        var debounce = new ToggleSwitch { Content = "弹键防抖", IsChecked = settings.KeyDebounceEnabled };
        debounce.IsCheckedChanged += async (_, _) =>
        {
            var enabled = debounce.IsChecked == true;
            if (enabled && !await EnsureRoutedInputModeAsync("弹键防抖"))
            {
                debounce.IsChecked = false;
                return;
            }
            settings.KeyDebounceEnabled = enabled;
            _host.NotifyEdited();
        };
        stack.Children.Add(debounce);
        stack.Children.Add(NumberRow("防抖窗口（ms）", settings.KeyDebounceMillis, 1, 200, 1, value =>
        {
            settings.KeyDebounceMillis = (int)value;
            _host.NotifyEdited();
        }));
        var restriction = new ToggleSwitch
        {
            Content = "按键限制（拦截所有非画布按键）",
            IsChecked = settings.KeyRestrictionEnabled
        };
        restriction.IsCheckedChanged += async (_, _) =>
        {
            var enabled = restriction.IsChecked == true;
            if (enabled && !await EnsureRoutedInputModeAsync("按键限制"))
            {
                restriction.IsChecked = false;
                return;
            }
            settings.KeyRestrictionEnabled = enabled;
            _host.NotifyEdited();
        };
        stack.Children.Add(restriction);
        var observationOnly = _platform is IInputBackendPlatformServices inputBackend
            && !inputBackend.ActiveInputBackendSupportsRouting;
        stack.Children.Add(Hint(observationOnly
            ? "当前是无障碍只读模式：这些选项会保留在配置中，但不会拦截、修改或重注入系统按键；切到 Shizuku 或 Root / Sui 完整模式后才会生效。"
            : OperatingSystem.IsWindows()
                ? "警告：开启后，画布里没有的按键会被系统级拦截；下方名单还会额外拦截画布中的指定键。Esc 和左右 Windows 键永久放行。"
                : "只响应画布中已有的按键；Esc 和左右 Windows 键永久保留。"));
        var blocked = new TextBox
        {
            PlaceholderText = OperatingSystem.IsWindows()
                ? "明确拦截，例如：space, a, f1"
                : "限制按键，例如：space, a, f1",
            Text = settings.KeyRestrictionBlockedKeys,
            AcceptsReturn = true,
            MinHeight = 70
        };
        blocked.LostFocus += (_, _) =>
        {
            settings.KeyRestrictionBlockedKeys = blocked.Text ?? string.Empty;
            _host.NotifyEdited();
            blocked.Text = settings.KeyRestrictionBlockedKeys;
        };
        stack.Children.Add(blocked);

        stack.Children.Add(Section("悬浮窗"));
        stack.Children.Add(NumberRow("悬浮窗缩放", settings.FloatWindowScale, 0.5, 2, 0.05, value =>
        {
            settings.FloatWindowScale = value;
            _platform.RefreshOverlay();
            _host.NotifyEdited();
        }));
        stack.Children.Add(NumberRow("键雨速度（当前配置）", _host.Document.RainSpeedDp, 0, 1000, 0.5, value =>
        {
            _host.Document.RainSpeedDp = value;
            foreach (var key in _host.Document.Keys)
            {
                key.RainSpeedDp = value;
            }
            _host.NotifyEdited();
        }));
        stack.Children.Add(Hint("速度会应用到当前配置的全部键雨；每个配置分别保存自己的速度。"));
        stack.Children.Add(NumberRow("键雨最大高度", settings.GlobalRainHeightDp, 0, 5000, 10, value =>
        {
            settings.GlobalRainHeightDp = (int)value;
            settings.GlobalRainReserveHeightDp = Math.Min(settings.GlobalRainReserveHeightDp, settings.GlobalRainHeightDp);
            _host.NotifyEdited();
        }));
        stack.Children.Add(NumberRow("键雨预留高度", settings.GlobalRainReserveHeightDp, 0, settings.GlobalRainHeightDp, 1, value =>
        {
            settings.GlobalRainReserveHeightDp = (int)value;
            _host.NotifyEdited();
        }));
        stack.Children.Add(NumberRow("键雨圆角半径（0 = 直角）", settings.GlobalRainCornerRadiusDp, 0, 1000, 1, value =>
        {
            settings.GlobalRainCornerRadiusDp = value;
            _host.NotifyEdited();
        }));

        stack.Children.Add(Section("配置与数据"));
        var reset = new Button { Content = "重置所有按键计数" };
        reset.Click += (_, _) => _host.State.ResetCounts();
        stack.Children.Add(reset);
        stack.Children.Add(Hint("将按键计数归零（KPS/TOTAL 不受影响）"));
        var resetPosition = new Button { Content = "重置悬浮窗位置" };
        resetPosition.Click += (_, _) =>
        {
            settings.FloatWindowX = 100;
            settings.FloatWindowY = 200;
            _platform.ResetOverlayPosition();
            _host.NotifyEdited();
            _statusText.Text = "悬浮窗位置已重置";
        };
        stack.Children.Add(resetPosition);
        var calibrate = new Button { Content = "校准键盘设备" };
        calibrate.Click += (_, _) =>
        {
            foreach (var source in _host.Runtime.Sources)
            {
                source.Stop();
                source.Start();
            }
            _host.State.ReleaseAll();
            _statusText.Text = "键盘设备已重新检测";
        };
        stack.Children.Add(calibrate);

        stack.Children.Add(Section("配置管理"));
        var configRow = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*") };
        var export = new Button { Content = "导出配置", Margin = new Thickness(0, 0, 4, 0) };
        export.Click += async (_, _) => await ExportConfigAsync();
        var import = new Button { Content = "导入配置", Margin = new Thickness(4, 0, 0, 0) };
        import.Click += async (_, _) => await ImportConfigAsync();
        configRow.Children.Add(export);
        Grid.SetColumn(import, 1);
        configRow.Children.Add(import);
        stack.Children.Add(configRow);
        stack.Children.Add(Hint("恢复预设会覆盖当前配置，其他配置不受影响。"));
        var presetNames = ConfigSerializer.BundledPresets.Select(preset => preset.Name).ToArray();
        var presetPicker = new ComboBox
        {
            ItemsSource = presetNames,
            SelectedIndex = 0,
            HorizontalAlignment = HorizontalAlignment.Stretch
        };
        stack.Children.Add(presetPicker);
        var restorePreset = new Button { Content = "恢复预设（覆盖当前配置）" };
        restorePreset.Click += async (_, _) =>
        {
            if (presetPicker.SelectedItem is not string presetName
                || !await ConfirmAsync("恢复预设", $"使用“{presetName}”覆盖当前配置？")) return;
            _host.RestoreCurrentFromBundledPreset(presetName);
            _canvas.ClearAssetCache();
            _canvas.SelectedKey = null;
            _currentKey = null;
            _statusText.Text = $"当前配置已恢复为：{presetName}";
        };
        stack.Children.Add(restorePreset);
        ShowPanel("全局设置", new ScrollViewer { Content = stack }, "global");
    }

    private void ShowKeyPanel(KeyDefinition? key)
    {
        if (key is null)
        {
            ShowPanel("按键设置", new TextBlock
            {
                Text = "请先在画布中选择一个按键。",
                Margin = new Thickness(20),
                TextWrapping = TextWrapping.Wrap
            }, "key");
            return;
        }

        var stack = PanelStack();
        var isSpecialKey = key.IsCounter;

        stack.Children.Add(Section("绑定按键"));
        stack.Children.Add(CaptureRow("绑定按键", key.BoundKey, value =>
        {
            key.BoundKey = value;
            RemoveSelfMapping(key);
            ShowKeyPanel(key);
        }));
        if (!isSpecialKey)
        {
            var mapping = new ToggleSwitch
            {
                Content = "启用按键映射",
                IsChecked = key.MappingEnabled
            };
            mapping.IsCheckedChanged += async (_, _) =>
            {
                var enabled = mapping.IsChecked == true;
                if (enabled && !await EnsureRoutedInputModeAsync("按键映射"))
                {
                    mapping.IsChecked = false;
                    return;
                }
                key.MappingEnabled = enabled;
                RemoveSelfMapping(key);
                _host.NotifyEdited();
                ShowKeyPanel(key);
            };
            stack.Children.Add(mapping);
            if (key.MappingEnabled)
            {
                stack.Children.Add(CaptureRow("映射按键", key.MappedKey, value =>
                {
                    key.MappedKey = value;
                    RemoveSelfMapping(key);
                    ShowKeyPanel(key);
                }));
            }
            var ghost = new ToggleSwitch
            {
                Content = "鬼键（显示但拦截输入）",
                IsChecked = key.GhostKey
            };
            ghost.IsCheckedChanged += async (_, _) =>
            {
                var enabled = ghost.IsChecked == true;
                if (enabled && !await EnsureRoutedInputModeAsync("鬼键"))
                {
                    ghost.IsChecked = false;
                    return;
                }
                key.GhostKey = enabled;
                _host.NotifyEdited();
            };
            stack.Children.Add(ghost);
        }

        stack.Children.Add(Section("显示"));
        stack.Children.Add(TextRow("显示文字", key.DisplayLabel, value => key.DisplayLabel = value));
        stack.Children.Add(NumberRow("文字大小 (sp)", key.TextSizeSp, 4, 300, 1, value => { key.TextSizeSp = value; _host.NotifyEdited(); }));
        stack.Children.Add(NumberRow("数字大小 (sp)", key.CountTextSizeSp ?? key.TextSizeSp, 4, 300, 1, value => { key.CountTextSizeSp = value; _host.NotifyEdited(); }));
        stack.Children.Add(NumberRow("文字 X 偏移", key.TextOffsetXDp, -5000, 5000, 1, value => { key.TextOffsetXDp = value; _host.NotifyEdited(); }));
        stack.Children.Add(NumberRow("文字 Y 偏移", key.TextOffsetYDp, -5000, 5000, 1, value => { key.TextOffsetYDp = value; _host.NotifyEdited(); }));
        stack.Children.Add(NumberRow("数字 X 偏移", key.CountOffsetXDp, -5000, 5000, 1, value => { key.CountOffsetXDp = value; _host.NotifyEdited(); }));
        stack.Children.Add(NumberRow("数字 Y 偏移", key.CountOffsetYDp, -5000, 5000, 1, value => { key.CountOffsetYDp = value; _host.NotifyEdited(); }));
        var autoFitText = new ToggleSwitch { Content = "文字自动适应按键大小", IsChecked = key.AutoFitText };
        autoFitText.IsCheckedChanged += (_, _) =>
        {
            key.AutoFitText = autoFitText.IsChecked == true;
            _host.NotifyEdited();
        };
        stack.Children.Add(autoFitText);
        var textShadow = new ToggleSwitch { Content = "文字阴影", IsChecked = key.TextShadowEnabled };
        textShadow.IsCheckedChanged += (_, _) =>
        {
            key.TextShadowEnabled = textShadow.IsChecked == true;
            _host.NotifyEdited();
            ShowKeyPanel(key);
        };
        stack.Children.Add(textShadow);
        if (key.TextShadowEnabled)
        {
            stack.Children.Add(ColorRow("阴影颜色", key.TextShadowColor, value => key.TextShadowColor = value));
            stack.Children.Add(NumberRow("阴影 X 偏移", key.TextShadowOffsetXDp, -500, 500, 0.5,
                value => { key.TextShadowOffsetXDp = value; _host.NotifyEdited(); }));
            stack.Children.Add(NumberRow("阴影 Y 偏移", key.TextShadowOffsetYDp, -500, 500, 0.5,
                value => { key.TextShadowOffsetYDp = value; _host.NotifyEdited(); }));
            stack.Children.Add(NumberRow("阴影模糊半径", key.TextShadowBlurRadiusDp, 0, 100, 0.5,
                value => { key.TextShadowBlurRadiusDp = value; _host.NotifyEdited(); }));
        }
        stack.Children.Add(Section("字体"));
        stack.Children.Add(BuildFontPicker(
            key.FontAsset,
            value => { key.FontAsset = value; _canvas.ClearAssetCache(); _host.NotifyEdited(); ShowKeyPanel(key); },
            async () => await ChooseKeyAssetAsync(key, true)));
        stack.Children.Add(Section("按键图片（PNG / JPEG / WebP / GIF）"));
        var imageRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var chooseImage = new Button { Content = string.IsNullOrWhiteSpace(key.ImageAsset) ? "选择图片" : "图片已设置" };
        chooseImage.Click += async (_, _) => await ChooseKeyAssetAsync(key, false);
        var clearImage = new Button { Content = "清除", IsEnabled = !string.IsNullOrWhiteSpace(key.ImageAsset) };
        clearImage.Click += (_, _) =>
        {
            key.ImageAsset = string.Empty;
            _canvas.ClearAssetCache();
            _host.NotifyEdited();
            ShowKeyPanel(key);
        };
        imageRow.Children.Add(chooseImage);
        imageRow.Children.Add(clearImage);
        stack.Children.Add(imageRow);
        var replaceLabel = new ToggleSwitch { Content = "图片取代键名（保留计数）", IsChecked = key.ImageReplacesLabel };
        replaceLabel.IsCheckedChanged += (_, _) => { key.ImageReplacesLabel = replaceLabel.IsChecked == true; _host.NotifyEdited(); };
        stack.Children.Add(replaceLabel);
        if (!isSpecialKey)
        {
            var showCount = new ToggleSwitch { Content = "显示按键次数", IsChecked = key.ShowCount };
            showCount.IsCheckedChanged += (_, _) => { key.ShowCount = showCount.IsChecked == true; _host.NotifyEdited(); };
            stack.Children.Add(showCount);
            stack.Children.Add(NumberRow("按键次数", _host.State.GetCount(key.BoundKey), 0, int.MaxValue, 1, value =>
            {
                _host.State.SetCount(key.BoundKey, (int)value);
            }));
        }
        stack.Children.Add(NumberRow("图层顺序", key.ZIndex, -100000, 100000, 1, value => { key.ZIndex = (int)value; _host.NotifyEdited(); }));

        stack.Children.Add(Section("尺寸"));
        stack.Children.Add(NumberRow("W", key.Width, 8, 5000, 1, value => { key.Width = value; _host.NotifyEdited(); }));
        stack.Children.Add(NumberRow("H", key.Height, 8, 5000, 1, value => { key.Height = value; _host.NotifyEdited(); }));
        stack.Children.Add(NumberRow("圆角半径", key.CornerRadiusDp, 0, 2500, 1, value => { key.CornerRadiusDp = value; _host.NotifyEdited(); }));
        stack.Children.Add(NumberRow("边框宽度", key.BorderWidthDp, 0, 500, 1, value => { key.BorderWidthDp = value; _host.NotifyEdited(); }));

        stack.Children.Add(Section("颜色设置"));
        if (isSpecialKey)
        {
            stack.Children.Add(ColorRow("填充颜色", key.FillColorUp, value => key.FillColorUp = value));
            stack.Children.Add(ColorRow("边框颜色", key.BorderColorUp, value => key.BorderColorUp = value));
            stack.Children.Add(ColorRow("文字颜色", key.TextColorUp, value => key.TextColorUp = value));
            stack.Children.Add(ColorRow("计数颜色", key.CountColorUp, value => key.CountColorUp = value));
        }
        else
        {
            stack.Children.Add(Section("放开时"));
            stack.Children.Add(ColorRow("填充颜色", key.FillColorUp, value => key.FillColorUp = value));
            stack.Children.Add(ColorRow("边框颜色", key.BorderColorUp, value => key.BorderColorUp = value));
            stack.Children.Add(ColorRow("文字颜色", key.TextColorUp, value => key.TextColorUp = value));
            stack.Children.Add(ColorRow("计数颜色", key.CountColorUp, value => key.CountColorUp = value));
            stack.Children.Add(Section("按下时"));
            stack.Children.Add(ColorRow("填充颜色", key.FillColorDown, value => key.FillColorDown = value));
            stack.Children.Add(ColorRow("边框颜色", key.BorderColorDown, value => key.BorderColorDown = value));
            stack.Children.Add(ColorRow("文字颜色", key.TextColorDown, value => key.TextColorDown = value));
            stack.Children.Add(ColorRow("计数颜色", key.CountColorDown, value => key.CountColorDown = value));
        }

        if (!isSpecialKey)
        {
            stack.Children.Add(Section("键雨效果"));
            var rain = new ToggleSwitch { Content = "启用键雨", IsChecked = key.RainEnabled };
            rain.IsCheckedChanged += (_, _) =>
            {
                key.RainEnabled = rain.IsChecked == true;
                _host.NotifyEdited();
                ShowKeyPanel(key);
            };
            stack.Children.Add(rain);
            if (key.RainEnabled)
            {
                stack.Children.Add(NumberRow("X 偏移", key.RainOffsetXDp, -5000, 5000, 1, value => { key.RainOffsetXDp = value; _host.NotifyEdited(); }));
                stack.Children.Add(NumberRow("Y 偏移", key.RainOffsetYDp, -5000, 5000, 1, value => { key.RainOffsetYDp = value; _host.NotifyEdited(); }));
                stack.Children.Add(NumberRow("宽度（% 外框宽度）", key.RainWidthPercent, 1, 1000, 1, value => { key.RainWidthPercent = value; _host.NotifyEdited(); }));
                stack.Children.Add(ColorRow("键雨颜色", key.RainColor, value => key.RainColor = value));
                var glow = new ToggleSwitch { Content = "启用键雨泛光", IsChecked = key.RainGlowEnabled };
                glow.IsCheckedChanged += (_, _) => { key.RainGlowEnabled = glow.IsChecked == true; _host.NotifyEdited(); };
                stack.Children.Add(glow);
                stack.Children.Add(NumberRow("泛光半径 (dp)", key.RainGlowRadiusDp, 0, 200, 1, value => { key.RainGlowRadiusDp = value; _host.NotifyEdited(); }));
            }
        }

        if (isSpecialKey)
        {
            stack.Children.Add(Section("KPS/TOTAL 显示"));
            var noWrap = new ToggleSwitch { Content = "不换行（单行）", IsChecked = key.KpsTotalNoWrap };
            noWrap.IsCheckedChanged += (_, _) =>
            {
                key.KpsTotalNoWrap = noWrap.IsChecked == true;
                _host.NotifyEdited();
                ShowKeyPanel(key);
            };
            stack.Children.Add(noWrap);
            stack.Children.Add(CounterCaseRow(key.CounterLabelCase, value =>
            {
                key.CounterLabelCase = value;
                _host.NotifyEdited();
            }));
        }

        ShowPanel("按键设置", new ScrollViewer { Content = stack }, "key");
    }

    private void ShowBatchKeyPanel()
    {
        var baseline = _canvas.SelectionBaseline;
        var keys = baseline is null
            ? _canvas.SelectedKeys.ToArray()
            : new[] { baseline }
                .Concat(_canvas.SelectedKeys.Where(key => !ReferenceEquals(key, baseline)))
                .ToArray();
        if (!_canvas.BatchEditMode || keys.Length == 0)
        {
            ShowPanel("批量设置", new TextBlock
            {
                Text = "请先进入批量编辑并选择至少一个按键。",
                Margin = new Thickness(20),
                TextWrapping = TextWrapping.Wrap
            }, "batch");
            return;
        }

        var stack = PanelStack();
        stack.Children.Add(Hint($"已选择 {keys.Length} 个按键。当前以“{baseline?.DisplayText ?? "未绑定"}”为基准显示数值；只有你实际修改的项目才会覆盖全部。"));

        stack.Children.Add(Section("绑定与标记"));
        stack.Children.Add(BatchCaptureRow("绑定按键", keys.Select(key => key.BoundKey).ToArray(), value =>
        {
            ApplyBatch(keys, new KeyBatchPatch { BoundKey = value }, "绑定按键");
            foreach (var key in keys)
            {
                RemoveSelfMapping(key);
            }
            _host.NotifyEdited();
            ShowBatchKeyPanel();
        }));
        var mappingValues = keys.Select(key => key.MappingEnabled).ToArray();
        var mappingToggle = new ToggleSwitch
        {
            Content = "启用按键映射",
            IsChecked = mappingValues[0]
        };
        mappingToggle.IsCheckedChanged += async (_, _) =>
        {
            if (mappingToggle.IsChecked is not bool enabled)
            {
                return;
            }

            mappingToggle.IsThreeState = false;
            if (enabled && !await EnsureRoutedInputModeAsync("批量按键映射"))
            {
                mappingToggle.IsChecked = false;
                return;
            }
            if (enabled)
            {
                foreach (var key in keys)
                {
                    RemoveSelfMapping(key);
                }
            }
            ApplyBatch(keys, new KeyBatchPatch { MappingEnabled = enabled }, "映射开关");
            ShowBatchKeyPanel();
        };
        stack.Children.Add(mappingToggle);
        if (mappingValues[0])
        {
            stack.Children.Add(BatchCaptureRow("映射按键", keys.Select(key => key.MappedKey).ToArray(), value =>
            {
                ApplyBatch(keys, new KeyBatchPatch { MappedKey = value }, "映射按键");
                ShowBatchKeyPanel();
            }));
        }
        var ghostValues = keys.Select(key => key.GhostKey).ToArray();
        var ghostToggle = new ToggleSwitch
        {
            Content = "鬼键（显示但拦截输入）",
            IsChecked = ghostValues[0]
        };
        ghostToggle.IsCheckedChanged += async (_, _) =>
        {
            if (ghostToggle.IsChecked is not bool enabled) return;
            ghostToggle.IsThreeState = false;
            if (enabled && !await EnsureRoutedInputModeAsync("批量鬼键"))
            {
                ghostToggle.IsChecked = false;
                return;
            }
            ApplyBatch(keys, new KeyBatchPatch { GhostKey = enabled }, "鬼键");
        };
        stack.Children.Add(ghostToggle);

        stack.Children.Add(Section("显示"));
        stack.Children.Add(BatchTextRow("显示文字", keys.Select(key => key.DisplayLabel).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { DisplayLabel = value }, "显示文字")));
        stack.Children.Add(BatchNumberRow("文字大小 (sp)", keys.Select(key => key.TextSizeSp).ToArray(), 4, 300, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { TextSizeSp = value }, "文字大小")));
        stack.Children.Add(BatchNumberRow("数字大小 (sp)", keys.Select(key => key.CountTextSizeSp ?? key.TextSizeSp).ToArray(), 4, 300, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { CountTextSizeSp = value }, "数字大小")));
        stack.Children.Add(BatchNumberRow("文字 X 偏移", keys.Select(key => key.TextOffsetXDp).ToArray(), -5000, 5000, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { TextOffsetXDp = value }, "文字 X 偏移")));
        stack.Children.Add(BatchNumberRow("文字 Y 偏移", keys.Select(key => key.TextOffsetYDp).ToArray(), -5000, 5000, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { TextOffsetYDp = value }, "文字 Y 偏移")));
        stack.Children.Add(BatchNumberRow("数字 X 偏移", keys.Select(key => key.CountOffsetXDp).ToArray(), -5000, 5000, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { CountOffsetXDp = value }, "数字 X 偏移")));
        stack.Children.Add(BatchNumberRow("数字 Y 偏移", keys.Select(key => key.CountOffsetYDp).ToArray(), -5000, 5000, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { CountOffsetYDp = value }, "数字 Y 偏移")));
        stack.Children.Add(BatchToggleRow("文字自动适应按键大小", keys.Select(key => key.AutoFitText).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { AutoFitText = value }, "文字自动适应")));
        stack.Children.Add(BatchToggleRow("文字阴影", keys.Select(key => key.TextShadowEnabled).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { TextShadowEnabled = value }, "文字阴影")));
        stack.Children.Add(BatchColorRow("阴影颜色", keys.Select(key => key.TextShadowColor).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { TextShadowColor = value }, "阴影颜色")));
        stack.Children.Add(BatchNumberRow("阴影 X 偏移", keys.Select(key => key.TextShadowOffsetXDp).ToArray(), -500, 500, 0.5,
            value => ApplyBatch(keys, new KeyBatchPatch { TextShadowOffsetXDp = value }, "阴影 X 偏移")));
        stack.Children.Add(BatchNumberRow("阴影 Y 偏移", keys.Select(key => key.TextShadowOffsetYDp).ToArray(), -500, 500, 0.5,
            value => ApplyBatch(keys, new KeyBatchPatch { TextShadowOffsetYDp = value }, "阴影 Y 偏移")));
        stack.Children.Add(BatchNumberRow("阴影模糊半径", keys.Select(key => key.TextShadowBlurRadiusDp).ToArray(), 0, 100, 0.5,
            value => ApplyBatch(keys, new KeyBatchPatch { TextShadowBlurRadiusDp = value }, "阴影模糊")));
        stack.Children.Add(BatchToggleRow("显示按键次数", keys.Select(key => key.ShowCount).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { ShowCount = value }, "显示次数")));
        stack.Children.Add(BatchToggleRow("图片取代键名", keys.Select(key => key.ImageReplacesLabel).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { ImageReplacesLabel = value }, "图片显示方式")));

        stack.Children.Add(Section("字体"));
        stack.Children.Add(BatchAssetRow(keys, true));
        stack.Children.Add(Section("按键图片（PNG / JPEG / WebP / GIF）"));
        stack.Children.Add(BatchAssetRow(keys, false));

        stack.Children.Add(Section("尺寸"));
        stack.Children.Add(BatchNumberRow("W", keys.Select(key => key.Width).ToArray(), 8, 5000, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { Width = value }, "宽度")));
        stack.Children.Add(BatchNumberRow("H", keys.Select(key => key.Height).ToArray(), 8, 5000, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { Height = value }, "高度")));
        stack.Children.Add(BatchNumberRow("圆角半径", keys.Select(key => key.CornerRadiusDp).ToArray(), 0, 2500, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { CornerRadiusDp = value }, "圆角")));
        stack.Children.Add(BatchNumberRow("边框宽度", keys.Select(key => key.BorderWidthDp).ToArray(), 0, 500, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { BorderWidthDp = value }, "边框宽度")));

        stack.Children.Add(Section("放开时颜色"));
        stack.Children.Add(BatchColorRow("填充颜色", keys.Select(key => key.FillColorUp).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { FillColorUp = value }, "放开填充颜色")));
        stack.Children.Add(BatchColorRow("边框颜色", keys.Select(key => key.BorderColorUp).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { BorderColorUp = value }, "放开边框颜色")));
        stack.Children.Add(BatchColorRow("文字颜色", keys.Select(key => key.TextColorUp).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { TextColorUp = value }, "放开文字颜色")));
        stack.Children.Add(BatchColorRow("计数颜色", keys.Select(key => key.CountColorUp).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { CountColorUp = value }, "放开计数颜色")));

        stack.Children.Add(Section("按下时颜色"));
        stack.Children.Add(BatchColorRow("填充颜色", keys.Select(key => key.FillColorDown).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { FillColorDown = value }, "按下填充颜色")));
        stack.Children.Add(BatchColorRow("边框颜色", keys.Select(key => key.BorderColorDown).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { BorderColorDown = value }, "按下边框颜色")));
        stack.Children.Add(BatchColorRow("文字颜色", keys.Select(key => key.TextColorDown).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { TextColorDown = value }, "按下文字颜色")));
        stack.Children.Add(BatchColorRow("计数颜色", keys.Select(key => key.CountColorDown).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { CountColorDown = value }, "按下计数颜色")));

        stack.Children.Add(Section("键雨效果"));
        stack.Children.Add(BatchToggleRow("启用键雨", keys.Select(key => key.RainEnabled).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { RainEnabled = value }, "键雨开关")));
        stack.Children.Add(BatchNumberRow("X 偏移", keys.Select(key => key.RainOffsetXDp).ToArray(), -5000, 5000, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { RainOffsetXDp = value }, "键雨 X 偏移")));
        stack.Children.Add(BatchNumberRow("Y 偏移", keys.Select(key => key.RainOffsetYDp).ToArray(), -5000, 5000, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { RainOffsetYDp = value }, "键雨 Y 偏移")));
        stack.Children.Add(BatchNumberRow("宽度（% 外框宽度）", keys.Select(key => key.RainWidthPercent).ToArray(), 1, 1000, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { RainWidthPercent = value }, "键雨宽度")));
        stack.Children.Add(BatchColorRow("键雨颜色", keys.Select(key => key.RainColor).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { RainColor = value }, "键雨颜色")));
        stack.Children.Add(BatchToggleRow("启用键雨泛光", keys.Select(key => key.RainGlowEnabled).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { RainGlowEnabled = value }, "键雨泛光")));
        stack.Children.Add(BatchNumberRow("泛光半径", keys.Select(key => key.RainGlowRadiusDp).ToArray(), 0, 200, 1,
            value => ApplyBatch(keys, new KeyBatchPatch { RainGlowRadiusDp = value }, "泛光半径")));

        stack.Children.Add(Section("KPS / TOTAL 显示"));
        stack.Children.Add(BatchToggleRow("不换行（单行）", keys.Select(key => key.KpsTotalNoWrap).ToArray(),
            value => ApplyBatch(keys, new KeyBatchPatch { KpsTotalNoWrap = value }, "KPS/TOTAL 换行")));
        stack.Children.Add(CounterCaseRow(keys[0].CounterLabelCase,
            value => ApplyBatch(keys.Where(key => key.IsCounter).ToArray(), new KeyBatchPatch { CounterLabelCase = value }, "计数器大小写")));

        ShowPanel($"批量设置（{keys.Length}）", new ScrollViewer { Content = stack }, "batch");
    }

    private void ApplyBatch(IReadOnlyList<KeyDefinition> keys, KeyBatchPatch patch, string propertyName)
    {
        var current = keys.Where(key => _host.Document.Keys.Contains(key)).ToArray();
        if (current.Length == 0)
        {
            return;
        }

        patch.Apply(current);
        _host.NotifyEdited();
        _statusText.Text = $"已将“{propertyName}”应用到 {current.Length} 个按键";
    }

    private static void RemoveSelfMapping(KeyDefinition key)
    {
        if (string.IsNullOrWhiteSpace(key.MappedKey)
            || !KeyViewer.Core.KeyNames.Equals(key.BoundKey, key.MappedKey))
        {
            return;
        }

        key.MappingEnabled = false;
        key.MappedKey = string.Empty;
    }

    private Control BatchTextRow(string label, IReadOnlyList<string> values, Action<string> update)
    {
        var box = new TextBox
        {
            Text = values[0]
        };
        var dirty = false;
        box.TextChanged += (_, _) => dirty = true;
        box.LostFocus += (_, _) =>
        {
            if (!dirty)
            {
                return;
            }
            update(box.Text ?? string.Empty);
            dirty = false;
        };
        return Labeled(label, box);
    }

    private static Control BatchNumberRow(
        string label,
        IReadOnlyList<double> values,
        double minimum,
        double maximum,
        double increment,
        Action<double> update)
    {
        var number = new NumericUpDown
        {
            Value = (decimal)values[0],
            Minimum = (decimal)minimum,
            Maximum = (decimal)maximum,
            Increment = (decimal)increment,
            FormatString = increment < 1 ? "0.00" : "0"
        };
        var ready = true;
        number.ValueChanged += (_, _) =>
        {
            if (ready && number.Value is decimal current)
            {
                update((double)current);
            }
        };
        return Labeled(label, number);
    }

    private static Control BatchToggleRow(string label, IReadOnlyList<bool> values, Action<bool> update)
    {
        var toggle = new ToggleSwitch
        {
            Content = label,
            IsChecked = values[0]
        };
        var ready = true;
        toggle.IsCheckedChanged += (_, _) =>
        {
            if (ready && toggle.IsChecked is bool current)
            {
                toggle.IsThreeState = false;
                update(current);
            }
        };
        return toggle;
    }

    private Control BatchCaptureRow(string label, IReadOnlyList<string> values, Action<string> update)
    {
        var original = values[0];
        var button = new Button
        {
            Content = !string.IsNullOrWhiteSpace(original)
                ? original
                : "点击后按键",
            HorizontalContentAlignment = HorizontalAlignment.Left
        };
        button.Click += (_, _) => BeginKeyCapture(button, original, update);
        return Labeled(label, button);
    }

    private Control BatchColorRow(string label, IReadOnlyList<int> values, Action<int> update)
    {
        var box = new TextBox
        {
            Text = $"#{unchecked((uint)values[0]):X8}"
        };
        var picker = new ColorPicker
        {
            Width = 44,
            Height = 36,
            Color = Color.FromUInt32(unchecked((uint)values[0])),
            IsAlphaEnabled = true,
            IsAlphaVisible = true
        };
        var dirty = false;
        box.TextChanged += (_, _) => dirty = true;
        box.LostFocus += (_, _) =>
        {
            if (!dirty)
            {
                return;
            }
            if (TryParseColor(box.Text, out var parsed))
            {
                update(parsed);
                picker.Color = Color.FromUInt32(unchecked((uint)parsed));
                dirty = false;
            }
        };
        picker.ColorChanged += (_, _) =>
        {
            var parsed = unchecked((int)picker.Color.ToUInt32());
            box.Text = $"#{unchecked((uint)parsed):X8}";
            update(parsed);
            dirty = false;
        };
        var editor = new Grid { ColumnDefinitions = new ColumnDefinitions("44,*"), ColumnSpacing = 8 };
        editor.Children.Add(picker);
        Grid.SetColumn(box, 1);
        editor.Children.Add(box);
        return Labeled(label, editor);
    }

    private Control BatchAssetRow(IReadOnlyList<KeyDefinition> keys, bool font)
    {
        if (font)
        {
            var fontValues = keys.Select(key => key.FontAsset).ToArray();
            return BuildFontPicker(
                fontValues[0],
                value =>
                {
                    ApplyBatch(keys, new KeyBatchPatch { FontAsset = value }, "字体");
                    _canvas.ClearAssetCache();
                    ShowBatchKeyPanel();
                },
                async () => await ChooseBatchKeyAssetAsync(keys, true));
        }

        var values = keys.Select(key => font ? key.FontAsset : key.ImageAsset).ToArray();
        var hasAsset = !string.IsNullOrWhiteSpace(values[0]);
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var choose = new Button
        {
            Content = hasAsset ? (font ? "字体已设置" : "图片已设置") : (font ? "选择字体" : "选择图片")
        };
        choose.Click += async (_, _) => await ChooseBatchKeyAssetAsync(keys, font);
        var clear = new Button { Content = "全部清除" };
        clear.Click += (_, _) =>
        {
            ApplyBatch(keys, font
                ? new KeyBatchPatch { FontAsset = string.Empty }
                : new KeyBatchPatch { ImageAsset = string.Empty }, font ? "字体" : "图片");
            if (!font)
            {
                _canvas.ClearAssetCache();
            }
            ShowBatchKeyPanel();
        };
        row.Children.Add(choose);
        row.Children.Add(clear);
        return row;
    }

    private static Control CounterCaseRow(string currentValue, Action<string> apply)
    {
        var choices = new[]
        {
            new CounterCaseChoice("全大写", "upper"),
            new CounterCaseChoice("首字母大写", "title"),
            new CounterCaseChoice("全小写", "lower")
        };
        var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,180"), Margin = new Thickness(0, 2) };
        row.Children.Add(new TextBlock { Text = "标签大小写", VerticalAlignment = VerticalAlignment.Center });
        var picker = new ComboBox
        {
            ItemsSource = choices,
            SelectedItem = choices.FirstOrDefault(choice => choice.Value == currentValue) ?? choices[0]
        };
        Grid.SetColumn(picker, 1);
        picker.SelectionChanged += (_, _) =>
        {
            if (picker.SelectedItem is CounterCaseChoice choice)
            {
                apply(choice.Value);
            }
        };
        row.Children.Add(picker);
        return row;
    }

    private Control BuildFontPicker(string? currentValue, Action<string> apply, Func<Task> import)
    {
        var choices = FontChoices(currentValue).ToArray();
        var stack = new StackPanel { Spacing = 8 };
        var picker = new ComboBox
        {
            HorizontalAlignment = HorizontalAlignment.Stretch,
            ItemsSource = choices,
            SelectedItem = choices.FirstOrDefault(choice =>
                string.Equals(choice.Value, currentValue ?? string.Empty, StringComparison.OrdinalIgnoreCase))
                ?? choices[0]
        };
        var syncing = true;
        picker.SelectionChanged += (_, _) =>
        {
            if (!syncing && picker.SelectedItem is FontChoice choice)
            {
                apply(choice.Value);
            }
        };
        syncing = false;
        var importButton = new Button { Content = "导入 TTF / OTF 文件" };
        importButton.Click += async (_, _) => await import();
        stack.Children.Add(picker);
        stack.Children.Add(importButton);
        if (OperatingSystem.IsWindows())
        {
            stack.Children.Add(Hint("系统字体只保存字体家族名称；配置在安卓或缺少该字体的电脑上会回退到 Maplestory。"));
        }
        return stack;
    }

    private static IEnumerable<FontChoice> FontChoices(string? currentValue)
    {
        yield return new FontChoice("Maplestory OTF Bold（默认内建）", string.Empty);
        yield return new FontChoice("ADOFAI / godoMaum（内建）", "builtin:adofai");
        yield return new FontChoice("系统无衬线", "builtin:sans");
        yield return new FontChoice("系统衬线", "builtin:serif");
        yield return new FontChoice("系统等宽", "builtin:monospace");

        if (!string.IsNullOrWhiteSpace(currentValue)
            && !currentValue.StartsWith("builtin:", StringComparison.OrdinalIgnoreCase)
            && !currentValue.StartsWith("system:", StringComparison.OrdinalIgnoreCase))
        {
            yield return new FontChoice("已导入字体文件", currentValue);
        }

        if (!OperatingSystem.IsWindows()) yield break;
        foreach (var name in FontManager.Current.SystemFonts
                     .Select(font => font.Name)
                     .Where(name => !string.IsNullOrWhiteSpace(name))
                     .Distinct(StringComparer.CurrentCultureIgnoreCase)
                     .OrderBy(name => name, StringComparer.CurrentCultureIgnoreCase))
        {
            yield return new FontChoice($"Windows：{name}", $"system:{name}");
        }
    }

    private sealed record FontChoice(string Label, string Value)
    {
        public override string ToString() => Label;
    }

    private sealed record CounterCaseChoice(string Label, string Value)
    {
        public override string ToString() => Label;
    }

    private void ShowLayersPanel()
    {
        var darkMode = _host.Document.GlobalSettings.DarkModeEnabled;
        var listBackground = new SolidColorBrush(Color.Parse(darkMode ? "#252525" : "#FFFFFF"));
        var rowBackground = new SolidColorBrush(Color.Parse(darkMode ? "#2D2D2D" : "#FFFFFF"));
        var selectedBackground = new SolidColorBrush(Color.Parse(darkMode ? "#294A63" : "#E3F2FD"));
        var primaryText = new SolidColorBrush(Color.Parse(darkMode ? "#FFFFFF" : "#212121"));
        var secondaryText = new SolidColorBrush(Color.Parse(darkMode ? "#BDBDBD" : "#777777"));
        var root = new Grid
        {
            Margin = new Thickness(18, 4, 18, 16),
            RowDefinitions = new RowDefinitions("Auto,*,Auto")
        };
        var hints = new StackPanel { Spacing = 8, Margin = new Thickness(0, 8, 0, 12) };
        hints.Children.Add(Hint("拖动 ≡ 调整图层顺序"));
        hints.Children.Add(Hint("画面越靠下（Y 越大）的按键，图层越高"));
        root.Children.Add(hints);

        var ordered = _host.Document.Keys
            .OrderByDescending(key => key.ZIndex)
            .ToList();
        var list = new StackPanel { Background = listBackground, Spacing = 0 };

        void ApplyOrder(bool persist)
        {
            for (var index = 0; index < ordered.Count; index++)
            {
                ordered[index].ZIndex = ordered.Count - index;
            }

            _canvas.InvalidateVisual();
            if (persist)
            {
                _host.NotifyEdited();
            }
        }

        foreach (var key in ordered)
        {
            var row = new Grid
            {
                Height = 56,
                ColumnDefinitions = new ColumnDefinitions("40,24,*,Auto,24"),
                Background = ReferenceEquals(_canvas.SelectedKey, key)
                    ? selectedBackground
                    : rowBackground
            };
            var handle = new Border
            {
                Width = 40,
                Height = 40,
                Background = Brushes.Transparent,
                Cursor = new Cursor(StandardCursorType.SizeNorthSouth),
                Child = new TextBlock
                {
                    Text = "≡",
                    Foreground = new SolidColorBrush(Color.Parse("#999999")),
                    FontSize = 20,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center
                }
            };
            var color = new Border
            {
                Width = 16,
                Height = 16,
                Background = new SolidColorBrush(Color.FromUInt32(unchecked((uint)key.FillColorUp))),
                VerticalAlignment = VerticalAlignment.Center
            };
            var name = new TextBlock
            {
                Text = string.IsNullOrWhiteSpace(key.DisplayText) ? "未命名按键" : key.DisplayText,
                VerticalAlignment = VerticalAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(8, 0),
                Foreground = primaryText
            };
            var zIndex = new TextBlock
            {
                Text = key.ZIndex.ToString(CultureInfo.InvariantCulture),
                FontSize = 12,
                Opacity = 0.6,
                Foreground = secondaryText,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(4, 0, 8, 0)
            };
            var direction = new TextBlock
            {
                Text = "↕",
                Foreground = secondaryText,
                VerticalAlignment = VerticalAlignment.Center
            };
            row.PointerPressed += (_, e) =>
            {
                if (e.GetCurrentPoint(row).Properties.IsLeftButtonPressed
                    && e.GetPosition(row).X >= 40)
                {
                    _canvas.SelectedKey = key;
                    _currentKey = key;
                    row.Background = selectedBackground;
                }
            };

            var dragStarted = false;
            handle.PointerPressed += (_, e) =>
            {
                if (!e.GetCurrentPoint(handle).Properties.IsLeftButtonPressed)
                {
                    return;
                }
                dragStarted = true;
                e.Pointer.Capture(handle);
                _statusText.Text = $"正在调整图层：{key.DisplayText}";
                e.Handled = true;
            };
            handle.PointerMoved += (_, e) =>
            {
                if (!dragStarted || ordered.Count == 0)
                {
                    return;
                }
                var targetIndex = Math.Clamp((int)(e.GetPosition(list).Y / 56), 0, ordered.Count - 1);
                var currentIndex = ordered.IndexOf(key);
                if (targetIndex == currentIndex)
                {
                    return;
                }
                ordered.RemoveAt(currentIndex);
                ordered.Insert(targetIndex, key);
                ApplyOrder(false);
                _statusText.Text = $"正在调整图层：第 {targetIndex + 1} 层";
                e.Handled = true;
            };
            handle.PointerReleased += (_, e) =>
            {
                if (dragStarted)
                {
                    dragStarted = false;
                    ApplyOrder(true);
                    e.Pointer.Capture(null);
                    _statusText.Text = "图层顺序已更新";
                    ShowLayersPanel();
                }
                e.Handled = true;
            };

            row.Children.Add(handle);
            Grid.SetColumn(color, 1);
            row.Children.Add(color);
            Grid.SetColumn(name, 2);
            row.Children.Add(name);
            Grid.SetColumn(zIndex, 3);
            row.Children.Add(zIndex);
            Grid.SetColumn(direction, 4);
            row.Children.Add(direction);
            list.Children.Add(row);
        }

        var scroll = new ScrollViewer { Content = list };
        Grid.SetRow(scroll, 1);
        root.Children.Add(scroll);
        var actions = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*") };
        var autoArrange = new Button { Content = "自动排列", Height = 48, Margin = new Thickness(0, 8, 4, 0) };
        autoArrange.Click += (_, _) =>
        {
            ordered = ordered
                .OrderByDescending(key => key.CenterY)
                .ThenBy(key => key.CenterX)
                .ToList();
            ApplyOrder(true);
            ShowLayersPanel();
        };
        var reset = new Button
        {
            Content = "重置图层",
            Height = 48,
            Margin = new Thickness(4, 8, 0, 0),
            Background = new SolidColorBrush(Color.Parse("#FF9800")),
            Foreground = Brushes.White
        };
        reset.Click += (_, _) =>
        {
            ordered = ordered.OrderByDescending(key => key.ZIndex).ToList();
            ApplyOrder(true);
            ShowLayersPanel();
        };
        actions.Children.Add(autoArrange);
        Grid.SetColumn(reset, 1);
        actions.Children.Add(reset);
        Grid.SetRow(actions, 2);
        root.Children.Add(actions);
        ShowPanel("图层管理", root, "layers");
    }

    private void ShowConfigPanel()
    {
        var darkMode = _host.Document.GlobalSettings.DarkModeEnabled;
        var listBackground = new SolidColorBrush(Color.Parse(darkMode ? "#252525" : "#FFFFFF"));
        var rowBackground = new SolidColorBrush(Color.Parse(darkMode ? "#2D2D2D" : "#FFFFFF"));
        var primaryText = new SolidColorBrush(Color.Parse(darkMode ? "#FFFFFF" : "#212121"));
        var root = new Grid
        {
            Margin = new Thickness(18, 4, 18, 16),
            RowDefinitions = new RowDefinitions("Auto,*,Auto")
        };
        var hints = new StackPanel { Spacing = 8, Margin = new Thickness(0, 8, 0, 12) };
        hints.Children.Add(Hint("点击配置名称切换"));
        hints.Children.Add(Hint("点击 ⋮ 排序、重命名或删除"));
        hints.Children.Add(Hint("切换配置不会改变全局设置"));
        root.Children.Add(hints);
        var list = new StackPanel { Background = listBackground };
        var names = _host.ListNamedConfigurations().ToArray();
        foreach (var name in names)
        {
            var isDefault = name.Equals("default", StringComparison.OrdinalIgnoreCase);
            var isCurrent = name.Equals(_host.CurrentConfigName, StringComparison.OrdinalIgnoreCase);
            var row = new Grid
            {
                Height = 56,
                ColumnDefinitions = new ColumnDefinitions("4,*,40"),
                Background = rowBackground
            };
            var indicator = new Border
            {
                Width = 4,
                Height = 24,
                Background = new SolidColorBrush(Color.Parse("#2196F3")),
                VerticalAlignment = VerticalAlignment.Center,
                IsVisible = isCurrent
            };
            var load = new Button
            {
                Content = isDefault ? "默认" : name,
                HorizontalContentAlignment = HorizontalAlignment.Left,
                Background = Brushes.Transparent,
                Foreground = primaryText,
                FontWeight = isCurrent ? FontWeight.SemiBold : FontWeight.Normal,
                Margin = new Thickness(8, 0, 0, 0)
            };
            load.Click += (_, _) =>
            {
                if (isCurrent)
                {
                    return;
                }
                try
                {
                    LoadConfiguration(name);
                }
                catch (Exception exception)
                {
                    _statusText.Text = exception.Message;
                }
            };

            var more = new Button
            {
                Content = "⋮",
                Width = 40,
                Height = 40,
                Padding = new Thickness(0),
                Background = Brushes.Transparent,
                Foreground = primaryText,
                IsEnabled = true
            };
            var menu = new ContextMenu();
            var moveUp = new MenuItem { Header = "上移" };
            moveUp.Click += (_, _) => { _host.MoveNamedConfiguration(name, -1); ShowConfigPanel(); };
            var moveDown = new MenuItem { Header = "下移" };
            moveDown.Click += (_, _) => { _host.MoveNamedConfiguration(name, 1); ShowConfigPanel(); };
            var rename = new MenuItem { Header = "重命名" };
            rename.Click += async (_, _) =>
            {
                var newName = await PromptForTextAsync("重命名配置", name);
                if (newName is null)
                {
                    return;
                }
                try
                {
                    _host.RenameNamedConfiguration(name, newName);
                    _statusText.Text = $"已重命名配置：{newName}";
                    ShowConfigPanel();
                }
                catch (Exception exception)
                {
                    _statusText.Text = exception.Message;
                }
            };
            var delete = new MenuItem { Header = "删除" };
            delete.Click += async (_, _) =>
            {
                if (!await ConfirmAsync("删除配置", $"确定删除“{name}”吗？"))
                {
                    return;
                }
                try
                {
                    _host.DeleteNamedConfiguration(name);
                    _canvas.ClearAssetCache();
                    _canvas.SelectedKey = null;
                    _statusText.Text = $"已删除配置：{name}";
                    ShowConfigPanel();
                }
                catch (Exception exception)
                {
                    _statusText.Text = exception.Message;
                }
            };
            menu.Items.Add(moveUp);
            menu.Items.Add(moveDown);
            if (!isDefault)
            {
                menu.Items.Add(rename);
                menu.Items.Add(delete);
            }
            more.ContextMenu = menu;
            more.Click += (_, _) => menu.Open(more);

            row.Children.Add(indicator);
            Grid.SetColumn(load, 1);
            row.Children.Add(load);
            Grid.SetColumn(more, 2);
            row.Children.Add(more);
            list.Children.Add(row);
        }
        var scroll = new ScrollViewer { Content = list };
        Grid.SetRow(scroll, 1);
        root.Children.Add(scroll);

        var create = new Button
        {
            Content = "+ 新建配置",
            Height = 48,
            Margin = new Thickness(0, 8, 0, 0),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Center
        };
        create.Click += async (_, _) =>
        {
            var name = await PromptForTextAsync("新建配置", string.Empty);
            if (name is null)
            {
                return;
            }
            try
            {
                _host.CreateNamedConfiguration(name);
                _statusText.Text = $"已新建并切换配置：{name}";
                ShowConfigPanel();
            }
            catch (Exception exception)
            {
                _statusText.Text = exception.Message;
            }
        };
        Grid.SetRow(create, 2);
        root.Children.Add(create);
        ShowPanel("配置管理", root, "config");
    }

    private void LoadConfiguration(string name)
    {
        var gridAlignment = _host.LoadNamedConfiguration(name);
        _canvas.ClearAssetCache();
        _canvas.SelectedKey = null;
        _currentKey = null;
        var displayName = name.Equals("default", StringComparison.OrdinalIgnoreCase) ? "默认" : name;
        _statusText.Text = gridAlignment.Changed
            ? $"已切换配置：{displayName}（已自动对齐网格）"
            : $"已切换配置：{displayName}";
        ShowConfigPanel();
    }

    private async Task<string?> PromptForTextAsync(string title, string initial)
    {
        var completion = new TaskCompletionSource<string?>();
        var stack = new StackPanel { Margin = new Thickness(18), Spacing = 12 };
        var box = new TextBox { Text = initial, PlaceholderText = "配置名称", MaxLength = 64 };
        var actions = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*") };
        var cancel = new Button { Content = "取消", Margin = new Thickness(0, 0, 4, 0) };
        var confirm = new Button { Content = "确定", Margin = new Thickness(4, 0, 0, 0) };
        void Complete(string? value)
        {
            _dialogOverlay.IsVisible = false;
            _dialogContent.Content = null;
            completion.TrySetResult(value);
        }
        cancel.Click += (_, _) => Complete(null);
        confirm.Click += (_, _) => Complete(box.Text?.Trim());
        box.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter)
            {
                Complete(box.Text?.Trim());
            }
        };
        actions.Children.Add(cancel);
        Grid.SetColumn(confirm, 1);
        actions.Children.Add(confirm);
        stack.Children.Add(box);
        stack.Children.Add(actions);
        _dialogContent.Content = new Border
        {
            Width = 360,
            CornerRadius = new CornerRadius(12),
            Background = new SolidColorBrush(_host.Document.GlobalSettings.DarkModeEnabled
                ? Color.Parse("#2D2D2D") : Colors.White),
            BoxShadow = new BoxShadows(new BoxShadow { Blur = 18, Color = Color.Parse("#55000000") }),
            Child = stack
        };
        _dialogOverlay.IsVisible = true;
        Dispatcher.UIThread.Post(() => box.Focus(), DispatcherPriority.Input);
        var result = await completion.Task;
        return string.IsNullOrWhiteSpace(result) ? null : result;
    }

    private async Task<bool> EnsureRoutedInputModeAsync(string featureName)
    {
        if (_platform is not IInputBackendPlatformServices backend
            || backend.ActiveInputBackendSupportsRouting)
        {
            return true;
        }

        var selected = await PromptForInputBackendAsync(featureName);
        if (selected is null)
        {
            return false;
        }

        backend.SetInputBackend(selected);
        _statusText.Text = selected == "root"
            ? "已切换到 Root / Sui；若尚未安装或授权，请到全局设置继续配置"
            : "已切换到 Shizuku；若尚未启动或配对，请到全局设置继续配置";
        return true;
    }

    private async Task<string?> PromptForInputBackendAsync(string featureName)
    {
        var completion = new TaskCompletionSource<string?>();
        var stack = new StackPanel { Margin = new Thickness(18), Spacing = 12 };
        stack.Children.Add(new TextBlock
        {
            Text = $"“{featureName}”需要完整输入模式",
            FontSize = 18,
            FontWeight = FontWeight.SemiBold
        });
        stack.Children.Add(new TextBlock
        {
            Text = "无障碍模式只读取按键，不能拦截或替换游戏输入。你可以取消这次修改，或立即切换输入模式；切换不会自动完成 Shizuku 配对、Sui 安装或系统授权。",
            TextWrapping = TextWrapping.Wrap
        });

        var actions = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*,*") };
        var cancel = new Button { Content = "取消", Margin = new Thickness(0, 0, 4, 0) };
        var root = new Button
        {
            Content = "Root / Sui",
            Margin = new Thickness(4, 0),
            Background = new SolidColorBrush(Color.Parse("#7B1FA2")),
            Foreground = Brushes.White
        };
        var shizuku = new Button
        {
            Content = "Shizuku",
            Margin = new Thickness(4, 0, 0, 0),
            Background = new SolidColorBrush(Color.Parse("#FF9800")),
            Foreground = Brushes.White
        };
        void Complete(string? value)
        {
            _dialogOverlay.IsVisible = false;
            _dialogContent.Content = null;
            completion.TrySetResult(value);
        }
        cancel.Click += (_, _) => Complete(null);
        root.Click += (_, _) => Complete("root");
        shizuku.Click += (_, _) => Complete("shizuku");
        actions.Children.Add(cancel);
        Grid.SetColumn(root, 1);
        actions.Children.Add(root);
        Grid.SetColumn(shizuku, 2);
        actions.Children.Add(shizuku);
        stack.Children.Add(actions);

        _dialogContent.Content = new Border
        {
            Width = 440,
            CornerRadius = new CornerRadius(12),
            Background = new SolidColorBrush(_host.Document.GlobalSettings.DarkModeEnabled
                ? Color.Parse("#2D2D2D") : Colors.White),
            BoxShadow = new BoxShadows(new BoxShadow { Blur = 18, Color = Color.Parse("#55000000") }),
            Child = stack
        };
        _dialogOverlay.IsVisible = true;
        return await completion.Task;
    }

    private async Task<bool> ConfirmAsync(string title, string message)
    {
        var completion = new TaskCompletionSource<bool>();
        var stack = new StackPanel { Margin = new Thickness(18), Spacing = 16 };
        stack.Children.Add(new TextBlock
        {
            Text = title,
            FontSize = 18,
            FontWeight = FontWeight.SemiBold
        });
        stack.Children.Add(new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap });
        var actions = new Grid { ColumnDefinitions = new ColumnDefinitions("*,*") };
        var cancel = new Button { Content = "取消", Margin = new Thickness(0, 0, 4, 0) };
        var confirm = new Button
        {
            Content = "删除",
            Margin = new Thickness(4, 0, 0, 0),
            Background = new SolidColorBrush(Color.Parse("#D32F2F")),
            Foreground = Brushes.White
        };
        void Complete(bool value)
        {
            _dialogOverlay.IsVisible = false;
            _dialogContent.Content = null;
            completion.TrySetResult(value);
        }
        cancel.Click += (_, _) => Complete(false);
        confirm.Click += (_, _) => Complete(true);
        actions.Children.Add(cancel);
        Grid.SetColumn(confirm, 1);
        actions.Children.Add(confirm);
        stack.Children.Add(actions);
        _dialogContent.Content = new Border
        {
            Width = 360,
            CornerRadius = new CornerRadius(12),
            Background = new SolidColorBrush(_host.Document.GlobalSettings.DarkModeEnabled
                ? Color.Parse("#2D2D2D") : Colors.White),
            BoxShadow = new BoxShadows(new BoxShadow { Blur = 18, Color = Color.Parse("#55000000") }),
            Child = stack
        };
        _dialogOverlay.IsVisible = true;
        return await completion.Task;
    }

    private void ShowInputPanel()
    {
        var stack = PanelStack();
        stack.Children.Add(Section("输入来源"));
        if (_platform is IInputBackendPlatformServices backend)
        {
            stack.Children.Add(Hint("输入后端互斥运行，不会让无障碍与 Shizuku 对同一次按键重复计数。"));
            foreach (var option in backend.InputBackendOptions)
            {
                var selected = option.Id.Equals(
                    backend.ActiveInputBackend, StringComparison.OrdinalIgnoreCase);
                var button = new Button
                {
                    Content = $"{(selected ? "●" : "○")} {option.DisplayName}",
                    IsEnabled = option.IsEnabled,
                    HorizontalContentAlignment = HorizontalAlignment.Left,
                    HorizontalAlignment = HorizontalAlignment.Stretch
                };
                button.Click += (_, _) =>
                {
                    backend.SetInputBackend(option.Id);
                    ShowInputPanel();
                };
                stack.Children.Add(button);
                stack.Children.Add(Hint(option.Description));
            }

            stack.Children.Add(Section("当前状态"));
            stack.Children.Add(Hint(backend.InputBackendStatus));
            var settings = new Button
            {
                Content = backend.ActiveInputBackend.Equals(
                    "accessibility", StringComparison.OrdinalIgnoreCase)
                    ? "打开系统无障碍设置"
                    : backend.ActiveInputBackend.Equals("root", StringComparison.OrdinalIgnoreCase)
                        ? "检查 Root / Sui 授权"
                        : "检查 / 授予 Shizuku 权限"
            };
            settings.Click += (_, _) => backend.OpenInputBackendSettings();
            stack.Children.Add(settings);

            if (!backend.ActiveInputBackendSupportsRouting && ConfigurationNeedsRoutedInput())
            {
                stack.Children.Add(Hint("⚠ 当前配置包含映射、鬼键、防抖或按键限制。无障碍模式仍会正确显示和计数，但这些拦截功能不会作用于游戏。"));
            }
        }
        foreach (var source in _host.Runtime.Sources)
        {
            stack.Children.Add(Hint($"{(source.IsRunning ? "●" : "○")} {source.DisplayName}"));
        }
        if (OperatingSystem.IsWindows())
        {
            stack.Children.Add(Hint("Windows 默认在后台只读监听并完整透传；仅当你明确启用映射、鬼键、防抖或按键限制时，才会拦截或替换对应按键。"));
        }
        stack.Children.Add(Section("BepInEx 桥接"));
        stack.Children.Add(Hint("把 KeyViewer.BepInExBridge.dll 放进目标程序的 BepInEx/plugins。其他模组可调用 KeyViewerBridge.PublishKey() 发送游戏内事件。"));
        stack.Children.Add(Hint($"管道：{_host.Document.GlobalSettings.BridgePipeName}"));
        if (OperatingSystem.IsLinux())
        {
            stack.Children.Add(Section("Linux 权限"));
            stack.Children.Add(Hint("需要当前用户拥有 /dev/input/event* 读取权限；否则使用 input 组或 udev 规则授权。Linux 后端目前只观察，不独占系统输入。"));
        }
        ShowPanel("输入来源", new ScrollViewer { Content = stack }, "input");
    }

    private bool ConfigurationNeedsRoutedInput()
    {
        var settings = _host.Document.GlobalSettings;
        return settings.KeyDebounceEnabled
            || settings.KeyRestrictionEnabled
            || _host.Document.Keys.Any(key => key.GhostKey || key.MappingEnabled);
    }

    private static StackPanel PanelStack() => new()
    {
        Margin = new Thickness(18, 4, 18, 24),
        Spacing = 10
    };

    private static TextBlock Section(string text) => new()
    {
        Text = text,
        FontSize = 15,
        FontWeight = FontWeight.SemiBold,
        Margin = new Thickness(0, 14, 0, 2)
    };

    private static TextBlock Hint(string text) => new()
    {
        Text = text,
        FontSize = 12,
        Opacity = 0.72,
        TextWrapping = TextWrapping.Wrap
    };

    private Control TextRow(string label, string value, Action<string> update)
    {
        var box = new TextBox { Text = value };
        box.LostFocus += (_, _) => { update(box.Text ?? string.Empty); _host.NotifyEdited(); };
        return Labeled(label, box);
    }

    private Control CaptureRow(string label, string value, Action<string> update)
    {
        var button = new Button
        {
            Content = string.IsNullOrWhiteSpace(value) ? "点击后按键" : value,
            HorizontalContentAlignment = HorizontalAlignment.Left
        };
        button.Click += (_, _) => BeginKeyCapture(button, value, update);
        return Labeled(label, button);
    }

    private void BeginKeyCapture(Button button, string original, Action<string> update)
    {
        if (ReferenceEquals(_captureButton, button))
        {
            CancelKeyCapture();
            return;
        }

        CancelKeyCapture();
        _captureButton = button;
        _captureAction = update;
        _captureOriginal = original;
        button.Content = "请按一个实体键…";
        button.Background = new SolidColorBrush(Color.Parse("#FF9800"));
        button.Foreground = Brushes.White;
        _statusText.Text = "正在绑定：请按一个实体键（再次点击可取消）";
    }

    private void CancelKeyCapture()
    {
        if (_captureButton is not null)
        {
            _captureButton.Content = string.IsNullOrWhiteSpace(_captureOriginal)
                ? "点击后按键"
                : _captureOriginal;
            _captureButton.ClearValue(Button.BackgroundProperty);
            _captureButton.ClearValue(Button.ForegroundProperty);
        }

        _captureButton = null;
        _captureAction = null;
        _captureOriginal = string.Empty;
    }

    private void OnRoutedForBinding(KeyRouteContext route)
    {
        if (_captureAction is null || route.Input.State != KeyEventState.Down)
        {
            return;
        }

        var keyName = KeyViewer.Core.KeyNames.Normalize(route.Input.Key);
        Dispatcher.UIThread.Post(() =>
        {
            var action = _captureAction;
            var button = _captureButton;
            _captureAction = null;
            _captureButton = null;
            _captureOriginal = string.Empty;
            action?.Invoke(keyName);
            if (button is not null)
            {
                button.Content = keyName;
                button.ClearValue(Button.BackgroundProperty);
                button.ClearValue(Button.ForegroundProperty);
            }
            _host.NotifyEdited();
            _statusText.Text = $"绑定完成：{keyName}";
        });
    }

    private Control ColorRow(string label, int value, Action<int> update)
    {
        var box = new TextBox { Text = $"#{unchecked((uint)value):X8}" };
        var picker = new ColorPicker
        {
            Width = 44,
            Height = 36,
            Color = Color.FromUInt32(unchecked((uint)value)),
            IsAlphaEnabled = true,
            IsAlphaVisible = true
        };
        box.LostFocus += (_, _) =>
        {
            if (TryParseColor(box.Text, out var parsed))
            {
                update(parsed);
                picker.Color = Color.FromUInt32(unchecked((uint)parsed));
                _host.NotifyEdited();
            }
            else
            {
                box.Text = $"#{unchecked((uint)value):X8}";
            }
        };
        picker.ColorChanged += (_, _) =>
        {
            var parsed = unchecked((int)picker.Color.ToUInt32());
            box.Text = $"#{unchecked((uint)parsed):X8}";
            update(parsed);
            _host.NotifyEdited();
        };
        var editor = new Grid { ColumnDefinitions = new ColumnDefinitions("44,*"), ColumnSpacing = 8 };
        editor.Children.Add(picker);
        Grid.SetColumn(box, 1);
        editor.Children.Add(box);
        return Labeled(label, editor);
    }

    private static Control NumberRow(
        string label,
        double value,
        double minimum,
        double maximum,
        double increment,
        Action<double> update)
    {
        var number = new NumericUpDown
        {
            Value = (decimal)value,
            Minimum = (decimal)minimum,
            Maximum = (decimal)maximum,
            Increment = (decimal)increment,
            FormatString = increment < 1 ? "0.00" : "0"
        };
        number.ValueChanged += (_, _) =>
        {
            if (number.Value is decimal current)
            {
                update((double)current);
            }
        };
        return Labeled(label, number);
    }

    private static Control Labeled(string label, Control control)
    {
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("120,*") };
        grid.Children.Add(new TextBlock
        {
            Text = label,
            VerticalAlignment = VerticalAlignment.Center,
            TextWrapping = TextWrapping.Wrap
        });
        Grid.SetColumn(control, 1);
        grid.Children.Add(control);
        return grid;
    }

    private async Task ChooseBackgroundAsync()
    {
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "选择 KeyViewer 背景图片",
            AllowMultiple = false,
            FileTypeFilter =
            [
                new FilePickerFileType("图片")
                {
                    Patterns = ["*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp", "*.gif"]
                }
            ]
        });
        var file = files.FirstOrDefault();
        if (file is null)
        {
            return;
        }

        await using var stream = await file.OpenReadAsync();
        var name = await _host.ImportAssetAsync(stream, Path.GetExtension(file.Name), "image");
        _host.Document.GlobalSettings.BackgroundImageAsset = name;
        _canvas.ClearAssetCache();
        _host.NotifyEdited();
        ShowGlobalPanel();
    }

    private async Task ChooseKeyAssetAsync(KeyDefinition key, bool font)
    {
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = font ? "选择按键字体" : "选择按键图片",
            AllowMultiple = false,
            FileTypeFilter = font
                ? [new FilePickerFileType("字体") { Patterns = ["*.ttf", "*.otf"] }]
                : [new FilePickerFileType("图片") { Patterns = ["*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp", "*.gif"] }]
        });
        var file = files.FirstOrDefault();
        if (file is null)
        {
            return;
        }

        try
        {
            await using var stream = await file.OpenReadAsync();
            var name = await _host.ImportAssetAsync(
                stream,
                Path.GetExtension(file.Name),
                font ? "font" : "image");
            if (font)
            {
                key.FontAsset = name;
            }
            else
            {
                key.ImageAsset = name;
                _canvas.ClearAssetCache();
            }
            _host.NotifyEdited();
            _statusText.Text = font ? "字体已导入" : "图片已导入";
            ShowKeyPanel(key);
        }
        catch (Exception exception)
        {
            _statusText.Text = $"素材导入失败：{exception.Message}";
        }
    }

    private async Task ChooseBatchKeyAssetAsync(IReadOnlyList<KeyDefinition> keys, bool font)
    {
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = font ? "批量选择按键字体" : "批量选择按键图片",
            AllowMultiple = false,
            FileTypeFilter = font
                ? [new FilePickerFileType("字体") { Patterns = ["*.ttf", "*.otf"] }]
                : [new FilePickerFileType("图片") { Patterns = ["*.png", "*.jpg", "*.jpeg", "*.webp", "*.bmp", "*.gif"] }]
        });
        var file = files.FirstOrDefault();
        if (file is null)
        {
            return;
        }

        try
        {
            await using var stream = await file.OpenReadAsync();
            var name = await _host.ImportAssetAsync(
                stream,
                Path.GetExtension(file.Name),
                font ? "font" : "image");
            ApplyBatch(keys, font
                ? new KeyBatchPatch { FontAsset = name }
                : new KeyBatchPatch { ImageAsset = name }, font ? "字体" : "图片");
            if (!font)
            {
                _canvas.ClearAssetCache();
            }
            ShowBatchKeyPanel();
        }
        catch (Exception exception)
        {
            _statusText.Text = $"批量素材导入失败：{exception.Message}";
        }
    }

    private async Task ImportConfigAsync()
    {
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "导入 KeyViewer 配置",
            AllowMultiple = false,
            FileTypeFilter = [new FilePickerFileType("KeyViewer JSON") { Patterns = ["*.json"] }]
        });
        var file = files.FirstOrDefault();
        if (file is null)
        {
            return;
        }

        await using var stream = await file.OpenReadAsync();
        var gridAlignment = _host.ReplaceDocument(ConfigSerializer.Load(stream));
        _canvas.ClearAssetCache();
        _canvas.SelectedKey = null;
        _statusText.Text = gridAlignment.Changed
            ? $"已导入 {file.Name}，整体对齐网格（X {gridAlignment.OffsetX:+0.###;-0.###;0}，Y {gridAlignment.OffsetY:+0.###;-0.###;0}，细调 {gridAlignment.IndividuallyAdjustedKeyCount} 个）"
            : $"已导入 {file.Name}（已在网格上）";
        ShowGlobalPanel();
    }

    private async Task ExportConfigAsync()
    {
        var file = await StorageProvider.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            Title = "导出 KeyViewer 配置",
            SuggestedFileName = _host.CurrentConfigName + ".json",
            DefaultExtension = "json",
            FileTypeChoices = [new FilePickerFileType("KeyViewer JSON") { Patterns = ["*.json"] }]
        });
        if (file is null)
        {
            return;
        }

        await using var stream = await file.OpenWriteAsync();
        stream.SetLength(0);
        ConfigSerializer.SaveConfiguration(stream, _host.Document);
        await stream.FlushAsync();
        _statusText.Text = $"已导出 {file.Name}";
    }

    private async Task InstallPluginAsync()
    {
        var files = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "安装 KeyViewer 模组",
            AllowMultiple = false,
            FileTypeFilter = [new FilePickerFileType(".NET DLL") { Patterns = ["*.dll"] }]
        });
        var file = files.FirstOrDefault();
        if (file is null)
        {
            return;
        }

        await using var stream = await file.OpenReadAsync();
        var status = await _host.InstallPluginAsync(stream, file.Name);
        _statusText.Text = status.IsLoaded ? $"模组已加载：{status.Name}" : $"模组失败：{status.Error}";
        ShowGlobalPanel();
    }

    private void AddKey()
    {
        var center = _canvas.SnapToGrid(_canvas.ViewCenterWorld);
        var key = new KeyDefinition
        {
            CenterX = center.X,
            CenterY = center.Y,
            Width = 64,
            Height = 64,
            ZIndex = _host.Document.Keys.Count == 0 ? 0 : _host.Document.Keys.Max(item => item.ZIndex) + 1
        };
        _host.Document.Keys.Add(key);
        if (!_canvas.BatchEditMode)
        {
            _canvas.SelectedKey = key;
            _currentKey = key;
        }
        _host.NotifyEdited();
    }

    private void DeleteSelected()
    {
        if (!_canvas.DeleteSelection())
        {
            return;
        }
        _currentKey = null;
        ClosePanel();
        _statusText.Text = "已删除选中的按键";
    }

    private void CopySelected()
    {
        if (_canvas.CopySelection())
        {
            _statusText.Text = $"已复制 {_canvas.SelectedKeys.Count} 个按键";
        }
        else
        {
            _statusText.Text = "请先选择要复制的按键";
        }
    }

    private void StartPaste()
    {
        if (!_canvas.StartPaste())
        {
            _statusText.Text = "剪贴板里还没有按键";
            return;
        }

        ClosePanel();
        _statusText.Text = "拖动预览到目标位置，然后确认粘贴";
        UpdateEditModeUi();
    }

    private void ConfirmPaste()
    {
        if (_canvas.ConfirmPaste())
        {
            _statusText.Text = "粘贴完成";
        }
        UpdateEditModeUi();
    }

    private void CancelPaste()
    {
        _canvas.CancelPaste();
        _statusText.Text = "已取消粘贴";
        UpdateEditModeUi();
    }

    private void ToggleBatchEdit()
    {
        _canvas.SetBatchEditMode(!_canvas.BatchEditMode);
        ClosePanel();
        _statusText.Text = _canvas.BatchEditMode
            ? "批量编辑：选择多个按键后，可整体移动或点击齿轮批量设置"
            : BuildStatus();
        UpdateEditModeUi();
    }

    private void UpdateEditModeUi()
    {
        if (_normalEditToolbar is null || _pasteToolbar is null || _batchEditButton is null
            || _batchPropertiesButton is null || _selectAllButton is null)
        {
            return;
        }

        _normalEditToolbar.IsVisible = !_canvas.IsPasting;
        _pasteToolbar.IsVisible = _canvas.IsPasting;
        var hasSelection = _canvas.SelectedKeys.Count > 0;
        var copy = RequireButton("CopyButton");
        var paste = RequireButton("PasteButton");
        var delete = RequireButton("DeleteKeyButton");
        copy.IsVisible = _canvas.BatchEditMode;
        paste.IsVisible = _canvas.BatchEditMode;
        _batchPropertiesButton.IsVisible = _canvas.BatchEditMode;
        _selectAllButton.IsVisible = _canvas.BatchEditMode;
        copy.IsEnabled = hasSelection;
        paste.IsEnabled = _canvas.CanPaste;
        _batchPropertiesButton.IsEnabled = hasSelection;
        _selectAllButton.IsEnabled = _canvas.BatchEditMode && hasSelection;
        delete.IsEnabled = hasSelection;
        _batchEditButton.Background = Brushes.Transparent;
        _batchEditButton.Opacity = _canvas.BatchEditMode ? 1 : 0.5;
    }

    private void ToggleOverlay()
    {
        _platform.ToggleOverlay();
        _host.Document.GlobalSettings.FloatWindowEnabled = _platform.IsOverlayVisible;
        _overlayButton.Content = _platform.IsOverlayVisible ? "关闭悬浮窗" : "开启悬浮窗";
        _host.NotifyEdited();
    }

    private void OnOverlayVisibilityChanged(bool visible)
        => Dispatcher.UIThread.Post(() =>
        {
            _overlayButton.Content = visible ? "关闭悬浮窗" : "开启悬浮窗";
            if (_host.Document.GlobalSettings.FloatWindowEnabled != visible)
            {
                _host.Document.GlobalSettings.FloatWindowEnabled = visible;
                _host.NotifyEdited();
            }
        });

    private void OnInputBackendChanged()
        => Dispatcher.UIThread.Post(() =>
        {
            _statusText.Text = BuildStatus();
            if (_panelMode == "global") ShowGlobalPanel();
            else if (_panelMode == "input") ShowInputPanel();
        });

    private void ToggleListening()
    {
        var anyRunning = _host.Runtime.Sources.Any(source => source.IsRunning);
        foreach (var source in _host.Runtime.Sources)
        {
            if (anyRunning)
            {
                source.Stop();
            }
            else
            {
                source.Start();
            }
        }
        _host.State.ReleaseAll();
        _listenButton.Content = anyRunning ? "已停止" : "监听中";
        _listenButton.Background = new SolidColorBrush(Color.Parse(anyRunning ? "#757575" : "#FF9800"));
        _statusText.Text = BuildStatus();
    }

    private void OnSelectedKeyChanged(KeyDefinition? key)
    {
        _currentKey = key;
        if (!_canvas.BatchEditMode && !_canvas.IsPasting && key is not null)
        {
            ShowKeyPanel(key);
        }
        else if (_panelMode == "key")
        {
            ClosePanel();
        }
    }

    private void OnSelectionChanged()
    {
        UpdateEditModeUi();
        if (_canvas.BatchEditMode)
        {
            _statusText.Text = $"批量编辑：已选择 {_canvas.SelectedKeys.Count} 个按键";
            if (_panelMode == "batch")
            {
                ShowBatchKeyPanel();
            }
        }
    }

    private void OnHostChanged()
    {
        Dispatcher.UIThread.Post(() =>
        {
            _statusText.Text = BuildStatus();
            if (_panelMode == "layers")
            {
                ShowLayersPanel();
            }
        });
    }

    private void OnLogMessage(string message)
        => Dispatcher.UIThread.Post(() => _statusText.Text = message.Length > 90 ? message[..90] + "…" : message);

    private string BuildStatus()
        => $"KPS {_host.State.Kps}    TOTAL {_host.Document.TotalPressCount:N0}    "
           + $"{_host.Runtime.Sources.Count(source => source.IsRunning)}/{_host.Runtime.Sources.Count} 输入源"
           + (_host.Document.GlobalSettings.KeyRestrictionEnabled ? "    ⚠ 按键限制已开启" : string.Empty);

    private static bool TryParseColor(string? text, out int value)
    {
        value = 0;
        text = text?.Trim().TrimStart('#');
        if (text?.Length == 6)
        {
            text = "FF" + text;
        }
        if (text?.Length != 8
            || !uint.TryParse(text, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var parsed))
        {
            return false;
        }
        value = unchecked((int)parsed);
        return true;
    }

    private void OnDetachedFromVisualTree(object? sender, VisualTreeAttachmentEventArgs e)
    {
        _host.Changed -= OnHostChanged;
        _host.LogMessage -= OnLogMessage;
        _host.Router.Routed -= OnRoutedForBinding;
        _canvas.SelectedKeyChanged -= OnSelectedKeyChanged;
        _canvas.SelectionChanged -= OnSelectionChanged;
        _canvas.EditModeChanged -= UpdateEditModeUi;
        _platform.OverlayVisibilityChanged -= OnOverlayVisibilityChanged;
        if (_platform is IInputBackendPlatformServices inputBackend)
        {
            inputBackend.InputBackendChanged -= OnInputBackendChanged;
        }
        _canvas.Dispose();
        if (_ownsHost)
        {
            _platform.Dispose();
            _host.Dispose();
        }
    }
}
