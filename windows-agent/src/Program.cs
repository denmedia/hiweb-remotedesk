using System;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

[assembly: AssemblyTitle("HiWeb Agent")]
[assembly: AssemblyProduct("HiWeb Agent")]
[assembly: AssemblyDescription("HiWeb Agent")]
[assembly: AssemblyCompany("HiWeb")]
[assembly: AssemblyVersion(HiWebAgent.BuildInfo.Version + ".0")]
[assembly: AssemblyFileVersion(HiWebAgent.BuildInfo.Version + ".0")]

namespace HiWebAgent
{
    /// <summary>
    /// Один exe: запущенный не из папки установки (например, с диска планшета \\tsclient\HiWeb) — устанавливает
    /// себя; с ключом --uninstall — удаляет; из папки установки — работает агентом в трее.
    /// </summary>
    static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            // Повышенная копия для блокировки подсетей (запускается агентом через UAC): без трея и окон.
            if (args.Length > 0 && args[0] == FirewallRules.ElevatedArg) return FirewallRules.RunElevated(args);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            string self = Assembly.GetExecutingAssembly().Location;
            try
            {
                if (args.Contains("--uninstall"))
                {
                    // «Установленные приложения» → Удалить запускает эту команду; --quiet — без вопросов (QuietUninstallString).
                    bool quiet = args.Contains("--quiet");
                    if (!quiet && MessageBox.Show("Удалить " + AgentInfo.Product + " с этого компьютера?", AgentInfo.Product,
                            MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return 1;
                    Installer.Uninstall(false);
                    if (!quiet) MessageBox.Show(AgentInfo.Product + " удалён.", AgentInfo.Product, MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return 0;
                }
                if (!Installer.IsInstalledCopy(self))
                {
                    Installer.Install(self);
                    return 0;
                }
            }
            catch (Exception e)
            {
                Log.Write("setup failed: " + e);
                MessageBox.Show("Не удалось выполнить действие: " + e.Message, AgentInfo.Product, MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }

            // Агент без окон: падение иначе проходит молча, а в журнале остаётся только «started».
            AppDomain.CurrentDomain.UnhandledException += delegate(object s, UnhandledExceptionEventArgs e) { Log.Write("crash: " + e.ExceptionObject); };
            Application.ThreadException += delegate(object s, ThreadExceptionEventArgs e) { Log.Write("error: " + e.Exception); };
            bool createdNew;
            using (var instance = new Mutex(true, Installer.InstanceMutex, out createdNew))
            {
                if (!createdNew) return 0;
                Log.Write("agent " + AgentInfo.Version + " started");
                Application.Run(new AgentApp(args.Contains("--installed")));
                Log.Write("agent stopped");
                instance.ReleaseMutex();
            }
            return 0;
        }
    }
}
