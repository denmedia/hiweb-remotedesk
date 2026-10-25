using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace HiWebAgent
{
    /// <summary>
    /// Связь с планшетом по локальной сети без сеанса RDP: агент сам подключается к планшету по TCP (порт из
    /// pair). Исходящее соединение не требует правил брандмауэра и прав администратора. Через эту связь
    /// планшет видит, что ПК с агентом доступен, и какие действия питания возможны, и отдаёт команды питания.
    ///
    /// Рукопожатие: планшет → challenge(n1); агент → auth(pc, n2, HMAC(secret, auth|pc|n1|n2));
    /// планшет → ok(HMAC(secret, ok|n1|n2)). Далее каждая строка «mac json», где mac = HMAC(K, dir|seq|json),
    /// K = HMAC(secret, session|n1|n2), dir — «a» от агента и «t» от планшета, seq — номер строки в направлении.
    /// </summary>
    sealed class LanLink
    {
        const int RETRY_MS = 10000;
        const int CONNECT_MS = 1500;
        const int PING_MS = 15000;
        const int READ_TIMEOUT_MS = 45000;
        const int POWER_DELAY_MS = 1500;

        readonly Func<TabletPairing> pairing;
        readonly Action<TabletPairing> savePairing;
        readonly Func<Dictionary<string, object>> status;
        readonly JavaScriptSerializer json = new JavaScriptSerializer();
        volatile bool stopped;
        volatile TcpClient current;
        volatile SecureLines live;

        /// <summary>Связь установлена (true) или потеряна (false). Вызывается из фонового потока.</summary>
        public event Action<bool> ConnectedChanged;

        public LanLink(Func<TabletPairing> pairing, Action<TabletPairing> savePairing, Func<Dictionary<string, object>> status)
        {
            this.pairing = pairing;
            this.savePairing = savePairing;
            this.status = status;
        }

        /// <summary>
        /// Сообщает планшету состояние ПК сразу, в вызывающем потоке: перед сном у Windows есть секунда-две, пока
        /// сеть ещё работает. Без связи ничего не делает.
        /// </summary>
        public void SendStatus(Dictionary<string, object> snapshot)
        {
            SecureLines c = live;
            if (c == null) return;
            try { c.Send(json.Serialize(snapshot)); }
            catch (Exception e) { Log.Write("lan status: " + e.Message); }
        }

        /// <summary>
        /// Шлёт снимок экрана (JPEG) для превью ПК на планшете, зашифрованным. Блокирует до отправки, поэтому
        /// вызывать из фонового потока. Без связи ничего не делает; возвращает, ушёл ли снимок.
        /// </summary>
        public bool SendScreen(byte[] jpeg, int width, int height)
        {
            SecureLines c = live;
            if (c == null) return false;
            try
            {
                byte[][] sealedData = c.Encrypt(jpeg);
                var msg = new Dictionary<string, object>();
                msg["t"] = "screen";
                msg["w"] = width;
                msg["h"] = height;
                msg["iv"] = Convert.ToBase64String(sealedData[0]);
                msg["data"] = Convert.ToBase64String(sealedData[1]);
                c.Send(json.Serialize(msg));
                return true;
            }
            catch (Exception e)
            {
                Log.Write("lan screen: " + e.Message);
                return false;
            }
        }

        public void Start()
        {
            var t = new Thread(Loop);
            t.IsBackground = true;
            t.Name = "lan-link";
            t.Start();
        }

        public void Stop()
        {
            stopped = true;
            TcpClient c = current;
            if (c != null) c.Close();
        }

        void Loop()
        {
            while (!stopped)
            {
                TabletPairing p = pairing();
                if (p != null)
                {
                    try { Session(p); }
                    catch (Exception e) { if (!stopped) Log.Write("lan: " + e.GetBaseException().Message); }
                }
                for (int waited = 0; waited < RETRY_MS && !stopped; waited += 500) Thread.Sleep(500);
            }
        }

        void Session(TabletPairing p)
        {
            TcpClient client = ConnectAny(p);
            if (client == null)
            {
                // Адрес планшета мог смениться: спрашиваем сеть, где он.
                IPAddress found = TabletSwitch.Discover(p);
                if (found == null) return;
                if (Array.IndexOf(p.Addresses, found.ToString()) < 0)
                {
                    var list = new List<string> { found.ToString() };
                    list.AddRange(p.Addresses);
                    p.Addresses = list.ToArray();
                    savePairing(p);
                }
                client = Connect(found, p.Port);
                if (client == null) return;
            }
            current = client;
            bool announced = false;
            try
            {
                client.ReceiveTimeout = READ_TIMEOUT_MS;
                NetworkStream stream = client.GetStream();
                var reader = new StreamReader(stream, new UTF8Encoding(false));
                var writer = new StreamWriter(stream, new UTF8Encoding(false));
                writer.NewLine = "\n";
                writer.AutoFlush = true;

                Dictionary<string, object> challenge = Parse(reader.ReadLine());
                string n1 = Str(challenge, "nonce");
                if (Str(challenge, "t") != "challenge" || string.IsNullOrEmpty(n1)) return;
                string n2 = Guid.NewGuid().ToString("N");
                var auth = new Dictionary<string, object>();
                auth["t"] = "auth";
                auth["pc"] = p.DeviceId;
                auth["nonce"] = n2;
                auth["mac"] = Crypto.Mac(p.Secret, "auth|" + p.DeviceId + "|" + n1 + "|" + n2);
                writer.WriteLine(json.Serialize(auth));
                Dictionary<string, object> ok = Parse(reader.ReadLine());
                if (Str(ok, "t") != "ok" || Str(ok, "mac") != Crypto.Mac(p.Secret, "ok|" + n1 + "|" + n2))
                {
                    Log.Write("lan: tablet failed authentication");
                    return;
                }
                var channel = new SecureLines(Crypto.Hmac(p.Secret, "session|" + n1 + "|" + n2), writer);
                Log.Write("lan: connected to tablet " + client.Client.RemoteEndPoint);
                announced = true;
                Raise(true);
                channel.Send(json.Serialize(Caps()));
                channel.Send(json.Serialize(status()));
                live = channel;

                var pinger = new Thread(() => PingLoop(client, channel));
                pinger.IsBackground = true;
                pinger.Start();

                while (!stopped)
                {
                    string line = reader.ReadLine();
                    if (line == null) break;
                    string body = channel.Verify(line);
                    if (body == null)
                    {
                        Log.Write("lan: bad message signature");
                        break;
                    }
                    Handle(Parse(body), channel);
                }
            }
            finally
            {
                live = null;
                current = null;
                client.Close();
                if (announced)
                {
                    Log.Write("lan: disconnected");
                    Raise(false);
                }
            }
        }

        void PingLoop(TcpClient client, SecureLines channel)
        {
            int n = 0;
            try
            {
                while (client.Connected && current == client)
                {
                    Thread.Sleep(PING_MS);
                    // Раз в минуту заново сообщаем возможности: гибернацию могли включить или выключить.
                    if (++n % 4 == 0) channel.Send(json.Serialize(Caps()));
                    else channel.Send("{\"t\":\"ping\"}");
                }
            }
            catch (Exception)
            {
                client.Close();
            }
        }

        void Handle(Dictionary<string, object> msg, SecureLines channel)
        {
            if (msg == null || Str(msg, "t") != "power") return;
            string action = Str(msg, "action");
            var result = new Dictionary<string, object>();
            result["t"] = "power_result";
            result["action"] = action;
            if (action == null || !Power.Available().Contains(action))
            {
                result["ok"] = false;
                result["error"] = "Действие недоступно на этом ПК";
                channel.Send(json.Serialize(result));
                return;
            }
            result["ok"] = true;
            channel.Send(json.Serialize(result));
            Log.Write("lan: power " + action + " requested by tablet");
            // Пауза: ответ успевает уйти до того, как Windows начнёт засыпать или выключаться.
            var t = new Thread(() =>
            {
                Thread.Sleep(POWER_DELAY_MS);
                try { Power.Run(action); }
                catch (Exception e) { Log.Write("power " + action + ": " + e.Message); }
            });
            t.IsBackground = true;
            t.Start();
        }

        static Dictionary<string, object> Caps()
        {
            var caps = new Dictionary<string, object>();
            caps["t"] = "caps";
            caps["ver"] = AgentInfo.Version;
            caps["host"] = Environment.MachineName;
            caps["power"] = Power.Available();
            return caps;
        }

        void Raise(bool connected)
        {
            var h = ConnectedChanged;
            if (h != null) h(connected);
        }

        static TcpClient ConnectAny(TabletPairing p)
        {
            foreach (string a in p.Addresses)
            {
                IPAddress ip;
                if (!IPAddress.TryParse(a, out ip)) continue;
                TcpClient c = Connect(ip, p.Port);
                if (c != null) return c;
            }
            return null;
        }

        static TcpClient Connect(IPAddress ip, int port)
        {
            var c = new TcpClient(AddressFamily.InterNetwork);
            try
            {
                IAsyncResult r = c.BeginConnect(ip, port, null, null);
                if (r.AsyncWaitHandle.WaitOne(CONNECT_MS) && c.Connected)
                {
                    c.EndConnect(r);
                    c.NoDelay = true;
                    return c;
                }
            }
            catch (Exception)
            {
            }
            c.Close();
            return null;
        }

        Dictionary<string, object> Parse(string line)
        {
            if (line == null) return null;
            try { return json.Deserialize<Dictionary<string, object>>(line); }
            catch (Exception) { return null; }
        }

        static string Str(Dictionary<string, object> msg, string key)
        {
            object v;
            return msg != null && msg.TryGetValue(key, out v) && v != null ? v.ToString() : null;
        }
    }

    /// <summary>
    /// Подписанные строки после рукопожатия; запись потокобезопасна. Строки не шифруются, кроме полей с
    /// содержимым экрана: их шифрует <see cref="Encrypt(byte[])"/> (AES-256-CBC ключом HMAC(K, enc)),
    /// а целостность по-прежнему даёт подпись строки.
    /// </summary>
    sealed class SecureLines
    {
        readonly byte[] key;
        readonly byte[] encKey;
        readonly StreamWriter writer;
        readonly object sync = new object();
        long sent;
        long received;

        public SecureLines(byte[] key, StreamWriter writer)
        {
            this.key = key;
            this.writer = writer;
            encKey = Crypto.Hmac(key, "enc");
        }

        /// <summary>Шифрует данные со случайным IV: возвращает {iv, шифротекст}.</summary>
        public byte[][] Encrypt(byte[] data)
        {
            var iv = new byte[16];
            using (var rng = new System.Security.Cryptography.RNGCryptoServiceProvider()) rng.GetBytes(iv);
            return new[] { iv, Encrypt(data, iv) };
        }

        internal byte[] Encrypt(byte[] data, byte[] iv)
        {
            using (var aes = new System.Security.Cryptography.AesCryptoServiceProvider())
            {
                aes.Mode = System.Security.Cryptography.CipherMode.CBC;
                aes.Padding = System.Security.Cryptography.PaddingMode.PKCS7;
                aes.Key = encKey;
                aes.IV = iv;
                using (var enc = aes.CreateEncryptor()) return enc.TransformFinalBlock(data, 0, data.Length);
            }
        }

        public void Send(string body)
        {
            lock (sync)
            {
                string mac = Crypto.Mac(key, "a|" + sent + "|" + body);
                sent++;
                writer.WriteLine(mac + " " + body);
            }
        }

        /// <summary>Тело строки от планшета или null, если подпись не сходится.</summary>
        public string Verify(string line)
        {
            int space = line.IndexOf(' ');
            if (space <= 0) return null;
            string body = line.Substring(space + 1);
            if (line.Substring(0, space) != Crypto.Mac(key, "t|" + received + "|" + body)) return null;
            received++;
            return body;
        }
    }
}
