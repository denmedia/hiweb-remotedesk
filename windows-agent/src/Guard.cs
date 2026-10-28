using System;
using System.Collections.Generic;
using System.Diagnostics.Eventing.Reader;
using System.IO;
using System.Net;
using System.Threading;
using System.Web.Script.Serialization;

namespace HiWebAgent
{
    /// <summary>Запись журнала подключений к ПК. Неудачные попытки с одного адреса подряд сливаются в одну запись.</summary>
    sealed class GuardEntry
    {
        public string Id;
        public long Time;      // последнего слитого события
        public string Kind;
        public string Ip;
        public string User;
        public int Count = 1;
        public bool Local;     // адрес из своей сети
        public bool Trusted;   // адрес, с которого планшет уже входил или который отмечен как свой
    }

    /// <summary>Событие журнала Windows вместе с его положением в журнале (для отсева повторов).</summary>
    sealed class RawRdpEvent
    {
        public string Channel;
        public long RecordId;
        public RdpEvent Event;
    }

    /// <summary>
    /// Источник событий о подключениях по RDP из журналов Windows. Оба журнала читаются без прав администратора
    /// (журнал «Безопасность» — нет, поэтому используются эти):
    /// RdpCoreTS/Operational, событие 140 — неудачный вход (неверное имя или пароль) с адресом клиента;
    /// TerminalServices-LocalSessionManager/Operational, события 21 и 25 — вход и возврат в сеанс, 23 и 24 — выход и
    /// отключение, с адресом клиента (для консоли вместо адреса — «LOCAL»).
    /// </summary>
    sealed class RdpEventSource : IDisposable
    {
        const string CoreChannel = "Microsoft-Windows-RemoteDesktopServices-RdpCoreTS/Operational";
        const string SessionChannel = "Microsoft-Windows-TerminalServices-LocalSessionManager/Operational";
        const string CoreIds = "EventID=140";
        const string SessionIds = "EventID=21 or EventID=23 or EventID=24 or EventID=25";

        static readonly DateTime Epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);

        readonly List<EventLogWatcher> watchers = new List<EventLogWatcher>();

        /// <summary>Начинает получать новые события; [onEvent] вызывается из потока пула.</summary>
        public void Watch(Action<RawRdpEvent> onEvent)
        {
            Watch(CoreChannel, CoreIds, onEvent);
            Watch(SessionChannel, SessionIds, onEvent);
        }

        void Watch(string channel, string ids, Action<RawRdpEvent> onEvent)
        {
            try
            {
                var watcher = new EventLogWatcher(new EventLogQuery(channel, PathType.LogName, "*[System[(" + ids + ")]]"));
                watcher.EventRecordWritten += delegate(object s, EventRecordWrittenEventArgs a)
                {
                    EventRecord record = a.EventRecord;
                    if (record == null) return;
                    try
                    {
                        RawRdpEvent raw = Parse(channel, record);
                        if (raw != null) onEvent(raw);
                    }
                    catch (Exception e)
                    {
                        Log.Write("guard event: " + e.Message);
                    }
                    finally
                    {
                        record.Dispose();
                    }
                };
                watcher.Enabled = true;
                watchers.Add(watcher);
            }
            catch (Exception e)
            {
                // Журнал выключен или недоступен: защита работает без него, остальное в агенте — как раньше.
                Log.Write("guard watch " + channel + ": " + e.Message);
            }
        }

        /// <summary>События с момента [sinceMs] (мс Unix) из обоих журналов, по возрастанию времени.</summary>
        public List<RawRdpEvent> ReadSince(long sinceMs)
        {
            var list = new List<RawRdpEvent>();
            Read(CoreChannel, CoreIds, sinceMs, list);
            Read(SessionChannel, SessionIds, sinceMs, list);
            list.Sort(delegate(RawRdpEvent a, RawRdpEvent b) { return a.Event.Time.CompareTo(b.Event.Time); });
            return list;
        }

