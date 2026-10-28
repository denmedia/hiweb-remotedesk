using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Reflection;
using Microsoft.Win32;

namespace HiWebAgent
{
    /// <summary>Подсеть, заблокированная агентом (правило брандмауэра группы «HiWeb Agent»).</summary>
    sealed class BlockedRange
    {
        public string Cidr;
        public bool Enabled;
    }

    /// <summary>
    /// Блокировка подсетей правилами брандмауэра Windows. Читать правила и состояние брандмауэра можно без прав
    /// администратора; менять — нельзя, поэтому агент запускает собственный exe с ключом <see cref="ElevatedArg"/>
    /// и запросом UAC (<see cref="RunWithUac"/>), а тот выполняет <see cref="RunElevated"/> и завершается.
    /// Правила — входящие, для всех протоколов и профилей, имя «HiWeb Agent: блок &lt;подсеть&gt;».
    /// </summary>
    static class FirewallRules
    {
        public const string ElevatedArg = "--guard-fw";
        public const string Group = "HiWeb Agent";
        const string NamePrefix = "HiWeb Agent: блок ";
        const string AllowRdpName = "HiWeb Agent: RDP порт ";
        const int PROFILES_ALL = 0x7FFFFFFF;
        const int DIR_IN = 1;
        const int ACTION_BLOCK = 0;
        const int ACTION_ALLOW = 1;
        const int PROTOCOL_ANY = 256;
        const int PROTOCOL_TCP = 6;
        const int UAC_CANCELLED = 1223;
        const int UAC_WAIT_MS = 3 * 60 * 1000;

        // Коды завершения повышенного процесса.
        public const int ExitOk = 0;
        public const int ExitBadArgs = 1;
        public const int ExitError = 2;
        /// <summary>Правила записаны, но брандмауэр выключен — они не работают.</summary>
        public const int ExitFirewallOff = 10;

        static object NewPolicy()
        {
            return Activator.CreateInstance(Type.GetTypeFromProgID("HNetCfg.FwPolicy2"));
        }

        /// <summary>Подсети, заблокированные агентом. Пустой список и при ошибке чтения.</summary>
        public static List<BlockedRange> Blocked()
        {
            var list = new List<BlockedRange>();
            try
            {
                dynamic policy = NewPolicy();
                foreach (dynamic rule in (System.Collections.IEnumerable)policy.Rules)
                {
                    string name = rule.Name;
                    if (name == null || !name.StartsWith(NamePrefix)) continue;
                    string remote = rule.RemoteAddresses;
                    if (string.IsNullOrEmpty(remote)) continue;
                    foreach (string part in remote.Split(','))
                    {
                        var r = new BlockedRange();
                        r.Cidr = IpTools.NormalizeBlock(part) ?? part;
                        r.Enabled = rule.Enabled;
                        list.Add(r);
                    }
                }
            }
            catch (Exception e)
            {
                Log.Write("firewall read: " + e.Message);
            }
            return list;
        }

        /// <summary>
        /// Брандмауэр включён во всех действующих профилях. Выключенный не применяет никаких правил, в том числе
        /// блокировки. Если прочитать не удалось — считаем включённым: ложная тревога хуже молчания об ошибке чтения.
        /// </summary>
        public static bool Enabled()
        {
            try
            {
                object policy = NewPolicy();
                int current = (int)GetProp(policy, "CurrentProfileTypes");
                foreach (int profile in new[] { 1, 2, 4 })
                {
                    if ((current & profile) == 0) continue;
                    if (!(bool)policy.GetType().InvokeMember("FirewallEnabled", BindingFlags.GetProperty, null, policy, new object[] { profile })) return false;
                }
                return true;
            }
            catch (Exception e)
            {
                Log.Write("firewall state: " + e.Message);
                return true;
            }
        }

        static object GetProp(object com, string name)
        {
            return com.GetType().InvokeMember(name, BindingFlags.GetProperty, null, com, null);
        }

