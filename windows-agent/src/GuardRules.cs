using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;

namespace HiWebAgent
{
    /// <summary>
    /// Адреса и подсети. Строки адресов в журнале — канонические (без порта, IPv4-в-IPv6 свёрнут в IPv4), подсети —
    /// в виде «198.51.100.0/24» (IPv4) и «2001:db8:1:2::/64» (IPv6).
    /// </summary>
    static class IpTools
    {
        /// <summary>Самые широкие подсети, которые можно заблокировать: /16 для IPv4 и /48 для IPv6.</summary>
        const int MIN_PREFIX_V4 = 16;
        const int MIN_PREFIX_V6 = 48;

        /// <summary>Разбирает адрес (порт и «[…]» отбрасываются); false для не адреса, например «LOCAL» или «ЛОКАЛЬНЫЕ».</summary>
        public static bool TryParse(string text, out IPAddress ip)
        {
            ip = null;
            if (string.IsNullOrEmpty(text)) return false;
            string s = text.Trim();
            if (s.StartsWith("["))
            {
                int end = s.IndexOf(']');
                if (end < 0) return false;
                s = s.Substring(1, end - 1);
            }
            else if (s.IndexOf(':') >= 0 && s.IndexOf(':') == s.LastIndexOf(':'))
            {
                // «1.2.3.4:5678»: единственное двоеточие — порт IPv4.
                s = s.Substring(0, s.IndexOf(':'));
            }
            int scope = s.IndexOf('%');
            if (scope >= 0) s = s.Substring(0, scope);
            // «1» IPAddress считает адресом 0.0.0.1: нужна полная запись.
            if (s.IndexOf(':') < 0 && s.Split('.').Length != 4) return false;
            IPAddress parsed;
            if (!IPAddress.TryParse(s, out parsed)) return false;
            if (parsed.AddressFamily != AddressFamily.InterNetwork && parsed.AddressFamily != AddressFamily.InterNetworkV6) return false;
            byte[] b = parsed.GetAddressBytes();
            if (b.Length == 16)
            {
                bool mapped = b[10] == 0xff && b[11] == 0xff;
                for (int i = 0; i < 10 && mapped; i++) mapped = b[i] == 0;
                if (mapped) parsed = new IPAddress(new[] { b[12], b[13], b[14], b[15] });
            }
            ip = parsed;
            return true;
        }

        /// <summary>Канонический адрес или null.</summary>
        public static string Normalize(string text)
        {
            IPAddress ip;
            return TryParse(text, out ip) ? ip.ToString() : null;
        }

        /// <summary>
        /// Свой адрес: петля, частные сети, link-local, 100.64.0.0/10 (CGNAT и Tailscale), «этот узел» 0.0.0.0/8,
        /// IPv6 ULA и link-local. Такие адреса не бывают «чужим из интернета»: не подозреваются и не блокируются.
        /// </summary>
        public static bool IsLocal(IPAddress ip)
        {
            byte[] b = ip.GetAddressBytes();
            if (b.Length == 4)
            {
                if (b[0] == 0 || b[0] == 10 || b[0] == 127) return true;
                if (b[0] == 169 && b[1] == 254) return true;
                if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return true;
                if (b[0] == 192 && b[1] == 168) return true;
                if (b[0] == 100 && b[1] >= 64 && b[1] <= 127) return true;
                return false;
            }
            if (IPAddress.IsLoopback(ip) || ip.Equals(IPAddress.IPv6None) || ip.Equals(IPAddress.IPv6Any)) return true;
            if ((b[0] & 0xfe) == 0xfc) return true;
            if (b[0] == 0xfe && (b[1] & 0xc0) == 0x80) return true;
            return false;
        }

        /// <summary>Подсеть адреса: /24 для IPv4, /64 для IPv6.</summary>
        public static string Subnet(IPAddress ip)
        {
            byte[] b = ip.GetAddressBytes();
            if (b.Length == 4) return b[0] + "." + b[1] + "." + b[2] + ".0/24";
            var net = new byte[16];
            Array.Copy(b, net, 8);
            return new IPAddress(net) + "/64";
        }

        /// <summary>Один адрес как подсеть: «1.2.3.4/32».</summary>
        public static string Single(IPAddress ip)
        {
            return ip + "/" + (ip.AddressFamily == AddressFamily.InterNetwork ? 32 : 128);
        }

        /// <summary>Разбирает «адрес/длина» (длина — число или маска «255.255.255.0», как отдаёт брандмауэр).</summary>
        public static bool TryParseCidr(string cidr, out IPAddress net, out int prefix)
        {
            net = null;
            prefix = 0;
            if (string.IsNullOrEmpty(cidr)) return false;
            int slash = cidr.IndexOf('/');
            string addr = slash < 0 ? cidr : cidr.Substring(0, slash);
            if (!TryParse(addr, out net)) return false;
            int max = net.AddressFamily == AddressFamily.InterNetwork ? 32 : 128;
            if (slash < 0)
            {
                prefix = max;
                return true;
            }
            string len = cidr.Substring(slash + 1);
            if (len.IndexOf('.') >= 0)
            {
                IPAddress mask;
                if (net.AddressFamily != AddressFamily.InterNetwork || !IPAddress.TryParse(len, out mask)) return false;
                prefix = 0;
                bool zero = false;
                foreach (byte x in mask.GetAddressBytes())
                {
                    for (int bit = 7; bit >= 0; bit--)
                    {
                        bool one = (x >> bit & 1) == 1;
                        if (one && zero) return false;
                        if (one) prefix++;
                        else zero = true;
                    }
                }
                return true;
            }
            return int.TryParse(len, out prefix) && prefix >= 0 && prefix <= max;
        }

