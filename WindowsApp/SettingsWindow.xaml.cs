using System;
using System.Windows;

namespace HeartRateOverlay
{
    public partial class SettingsWindow : Window
    {
        private Action<AppConfig> _onSave;
        private AppConfig _tempConfig;

        public SettingsWindow(AppConfig currentConfig, Action<AppConfig> onSave)
        {
            InitializeComponent();
            _tempConfig = new AppConfig
            {
                ServerUrl = currentConfig.ServerUrl,
                Token = currentConfig.Token,
                Theme = currentConfig.Theme
            };

            TxtServerUrl.Text = _tempConfig.ServerUrl;
            TxtToken.Text = _tempConfig.Token;
            _onSave = onSave;

            if (string.IsNullOrEmpty(_tempConfig.Theme) || _tempConfig.Theme.Equals("Dark", StringComparison.OrdinalIgnoreCase))
            {
                RadDarkTheme.IsChecked = true;
            }
            else
            {
                RadLightTheme.IsChecked = true;
            }
        }

        private void BtnSave_Click(object sender, RoutedEventArgs e)
        {
            string url = TxtServerUrl.Text.Trim();
            string token = TxtToken.Text.Trim();

            if (string.IsNullOrEmpty(url))
            {
                MessageBox.Show("服务器地址不能为空", "提示", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            _tempConfig.ServerUrl = url;
            _tempConfig.Token = token;
            _tempConfig.Theme = RadDarkTheme.IsChecked == true ? "Dark" : "Light";

            if (_onSave != null)
            {
                _onSave(_tempConfig);
            }
            this.DialogResult = true;
            this.Close();
        }

        private void BtnCancel_Click(object sender, RoutedEventArgs e)
        {
            this.DialogResult = false;
            this.Close();
        }
    }
}