        // ------------------------------------------------------------------ с запросом UAC

        /// <summary>
        /// Запускает повышенную копию агента: block/unblock подсетей, при [enableFirewall] — ещё и включает брандмауэр.
        /// Блокирует до ответа на запрос UAC (до 3 минут), поэтому вызывать из фонового потока. null — готово,
        /// иначе текст ошибки для пользователя; [applied] — правила всё же записаны (брандмауэр при этом мог
        /// остаться выключенным).
        /// </summary>
        public static string RunWithUac(string verb, IEnumerable<string> cidrs, bool enableFirewall, out bool applied)
        {
            applied = false;
            var args = new List<string>();
            args.Add(ElevatedArg);
            args.Add(verb);
            if (enableFirewall) args.Add("--enable-fw");
            foreach (string c in cidrs)
            {
                string ok = IpTools.NormalizeBlock(c);
                if (ok == null) return "Эту подсеть блокировать нельзя: " + c;
                args.Add(ok);
            }
            var psi = new ProcessStartInfo(Assembly.GetExecutingAssembly().Location, string.Join(" ", args.ToArray()));
            psi.UseShellExecute = true;
            psi.Verb = "runas";
            psi.WindowStyle = ProcessWindowStyle.Hidden;
            try
            {
                using (Process p = Process.Start(psi))
                {
                    if (p == null) return "Не удалось запустить повышенный процесс";
                    if (!p.WaitForExit(UAC_WAIT_MS)) return "Нет ответа на запрос Windows о правах администратора";
                    applied = p.ExitCode == ExitOk || p.ExitCode == ExitFirewallOff;
                    switch (p.ExitCode)
                    {
                        case ExitOk: return null;
                        case ExitFirewallOff: return "Правило добавлено, но брандмауэр Windows выключен — оно не работает, пока он не включён";
                        case ExitBadArgs: return "Недопустимая подсеть";
                        default: return "Не удалось изменить правила брандмауэра (подробности — в журнале агента)";
                    }
                }
            }
            catch (Win32Exception e)
            {
                if (e.NativeErrorCode == UAC_CANCELLED) return "Запрос прав администратора отклонён";
                Log.Write("uac: " + e.Message);
                return "Не удалось запросить права администратора: " + e.Message;
            }
        }

        // ------------------------------------------------------------------ повышенный процесс

        /// <summary>Тело повышенной копии: <c>--guard-fw block|unblock [--enable-fw] подсеть…</c>. Возвращает код завершения.</summary>
        public static int RunElevated(string[] args)
        {
            if (args.Length < 3) return ExitBadArgs;
            string verb = args[1];
            bool enable = false;
            var cidrs = new List<string>();
            for (int i = 2; i < args.Length; i++)
            {
                if (args[i] == "--enable-fw")
                {
                    enable = true;
                    continue;
                }
                // Подсеть проверяется ещё раз здесь: этот процесс с правами администратора доверять аргументам не должен.
                string cidr = IpTools.NormalizeBlock(args[i]);
                if (cidr == null)
                {
                    Log.Write("guard-fw: rejected " + args[i]);
                    return ExitBadArgs;
                }
                cidrs.Add(cidr);
            }
            if (verb != "block" && verb != "unblock") return ExitBadArgs;
            if (cidrs.Count == 0) return ExitBadArgs;
            try
            {
                dynamic policy = NewPolicy();
                if (verb == "unblock")
                {
                    foreach (string cidr in cidrs) policy.Rules.Remove(NamePrefix + cidr);
                    Log.Write("guard-fw: unblocked " + string.Join(", ", cidrs.ToArray()));
                    return ExitOk;
                }
                // Сначала правила, потом включение брандмауэра: иначе удалённый сеанс успел бы потерять связь.
                foreach (string cidr in cidrs) AddBlock(policy, cidr);
                Log.Write("guard-fw: blocked " + string.Join(", ", cidrs.ToArray()));
                if (enable) EnableFirewall(policy);
                return Enabled() ? ExitOk : ExitFirewallOff;
            }
            catch (Exception e)
            {
                Log.Write("guard-fw: " + e);
                return ExitError;
            }
        }

