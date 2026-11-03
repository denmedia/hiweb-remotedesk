using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Reflection;
using System.Security.AccessControl;
using System.Security.Principal;
using System.ServiceProcess;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace HiWebAgent
{
    /// <summary>
    /// Сторона агента: запросы помощнику блокировки по именованному каналу (<see cref="HelperProtocol"/>). Прав
    /// администратора не нужно — канал открыт для пользователя, который установил помощника.
    /// </summary>
    static class HelperClient
    {
        public static HelperReply Send(string line, int timeoutMs)
        {
            var reply = new HelperReply();
            NamedPipeClientStream pipe = null;
            try
            {
                pipe = new NamedPipeClientStream(".", HelperProtocol.PipeName, PipeDirection.InOut, PipeOptions.None);
                pipe.Connect(Math.Min(timeoutMs, 3000));
                reply.Reached = true;
                NamedPipeClientStream p = pipe;
                Task<string> work = Task.Factory.StartNew(delegate
                {
                    byte[] data = Encoding.ASCII.GetBytes(line + "\n");
                    p.Write(data, 0, data.Length);
                    p.Flush();
                    return ReadLine(p);
                });
                if (!work.Wait(timeoutMs))
                {
                    reply.Detail = "помощник не ответил";
                    return reply;
                }
                bool ok;
                string detail;
                if (!HelperProtocol.TryParseReply(work.Result, out ok, out detail))
                {
                    reply.Detail = "непонятный ответ помощника";
                    return reply;
                }
                reply.Ok = ok;
                reply.Detail = detail;
            }
            catch (System.TimeoutException)
            {
                reply.Detail = "помощник не установлен или не запущен";
            }
            catch (Exception e)
            {
                reply.Detail = reply.Reached ? e.Message : "помощник не установлен или не запущен";
            }
            finally
            {
                if (pipe != null) pipe.Dispose();
            }
            return reply;
        }

        internal static string ReadLine(Stream stream)
        {
            var bytes = new List<byte>();
            int b;
            while ((b = stream.ReadByte()) >= 0)
            {
                if (b == '\n') break;
                bytes.Add((byte)b);
                if (bytes.Count > HelperProtocol.MaxLine) break;
            }
            return Encoding.ASCII.GetString(bytes.ToArray());
        }

        /// <summary>Помощник отвечает; в Detail — версия и состояние брандмауэра («1.10.0 fw=on»).</summary>
        public static HelperReply Status()
        {
            return Send("status", 3000);
        }

        public static HelperReply Ban(string cidr)
        {
            return Send("ban " + cidr, 20000);
        }

        public static HelperReply Unban(string cidr)
        {
            return Send("unban " + cidr, 20000);
        }
    }

    /// <summary>
    /// Установка и удаление помощника блокировки, а также его служба. Помощник — копия этого же exe в Program Files (защищённая
    /// от записи обычным пользователем папка), зарегистрированная службой Windows от имени системы. Служба слушает именованный
    /// канал, доступный только установившему её пользователю, и по запросам агента добавляет и убирает правила брандмауэра
    /// «HiWeb Agent: блок &lt;подсеть&gt;» — только для публичных подсетей /24 (IPv6 — /64) и уже (<see cref="HelperProtocol"/>).
    /// Устанавливается одним запросом UAC; дальше блокировки идут без запросов, даже когда владельца нет у ПК.
    /// </summary>
    static class HelperInstaller
    {
        public const string InstallVerb = "install-helper";
        public const string RemoveVerb = "remove-helper";
        public const string ServiceArg = "--guard-service";
        const string SidArg = "--sid";
        const string EnableFwArg = "--enable-fw";
        const string DirName = "HiWeb Agent";

        static string TargetDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), DirName); }
        }

        static string TargetExe
        {
            get { return Path.Combine(TargetDir, "HiWebAgent.exe"); }
        }

        /// <summary>Служба помощника зарегистрирована в Windows (читается без прав администратора).</summary>
        public static bool ServiceExists()
        {
            try
            {
                foreach (ServiceController s in ServiceController.GetServices())
                {
                    using (s) if (s.ServiceName == HelperProtocol.ServiceName) return true;
                }
            }
            catch (Exception e)
            {
                Log.Write("helper: service query: " + e.Message);
            }
            return false;
        }

        // ------------------------------------------------------------------ с запросом UAC (в процессе агента)

        /// <summary>
        /// Устанавливает помощника: один запрос UAC на ПК. [enableFirewall] — заодно включить брандмауэр (без него правила
        /// блокировки не действуют). Блокирует до ответа — звать из фонового потока. null — готово, иначе текст ошибки.
        /// </summary>
        public static string InstallWithUac(bool enableFirewall)
        {
            var args = new List<string>();
            args.Add(FirewallRules.ElevatedArg);
            args.Add(InstallVerb);
            args.Add(SidArg);
            args.Add(WindowsIdentity.GetCurrent().User.Value);
            if (enableFirewall) args.Add(EnableFwArg);
            int exit;
            string failure = FirewallRules.StartElevated(args, out exit);
            if (failure != null) return failure;
            switch (exit)
            {
                case FirewallRules.ExitOk: return null;
                case FirewallRules.ExitFirewallOff: return "Помощник установлен, но брандмауэр Windows выключен — блокировки не действуют, пока он не включён";
                case FirewallRules.ExitBadArgs: return "Недопустимые параметры установки";
                default: return "Не удалось установить помощника (подробности — в helper.log рядом с ним и в журнале агента)";
            }
        }

        public static string RemoveWithUac()
        {
            int exit;
            string failure = FirewallRules.StartElevated(new[] { FirewallRules.ElevatedArg, RemoveVerb }, out exit);
            if (failure != null) return failure;
            return exit == FirewallRules.ExitOk ? null : "Не удалось удалить помощника (подробности — в журнале агента)";
        }

        // ------------------------------------------------------------------ повышенный процесс

        /// <summary>Тело повышенной копии: <c>--guard-fw install-helper --sid SID [--enable-fw]</c> и <c>--guard-fw remove-helper</c>.</summary>
        public static int RunElevated(string[] args)
        {
            try
            {
                if (args[1] == RemoveVerb) return Remove();
                string sid = null;
                bool enableFw = false;
                for (int i = 2; i < args.Length; i++)
                {
                    if (args[i] == SidArg && i + 1 < args.Length) sid = args[++i];
                    else if (args[i] == EnableFwArg) enableFw = true;
                    else return FirewallRules.ExitBadArgs;
                }
                if (!IsUserSid(sid)) return FirewallRules.ExitBadArgs;
                return Install(sid, enableFw);
            }
            catch (Exception e)
            {
                Log.Write("guard-fw helper: " + e);
                return FirewallRules.ExitError;
            }
        }

        /// <summary>Только учётная запись пользователя (локальная, доменная, Microsoft/Azure AD): не «Все» и не группа.</summary>
        static bool IsUserSid(string text)
        {
            if (string.IsNullOrEmpty(text)) return false;
            try
            {
                var sid = new SecurityIdentifier(text);
                return sid.Value.StartsWith("S-1-5-21-") || sid.Value.StartsWith("S-1-12-1-");
            }
            catch (ArgumentException)
            {
                return false;
            }
        }

        static int Install(string sid, bool enableFw)
        {
            string self = Assembly.GetExecutingAssembly().Location;
            Directory.CreateDirectory(TargetDir);
            StopService();
            if (!string.Equals(Path.GetFullPath(self), Path.GetFullPath(TargetExe), StringComparison.OrdinalIgnoreCase)) File.Copy(self, TargetExe, true);
            string binPath = "\\\"" + TargetExe + "\\\" " + ServiceArg + " " + SidArg + " " + sid;
            string output;
            string verb = ServiceExists() ? "config" : "create";
            if (Sc(verb + " " + HelperProtocol.ServiceName + " binPath= \"" + binPath + "\" start= auto obj= LocalSystem DisplayName= \"HiWeb Agent Guard\"", out output) != 0)
            {
                Log.Write("guard-fw helper: sc " + verb + ": " + output);
                return FirewallRules.ExitError;
            }
            Sc("description " + HelperProtocol.ServiceName + " \"Блокирует подсети, с которых подбирают пароль по RDP, по запросу HiWeb Agent\"", out output);
            Sc("failure " + HelperProtocol.ServiceName + " reset= 86400 actions= restart/5000/restart/30000/restart/60000", out output);
            if (Sc("start " + HelperProtocol.ServiceName, out output) != 0 && !output.Contains("1056"))
            {
                Log.Write("guard-fw helper: sc start: " + output);
                return FirewallRules.ExitError;
            }
            Log.Write("guard-fw: helper installed for " + sid + " in " + TargetDir);
            // Правила блокировки сначала, брандмауэр потом — как и при обычной блокировке.
            if (enableFw) FirewallRules.EnableFirewallNow();
            return FirewallRules.Enabled() ? FirewallRules.ExitOk : FirewallRules.ExitFirewallOff;
        }

        static int Remove()
        {
            StopService();
            string output;
            if (ServiceExists() && Sc("delete " + HelperProtocol.ServiceName, out output) != 0)
            {
                Log.Write("guard-fw helper: sc delete: " + output);
                return FirewallRules.ExitError;
            }
            // Файл освобождается не сразу после остановки службы.
            for (int i = 0; i < 10; i++)
            {
                try
                {
                    if (Directory.Exists(TargetDir)) Directory.Delete(TargetDir, true);
                    break;
                }
                catch (Exception)
                {
                    Thread.Sleep(500);
                }
            }
            Log.Write("guard-fw: helper removed");
            return FirewallRules.ExitOk;
        }

        static void StopService()
        {
            if (!ServiceExists()) return;
            try
            {
                using (var sc = new ServiceController(HelperProtocol.ServiceName))
                {
                    if (sc.Status == ServiceControllerStatus.Stopped) return;
                    sc.Stop();
                    sc.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(15));
                }
            }
            catch (Exception e)
            {
                Log.Write("guard-fw helper: stop: " + e.Message);
            }
        }

        static int Sc(string args, out string output)
        {
            var psi = new ProcessStartInfo("sc.exe", args);
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardOutput = true;
            using (Process p = Process.Start(psi))
            {
                output = p.StandardOutput.ReadToEnd();
                if (!p.WaitForExit(30000)) throw new System.TimeoutException("sc " + args);
                return p.ExitCode;
            }
        }

        // ------------------------------------------------------------------ служба

        /// <summary>Точка входа службы: <c>HiWebAgent.exe --guard-service --sid SID</c> (так её запускает Windows).</summary>
        public static int RunService(string[] args)
        {
            string sid = null;
            for (int i = 1; i + 1 < args.Length; i++) if (args[i] == SidArg) sid = args[i + 1];
            if (!IsUserSid(sid)) return FirewallRules.ExitBadArgs;
            ServiceBase.Run(new GuardService(sid));
            return 0;
        }
    }

    /// <summary>Служба помощника блокировки: принимает запросы агента по именованному каналу и меняет правила брандмауэра.</summary>
    sealed class GuardService : ServiceBase
    {
        const int MAX_BANS_PER_HOUR = 200;
        const int CLIENT_TIMEOUT_MS = 5000;

        readonly string sid;
        readonly List<long> bans = new List<long>();
        volatile bool stopping;
        Thread listener;

        public GuardService(string sid)
        {
            this.sid = sid;
            ServiceName = HelperProtocol.ServiceName;
            CanStop = true;
            AutoLog = false;
        }

        static string LogPath
        {
            get { return Path.Combine(Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location), "helper.log"); }
        }

        /// <summary>Журнал службы лежит рядом с ней, в Program Files: записывать в него может только система, читать — все.</summary>
        static void Write(string line)
        {
            try
            {
                var fi = new FileInfo(LogPath);
                if (fi.Exists && fi.Length > 256 * 1024) fi.Delete();
                File.AppendAllText(LogPath, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss ") + line + Environment.NewLine);
            }
            catch (Exception)
            {
            }
        }

        protected override void OnStart(string[] args)
        {
            listener = new Thread(Listen);
            listener.IsBackground = true;
            listener.Start();
            Write("helper " + AgentInfo.Version + " started for " + sid);
        }

        protected override void OnStop()
        {
            stopping = true;
            try
            {
                // Будим ожидающий WaitForConnection.
                using (var wake = new NamedPipeClientStream(".", HelperProtocol.PipeName, PipeDirection.InOut)) wake.Connect(1000);
            }
            catch (Exception)
            {
            }
            Write("helper stopped");
        }

        PipeSecurity BuildSecurity()
        {
            var security = new PipeSecurity();
            // Сети (удалённым клиентам) канал закрыт, SYSTEM и администраторам — полный доступ, пользователю — чтение и запись.
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.NetworkSid, null), PipeAccessRights.FullControl, AccessControlType.Deny));
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
            // Тому, под кем запущена служба (обычно тот же SYSTEM), нужно право создавать следующие экземпляры канала.
            security.AddAccessRule(new PipeAccessRule(WindowsIdentity.GetCurrent().User, PipeAccessRights.FullControl, AccessControlType.Allow));
            security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(sid), PipeAccessRights.ReadWrite, AccessControlType.Allow));
            return security;
        }

        void Listen()
        {
            PipeSecurity security = BuildSecurity();
            while (!stopping)
            {
                NamedPipeServerStream pipe = null;
                try
                {
                    // Флаг «первый экземпляр» .NET 4.x не принимает: имя канала занимает служба, стартующая при загрузке системы.
                    pipe = new NamedPipeServerStream(HelperProtocol.PipeName, PipeDirection.InOut, 8, PipeTransmissionMode.Byte, PipeOptions.None, 512, 512, security);
                    pipe.WaitForConnection();
                    if (stopping)
                    {
                        pipe.Dispose();
                        break;
                    }
                    NamedPipeServerStream client = pipe;
                    pipe = null;
                    ThreadPool.QueueUserWorkItem(delegate { Serve(client); });
                }
                catch (Exception e)
                {
                    Write("listen: " + e.Message);
                    if (pipe != null) pipe.Dispose();
                    Thread.Sleep(1000);
                }
            }
        }

        void Serve(NamedPipeServerStream pipe)
        {
            // Клиент, который подключился и молчит, не должен занимать канал: через CLIENT_TIMEOUT_MS его соединение закрывается.
            using (pipe)
            using (new Timer(delegate { try { pipe.Dispose(); } catch (Exception) { } }, null, CLIENT_TIMEOUT_MS, Timeout.Infinite))
            {
                try
                {
                    string reply = Handle(HelperClient.ReadLine(pipe));
                    byte[] data = Encoding.ASCII.GetBytes(reply + "\n");
                    pipe.Write(data, 0, data.Length);
                    pipe.Flush();
                    pipe.WaitForPipeDrain();
                }
                catch (Exception e)
                {
                    Write("serve: " + e.Message);
                }
            }
        }

        string Handle(string line)
        {
            string verb, cidr;
            if (!HelperProtocol.TryParseRequest(line, out verb, out cidr)) return HelperProtocol.Err("bad request");
            try
            {
                bool fwOn = FirewallRules.Enabled();
                switch (verb)
                {
                    case "status":
                        return HelperProtocol.Ok(AgentInfo.Version + " fw=" + (fwOn ? "on" : "off"));
                    case "ban":
                        if (!AllowBan()) return HelperProtocol.Err("limit");
                        bool existed = FirewallRules.BanRange(cidr);
                        Write("ban " + cidr + (existed ? " (already)" : "") + (fwOn ? "" : " (firewall off)"));
                        return HelperProtocol.Ok((existed ? "exists" : "") + (existed && !fwOn ? " " : "") + (fwOn ? "" : "fwoff"));
                    default:
                        FirewallRules.UnbanRange(cidr);
                        Write("unban " + cidr);
                        return HelperProtocol.Ok("");
                }
            }
            catch (Exception e)
            {
                Write(verb + " " + cidr + ": " + e.Message);
                return HelperProtocol.Err("firewall");
            }
        }

        /// <summary>Не больше MAX_BANS_PER_HOUR новых блокировок в час: запросы шлёт процесс обычного пользователя.</summary>
        bool AllowBan()
        {
            lock (bans)
            {
                long now = DateTime.UtcNow.Ticks / TimeSpan.TicksPerMillisecond;
                bans.RemoveAll(delegate(long t) { return t < now - 60 * 60 * 1000; });
                if (bans.Count >= MAX_BANS_PER_HOUR) return false;
                bans.Add(now);
                return true;
            }
        }
    }
}