        /// <summary>
        /// Подсеть, пригодная для блокировки, в каноническом виде (host-биты обнулены), иначе null: не адрес, слишком
        /// широкая (весь интернет) или своя (локальная сеть, Tailscale).
        /// </summary>
        public static string NormalizeBlock(string cidr)
        {
            IPAddress net;
            int prefix;
            if (!TryParseCidr(cidr, out net, out prefix)) return null;
            bool v4 = net.AddressFamily == AddressFamily.InterNetwork;
            if (prefix < (v4 ? MIN_PREFIX_V4 : MIN_PREFIX_V6)) return null;
            byte[] b = net.GetAddressBytes();
            for (int i = 0; i < b.Length; i++)
            {
                int keep = Math.Max(0, Math.Min(8, prefix - i * 8));
                b[i] = (byte)(b[i] & (keep == 0 ? 0 : 0xff << (8 - keep)));
            }
            var canonical = new IPAddress(b);
            if (IsLocal(canonical)) return null;
            return canonical + "/" + prefix;
        }

        public static bool InCidr(IPAddress ip, string cidr)
        {
            IPAddress net;
            int prefix;
            if (!TryParseCidr(cidr, out net, out prefix) || net.AddressFamily != ip.AddressFamily) return false;
            byte[] a = ip.GetAddressBytes();
            byte[] n = net.GetAddressBytes();
            for (int i = 0; i < a.Length; i++)
            {
                int keep = Math.Max(0, Math.Min(8, prefix - i * 8));
                if (keep == 0) break;
                int mask = 0xff << (8 - keep) & 0xff;
                if ((a[i] & mask) != (n[i] & mask)) return false;
            }
            return true;
        }
    }

    /// <summary>Что случилось с подключением к ПК.</summary>
    static class GuardKind
    {
        /// <summary>Неверное имя пользователя или пароль.</summary>
        public const string Fail = "fail";

        /// <summary>Вход в сеанс или возврат в отключённый сеанс.</summary>
        public const string Logon = "logon";

        /// <summary>Выход из сеанса.</summary>
        public const string Logoff = "logoff";

        /// <summary>Отключение от сеанса без выхода (сеанс остаётся на ПК).</summary>
        public const string Disconnect = "disconnect";
    }

    /// <summary>Событие журнала Windows о подключении по RDP, приведённое к общему виду.</summary>
    sealed class RdpEvent
    {
        public string Kind;
        public long Time;     // мс Unix
        public string Ip;     // канонический адрес источника
        public string User;
    }

    /// <summary>Подозрительная активность: подбор пароля или вход с неизвестного адреса.</summary>
    sealed class Threat
    {
        public const string BruteForce = "bruteforce";
        public const string UnknownLogon = "unknown_logon";

        public const string StatusNew = "new";
        public const string StatusBlocked = "blocked";
        public const string StatusTrusted = "trusted";
        public const string StatusDismissed = "dismissed";

        public string Id;
        public string Rule;
        public long Time;
        public string Ip;        // последний адрес
        public string Cidr;      // что предлагается заблокировать
        public int Count;        // неудачных попыток в окне
        public string User;
        public bool AfterFail;   // успешный вход последовал за неудачными попытками
        public string Status = StatusNew;
    }

    /// <summary>
    /// Правила подозрительности. События подаются по времени возрастания (<see cref="Feed"/>); время берётся из самих
    /// событий, поэтому одни и те же правила работают и на живом потоке, и на истории журнала при запуске агента.
    ///
    /// 1) Подбор пароля: <see cref="FailThreshold"/> неудачных попыток за <see cref="WindowMs"/> с одной подсети
    ///    (/24 или /64: боты меняют адрес внутри подсети). Свои адреса и адреса, с которых планшет уже входил, не в счёт.
    /// 2) Вход с неизвестного адреса: успешный вход, после которого за <see cref="LogonGraceMs"/> планшет не открыл
    ///    канал HiWeb::Agent. Если открыл — адрес запоминается как свой (<see cref="learn"/>).
    /// Об одном и том же источнике повторно сообщается не чаще раза в <see cref="RealertMs"/>.
    /// </summary>
    sealed class ThreatDetector
    {
        public const int FailThreshold = 5;
        public const long WindowMs = 10 * 60 * 1000;
        public const long RealertMs = 60 * 60 * 1000;
        public const long LogonGraceMs = 30 * 1000;

        /// <summary>Канал планшета мог открыться чуть раньше записи о входе в журнале.</summary>
        const long TABLET_SLACK_MS = 5 * 1000;
        const long HISTORY_MS = 24 * 60 * 60 * 1000;

