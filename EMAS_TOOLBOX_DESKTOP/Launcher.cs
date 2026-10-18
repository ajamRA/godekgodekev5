using System;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Threading;

namespace EmasTool
{
    class Program
    {
        static void Main(string[] args)
        {
            try
            {
                string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                string serverScript = Path.Combine(baseDir, "server.js");
                string nodeExe = FindNode(baseDir);

                // 1. Start Node.js backend server if port 5500 is not already open
                if (!IsPortOpen("127.0.0.1", 5500))
                {
                    var psiNode = new ProcessStartInfo
                    {
                        FileName = nodeExe,
                        Arguments = string.Format("\"{0}\"", serverScript),
                        WorkingDirectory = baseDir,
                        CreateNoWindow = true,
                        UseShellExecute = false
                    };
                    Process.Start(psiNode);

                    // Wait up to 6 seconds for server to listen on port 5500
                    for (int i = 0; i < 30; i++)
                    {
                        if (IsPortOpen("127.0.0.1", 5500)) break;
                        Thread.Sleep(200);
                    }
                }

                // 2. Locate Browser for App Mode (MS Edge or Chrome)
                string edgePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"Microsoft\Edge\Application\msedge.exe");
                if (!File.Exists(edgePath))
                {
                    edgePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Microsoft\Edge\Application\msedge.exe");
                }

                string chromePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Google\Chrome\Application\chrome.exe");
                if (!File.Exists(chromePath))
                {
                    chromePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"Google\Chrome\Application\chrome.exe");
                }

                string browserExe = File.Exists(edgePath) ? edgePath : (File.Exists(chromePath) ? chromePath : null);

                if (browserExe != null)
                {
                    string userDataDir = Path.Combine(baseDir, "cache_profile");
                    var psiBrowser = new ProcessStartInfo
                    {
                        FileName = browserExe,
                        Arguments = string.Format("--app=http://127.0.0.1:5500 --window-size=1280,820 --user-data-dir=\"{0}\" --no-first-run --no-default-browser-check", userDataDir),
                        UseShellExecute = false
                    };
                    Process.Start(psiBrowser);
                }
                else
                {
                    // Fallback to default browser
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = "http://127.0.0.1:5500",
                        UseShellExecute = true
                    });
                }
            }
            catch (Exception ex)
            {
                System.Windows.Forms.MessageBox.Show(ex.Message, "Ralat EmasTool", System.Windows.Forms.MessageBoxButtons.OK, System.Windows.Forms.MessageBoxIcon.Error);
            }
        }

        static string FindNode(string baseDir)
        {
            // 1. Local bundled node in tools/
            string localToolsNode = Path.Combine(baseDir, "tools", "node.exe");
            if (File.Exists(localToolsNode)) return localToolsNode;

            // 2. Common system/scoop paths
            string userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            string[] candidates = new string[]
            {
                Path.Combine(userProfile, @"scoop\apps\nvm\current\nodejs\nodejs\node.exe"),
                Path.Combine(userProfile, @"scoop\apps\nodejs\current\node.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"nodejs\node.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), @"nodejs\node.exe")
            };

            foreach (var c in candidates)
            {
                if (File.Exists(c)) return c;
            }

            return "node";
        }

        static bool IsPortOpen(string host, int port)
        {
            try
            {
                using (var client = new TcpClient())
                {
                    var result = client.BeginConnect(host, port, null, null);
                    bool success = result.AsyncWaitHandle.WaitOne(300);
                    if (!success) return false;
                    client.EndConnect(result);
                    return true;
                }
            }
            catch
            {
                return false;
            }
        }
    }
}
