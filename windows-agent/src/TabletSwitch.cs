using System;
using System.Collections.Generic;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;
using Microsoft.Win32;

namespace HiWebAgent
{
    /// <summary>
    /// Планшет, к которому привязан агент: приходит по каналу RDP (сообщение pair) и хранится в реестре
    /// пользователя; общий ключ зашифрован DPAPI. Нужен, чтобы позвать планшет, когда он не подключён.
    /// </summary>
    sealed class TabletPairing
    {
        const string Key = Installer.SettingsKey + @"\Tablet";

        public string DeviceId;   // идентификатор этого ПК в приложении на планшете
        public string Name;       // модель планшета
        public int Port;
        public string[] Addresses;
        public byte[] Secret;

        public static TabletPairing Load()
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(Key))
                {
                    if (k == null) return null;
                    var p = new TabletPairing();
                    p.DeviceId = k.GetValue("DeviceId") as string;
                    p.Name = k.GetValue("Name") as string;
                    object port = k.GetValue("Port");
                    p.Port = port is int ? (int)port : 0;
                    p.Addresses = (k.GetValue("Addresses") as string ?? "").Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
                    byte[] secret = k.GetValue("Secret") as byte[];
                    if (p.DeviceId == null || p.Port <= 0 || secret == null) return null;
                    p.Secret = ProtectedData.Unprotect(secret, null, DataProtectionScope.CurrentUser);
                    return p;
                }
            }
            catch (Exception e)
            {
                Log.Write("pairing load: " + e.Message);
                return null;
            }
        }

        public void Save()
        {
            using (RegistryKey k = Registry.CurrentUser.CreateSubKey(Key))
            {
                k.SetValue("DeviceId", DeviceId);
                k.SetValue("Name", Name ?? "");
                k.SetValue("Port", Port, RegistryValueKind.DWord);
                k.SetValue("Addresses", string.Join(" ", Addresses ?? new string[0]));
                k.SetValue("Secret", ProtectedData.Protect(Secret, null, DataProtectionScope.CurrentUser), RegistryValueKind.Binary);
            }
        }
    }

    /// <summary>
    /// «Переключиться на планшет», когда планшет не подключён: подписанная (HMAC-SHA256) команда по UDP на
    /// известные адреса планшета и широковещательно в локальные сети; планшет отвечает подписанным подтверждением.
    /// </summary>
    static class TabletSwitch
    {
        const int ATTEMPTS = 4;
        const int WAIT_MS = 800;

        /// <summary>Блокирует поток до ~4 секунд. true — планшет подтвердил команду.</summary>
        public static bool Call(TabletPairing p)
        {
            return Exchange(p, "switch", "ack", ATTEMPTS) != null;
        }

        /// <summary>Ищет планшет в локальной сети (запрос «where»); возвращает его адрес или null.</summary>
        public static IPAddress Discover(TabletPairing p)
        {
            IPEndPoint from = Exchange(p, "where", "here", 2);
            return from == null ? null : from.Address;
        }

        /// <summary>
        /// Подписанный запрос [type] на известные адреса планшета и широковещательно; ждёт ответ [replyType],
        /// подписанный тем же ключом (HMAC от «replyType|nonce»). Возвращает адрес ответившего или null.
        /// </summary>
        static IPEndPoint Exchange(TabletPairing p, string type, string replyType, int attempts)
        {
            var json = new JavaScriptSerializer();
            string nonce = Guid.NewGuid().ToString("N");
            long ts = (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
            var msg = new Dictionary<string, object>();
            msg["t"] = type;
            msg["pc"] = p.DeviceId;
            msg["ts"] = ts;
            msg["nonce"] = nonce;
            msg["mac"] = Crypto.Mac(p.Secret, type + "|" + p.DeviceId + "|" + ts + "|" + nonce);
            byte[] payload = Encoding.UTF8.GetBytes(json.Serialize(msg));
            string expected = Crypto.Mac(p.Secret, replyType + "|" + nonce);

            var targets = new List<IPEndPoint>();
            foreach (string a in p.Addresses)
            {
                IPAddress ip;
                if (IPAddress.TryParse(a, out ip)) targets.Add(new IPEndPoint(ip, p.Port));
            }
            foreach (IPAddress b in Broadcasts()) targets.Add(new IPEndPoint(b, p.Port));
            targets.Add(new IPEndPoint(IPAddress.Broadcast, p.Port));

            using (var udp = new UdpClient(0))
            {
                udp.EnableBroadcast = true;
                udp.Client.ReceiveTimeout = WAIT_MS;
                for (int i = 0; i < attempts; i++)
                {
                    foreach (IPEndPoint t in targets)
                    {
                        try { udp.Send(payload, payload.Length, t); }
                        catch (SocketException) { }
                    }
                    DateTime until = DateTime.UtcNow.AddMilliseconds(WAIT_MS);
                    while (DateTime.UtcNow < until)
                    {
                        try
                        {
                            IPEndPoint from = null;
                            byte[] data = udp.Receive(ref from);
                            var reply = json.Deserialize<Dictionary<string, object>>(Encoding.UTF8.GetString(data));
                            object t, n, m;
                            if (reply != null && reply.TryGetValue("t", out t) && replyType.Equals(t) && reply.TryGetValue("nonce", out n) && nonce.Equals(n)
                                && reply.TryGetValue("mac", out m) && expected.Equals(m))
                            {
                                return from;
                            }
                        }
                        catch (SocketException) { break; }
                        catch (Exception) { }
                    }
                }
            }
            return null;
        }

        /// <summary>Адреса широковещания всех активных IPv4-сетей ПК.</summary>
        static IEnumerable<IPAddress> Broadcasts()
        {
            var list = new List<IPAddress>();
            foreach (NetworkInterface ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up || ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (UnicastIPAddressInformation u in ni.GetIPProperties().UnicastAddresses)
                {
                    if (u.Address.AddressFamily != AddressFamily.InterNetwork || u.IPv4Mask == null) continue;
                    byte[] ip = u.Address.GetAddressBytes();
                    byte[] mask = u.IPv4Mask.GetAddressBytes();
                    for (int i = 0; i < 4; i++) ip[i] = (byte)(ip[i] | ~mask[i]);
                    list.Add(new IPAddress(ip));
                }
            }
            return list;
        }
    }

    static class Crypto
    {
        public static byte[] Hmac(byte[] key, string text)
        {
            using (var h = new HMACSHA256(key)) return h.ComputeHash(Encoding.UTF8.GetBytes(text));
        }

        public static string Mac(byte[] key, string text)
        {
            return Convert.ToBase64String(Hmac(key, text));
        }
    }

    static class Display
    {
        [DllImport("user32.dll")]
        static extern bool PostMessage(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam);

        /// <summary>Гасит мониторы ПК (как при простое); любое движение мыши или клавиша включат их снова.</summary>
        public static void TurnOff()
        {
            const int WM_SYSCOMMAND = 0x0112;
            const int SC_MONITORPOWER = 0xF170;
            PostMessage(new IntPtr(0xFFFF), WM_SYSCOMMAND, new IntPtr(SC_MONITORPOWER), new IntPtr(2));
        }
    }
}
