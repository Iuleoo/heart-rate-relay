using System;
using System.Collections.Generic;
using System.IO;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media.Animation;
using System.Web.Script.Serialization;

namespace HeartRateOverlay
{
    public partial class MainWindow : Window
    {
        private const string ConfigFileName = "config.json";
        private AppConfig _config;
        private ClientWebSocket _ws;
        private CancellationTokenSource _cts;
        private System.Windows.Forms.NotifyIcon _notifyIcon;
        private bool _isConnecting = false;

        // Win32 API 导入 (用于鼠标穿透)
        [DllImport("user32.dll")]
        private static extern int GetWindowLong(IntPtr hWnd, int nIndex);

        [DllImport("user32.dll")]
        private static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);

        private const int GWL_EXSTYLE = -20;
        private const int WS_EX_TRANSPARENT = 0x00000020;

        public MainWindow()
        {
            InitializeComponent();
            LoadConfiguration();
            InitializeNotifyIcon();

            this.Loaded += MainWindow_Loaded;
            this.Closing += MainWindow_Closing;
        }

        private void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            // 恢复窗口位置
            this.Left = _config.WindowLeft;
            this.Top = _config.WindowTop;

            // 检查边界防越界
            if (this.Left < 0 || this.Left > SystemParameters.VirtualScreenWidth - 100) this.Left = 20;
            if (this.Top < 0 || this.Top > SystemParameters.VirtualScreenHeight - 50) this.Top = 20;

            // 应用穿透和主题状态
            ApplyClickThrough();
            ApplyTheme();

