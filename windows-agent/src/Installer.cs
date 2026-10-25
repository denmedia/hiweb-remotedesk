using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using Microsoft.Win32;

namespace HiWebAgent
{
    /// <summary>
    /// Установка в профиль пользователя без прав администратора: %LOCALAPPDATA%\HiWebAgent, автозапуск через
    /// HKCU\...\Run и запись в «Приложения» (HKCU\...\Uninstall), чтобы агент можно было удалить штатно.
    /// </summary>
    static class Installer
    {
        public const string InstanceMutex = @"Local\HiWebAgent.Instance";
        public const string ExitEvent = @"Local\HiWebAgent.Exit";

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\HiWebAgent";
        public const string SettingsKey = @"Software\HiWebAgent";
        const string RunValue = "HiWebAgent";

        public static bool IsInstalledCopy(string exe)
        {
            return string.Equals(Path.GetFullPath(exe), Path.GetFullPath(AgentInfo.InstalledExe), StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>Копирует себя на место установки (заменяя работающий агент) и запускает установленную копию.</summary>
        public static void Install(string self)
        {
            StopRunningAgent();
            Directory.CreateDirectory(AgentInfo.InstallDir);
            string target = AgentInfo.InstalledExe;
            for (int attempt = 0; ; attempt++)
            {
                try
                {
                    File.Copy(self, target, true);
                    break;
                }
                catch (IOException)
                {
                    if (attempt >= 10) throw;
                    Thread.Sleep(500);
                }
            }
            string quoted = "\"" + target + "\"";
            using (RegistryKey run = Registry.CurrentUser.CreateSubKey(RunKey))
            {
                run.SetValue(RunValue, quoted);
            }
            using (RegistryKey u = Registry.CurrentUser.CreateSubKey(UninstallKey))
            {
                u.SetValue("DisplayName", AgentInfo.Product);
                u.SetValue("DisplayVersion", AgentInfo.Version);
                u.SetValue("Publisher", "HiWeb");
                u.SetValue("DisplayIcon", target);
                u.SetValue("InstallLocation", AgentInfo.InstallDir);
                u.SetValue("UninstallString", quoted + " --uninstall");
                u.SetValue("QuietUninstallString", quoted + " --uninstall --quiet");
                u.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"));
                u.SetValue("EstimatedSize", (int)Math.Max(1, new FileInfo(target).Length / 1024), RegistryValueKind.DWord);
                u.SetValue("NoModify", 1, RegistryValueKind.DWord);
                u.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            }
            Log.Write("installed " + AgentInfo.Version + " from " + self);
            Process.Start(new ProcessStartInfo(target, "--installed") { UseShellExecute = false, WorkingDirectory = AgentInfo.InstallDir });
        }

        /// <summary>
        /// Убирает автозапуск и запись в «Приложения», затем удаляет папку программы, когда процесс завершится
        /// (файл запущенной программы удалить нельзя, поэтому это делает отложенная команда).
        /// </summary>
        public static void Uninstall(bool fromAgent)
        {
            if (!fromAgent) StopRunningAgent();
            using (RegistryKey run = Registry.CurrentUser.OpenSubKey(RunKey, true))
            {
                if (run != null) run.DeleteValue(RunValue, false);
            }
            Registry.CurrentUser.DeleteSubKeyTree(UninstallKey, false);
            Registry.CurrentUser.DeleteSubKeyTree(SettingsKey, false);
            var cmd = new ProcessStartInfo("cmd.exe", "/c ping 127.0.0.1 -n 4 > nul & rmdir /s /q \"" + AgentInfo.InstallDir + "\"");
            cmd.CreateNoWindow = true;
            cmd.UseShellExecute = false;
            cmd.WindowStyle = ProcessWindowStyle.Hidden;
            Process.Start(cmd);
        }

        /// <summary>Просит запущенный агент этого сеанса завершиться и ждёт до 10 секунд.</summary>
        static void StopRunningAgent()
        {
            EventWaitHandle exit;
            if (!EventWaitHandle.TryOpenExisting(ExitEvent, out exit)) return;
            using (exit) exit.Set();
            Mutex mutex;
            if (!Mutex.TryOpenExisting(InstanceMutex, out mutex)) return;
            using (mutex)
            {
                try
                {
                    if (mutex.WaitOne(10000)) mutex.ReleaseMutex();
                }
                catch (AbandonedMutexException)
                {
                }
            }
        }
    }
}
