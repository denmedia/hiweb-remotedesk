using System;
using System.Collections.Generic;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace HiWebAgent
{
    /// <summary>
    /// Состояние ПК для карточки на планшете: заблокирован ли экран, кто смотрит сеанс, работают ли за ПК,
    /// и куда ПК уходит (сон, гибернация, выключение, перезагрузка, выход). Плюс MAC-адреса для Wake-on-LAN.
    /// Меняется из потока интерфейса; снимок можно брать из любого потока.
    /// </summary>
    sealed class PcStatus
    {
        public const string Active = "active";
        public const string Locked = "locked";
        public const string Sleep = "sleep";
        public const string Hibernate = "hibernate";
        public const string Shutdown = "shutdown";
        public const string Restart = "restart";
        public const string Logoff = "logoff";

        /// <summary>Без ввода дольше этого за ПК, скорее всего, никого нет.</summary>
        const int BUSY_IDLE_SEC = 120;

        [StructLayout(LayoutKind.Sequential)]
        struct LastInputInfo
        {
            public int Size;
            public uint Time;
        }

        [DllImport("user32.dll")]
        static extern bool GetLastInputInfo(ref LastInputInfo info);

        volatile bool locked;
        volatile bool disconnected;
        volatile string leaving;
        volatile bool tabletSession;

        public bool ScreenLocked { set { locked = value; } }

        /// <summary>У сеанса нет клиента: RDP отключён без выхода или консоль отдана другому сеансу.</summary>
        public bool Disconnected { set { disconnected = value; } }

        /// <summary>Сеанс открыт на планшете (канал HiWeb::Agent на связи).</summary>
        public bool TabletSession { set { tabletSession = value; } }

        /// <summary>ПК засыпает или выключается: [state] из Sleep/Hibernate/Shutdown/Restart/Logoff, null — передумал.</summary>
        public string Leaving { set { leaving = value; } }

        /// <summary>
        /// На экране рабочий стол, который стоит показать в превью: не экран блокировки, сеанс не отключён,
        /// ПК никуда не уходит. Сеанс на планшете превью обновляет само приложение.
        /// </summary>
        public bool DesktopVisible
        {
            get { return !locked && !disconnected && leaving == null && !tabletSession; }
        }

        public Dictionary<string, object> Snapshot()
        {
            var msg = new Dictionary<string, object>();
            msg["t"] = "status";
            string l = leaving;
            msg["state"] = l ?? (locked ? Locked : Active);
            string session;
            if (tabletSession) session = "tablet";
            else if (disconnected) session = "none";
            else if (SystemInformation.TerminalServerSession) session = "remote";
            else session = "console";
            msg["session"] = session;
            msg["idle"] = IdleSeconds();
            msg["wol"] = WakeTargets();
            return msg;
        }

        /// <summary>Ключ для сравнения снимков: секунды простоя меняются всегда, важен только порог «за ПК работают».</summary>
        public static string Key(Dictionary<string, object> s)
        {
            int idle = (int)s["idle"];
            return s["state"] + "|" + s["session"] + "|" + (idle < BUSY_IDLE_SEC);
        }

        static int IdleSeconds()
        {
            var info = new LastInputInfo();
            info.Size = Marshal.SizeOf(typeof(LastInputInfo));
            if (!GetLastInputInfo(ref info)) return 0;
            uint ms = unchecked((uint)Environment.TickCount - info.Time);
            return (int)Math.Min(ms / 1000, int.MaxValue);
        }

        /// <summary>
        /// Проводные адаптеры с выходом в сеть: их MAC и широковещательный адрес подсети. Wi-Fi не будит ПК из сна
        /// почти нигде, а виртуальные адаптеры (Hyper-V, VPN, туннели) будить нечего.
        /// </summary>
        public static List<object> WakeTargets()
        {
            var list = new List<object>();
            try
            {
                foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType != NetworkInterfaceType.Ethernet && ni.NetworkInterfaceType != NetworkInterfaceType.GigabitEthernet) continue;
                    if (IsVirtual(ni.Description) || IsVirtual(ni.Name)) continue;
                    byte[] mac = ni.GetPhysicalAddress().GetAddressBytes();
                    if (mac.Length != 6) continue;
                    IPInterfaceProperties props = ni.GetIPProperties();
                    bool gateway = false;
                    foreach (GatewayIPAddressInformation g in props.GatewayAddresses)
                    {
                        if (g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any)) gateway = true;
                    }
                    if (!gateway) continue;
                    foreach (UnicastIPAddressInformation a in props.UnicastAddresses)
                    {
                        if (a.Address.AddressFamily != AddressFamily.InterNetwork || a.IPv4Mask == null) continue;
                        byte[] ip = a.Address.GetAddressBytes();
                        byte[] mask = a.IPv4Mask.GetAddressBytes();
                        var bcast = new byte[4];
                        for (int i = 0; i < 4; i++) bcast[i] = (byte)(ip[i] | ~mask[i]);
                        var t = new Dictionary<string, object>();
                        t["mac"] = BitConverter.ToString(mac).Replace('-', ':');
                        t["ip"] = a.Address.ToString();
                        t["bcast"] = new IPAddress(bcast).ToString();
                        list.Add(t);
                        break;
                    }
                }
            }
            catch (Exception e)
            {
                Log.Write("wol targets: " + e.Message);
            }
            return list;
        }

        static bool IsVirtual(string s)
        {
            if (s == null) return false;
            string l = s.ToLowerInvariant();
            foreach (string w in new[] { "virtual", "hyper-v", "vethernet", "vmware", "virtualbox", "tap-", "tun", "vpn", "wintun", "loopback", "bluetooth" })
            {
                if (l.Contains(w)) return true;
            }
            return false;
        }
    }
}
