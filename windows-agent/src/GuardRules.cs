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

        /// <summary>Подсети пересекаются: одна целиком лежит в другой (границы подсетей всегда вложены).</summary>
        public static bool Overlaps(string a, string b)
        {
            IPAddress netA, netB;
            int prefixA, prefixB;
            if (!TryParseCidr(a, out netA, out prefixA) || !TryParseCidr(b, out netB, out prefixB)) return false;
            return InCidr(netA, b) || InCidr(netB, a);
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

    /// <summary>Режим защиты: как относиться к адресам, которых нет в списке знакомых.</summary>
    static class GuardMode
    {
        /// <summary>Тревога при подборе пароля (несколько неудач подряд) и при входе, к которому не подключился планшет.</summary>
        public const string Normal = "normal";

        /// <summary>
        /// Белый список: знакомые — только адреса и подсети из списка (и своя сеть). Любая попытка с остального интернета —
        /// тревога с первой же неудачи, вход с неизвестного адреса — тревога, даже если планшет подключился, пока адрес
        /// не занесён в список (автоматически или вручную).
        /// </summary>
        public const string Whitelist = "whitelist";

        public static bool IsValid(string mode)
        {
            return mode == Normal || mode == Whitelist;
        }
    }

    /// <summary>Настройки защиты, которые меняет пользователь: режим и то, что запоминается само.</summary>
    sealed class GuardSettings
    {
        public string Mode = GuardMode.Normal;

        /// <summary>Адрес, с которого вошёл планшет (канал HiWeb::Agent открылся сразу после входа), заносится в список сам.</summary>
        public bool AutoLearn = true;

        /// <summary>Заносить сразу всю подсеть адреса (/24, IPv6 — /64), а не один адрес: удобно, когда провайдер меняет адрес в пределах подсети.</summary>
        public bool LearnSubnet;

        /// <summary>Автоматически блокировать подсеть после <see cref="BanAfter"/> неудачных попыток входа (нужен помощник блокировки).</summary>
        public bool AutoBan = true;

        /// <summary>Сколько неудачных попыток входа за <see cref="ThreatDetector.BanWindowMs"/> с одной подсети ведут к блокировке.</summary>
        public int BanAfter = DefaultBanAfter;

        public const int DefaultBanAfter = 5;
        public const int MinBanAfter = 2;
        public const int MaxBanAfter = 50;

        public static int ClampBanAfter(int n)
        {
            return Math.Max(MinBanAfter, Math.Min(MaxBanAfter, n));
        }

        public bool WhitelistMode
        {
            get { return Mode == GuardMode.Whitelist; }
        }
    }

    /// <summary>Запись списка знакомых адресов: адрес («1.2.3.4/32») или подсеть.</summary>
    sealed class WhitelistEntry
    {
        public const string Manual = "manual";
        public const string Auto = "auto";

        public string Cidr;
        public string Source;     // Manual — добавлено пользователем, Auto — запомнено по подключению планшета
        public long Time;         // мс Unix
    }

    /// <summary>
    /// Список знакомых адресов и подсетей («белый список»). Свои адреса (частные сети, петля и т. п.) в него не заносятся —
    /// они знакомы всегда. Подсети не шире /16 (IPv6 — /48), как и при блокировке: список не должен открыть «весь интернет».
    /// </summary>
    sealed class Whitelist
    {
        public const int MaxEntries = 200;

        readonly List<WhitelistEntry> entries = new List<WhitelistEntry>();

        public List<WhitelistEntry> Entries
        {
            get { return new List<WhitelistEntry>(entries); }
        }

        public int Count
        {
            get { return entries.Count; }
        }

        public bool Contains(string ip)
        {
            IPAddress parsed;
            return IpTools.TryParse(ip, out parsed) && Contains(parsed);
        }

        public bool Contains(IPAddress ip)
        {
            foreach (WhitelistEntry e in entries) if (IpTools.InCidr(ip, e.Cidr)) return true;
            return false;
        }

        /// <summary>Запись списка, содержащая точно эту подсеть, или null.</summary>
        public WhitelistEntry Find(string cidr)
        {
            foreach (WhitelistEntry e in entries) if (e.Cidr == cidr) return e;
            return null;
        }

        /// <summary>В списке есть адреса из этой подсети (или она сама лежит в записи списка): блокировать её нельзя.</summary>
        public bool Overlaps(string cidr)
        {
            foreach (WhitelistEntry e in entries) if (IpTools.Overlaps(cidr, e.Cidr)) return true;
            return false;
        }

        /// <summary>
        /// Добавляет адрес или подсеть. Возвращает каноническую запись или null, если это не адрес/подсеть, подсеть слишком
        /// широка или адрес из своей сети. Повторное добавление не плодит записей; ручное добавление закрепляет автоматическую.
        /// </summary>
        public string Add(string text, string source, long time)
        {
            string cidr = IpTools.NormalizeBlock(text);
            if (cidr == null) return null;
            WhitelistEntry existing = Find(cidr);
            if (existing != null)
            {
                if (source == WhitelistEntry.Manual) existing.Source = WhitelistEntry.Manual;
                return cidr;
            }
            var e = new WhitelistEntry();
            e.Cidr = cidr;
            e.Source = source;
            e.Time = time;
            entries.Add(e);
            if (entries.Count > MaxEntries) DropOldest();
            return cidr;
        }

        /// <summary>Запоминает адрес, с которого вошёл планшет: сам адрес или (при [wholeSubnet]) его подсеть.</summary>
        public string Learn(IPAddress ip, bool wholeSubnet, long time)
        {
            return Add(wholeSubnet ? IpTools.Subnet(ip) : IpTools.Single(ip), WhitelistEntry.Auto, time);
        }

        public bool Remove(string cidr)
        {
            string canonical = IpTools.NormalizeBlock(cidr) ?? cidr;
            return entries.RemoveAll(delegate(WhitelistEntry e) { return e.Cidr == canonical; }) > 0;
        }

        /// <summary>При переполнении уходит самая старая автоматическая запись, а если таких нет — самая старая вообще.</summary>
        void DropOldest()
        {
            int drop = -1;
            for (int i = 0; i < entries.Count; i++)
            {
                bool better = drop < 0
                    || entries[i].Source == WhitelistEntry.Auto && entries[drop].Source != WhitelistEntry.Auto
                    || entries[i].Source == entries[drop].Source && entries[i].Time < entries[drop].Time;
                if (better) drop = i;
            }
            if (drop >= 0) entries.RemoveAt(drop);
        }

        /// <summary>Загрузка из файла: уже проверенные записи, без ограничений на размер (его проверит <see cref="Add"/> позже).</summary>
        public void Load(string cidr, string source, long time)
        {
            string canonical = IpTools.NormalizeBlock(cidr);
            if (canonical == null || Find(canonical) != null) return;
            var e = new WhitelistEntry();
            e.Cidr = canonical;
            e.Source = source == WhitelistEntry.Manual ? WhitelistEntry.Manual : WhitelistEntry.Auto;
            e.Time = time;
            entries.Add(e);
        }
    }

    /// <summary>Подозрительная активность: подбор пароля, вход с неизвестного адреса, обращение с адреса вне белого списка.</summary>
    sealed class Threat
    {
        public const string BruteForce = "bruteforce";
        public const string UnknownLogon = "unknown_logon";

        /// <summary>Режим белого списка: неудачная попытка входа с адреса, которого в списке нет (даже первая).</summary>
        public const string Unlisted = "unlisted";

        /// <summary>Режим белого списка без автоматического занесения: вход с нового адреса, с которого подключился и планшет.</summary>
        public const string NewAddress = "new_address";

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
    /// Всегда (свои адреса — локальная сеть — и адреса из белого списка не в счёт):
    /// 1) Подбор пароля: <see cref="FailThreshold"/> неудачных попыток за <see cref="WindowMs"/> с одной подсети
    ///    (/24 или /64: боты меняют адрес внутри подсети).
    /// 2) Вход с неизвестного адреса: успешный вход, после которого за <see cref="LogonGraceMs"/> планшет не открыл
    ///    канал HiWeb::Agent. Если открыл — адрес запоминается в списке (при <see cref="GuardSettings.AutoLearn"/>).
    /// В режиме белого списка (<see cref="GuardSettings.WhitelistMode"/>) строже:
    /// 3) Любая неудачная попытка с адреса вне списка — тревога сразу (<see cref="Threat.Unlisted"/>), не дожидаясь порога;
    ///    дойдя до порога, она становится подбором пароля.
    /// 4) Вход, к которому подключился планшет, но автоматическое занесение выключено, — <see cref="Threat.NewAddress"/>:
    ///    адрес надо занести в список вручную.
    /// Об одном и том же источнике повторно сообщается не чаще раза в <see cref="RealertMs"/>.
    /// </summary>
    sealed class ThreatDetector
    {
        public const int FailThreshold = 3;
        public const long WindowMs = 10 * 60 * 1000;
        public const long RealertMs = 60 * 60 * 1000;
        public const long LogonGraceMs = 30 * 1000;

        /// <summary>Окно, в котором считаются попытки для автоматической блокировки (дольше, чем для тревоги: медленный подбор тоже должен остановиться).</summary>
        public const long BanWindowMs = 60 * 60 * 1000;

        /// <summary>Канал планшета мог открыться чуть раньше записи о входе в журнале.</summary>
        const long TABLET_SLACK_MS = 5 * 1000;
        const long HISTORY_MS = 24 * 60 * 60 * 1000;

        readonly Whitelist whitelist;
        readonly GuardSettings settings;
        readonly Action<string> learned;
        readonly Dictionary<string, List<long>> fails = new Dictionary<string, List<long>>();
        readonly Dictionary<string, long> lastAlert = new Dictionary<string, long>();
        readonly List<RdpEvent> pending = new List<RdpEvent>();
        readonly List<long> tabletOpens = new List<long>();

        /// <param name="learned">Вызывается после того, как адрес запомнен в списке (получает запись списка); может быть null.</param>
        public ThreatDetector(Whitelist whitelist, GuardSettings settings, Action<string> learned)
        {
            this.whitelist = whitelist;
            this.settings = settings;
            this.learned = learned;
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
                if (whitelist.Contains(ip)) return result;
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
                string rule = count >= FailThreshold ? Threat.BruteForce : settings.WhitelistMode ? Threat.Unlisted : null;
                if (rule != null)
                {
                    Threat t = Raise(rule, ip, e.Time, count, e.User, false);
                    if (t != null) result.Add(t);
                }
            }
            else if (e.Kind == GuardKind.Logon && !whitelist.Contains(ip))
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
                IPAddress ip;
                if (!IpTools.TryParse(e.Ip, out ip) || whitelist.Contains(ip)) continue;
                List<long> list;
                int recent = fails.TryGetValue(IpTools.Subnet(ip), out list) ? CountIn(list, e.Time) : 0;
                if (TabletOpenedNear(e.Time))
                {
                    if (settings.AutoLearn)
                    {
                        string cidr = whitelist.Learn(ip, settings.LearnSubnet, e.Time);
                        if (cidr != null && learned != null) learned(cidr);
                        continue;
                    }
                    // Планшет подключился, но адреса в списке нет и сам он туда не попадёт: в обычном режиме это не повод
                    // для тревоги, в режиме белого списка — вопрос «занести?».
                    if (!settings.WhitelistMode) continue;
                    Threat known = Raise(Threat.NewAddress, ip, e.Time, recent, e.User, recent > 0);
                    if (known != null) result.Add(known);
                    continue;
                }
                Threat t = Raise(Threat.UnknownLogon, ip, e.Time, recent, e.User, recent > 0);
                if (t != null) result.Add(t);
            }
            result.Sort(delegate(Threat a, Threat b) { return a.Time.CompareTo(b.Time); });
            return result;
        }

        /// <summary>Результат разбора неудачной попытки входа: что о ней сказать устройствам и пора ли блокировать.</summary>
        public sealed class FailInfo
        {
            public string Ip;
            public string Cidr;       // подсеть, которую заблокирует автоблокировка
            public int Count;         // неудач с этой подсети за BanWindowMs, считая эту (для своих и знакомых адресов — 0)
            public bool Local;        // адрес из своей сети
            public bool Known;        // адрес из белого списка
            public bool BanDue;       // автоблокировка включена, порог достигнут, адрес чужой
        }

        /// <summary>
        /// Разбирает неудачную попытку входа [e] — зовётся после <see cref="Feed"/> для того же события. null — не попытка
        /// или не адрес. Свои (локальные) и знакомые (белый список) адреса не блокируются, но о попытке всё равно сообщается.
        /// </summary>
        public FailInfo Describe(RdpEvent e)
        {
            IPAddress ip;
            if (e.Kind != GuardKind.Fail || !IpTools.TryParse(e.Ip, out ip)) return null;
            var info = new FailInfo();
            info.Ip = ip.ToString();
            info.Cidr = OfferedBlock(ip);
            info.Local = IpTools.IsLocal(ip);
            info.Known = whitelist.Contains(ip);
            if (!info.Local && !info.Known)
            {
                List<long> list;
                info.Count = fails.TryGetValue(IpTools.Subnet(ip), out list) ? CountIn(list, e.Time, BanWindowMs) : 0;
            }
            info.BanDue = settings.AutoBan && info.Count >= settings.BanAfter;
            return info;
        }

        /// <summary>Что предлагать заблокировать: подсеть, а если в ней есть знакомый адрес — только сам адрес.</summary>
        public string OfferedBlock(IPAddress ip)
        {
            string subnet = IpTools.Subnet(ip);
            return whitelist.Overlaps(subnet) ? IpTools.Single(ip) : subnet;
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
            return CountIn(list, now, WindowMs);
        }

        static int CountIn(List<long> list, long now, long window)
        {
            int n = 0;
            foreach (long t in list) if (t >= now - window && t <= now) n++;
            return n;
        }
    }

    /// <summary>Ответ помощника блокировки.</summary>
    sealed class HelperReply
    {
        /// <summary>Помощник на связи (служба установлена и запущена).</summary>
        public bool Reached;

        /// <summary>Запрос выполнен.</summary>
        public bool Ok;

        /// <summary>Что после «ok»/«err» в ответе; при недоступном помощнике — причина.</summary>
        public string Detail = "";

        public bool Has(string token)
        {
            foreach (string part in Detail.Split(' ')) if (part == token) return true;
            return false;
        }
    }

    /// <summary>
    /// Язык помощника блокировки — службы с правами администратора, которая по запросу агента добавляет и убирает
    /// правила брандмауэра. Запрос — одна строка: <c>ban подсеть</c>, <c>unban подсеть</c> или <c>status</c>; ответ — одна
    /// строка: <c>ok</c>, <c>ok exists</c> (правило уже было), <c>ok fwoff</c> (правило записано, но брандмауэр выключен),
    /// <c>ok версия fw=on|off</c> на status и <c>err причина</c>. Запросам службе доверять нельзя (их шлёт процесс обычного
    /// пользователя), поэтому она сужает допустимое: только подсети /24 (IPv6 — /64) и уже, только публичные.
    /// </summary>
    static class HelperProtocol
    {
        public const string PipeName = "HiWebAgentGuard";
        public const string ServiceName = "HiWebAgentGuard";
        public const int MaxLine = 200;

        public const int MinPrefixV4 = 24;
        public const int MinPrefixV6 = 64;

        /// <summary>Подсеть, которую помощник согласен блокировать, в каноническом виде, иначе null.</summary>
        public static string NormalizeTarget(string text)
        {
            string cidr = IpTools.NormalizeBlock(text);
            if (cidr == null) return null;
            IPAddress net;
            int prefix;
            if (!IpTools.TryParseCidr(cidr, out net, out prefix)) return null;
            bool v4 = net.AddressFamily == AddressFamily.InterNetwork;
            return prefix >= (v4 ? MinPrefixV4 : MinPrefixV6) ? cidr : null;
        }

        /// <summary>Разбирает строку запроса. false — не понята; для ban/unban проверенная подсеть — в [cidr].</summary>
        public static bool TryParseRequest(string line, out string verb, out string cidr)
        {
            verb = "";
            cidr = null;
            if (line == null || line.Length > MaxLine) return false;
            string[] parts = line.Trim().Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 1 && parts[0] == "status")
            {
                verb = "status";
                return true;
            }
            if (parts.Length != 2 || parts[0] != "ban" && parts[0] != "unban") return false;
            cidr = NormalizeTarget(parts[1]);
            if (cidr == null) return false;
            verb = parts[0];
            return true;
        }

        public static string Ok(string detail)
        {
            return string.IsNullOrEmpty(detail) ? "ok" : "ok " + detail;
        }

        public static string Err(string reason)
        {
            return "err " + (reason ?? "").Replace('\r', ' ').Replace('\n', ' ');
        }

        /// <summary>Разбор ответа: false — не ответ помощника; [ok] — запрос выполнен, [detail] — всё, что после «ok»/«err».</summary>
        public static bool TryParseReply(string line, out bool ok, out string detail)
        {
            ok = false;
            detail = "";
            if (line == null) return false;
            string t = line.Trim();
            if (t == "ok" || t.StartsWith("ok "))
            {
                ok = true;
                detail = t.Length > 3 ? t.Substring(3).Trim() : "";
                return true;
            }
            if (t == "err" || t.StartsWith("err "))
            {
                detail = t.Length > 4 ? t.Substring(4).Trim() : "";
                return true;
            }
            return false;
        }
    }
}
