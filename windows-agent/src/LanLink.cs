using System;
using System.Collections.Concurrent;
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
    /// Связь с устройствами (планшет, смартфон) по локальной сети без сеанса RDP: агент сам подключается к
    /// каждому известному устройству по TCP (порт из pair) и держит соединение, пока устройство в сети.
    /// Исходящее соединение не требует правил брандмауэра и прав администратора. Через эту связь устройство
    /// видит, что ПК с агентом доступен, какие действия питания возможны, и отдаёт команды питания.
    ///
    /// Рукопожатие: устройство → challenge(n1); агент → auth(pc, n2, HMAC(secret, auth|pc|n1|n2));
    /// устройство → ok(HMAC(secret, ok|n1|n2)). Далее каждая строка «mac json», где mac = HMAC(K, dir|seq|json),
    /// K = HMAC(secret, session|n1|n2), dir — «a» от агента и «t» от устройства, seq — номер строки в направлении.
    /// </summary>
    sealed class LanLink
    {
        const int RETRY_MS = 10000;
        const int CONNECT_MS = 1500;
        const int PING_MS = 15000;
        const int READ_TIMEOUT_MS = 45000;
        const int POWER_DELAY_MS = 1500;

        readonly Func<PairedDevice[]> all;
        readonly Func<string, PairedDevice> get;
        readonly Action<PairedDevice> save;
        readonly Func<Dictionary<string, object>> status;
        readonly JavaScriptSerializer json = new JavaScriptSerializer();
        readonly ConcurrentDictionary<string, Session> sessions = new ConcurrentDictionary<string, Session>();
        volatile bool stopped;

        /// <summary>Сеанс связи с одним устройством; ключ — идентификатор устройства.</summary>
        sealed class Session
        {
            public readonly PairedDevice Device;
            public TcpClient Client;
            public SecureLines Lines;

            public Session(PairedDevice device)
            {
                Device = device;
            }
        }

        /// <summary>Набор устройств на связи изменился. Вызывается из фонового потока.</summary>
        public event Action Changed;

        public LanLink(Func<PairedDevice[]> all, Func<string, PairedDevice> get, Action<PairedDevice> save, Func<Dictionary<string, object>> status)
        {
            this.all = all;
            this.get = get;
            this.save = save;
            this.status = status;
        }

        /// <summary>Устройства, с которыми связь сейчас есть.</summary>
        public PairedDevice[] Connected()
        {
            var list = new List<PairedDevice>();
            foreach (KeyValuePair<string, Session> pair in sessions) list.Add(pair.Value.Device);
            return list.ToArray();
        }

        /// <summary>Есть ли связь с устройством прямо сейчас.</summary>
        public bool IsConnected(string key)
        {
            return key != null && sessions.ContainsKey(key);
        }

        /// <summary>
        /// Сообщает состояние ПК всем устройствам сразу, в вызывающем потоке: перед сном у Windows есть
        /// секунда-две, пока сеть ещё работает. Без связи ничего не делает.
        /// </summary>
        public void SendStatus(Dictionary<string, object> snapshot)
        {
            Send(snapshot);
        }

        /// <summary>Сообщение всем устройствам на связи, в вызывающем потоке.</summary>
        public void Send(Dictionary<string, object> msg)
        {
            string text = json.Serialize(msg);
            foreach (KeyValuePair<string, Session> pair in sessions)
            {
                SecureLines c = pair.Value.Lines;
                if (c == null) continue;
                try { c.Send(text); }
                catch (Exception e) { Log.Write("lan send: " + e.Message); }
            }
        }

        /// <summary>Что слать устройству сразу после подключения (кроме возможностей и состояния); вызывается из потока связи.</summary>
        public Func<List<Dictionary<string, object>>> Greeting;

        /// <summary>
        /// Команда устройства защите RDP (сообщение guard): тело и способ ответить. Вызывается из потока связи —
        /// обработчик сам переносит работу в нужный поток.
        /// </summary>
        public Action<Dictionary<string, object>, Action<Dictionary<string, object>>> GuardCommand;

        /// <summary>
        /// Шлёт снимок экрана (JPEG) для превью ПК на устройства, зашифрованным. Блокирует до отправки, поэтому
        /// вызывать из фонового потока. Возвращает, ушёл ли снимок хоть кому-то.
        /// </summary>
        public bool SendScreen(byte[] jpeg, int width, int height)
        {
            bool sent = false;
            foreach (KeyValuePair<string, Session> pair in sessions)
            {
                SecureLines c = pair.Value.Lines;
                if (c == null) continue;
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
                    sent = true;
                }
                catch (Exception e)
                {
                    Log.Write("lan screen: " + e.Message);
                }
            }
            return sent;
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
            foreach (KeyValuePair<string, Session> pair in sessions)
            {
                TcpClient c = pair.Value.Client;
                if (c != null) c.Close();
            }
        }

        /// <summary>
        /// Следит за списком устройств: на каждое заводит поток связи, удалённым закрывает соединение. Устройства
        /// появляются в списке только когда к ПК подключаются (см. <see cref="PairedDevices.Upsert"/>).
        /// </summary>
        void Loop()
        {
            while (!stopped)
            {
                try
                {
                    var known = new List<string>();
                    foreach (PairedDevice d in all())
                    {
                        known.Add(d.Key);
                        if (sessions.ContainsKey(d.Key)) continue;
                        var session = new Session(d);
                        if (!sessions.TryAdd(d.Key, session)) continue;
                        var thread = new Thread(delegate() { RunLoop(session); });
                        thread.IsBackground = true;
                        thread.Name = "lan-" + d.Key;
                        thread.Start();
                    }
                    // Устройство удалили из списка агента: связь с ним больше не нужна.
                    foreach (KeyValuePair<string, Session> pair in sessions)
                    {
                        if (known.Contains(pair.Key)) continue;
                        TcpClient c = pair.Value.Client;
                        if (c != null) c.Close();
                    }
                }
                catch (Exception e)
                {
                    Log.Write("lan watch: " + e.Message);
                }
                for (int waited = 0; waited < RETRY_MS && !stopped; waited += 500) Thread.Sleep(500);
            }
        }

        /// <summary>Держит связь с одним устройством, пока его не удалят или не остановят агента.</summary>
        void RunLoop(Session session)
        {
            try
            {
                while (!stopped && sessions.ContainsKey(session.Device.Key))
                {
                    try { SessionRun(session); }
                    catch (Exception e) { if (!stopped) Log.Write("lan: " + e.GetBaseException().Message); }
                    for (int waited = 0; waited < RETRY_MS && !stopped && sessions.ContainsKey(session.Device.Key); waited += 500) Thread.Sleep(500);
                }
            }
            finally
            {
                Session own;
                sessions.TryRemove(session.Device.Key, out own);
                Raise();
            }
        }

        void SessionRun(Session session)
        {
            PairedDevice device = get(session.Device.Key);
            if (device == null) return;
            TcpClient client = ConnectAny(device);
            if (client == null)
            {
                // Адрес устройства мог смениться: спрашиваем сеть, где оно.
                IPAddress found = TabletSwitch.Discover(device);
                if (found == null) return;
                if (Array.IndexOf(device.Addresses, found.ToString()) < 0)
                {
                    var list = new List<string> { found.ToString() };
                    list.AddRange(device.Addresses);
                    device.Addresses = list.ToArray();
                    save(device);
                }
                client = Connect(found, device.Port);
                if (client == null) return;
            }
            bool announced = false;
            session.Client = client;
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
                auth["pc"] = device.PcId;
                auth["nonce"] = n2;
                auth["mac"] = Crypto.Mac(device.Secret, "auth|" + device.PcId + "|" + n1 + "|" + n2);
                writer.WriteLine(json.Serialize(auth));
                Dictionary<string, object> ok = Parse(reader.ReadLine());
                if (Str(ok, "t") != "ok" || Str(ok, "mac") != Crypto.Mac(device.Secret, "ok|" + n1 + "|" + n2))
                {
                    Log.Write("lan: device " + device.Title + " failed authentication");
                    return;
                }
                var channel = new SecureLines(Crypto.Hmac(device.Secret, "session|" + n1 + "|" + n2), writer);
                session.Lines = channel;
                Log.Write("lan: connected to " + device.Title + " at " + client.Client.RemoteEndPoint);
                announced = true;
                Raise();
                channel.Send(json.Serialize(Caps()));
                channel.Send(json.Serialize(status()));
                Func<List<Dictionary<string, object>>> greeting = Greeting;
                if (greeting != null)
                {
                    foreach (Dictionary<string, object> m in greeting()) channel.Send(json.Serialize(m));
                }

                var pinger = new Thread(delegate() { PingLoop(client, channel); });
                pinger.IsBackground = true;
                pinger.Start();

                while (!stopped && sessions.ContainsKey(device.Key))
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
                session.Lines = null;
                session.Client = null;
                client.Close();
                if (announced)
                {
                    Log.Write("lan: disconnected from " + device.Title);
                    Raise();
                }
            }
        }

        void PingLoop(TcpClient client, SecureLines channel)
        {
            int n = 0;
            try
            {
                while (client.Connected)
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
            if (msg == null) return;
            if (Str(msg, "t") == "guard")
            {
                var command = GuardCommand;
                if (command != null) command(msg, delegate(Dictionary<string, object> reply) { channel.Send(json.Serialize(reply)); });
                return;
            }
            if (Str(msg, "t") != "power") return;
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
            Log.Write("lan: power " + action + " requested by device");
            // Пауза: ответ успевает уйти до того, как Windows начнёт засыпать или выключаться.
            var t = new Thread(delegate()
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

        void Raise()
        {
            var h = Changed;
            if (h != null)
            {
                try { h(); }
                catch (Exception e) { Log.Write("lan changed: " + e.Message); }
            }
        }

        static TcpClient ConnectAny(PairedDevice p)
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

        /// <summary>Тело строки от устройства или null, если подпись не сходится.</summary>
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