            // 启动 WebSocket 通信线程
            StartWebSocketLoop();
        }

        private void MainWindow_Closing(object sender, System.ComponentModel.CancelEventArgs e)
        {
            StopWebSocket();
            if (_notifyIcon != null)
            {
                _notifyIcon.Visible = false;
                _notifyIcon.Dispose();
            }
        }

        // ─── 鼠标移动窗口 ──────────────────────────────────────────
        private void Window_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ChangedButton == MouseButton.Left)
            {
                try
                {
                    this.DragMove();
                    // 拖拽完后，保存位置
                    _config.WindowLeft = this.Left;
                    _config.WindowTop = this.Top;
                    SaveConfiguration();
                }
                catch { }
            }
        }

        // ─── 鼠标穿透逻辑 ──────────────────────────────────────────
        private void ApplyClickThrough()
        {
            try
            {
                IntPtr hwnd = new WindowInteropHelper(this).Handle;
                int extendedStyle = GetWindowLong(hwnd, GWL_EXSTYLE);
                if (_config.ClickThrough)
                {
                    SetWindowLong(hwnd, GWL_EXSTYLE, extendedStyle | WS_EX_TRANSPARENT);
                }
                else
                {
                    SetWindowLong(hwnd, GWL_EXSTYLE, extendedStyle & ~WS_EX_TRANSPARENT);
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine("应用穿透失败: " + ex.Message);
            }
        }

        // ─── 系统托盘初始化 ─────────────────────────────────────────
        private void InitializeNotifyIcon()
        {
            _notifyIcon = new System.Windows.Forms.NotifyIcon();
            
            // 加载自定义心率图标作为任务栏/系统托盘图标
            try
            {
                var iconUri = new Uri("pack://application:,,,/app_icon.ico");
                var streamResourceInfo = Application.GetResourceStream(iconUri);
                if (streamResourceInfo != null)
                {
                    using (var stream = streamResourceInfo.Stream)
                    {
                        _notifyIcon.Icon = new System.Drawing.Icon(stream);
                    }
                }
                else
                {
                    _notifyIcon.Icon = System.Drawing.SystemIcons.Application;
                }
            }
            catch
            {
                _notifyIcon.Icon = System.Drawing.SystemIcons.Application;
            }

            _notifyIcon.Text = "心率悬浮窗";
            _notifyIcon.Visible = true;

            // 托盘右键菜单
            var contextMenu = new System.Windows.Forms.ContextMenu();

            var menuShow = new System.Windows.Forms.MenuItem("显示/隐藏 悬浮窗");
            menuShow.Click += (s, e) => {
                if (this.IsVisible) this.Hide(); else this.Show();
            };
            contextMenu.MenuItems.Add(menuShow);

            var menuSettings = new System.Windows.Forms.MenuItem("设置...");
            menuSettings.Click += (s, e) => OpenSettingsWindow();
            contextMenu.MenuItems.Add(menuSettings);

            var menuClickThrough = new System.Windows.Forms.MenuItem("锁定 (鼠标穿透)");
            menuClickThrough.Checked = _config.ClickThrough;
            menuClickThrough.Click += (s, e) => {
                _config.ClickThrough = !_config.ClickThrough;
                menuClickThrough.Checked = _config.ClickThrough;
                ApplyClickThrough();
                SaveConfiguration();
            };
            contextMenu.MenuItems.Add(menuClickThrough);

            contextMenu.MenuItems.Add("-"); // 分割线

            var menuExit = new System.Windows.Forms.MenuItem("退出");
            menuExit.Click += (s, e) => Application.Current.Shutdown();
            contextMenu.MenuItems.Add(menuExit);

            _notifyIcon.ContextMenu = contextMenu;

            // 双击托盘图标打开设置
            _notifyIcon.DoubleClick += (s, e) => OpenSettingsWindow();
        }

        private void OpenSettingsWindow()
        {
            // 打开设置前确保悬浮窗可见
            this.Show();
            this.Activate();

            var settings = new SettingsWindow(_config, (config) => {
                _config.ServerUrl = config.ServerUrl;
                _config.Token = config.Token;
                _config.Theme = config.Theme;
                SaveConfiguration();
                
                // 应用主题设置
                ApplyTheme();
                
                // 重启 WebSocket 连接
                Task.Run(() => RestartWebSocket());
            });

            settings.Owner = this;
            settings.ShowDialog();
        }

        private void ApplyTheme()
        {
            Dispatcher.Invoke(() => {
                try
                {
                    bool isDark = string.IsNullOrEmpty(_config.Theme) || _config.Theme.Equals("Dark", StringComparison.OrdinalIgnoreCase);
                    
                    if (isDark)
                    {
                        MainBorder.Background = new System.Windows.Media.SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#E5121214"));
                        MainBorder.BorderBrush = new System.Windows.Media.SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#30FFFFFF"));
                        TxtUnit.Foreground = new System.Windows.Media.SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#938F99"));
                    }
                    else
                    {
                        MainBorder.Background = new System.Windows.Media.SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#E5F8F9FA"));
                        MainBorder.BorderBrush = new System.Windows.Media.SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#30000000"));
                        TxtUnit.Foreground = new System.Windows.Media.SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#49454F"));
                    }
                }
                catch (Exception ex)
                {
                    Console.WriteLine("应用主题失败: " + ex.Message);
                }
            });
        }

        // ─── WebSocket 实时数据拉取 ──────────────────────────────────
        private void StartWebSocketLoop()
        {
            _cts = new CancellationTokenSource();
            Task.Run(() => ConnectAndListenAsync(_cts.Token));
        }

        private void StopWebSocket()
        {
            if (_cts != null) _cts.Cancel();
            if (_ws != null) _ws.Dispose();
            _ws = null;
        }

        private async Task RestartWebSocket()
        {
            StopWebSocket();
            await Task.Delay(500);
            StartWebSocketLoop();
        }

        private async Task ConnectAndListenAsync(CancellationToken token)
        {
            if (_isConnecting) return;
            _isConnecting = true;

            var serializer = new JavaScriptSerializer();

            while (!token.IsCancellationRequested)
            {
                try
                {
                    // 拼装 WebSocket 最终地址
                    string targetUrl = _config.ServerUrl.Trim();
                    if (string.IsNullOrEmpty(targetUrl))
                    {
                        UpdateHRText("配置错误", false);
                        await Task.Delay(5000, token);
                        continue;
                    }

                    UpdateHRText("正在连接", false);

                    _ws = new ClientWebSocket();
                    if (!string.IsNullOrEmpty(_config.Token))
                    {
                        _ws.Options.SetRequestHeader("Authorization", "Bearer " + _config.Token);
                    }

                    using (var localCts = CancellationTokenSource.CreateLinkedTokenSource(token))
                    {
                        localCts.CancelAfter(8000); // 8秒连接超时
                        await _ws.ConnectAsync(new Uri(targetUrl), localCts.Token);
                    }

                    UpdateHRText("已连接", false);
                    _isConnecting = false;

                    byte[] receiveBuffer = new byte[4096];
                    while (_ws.State == WebSocketState.Open && !token.IsCancellationRequested)
                    {
                        using (MemoryStream ms = new MemoryStream())
                        {
                            WebSocketReceiveResult result;
                            do
                            {
                                result = await _ws.ReceiveAsync(new ArraySegment<byte>(receiveBuffer), token);
                                if (result.MessageType == WebSocketMessageType.Close)
                                {
                                    break;
                                }
                                ms.Write(receiveBuffer, 0, result.Count);
                            } while (!result.EndOfMessage);

                            if (result.MessageType == WebSocketMessageType.Close)
                            {
                                await _ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "Closing", token);
                                break;
                            }

                            if (result.MessageType == WebSocketMessageType.Text)
                            {
                                string jsonText = Encoding.UTF8.GetString(ms.ToArray());
                                try
                                {
                                    var msg = serializer.Deserialize<Dictionary<string, object>>(jsonText);
                                    if (msg != null && msg.ContainsKey("type"))
                                    {
                                        string type = msg["type"] as string;
                                        if (type == "heartrate" && msg.ContainsKey("hr"))
                                        {
                                            int hr = Convert.ToInt32(msg["hr"]);
                                            // 回到主线程更新 UI 且触发跳动动画
                                            Dispatcher.Invoke(() => {
                                                TxtHR.Text = hr.ToString();
                                                var storyboard = this.Resources["HeartBeat"] as Storyboard;
                                                if (storyboard != null)
                                                {
                                                    storyboard.Begin();
                                                }
                                            });
                                        }
                                        else if (type == "init")
                                        {
                                            // 连接上后可能返回初始连接状态
                                            bool isConnected = Convert.ToBoolean(msg["connected"]);
                                            if (!isConnected)
                                            {
                                                UpdateHRText("等待心率", false);
                                            }
                                        }
                                        else if (type == "status")
                                        {
                                            string status = msg["status"] as string;
                                            if (status == "disconnected")
                                            {
                                                UpdateHRText("断开连接", false);
                                            }
                                        }
                                    }
                                }
                                catch { }
                            }
                        }
                    }
                }
                catch (Exception ex)
                {
                    _isConnecting = false;
                    Console.WriteLine("WebSocket 异常: " + ex.Message);
                    UpdateHRText("重连中...", false);
                }

                if (_ws != null) _ws.Dispose();
                _ws = null;

                // 断线后等待 3 秒再次发起重连
                try { await Task.Delay(3000, token); } catch { break; }
            }
        }

        private void UpdateHRText(string text, bool animate)
        {
            Dispatcher.Invoke(() => {
                TxtHR.Text = text;
            });
        }

        // ─── 配置文件管理 ──────────────────────────────────────────
        private void LoadConfiguration()
        {
            try
            {
                if (File.Exists(ConfigFileName))
                {
                    string json = File.ReadAllText(ConfigFileName, Encoding.UTF8);
                    var serializer = new JavaScriptSerializer();
                    _config = serializer.Deserialize<AppConfig>(json);
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine("加载配置失败，使用默认配置: " + ex.Message);
            }

            if (_config == null)
            {
                _config = new AppConfig();
                SaveConfiguration();
            }
        }

        private void SaveConfiguration()
        {
            try
            {
                var serializer = new JavaScriptSerializer();
                string json = serializer.Serialize(_config);
                File.WriteAllText(ConfigFileName, json, Encoding.UTF8);
            }
            catch (Exception ex)
            {
                Console.WriteLine("保存配置失败: " + ex.Message);
            }
        }
    }

    public class AppConfig
    {
        public string ServerUrl { get; set; }
        public string Token { get; set; }
        public double WindowLeft { get; set; }
        public double WindowTop { get; set; }
        public bool ClickThrough { get; set; }
        public string Theme { get; set; }

        public AppConfig()
        {
            ServerUrl = "ws://localhost:8765/ws";
            Token = "";
            WindowLeft = 20;
            WindowTop = 20;
            ClickThrough = false;
            Theme = "Dark";
        }
    }
}