        readonly Func<string, bool> isTrusted;
        readonly Func<IEnumerable<string>> trustedList;
        readonly Action<string> learn;
        readonly Dictionary<string, List<long>> fails = new Dictionary<string, List<long>>();
        readonly Dictionary<string, long> lastAlert = new Dictionary<string, long>();
        readonly List<RdpEvent> pending = new List<RdpEvent>();
        readonly List<long> tabletOpens = new List<long>();

        public ThreatDetector(Func<string, bool> isTrusted, Func<IEnumerable<string>> trustedList, Action<string> learn)
        {
            this.isTrusted = isTrusted;
            this.trustedList = trustedList;
            this.learn = learn;
        }

        /// <summary>
        /// Канал HiWeb::Agent с планшетом открылся в момент [time]: вход, случившийся прямо перед этим, — свой.
        /// Моменты можно сообщать и задним числом (из журнала агента при запуске), в любом порядке.
        /// </summary>
        public void TabletOpened(long time)
        {
            tabletOpens.Add(time);
            tabletOpens.RemoveAll(delegate(long t) { return t < time - 2 * HISTORY_MS; });
        }

        bool TabletOpenedNear(long logon)
        {
            foreach (long t in tabletOpens) if (t >= logon - TABLET_SLACK_MS && t <= logon + LogonGraceMs) return true;
            return false;
        }

        /// <summary>Запоминает ранее поднятую угрозу, чтобы после перезапуска агента о ней не сообщать снова.</summary>
        public void Remember(Threat t)
        {
            long previous;
            string key = t.Rule + "|" + t.Cidr;
            if (!lastAlert.TryGetValue(key, out previous) || previous < t.Time) lastAlert[key] = t.Time;
        }

        public List<Threat> Feed(RdpEvent e)
        {
            var result = new List<Threat>();
            IPAddress ip;
            if (!IpTools.TryParse(e.Ip, out ip) || IpTools.IsLocal(ip)) return result;
            if (e.Kind == GuardKind.Fail)
            {
                if (isTrusted(ip.ToString())) return result;
                string subnet = IpTools.Subnet(ip);
                List<long> list;
                if (!fails.TryGetValue(subnet, out list))
                {
                    list = new List<long>();
                    fails[subnet] = list;
                }
                list.Add(e.Time);
                list.RemoveAll(delegate(long t) { return t < e.Time - HISTORY_MS; });
                int count = CountIn(list, e.Time);
                if (count >= FailThreshold)
                {
                    Threat t = Raise(Threat.BruteForce, ip, e.Time, count, e.User, false);
                    if (t != null) result.Add(t);
                }
            }
            else if (e.Kind == GuardKind.Logon && !isTrusted(ip.ToString()))
            {
                pending.Add(e);
            }
            return result;
        }

        /// <summary>Закрывает ожидавшие входы, которым уже хватило времени, чтобы планшет открыл канал.</summary>
        public List<Threat> Tick(long now)
        {
            var result = new List<Threat>();
            for (int i = pending.Count - 1; i >= 0; i--)
            {
                RdpEvent e = pending[i];
                if (now - e.Time < LogonGraceMs) continue;
                pending.RemoveAt(i);
                if (TabletOpenedNear(e.Time))
                {
                    learn(e.Ip);
                    continue;
                }
                IPAddress ip;
                if (!IpTools.TryParse(e.Ip, out ip) || isTrusted(ip.ToString())) continue;
                List<long> list;
                int recent = fails.TryGetValue(IpTools.Subnet(ip), out list) ? CountIn(list, e.Time) : 0;
                Threat t = Raise(Threat.UnknownLogon, ip, e.Time, recent, e.User, recent > 0);
                if (t != null) result.Add(t);
            }
            result.Sort(delegate(Threat a, Threat b) { return a.Time.CompareTo(b.Time); });
            return result;
        }

        /// <summary>Что предлагать заблокировать: подсеть, а если в ней есть свой адрес — только сам адрес.</summary>
        public string OfferedBlock(IPAddress ip)
        {
            string subnet = IpTools.Subnet(ip);
            foreach (string t in trustedList())
            {
                IPAddress known;
                if (IpTools.TryParse(t, out known) && IpTools.InCidr(known, subnet)) return IpTools.Single(ip);
            }
            return subnet;
        }

        Threat Raise(string rule, IPAddress ip, long time, int count, string user, bool afterFail)
        {
            string cidr = OfferedBlock(ip);
            string key = rule + "|" + cidr;
            long previous;
            if (lastAlert.TryGetValue(key, out previous) && time - previous < RealertMs) return null;
            lastAlert[key] = time;
            var t = new Threat();
            t.Id = rule + "|" + cidr + "|" + time;
            t.Rule = rule;
            t.Time = time;
            t.Ip = ip.ToString();
            t.Cidr = cidr;
            t.Count = count;
            t.User = user;
            t.AfterFail = afterFail;
            return t;
        }

        static int CountIn(List<long> list, long now)
        {
            int n = 0;
            foreach (long t in list) if (t >= now - WindowMs && t <= now) n++;
            return n;
        }
    }
}