        static void Read(string channel, string ids, long sinceMs, List<RawRdpEvent> into)
        {
            try
            {
                string from = Epoch.AddMilliseconds(sinceMs).ToString("yyyy-MM-ddTHH:mm:ss.fff") + "Z";
                var query = new EventLogQuery(channel, PathType.LogName, "*[System[(" + ids + ") and TimeCreated[@SystemTime>='" + from + "']]]");
                using (var reader = new EventLogReader(query))
                {
                    EventRecord record;
                    while ((record = reader.ReadEvent()) != null)
                    {
                        using (record)
                        {
                            RawRdpEvent raw = Parse(channel, record);
                            if (raw != null) into.Add(raw);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Log.Write("guard read " + channel + ": " + e.Message);
            }
        }

        static RawRdpEvent Parse(string channel, EventRecord record)
        {
            DateTime? created = record.TimeCreated;
            if (created == null) return null;
            IList<EventProperty> p = record.Properties;
            var e = new RdpEvent();
            e.Time = (long)(created.Value.ToUniversalTime() - Epoch).TotalMilliseconds;
            if (channel == CoreChannel)
            {
                if (record.Id != 140 || p.Count < 1) return null;
                e.Kind = GuardKind.Fail;
                e.Ip = IpTools.Normalize(Str(p[0]));
                e.User = "";
            }
            else
            {
                if (p.Count < 3) return null;
                switch (record.Id)
                {
                    case 21:
                    case 25: e.Kind = GuardKind.Logon; break;
                    case 23: e.Kind = GuardKind.Logoff; break;
                    case 24: e.Kind = GuardKind.Disconnect; break;
                    default: return null;
                }
                e.User = Str(p[0]);
                e.Ip = IpTools.Normalize(Str(p[2]));
            }
            if (e.Ip == null) return null;
            var raw = new RawRdpEvent();
            raw.Channel = channel;
            raw.RecordId = record.RecordId ?? 0;
            raw.Event = e;
            return raw;
        }

        static string Str(EventProperty p)
        {
            return p == null || p.Value == null ? "" : p.Value.ToString();
        }

        public void Dispose()
        {
            foreach (EventLogWatcher w in watchers)
            {
                try
                {
                    w.Enabled = false;
                    w.Dispose();
                }
                catch (Exception)
                {
                }
            }
            watchers.Clear();
        }
    }

    /// <summary>Что изменилось в защите: передаётся подписчикам (планшет, значок в трее, окно агента).</summary>
    sealed class GuardUpdate
    {
        public List<GuardEntry> Entries = new List<GuardEntry>();
        public List<Threat> Threats = new List<Threat>();
        public bool State;                       // изменились заблокированные подсети, свои адреса или брандмауэр
        public List<Threat> Announce = new List<Threat>();   // новые угрозы, о которых стоит сказать сразу
    }

    /// <summary>
    /// Защита RDP: журнал подключений к ПК, обнаружение подбора пароля и входов с неизвестных адресов
    /// (<see cref="ThreatDetector"/>), блокировка подсетей через брандмауэр (<see cref="FirewallRules"/>).
    /// Все данные меняются только в потоке интерфейса; события журнала Windows из потока пула передаются туда через
    /// [ui]. Журнал и угрозы хранятся в %LOCALAPPDATA%\HiWebAgent\guard.json: после перезапуска агента история на месте.
    /// </summary>
    sealed class ConnectionGuard : IDisposable
    {
        const int MAX_ENTRIES = 500;
        const int MAX_THREATS = 100;
        const int MAX_TRUSTED = 200;
        const int BATCH = 40;
        const long MERGE_MS = 10 * 60 * 1000;
        const long CATCH_UP_DEFAULT_MS = 24L * 60 * 60 * 1000;
        const long CATCH_UP_MAX_MS = 48L * 60 * 60 * 1000;
        /// <summary>Об угрозе из истории журнала агент сразу не сообщает, если она старше: планшет узнает о ней при синхронизации.</summary>
        const long ANNOUNCE_MAX_AGE_MS = 60 * 60 * 1000;
        const int FIREWALL_REFRESH_MS = 5 * 60 * 1000;

        readonly SynchronizationContext ui;
        readonly JavaScriptSerializer json = new JavaScriptSerializer();
        readonly string path = Path.Combine(AgentInfo.InstallDir, "guard.json");
        readonly List<GuardEntry> entries = new List<GuardEntry>();
        readonly List<Threat> threats = new List<Threat>();
        readonly List<string> trusted = new List<string>();
        readonly Dictionary<string, long> seen = new Dictionary<string, long>();
        readonly ThreatDetector detector;
        readonly System.Windows.Forms.Timer tick = new System.Windows.Forms.Timer();
        RdpEventSource source;
        List<BlockedRange> blocked = new List<BlockedRange>();
        bool firewallOn = true;
        long since;
        long entrySeq;
        int lastFirewallRefresh;
        bool dirty;
        bool disposed;

        /// <summary>Что-то изменилось; вызывается в потоке интерфейса.</summary>
        public event Action<GuardUpdate> Updated;

        public ConnectionGuard(SynchronizationContext ui)
        {
            this.ui = ui;
            detector = new ThreatDetector(IsTrusted, delegate { return trusted; }, delegate(string ip) { AddTrusted(ip); });
            Load();
            foreach (Threat t in threats) detector.Remember(t);
            tick.Interval = 5000;
            tick.Tick += delegate { OnTick(); };
        }

        public bool Running
        {
            get { return source != null; }
        }

        public bool FirewallOn
        {
            get { return firewallOn; }
        }

        public List<Threat> Threats
        {
            get { return new List<Threat>(threats); }
        }

        public List<GuardEntry> Entries
        {
            get { return new List<GuardEntry>(entries); }
        }

        public List<BlockedRange> Blocked
        {
            get { return new List<BlockedRange>(blocked); }
        }

        /// <summary>Число угроз, по которым пользователь ещё ничего не решил.</summary>
        public int OpenThreats
        {
            get
            {
                int n = 0;
                foreach (Threat t in threats) if (t.Status == Threat.StatusNew) n++;
                return n;
            }
        }

        // ------------------------------------------------------------------ запуск

        /// <summary>Читает историю журналов Windows с последнего запуска и начинает следить за новыми событиями.</summary>
        public void Start()
        {
            if (source != null || disposed) return;
            source = new RdpEventSource();
            // Подписка раньше чтения истории: события, пришедшие за это время, ждут в очереди интерфейса и
            // отсеиваются по номеру записи.
            source.Watch(delegate(RawRdpEvent raw) { ui.Post(delegate { Ingest(raw, true); }, null); });
            long now = PairedDevice.NowMs();
            long from = since > 0 ? Math.Max(since - 1000, now - CATCH_UP_MAX_MS) : now - CATCH_UP_DEFAULT_MS;
            // Когда планшет подключался в те часы, пока защиты не было, знает только журнал самого агента.
            foreach (long opened in TabletOpenTimes(from - 60 * 1000)) detector.TabletOpened(opened);
            var update = new GuardUpdate();
            foreach (RawRdpEvent raw in source.ReadSince(from)) Ingest(raw, false, update);
            AddThreats(detector.Tick(now), update);
            Log.Write("guard: history from " + from + ", " + entries.Count + " entries, " + threats.Count + " threats");
            tick.Start();
            RefreshFirewall();
            // Угрозы из истории показываются, только если ещё свежи; остальное попадёт на планшет при синхронизации.
            update.Entries.Clear();
            update.Threats.Clear();
            Raise(update);
        }

        public void Stop()
        {
            tick.Stop();
            if (source != null) source.Dispose();
            source = null;
        }

        public void Dispose()
        {
            disposed = true;
            Stop();
            tick.Dispose();
            Save();
        }

        /// <summary>Канал HiWeb::Agent с планшетом открылся: недавний вход по RDP — его собственный.</summary>
        public void TabletOpened()
        {
            detector.TabletOpened(PairedDevice.NowMs());
        }

        // ------------------------------------------------------------------ события

        void Ingest(RawRdpEvent raw, bool live)
        {
            var update = new GuardUpdate();
            Ingest(raw, live, update);
            Raise(update);
        }

        /// <summary>
        /// Моменты открытия канала планшета из agent.log: строки «гггг-мм-дд чч:мм:сс channel opened» (местное время).
        /// Файл читается так, чтобы не мешать самому агенту, который дописывает его.
        /// </summary>
        static List<long> TabletOpenTimes(long sinceMs)
        {
            var times = new List<long>();
            try
            {
                string file = Path.Combine(AgentInfo.InstallDir, "agent.log");
                if (!File.Exists(file)) return times;
                using (var stream = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (var reader = new StreamReader(stream))
                {
                    string line;
                    while ((line = reader.ReadLine()) != null)
                    {
                        if (!line.EndsWith(" channel opened") || line.Length < 19) continue;
                        DateTime local;
                        if (!DateTime.TryParseExact(line.Substring(0, 19), "yyyy-MM-dd HH:mm:ss", System.Globalization.CultureInfo.InvariantCulture,
                            System.Globalization.DateTimeStyles.AssumeLocal, out local)) continue;
                        long ms = (long)(local.ToUniversalTime() - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
                        if (ms >= sinceMs) times.Add(ms);
                    }
                }
            }
            catch (Exception e)
            {
                Log.Write("guard agent.log: " + e.Message);
            }
            return times;
        }

        void Ingest(RawRdpEvent raw, bool live, GuardUpdate update)
        {
            if (disposed) return;
            // Повтор: та же запись пришла и из истории, и от подписки (или читается заново после перезапуска агента).
            // Номера записей сбрасываются, если журнал очистили, поэтому новое по времени событие повтором не считается.
            long previous;
            if (raw.RecordId > 0 && seen.TryGetValue(raw.Channel, out previous) && raw.RecordId <= previous && raw.Event.Time <= since) return;
            if (raw.RecordId > 0) seen[raw.Channel] = raw.RecordId;
            RdpEvent e = raw.Event;
            GuardEntry entry = Record(e);
            if (!update.Entries.Contains(entry)) update.Entries.Add(entry);
            AddThreats(detector.Feed(e), update);
            if (e.Time > since) since = e.Time;
            dirty = true;
        }

        /// <summary>Записывает событие в журнал; неудачные попытки с того же адреса подряд сливаются в одну запись.</summary>
        GuardEntry Record(RdpEvent e)
        {
            IPAddress ip;
            IpTools.TryParse(e.Ip, out ip);
            if (e.Kind == GuardKind.Fail)
            {
                for (int i = entries.Count - 1; i >= 0 && i >= entries.Count - 20; i--)
                {
                    GuardEntry last = entries[i];
                    if (last.Kind == GuardKind.Fail && last.Ip == e.Ip && e.Time - last.Time < MERGE_MS && e.Time >= last.Time)
                    {
                        last.Count++;
                        last.Time = e.Time;
                        return last;
                    }
                }
            }
            var entry = new GuardEntry();
            entry.Id = e.Kind + "|" + e.Time + "|" + (entrySeq++) + "|" + e.Ip;
            entry.Time = e.Time;
            entry.Kind = e.Kind;
            entry.Ip = e.Ip;
            entry.User = e.User;
            entry.Local = ip != null && IpTools.IsLocal(ip);
            entry.Trusted = IsTrusted(e.Ip);
            entries.Add(entry);
            if (entries.Count > MAX_ENTRIES) entries.RemoveRange(0, entries.Count - MAX_ENTRIES);
            return entry;
        }

        void AddThreats(List<Threat> found, GuardUpdate update)
        {
            long now = PairedDevice.NowMs();
            foreach (Threat t in found)
            {
                threats.Add(t);
                update.Threats.Add(t);
                if (now - t.Time < ANNOUNCE_MAX_AGE_MS) update.Announce.Add(t);
                Log.Write("guard threat: " + t.Rule + " " + t.Ip + " -> " + t.Cidr + " x" + t.Count);
            }
            if (threats.Count > MAX_THREATS) threats.RemoveRange(0, threats.Count - MAX_THREATS);
        }

        void OnTick()
        {
            var update = new GuardUpdate();
            AddThreats(detector.Tick(PairedDevice.NowMs()), update);
            Raise(update);
            if (unchecked(Environment.TickCount - lastFirewallRefresh) > FIREWALL_REFRESH_MS) RefreshFirewall();
            if (dirty) Save();
        }

        void Raise(GuardUpdate update)
        {
            if (update.Entries.Count == 0 && update.Threats.Count == 0 && update.Announce.Count == 0 && !update.State) return;
            var h = Updated;
            if (h == null) return;
            try { h(update); }
            catch (Exception e) { Log.Write("guard updated: " + e.Message); }
        }

        // ------------------------------------------------------------------ свои адреса

        bool IsTrusted(string ip)
        {
            return ip != null && trusted.Contains(ip);
        }

        void AddTrusted(string ip)
        {
            if (ip == null || trusted.Contains(ip)) return;
            trusted.Add(ip);
            if (trusted.Count > MAX_TRUSTED) trusted.RemoveAt(0);
            foreach (GuardEntry e in entries) if (e.Ip == ip) e.Trusted = true;
            dirty = true;
            Log.Write("guard: trusted " + ip);
        }

        /// <summary>«Это я»: адрес считается своим, угрозы с него закрываются.</summary>
        public void Trust(string ip)
        {
            string canonical = IpTools.Normalize(ip);
            if (canonical == null) return;
            var update = new GuardUpdate();
            AddTrusted(canonical);
            foreach (GuardEntry e in entries) if (e.Ip == canonical) update.Entries.Add(e);
            foreach (Threat t in threats)
            {
                if (t.Status != Threat.StatusNew || t.Ip != canonical) continue;
                t.Status = Threat.StatusTrusted;
                update.Threats.Add(t);
            }
            update.State = true;
            dirty = true;
            Raise(update);
        }

        /// <summary>Угроза оставлена без действий: больше не беспокоит.</summary>
        public void Dismiss(string id)
        {
            foreach (Threat t in threats)
            {
                if (t.Id != id || t.Status != Threat.StatusNew) continue;
                t.Status = Threat.StatusDismissed;
                var update = new GuardUpdate();
                update.Threats.Add(t);
                dirty = true;
                Raise(update);
                return;
            }
        }

        // ------------------------------------------------------------------ блокировка

        /// <summary>
        /// Блокирует подсеть: Windows спросит права администратора (UAC). [done] получает null при успехе или текст
        /// ошибки, вызывается в потоке интерфейса. [enableFirewall] — заодно включить брандмауэр, если он выключен.
        /// </summary>
        public void Block(string cidr, bool enableFirewall, Action<string> done)
        {
            string canonical = IpTools.NormalizeBlock(cidr);
            if (canonical == null)
            {
                done("Эту подсеть блокировать нельзя");
                return;
            }
            ThreadPool.QueueUserWorkItem(delegate
            {
                bool applied;
                string error = FirewallRules.RunWithUac("block", new[] { canonical }, enableFirewall, out applied);
                List<BlockedRange> now = FirewallRules.Blocked();
                bool on = FirewallRules.Enabled();
                ui.Post(delegate
                {
                    var update = new GuardUpdate();
                    if (applied)
                    {
                        IPAddress net;
                        int prefix;
                        IpTools.TryParseCidr(canonical, out net, out prefix);
                        foreach (Threat t in threats)
                        {
                            if (t.Status != Threat.StatusNew) continue;
                            IPAddress ip;
                            if (t.Cidr != canonical && !(IpTools.TryParse(t.Ip, out ip) && IpTools.InCidr(ip, canonical))) continue;
                            t.Status = Threat.StatusBlocked;
                            update.Threats.Add(t);
                        }
                        dirty = true;
                    }
                    SetFirewall(now, on, update);
                    Raise(update);
                    done(error);
                }, null);
            });
        }

        public void Unblock(string cidr, Action<string> done)
        {
            string canonical = IpTools.NormalizeBlock(cidr);
            if (canonical == null)
            {
                done("Недопустимая подсеть");
                return;
            }
            ThreadPool.QueueUserWorkItem(delegate
            {
                bool applied;
                string error = FirewallRules.RunWithUac("unblock", new[] { canonical }, false, out applied);
                List<BlockedRange> now = FirewallRules.Blocked();
                bool on = FirewallRules.Enabled();
                ui.Post(delegate
                {
                    var update = new GuardUpdate();
                    if (applied)
                    {
                        foreach (Threat t in threats)
                        {
                            if (t.Status != Threat.StatusBlocked || t.Cidr != canonical) continue;
                            t.Status = Threat.StatusDismissed;
                            update.Threats.Add(t);
                        }
                        dirty = true;
                    }
                    SetFirewall(now, on, update);
                    Raise(update);
                    done(error);
                }, null);
            });
        }

        /// <summary>Перечитывает правила и состояние брандмауэра (долго: сотни правил через COM — в фоне).</summary>
        public void RefreshFirewall()
        {
            lastFirewallRefresh = Environment.TickCount;
            ThreadPool.QueueUserWorkItem(delegate
            {
                List<BlockedRange> now = FirewallRules.Blocked();
                bool on = FirewallRules.Enabled();
                ui.Post(delegate
                {
                    var update = new GuardUpdate();
                    SetFirewall(now, on, update);
                    Raise(update);
                }, null);
            });
        }

        void SetFirewall(List<BlockedRange> now, bool on, GuardUpdate update)
        {
            bool same = on == firewallOn && now.Count == blocked.Count;
            for (int i = 0; same && i < now.Count; i++) same = now[i].Cidr == blocked[i].Cidr && now[i].Enabled == blocked[i].Enabled;
            blocked = now;
            firewallOn = on;
            if (!same) update.State = true;
        }

        // ------------------------------------------------------------------ сообщения планшету

        public Dictionary<string, object> StateMessage()
        {
            var msg = new Dictionary<string, object>();
            msg["t"] = "guard_state";
            msg["fw"] = firewallOn;
            msg["port"] = FirewallRules.RdpPort();
            var list = new List<object>();
            foreach (BlockedRange b in blocked)
            {
                var o = new Dictionary<string, object>();
                o["cidr"] = b.Cidr;
                o["on"] = b.Enabled;
                list.Add(o);
            }
            msg["blocked"] = list;
            msg["trusted"] = new List<object>(trusted.ToArray());
            return msg;
        }

        public Dictionary<string, object> ThreatsMessage(IEnumerable<Threat> list)
        {
            var msg = new Dictionary<string, object>();
            msg["t"] = "threat";
            var arr = new List<object>();
            foreach (Threat t in list)
            {
                var o = new Dictionary<string, object>();
                o["id"] = t.Id;
                o["ts"] = t.Time;
                o["rule"] = t.Rule;
                o["ip"] = t.Ip;
                o["cidr"] = t.Cidr;
                o["count"] = t.Count;
                o["user"] = t.User ?? "";
                o["afterFail"] = t.AfterFail;
                o["status"] = t.Status;
                arr.Add(o);
            }
            msg["threats"] = arr;
            return msg;
        }

        public Dictionary<string, object> EntriesMessage(IEnumerable<GuardEntry> list)
        {
            var msg = new Dictionary<string, object>();
            msg["t"] = "conn";
            var arr = new List<object>();
            foreach (GuardEntry e in list)
            {
                var o = new Dictionary<string, object>();
                o["id"] = e.Id;
                o["ts"] = e.Time;
                o["kind"] = e.Kind;
                o["ip"] = e.Ip;
                o["user"] = e.User ?? "";
                o["count"] = e.Count;
                o["local"] = e.Local;
                o["trusted"] = e.Trusted;
                arr.Add(o);
            }
            msg["events"] = arr;
            return msg;
        }

        /// <summary>
        /// Всё, что нужно устройству после подключения: состояние, угрозы и записи журнала новее [since] (мс Unix;
        /// устройство присылает время последней известной ему записи). Записи — пачками, чтобы сообщения были небольшими.
        /// </summary>
        public List<Dictionary<string, object>> SyncMessages(long sinceTime)
        {
            var list = new List<Dictionary<string, object>>();
            list.Add(StateMessage());
            if (threats.Count > 0) list.Add(ThreatsMessage(threats));
            var batch = new List<GuardEntry>();
            foreach (GuardEntry e in entries)
            {
                if (e.Time <= sinceTime) continue;
                batch.Add(e);
                if (batch.Count < BATCH) continue;
                list.Add(EntriesMessage(batch));
                batch = new List<GuardEntry>();
            }
            if (batch.Count > 0) list.Add(EntriesMessage(batch));
            return list;
        }

        // ------------------------------------------------------------------ хранение

        void Save()
        {
            dirty = false;
            try
            {
                var root = new Dictionary<string, object>();
                root["since"] = since;
                root["seq"] = entrySeq;
                var seenOut = new Dictionary<string, object>();
                foreach (KeyValuePair<string, long> pair in seen) seenOut[pair.Key] = pair.Value;
                root["seen"] = seenOut;
                root["trusted"] = new List<object>(trusted.ToArray());
                var es = new List<object>();
                foreach (GuardEntry e in entries)
                {
                    var o = new Dictionary<string, object>();
                    o["id"] = e.Id;
                    o["ts"] = e.Time;
                    o["kind"] = e.Kind;
                    o["ip"] = e.Ip;
                    o["user"] = e.User ?? "";
                    o["count"] = e.Count;
                    o["local"] = e.Local;
                    o["trusted"] = e.Trusted;
                    es.Add(o);
                }
                root["entries"] = es;
                var ts = new List<object>();
                foreach (Threat t in threats)
                {
                    var o = new Dictionary<string, object>();
                    o["id"] = t.Id;
                    o["ts"] = t.Time;
                    o["rule"] = t.Rule;
                    o["ip"] = t.Ip;
                    o["cidr"] = t.Cidr;
                    o["count"] = t.Count;
                    o["user"] = t.User ?? "";
                    o["afterFail"] = t.AfterFail;
                    o["status"] = t.Status;
                    ts.Add(o);
                }
                root["threats"] = ts;
                Directory.CreateDirectory(AgentInfo.InstallDir);
                string temp = path + ".tmp";
                File.WriteAllText(temp, json.Serialize(root));
                if (File.Exists(path)) File.Delete(path);
                File.Move(temp, path);
            }
            catch (Exception e)
            {
                Log.Write("guard save: " + e.Message);
            }
        }

        void Load()
        {
            try
            {
                if (!File.Exists(path)) return;
                json.MaxJsonLength = int.MaxValue;
                var root = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(path));
                since = Num(root, "since");
                entrySeq = Num(root, "seq");
                object seenRaw;
                var seenIn = root.TryGetValue("seen", out seenRaw) ? seenRaw as Dictionary<string, object> : null;
                if (seenIn != null) foreach (KeyValuePair<string, object> pair in seenIn) seen[pair.Key] = Convert.ToInt64(pair.Value);
                foreach (object o in List(root, "trusted")) if (o != null) trusted.Add(o.ToString());
                foreach (object raw in List(root, "entries"))
                {
                    var o = raw as Dictionary<string, object>;
                    if (o == null) continue;
                    var e = new GuardEntry();
                    e.Id = Text(o, "id");
                    e.Time = Num(o, "ts");
                    e.Kind = Text(o, "kind");
                    e.Ip = Text(o, "ip");
                    e.User = Text(o, "user");
                    e.Count = (int)Math.Max(1, Num(o, "count"));
                    e.Local = Flag(o, "local");
                    e.Trusted = Flag(o, "trusted");
                    if (e.Id != null && e.Ip != null) entries.Add(e);
                }
                foreach (object raw in List(root, "threats"))
                {
                    var o = raw as Dictionary<string, object>;
                    if (o == null) continue;
                    var t = new Threat();
                    t.Id = Text(o, "id");
                    t.Time = Num(o, "ts");
                    t.Rule = Text(o, "rule");
                    t.Ip = Text(o, "ip");
                    t.Cidr = Text(o, "cidr");
                    t.Count = (int)Num(o, "count");
                    t.User = Text(o, "user");
                    t.AfterFail = Flag(o, "afterFail");
                    t.Status = Text(o, "status") ?? Threat.StatusNew;
                    if (t.Id != null && t.Cidr != null) threats.Add(t);
                }
            }
            catch (Exception e)
            {
                Log.Write("guard load: " + e.Message);
            }
        }

        static System.Collections.IEnumerable List(Dictionary<string, object> o, string key)
        {
            object v;
            var list = o.TryGetValue(key, out v) ? v as System.Collections.IEnumerable : null;
            return list ?? new object[0];
        }

        static long Num(Dictionary<string, object> o, string key)
        {
            object v;
            return o.TryGetValue(key, out v) && v != null ? Convert.ToInt64(v) : 0;
        }

        static string Text(Dictionary<string, object> o, string key)
        {
            object v;
            return o.TryGetValue(key, out v) && v != null ? v.ToString() : null;
        }

        static bool Flag(Dictionary<string, object> o, string key)
        {
            object v;
            return o.TryGetValue(key, out v) && v is bool && (bool)v;
        }
    }
}
