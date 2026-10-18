using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net.Sockets;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace EmasTool
{
    static class Program
    {
        internal const string AppUrl = "http://127.0.0.1:5500";

        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            try
            {
                CoreWebView2Environment.GetAvailableBrowserVersionString();
                string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                Process ownedServer = null;
                if (!IsPortOpen())
                {
                    ownedServer = Process.Start(new ProcessStartInfo
                    {
                        FileName = FindNode(baseDir),
                        Arguments = "\"" + Path.Combine(baseDir, "server.js") + "\"",
                        WorkingDirectory = baseDir,
                        CreateNoWindow = true,
                        UseShellExecute = false
                    });
                    for (int i = 0; i < 30 && !IsPortOpen(); i++) Thread.Sleep(200);
                    if (!IsPortOpen())
                    {
                        StopOwnedServer(ownedServer);
                        throw new InvalidOperationException("The local Toolbox server did not start on port 5500.");
                    }
                }
                Application.Run(new ToolboxWindow(baseDir, ownedServer));
            }
            catch (WebView2RuntimeNotFoundException)
            {
                MessageBox.Show("Microsoft Edge WebView2 Runtime is required. Install the Microsoft Evergreen WebView2 Runtime, then reopen Toolbox.", "e.MAS 5 Toolbox", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch (Exception error)
            {
                MessageBox.Show(error.Message, "e.MAS 5 Toolbox", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        internal static void StopOwnedServer(Process server)
        {
            if (server == null) return;
            try
            {
                if (!server.HasExited)
                {
                    server.Kill();
                    server.WaitForExit(3000);
                }
            }
            catch (InvalidOperationException) { }
            catch (System.ComponentModel.Win32Exception) { }
            finally { server.Dispose(); }
        }

        internal static bool IsPortOpen()
        {
            try
            {
                using (var client = new TcpClient())
                {
                    var result = client.BeginConnect("127.0.0.1", 5500, null, null);
                    using (var wait = result.AsyncWaitHandle)
                    {
                        if (!wait.WaitOne(200)) return false;
                    }
                    client.EndConnect(result);
                    return true;
                }
            }
            catch (SocketException) { return false; }
        }

        static string FindNode(string baseDir)
        {
            string local = Path.Combine(baseDir, "tools", "node.exe");
            if (File.Exists(local)) return local;
            string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            foreach (string candidate in new[]
            {
                Path.Combine(home, @"scoop\apps\nvm\current\nodejs\nodejs\node.exe"),
                Path.Combine(home, @"scoop\apps\nodejs\current\node.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"nodejs\node.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"nodejs\node.exe")
            }) if (File.Exists(candidate)) return candidate;
            return "node";
        }
    }

    sealed class ToolboxWindow : Form
    {
        readonly WebView2 web = new WebView2();
        readonly string baseDir;
        readonly Process ownedServer;
        bool closeApproved;
        bool closePending;

        internal ToolboxWindow(string directory, Process server)
        {
            baseDir = directory;
            ownedServer = server;
            Text = "e.MAS 5 — Toolbox";
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(1280, 820);
            MinimumSize = new Size(760, 560);
            BackColor = Color.FromArgb(216, 227, 223);
            Padding = new Padding(6);
            web.Dock = DockStyle.Fill;
            web.DefaultBackgroundColor = Color.FromArgb(244, 247, 248);
            Controls.Add(web);
            Shown += InitializeWebView;
            FormClosing += ConfirmClose;
        }

        protected override CreateParams CreateParams
        {
            get
            {
                var value = base.CreateParams;
                value.Style |= 0x00040000 | 0x00020000 | 0x00010000 | 0x00080000;
                return value;
            }
        }

        protected override void WndProc(ref Message message)
        {
            // Keep native sizing/snap behavior but no native caption or caption buttons.
            if (message.Msg == 0x0083 && message.WParam != IntPtr.Zero)
            {
                message.Result = IntPtr.Zero;
                return;
            }
            if (message.Msg == 0x0084 && WindowState == FormWindowState.Normal)
            {
                long position = message.LParam.ToInt64();
                Point point = PointToClient(new Point((short)(position & 0xffff), (short)((position >> 16) & 0xffff)));
                int grip = Padding.Left;
                bool left = point.X < grip;
                bool right = point.X >= ClientSize.Width - grip;
                bool top = point.Y < grip;
                bool bottom = point.Y >= ClientSize.Height - grip;
                int hit = top ? (left ? 13 : right ? 14 : 12) : bottom ? (left ? 16 : right ? 17 : 15) : left ? 10 : right ? 11 : 0;
                if (hit != 0) { message.Result = new IntPtr(hit); return; }
            }
            base.WndProc(ref message);
        }

        async void InitializeWebView(object sender, EventArgs args)
        {
            try
            {
                var environment = await CoreWebView2Environment.CreateAsync(null, Path.Combine(baseDir, "webview_profile"));
                await web.EnsureCoreWebView2Async(environment);
                web.CoreWebView2.Settings.IsNonClientRegionSupportEnabled = true;
                web.CoreWebView2.Settings.AreHostObjectsAllowed = false;
                web.CoreWebView2.Profile.PreferredColorScheme = CoreWebView2PreferredColorScheme.Light;
                web.CoreWebView2.NavigationStarting += delegate(object source, CoreWebView2NavigationStartingEventArgs navigation)
                {
                    if (!IsAppAddress(navigation.Uri)) navigation.Cancel = true;
                };
                web.CoreWebView2.NewWindowRequested += delegate(object source, CoreWebView2NewWindowRequestedEventArgs request) { request.Handled = true; };
                web.CoreWebView2.WebMessageReceived += WindowCommand;
                web.CoreWebView2.Navigate(Program.AppUrl);
            }
            catch (Exception error)
            {
                MessageBox.Show(this, "Unable to open the desktop interface: " + error.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Error);
                closeApproved = true;
                Close();
            }
        }

        static bool IsAppAddress(string address)
        {
            Uri uri;
            return Uri.TryCreate(address, UriKind.Absolute, out uri) && uri.Scheme == "http" && uri.Host == "127.0.0.1" && uri.Port == 5500;
        }

        void WindowCommand(object sender, CoreWebView2WebMessageReceivedEventArgs args)
        {
            if (!IsAppAddress(args.Source)) return;
            string command;
            try { command = args.TryGetWebMessageAsString(); }
            catch (ArgumentException) { return; }
            if (command == "minimize") WindowState = FormWindowState.Minimized;
            else if (command == "maximize")
            {
                MaximizedBounds = Screen.FromHandle(Handle).WorkingArea;
                WindowState = WindowState == FormWindowState.Maximized ? FormWindowState.Normal : FormWindowState.Maximized;
            }
            else if (command == "close") Close();
        }

        async void ConfirmClose(object sender, FormClosingEventArgs args)
        {
            if (closeApproved) return;
            args.Cancel = true;
            if (closePending) return;
            closePending = true;
            try
            {
                string allowed = web.CoreWebView2 == null ? "true" : await web.CoreWebView2.ExecuteScriptAsync("typeof window.requestDesktopClose === 'function' ? window.requestDesktopClose() : true");
                if (allowed != "true") return;
                // Only stop a server this window started. A shared/stale server on 5500
                // (possibly without the close route) remains untouched and Close succeeds.
                Program.StopOwnedServer(ownedServer);
                closeApproved = true;
                Close();
            }
            finally { closePending = false; }
        }
    }
}