        static void AddBlock(dynamic policy, string cidr)
        {
            string name = NamePrefix + cidr;
            try
            {
                dynamic existing = policy.Rules.Item(name);
                existing.Enabled = true;
                return;
            }
            catch (Exception)
            {
                // Правила нет — создаём.
            }
            dynamic rule = Activator.CreateInstance(Type.GetTypeFromProgID("HNetCfg.FWRule"));
            rule.Name = name;
            rule.Description = "Заблокировано HiWeb Agent " + DateTime.Now.ToString("yyyy-MM-dd HH:mm") + " из-за подозрительных подключений по RDP";
            rule.Grouping = Group;
            rule.Direction = DIR_IN;
            rule.Action = ACTION_BLOCK;
            rule.Protocol = PROTOCOL_ANY;
            rule.Profiles = PROFILES_ALL;
            rule.RemoteAddresses = cidr;
            rule.Enabled = true;
            policy.Rules.Add(rule);
        }

        /// <summary>
        /// Включает брандмауэр во всех профилях, предварительно разрешив входящий RDP: встроенными правилами и, если порт
        /// не 3389, собственным правилом на нужный порт. Остальные входящие подключения, не разрешённые правилами,
        /// после включения блокируются — это обычная настройка Windows.
        /// </summary>
        static void EnableFirewall(dynamic policy)
        {
            int port = RdpPort();
            if (port != 3389) AddAllowRdp(policy, port);
            RunPowerShell("Enable-NetFirewallRule -Name 'RemoteDesktop-UserMode-In-TCP','RemoteDesktop-UserMode-In-UDP' -ErrorAction SilentlyContinue; " +
                "Set-NetFirewallProfile -Profile Domain,Private,Public -Enabled True");
            Log.Write("guard-fw: firewall enabled, RDP port " + port);
        }

        static void AddAllowRdp(dynamic policy, int port)
        {
            string name = AllowRdpName + port;
            try
            {
                dynamic existing = policy.Rules.Item(name);
                existing.Enabled = true;
                return;
            }
            catch (Exception)
            {
            }
            dynamic rule = Activator.CreateInstance(Type.GetTypeFromProgID("HNetCfg.FWRule"));
            rule.Name = name;
            rule.Description = "Входящий RDP на нестандартный порт, разрешён HiWeb Agent при включении брандмауэра";
            rule.Grouping = Group;
            rule.Direction = DIR_IN;
            rule.Action = ACTION_ALLOW;
            rule.Protocol = PROTOCOL_TCP;
            rule.LocalPorts = port.ToString();
            rule.Profiles = PROFILES_ALL;
            rule.Enabled = true;
            policy.Rules.Add(rule);
        }

        /// <summary>Порт, на котором ПК принимает RDP (читается без прав администратора).</summary>
        public static int RdpPort()
        {
            try
            {
                using (RegistryKey k = Registry.LocalMachine.OpenSubKey(@"SYSTEM\CurrentControlSet\Control\Terminal Server\WinStations\RDP-Tcp"))
                {
                    object v = k == null ? null : k.GetValue("PortNumber");
                    if (v is int && (int)v > 0 && (int)v < 65536) return (int)v;
                }
            }
            catch (Exception)
            {
            }
            return 3389;
        }

        static void RunPowerShell(string command)
        {
            var psi = new ProcessStartInfo("powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"" + command + "\"");
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            using (Process p = Process.Start(psi))
            {
                if (!p.WaitForExit(60000)) throw new TimeoutException("powershell");
                if (p.ExitCode != 0) throw new InvalidOperationException("powershell exit " + p.ExitCode);
            }
        }
    }
}
