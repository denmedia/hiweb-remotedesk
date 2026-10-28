using System;
using System.Collections.Generic;
using System.Security.Cryptography;
using Microsoft.Win32;

namespace HiWebAgent
{
    /// <summary>
    /// Устройство (планшет или смартфон), которое подключалось к этому ПК. Агент заводит его только по
    /// сообщению pair — то есть когда устройство само подключилось к ПК; имя можно переименовать в списке
    /// агента. Данные лежат в HKCU\Software\HiWebAgent\Tablets\&lt;id устройства&gt;, ключ подписи — под DPAPI.
    /// </summary>
    sealed class PairedDevice
    {
        /// <summary>Приставка ключа для устройств, заведённых приложением без идентификатора (старые версии).</summary>
        public const string SyntheticPrefix = "model:";

        /// <summary>Наследство агента 1.6: единственное устройство в HKCU\Software\HiWebAgent\Tablet.</summary>
        public const string LegacyKey = "legacy";

        public string Key;        // стабильный идентификатор устройства в приложении
        public string PcId;       // идентификатор этого ПК в приложении
        public string Name;       // как устройство зовут в меню агента
        public string Model;      // модель, присланная устройством
        public string Kind;       // tablet — планшет, phone — смартфон
        public int Port;
        public string[] Addresses = new string[0];
        public byte[] Secret;
        public long LastSeen;     // мс Unix последнего подключения

        public string Title
        {
            get { return string.IsNullOrEmpty(Name) ? DefaultName(Kind, Model) : Name; }
        }

        /// <summary>Имя по умолчанию: «Планшет SM-X910» или «Смартфон Pixel 8».</summary>
        public static string DefaultName(string kind, string model)
        {
            string prefix = kind == "phone" ? "Смартфон" : kind == "tablet" ? "Планшет" : "Устройство";
            string m = (model ?? "").Trim();
            return m.Length == 0 ? prefix : prefix + " " + m;
        }

        public static long NowMs()
        {
            return (long)(DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
        }

        /// <summary>Время последнего подключения для списка устройств.</summary>
        public static string TimeText(long ms)
        {
            return ms <= 0 ? "—" : new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddMilliseconds(ms).ToLocalTime().ToString("dd.MM.yyyy HH:mm");
        }

        /// <summary>Имя подраздела реестра: ключ приходит от приложения, но обратная косая черта там недопустима.</summary>
        internal static string SafeName(string key)
        {
            return key.Replace('\\', '_').Replace('/', '_');
        }
    }

    /// <summary>
    /// Список устройств в памяти с записью в реестр. Порядок — по имени: меню не должно переставляться от
    /// того, кто подключился последним.
    /// </summary>
    static class PairedDevices
    {
        const string Root = Installer.SettingsKey + @"\Tablets";
        const string Legacy = Installer.SettingsKey + @"\Tablet";

        static readonly object sync = new object();
        static List<PairedDevice> list;

        /// <summary>Все устройства, известные агенту, по алфавиту.</summary>
        public static PairedDevice[] All()
        {
            lock (sync)
            {
                EnsureLoaded();
                var copy = new List<PairedDevice>(list);
                copy.Sort(delegate(PairedDevice a, PairedDevice b)
                {
                    return string.Compare(a.Title, b.Title, StringComparison.CurrentCultureIgnoreCase);
                });
                return copy.ToArray();
            }
        }

        public static PairedDevice Get(string key)
        {
            if (string.IsNullOrEmpty(key)) return null;
            lock (sync)
            {
                EnsureLoaded();
                return FindLocked(key);
            }
        }

        /// <summary>
        /// Устройство подключилось (сообщение pair): завести его или обновить адреса, порт, ключ и время.
        /// Пустой key — приложение старой версии без идентификатора: такие устройства различаются по модели.
        /// </summary>
        public static PairedDevice Upsert(string key, string pcId, string model, string kind, int port, string[] addresses, byte[] secret)
        {
            lock (sync)
            {
                EnsureLoaded();
                string id = string.IsNullOrEmpty(key) ? PairedDevice.SyntheticPrefix + (model ?? "") : key;
                id = PairedDevice.SafeName(id);
                PairedDevice d = FindLocked(id);
                if (d == null)
                {
                    d = new PairedDevice();
                    d.Key = id;
                    d.Name = PairedDevice.DefaultName(kind, model);
                    list.Add(d);
                }
                else if (string.IsNullOrEmpty(d.Name) || d.Name == PairedDevice.DefaultName(d.Kind, d.Model))
                {
                    // Имя не переименовывали — пусть следует за моделью.
                    d.Name = PairedDevice.DefaultName(kind, model);
                }
                d.PcId = pcId ?? "";
                d.Model = model ?? "";
                d.Kind = kind;
                d.Port = port;
                d.Addresses = addresses ?? new string[0];
                d.Secret = secret;
                d.LastSeen = PairedDevice.NowMs();

                // Прежние записи того же устройства без идентификатора (старое приложение или агент 1.6)
                // убираем: иначе в списке будет два одинаковых устройства.
                var stale = new List<PairedDevice>();
                foreach (PairedDevice e in list)
                {
                    if (e == d) continue;
                    if (e.Key != PairedDevice.LegacyKey && !e.Key.StartsWith(PairedDevice.SyntheticPrefix, StringComparison.Ordinal)) continue;
                    if (e.PcId != d.PcId) continue;
                    if (!string.IsNullOrEmpty(e.Model) && !string.IsNullOrEmpty(d.Model) && e.Model != d.Model) continue;
                    stale.Add(e);
                }
                foreach (PairedDevice e in stale)
                {
                    list.Remove(e);
                    DeleteKey(e.Key);
                }
                Write(d);
                Log.Write("device paired: " + d.Title + " (" + d.Key + "), адресов: " + d.Addresses.Length);
                return d;
            }
        }

        /// <summary>Записывает устройство в реестр (например, когда агент нашёл его в сети по новому адресу).</summary>
        public static void Save(PairedDevice d)
        {
            if (d == null) return;
            lock (sync) Write(d);
        }

        public static void Rename(string key, string name)
        {
            lock (sync)
            {
                EnsureLoaded();
                PairedDevice d = FindLocked(key);
                if (d == null) return;
                string trimmed = (name ?? "").Trim();
                d.Name = trimmed.Length == 0 ? PairedDevice.DefaultName(d.Kind, d.Model) : trimmed;
                Write(d);
                Log.Write("device renamed: " + d.Key + " -> " + d.Name);
            }
        }

        public static void Remove(string key)
        {
            lock (sync)
            {
                EnsureLoaded();
                PairedDevice d = FindLocked(key);
                if (d == null) return;
                list.Remove(d);
                DeleteKey(d.Key);
                Log.Write("device removed: " + d.Title + " (" + d.Key + ")");
            }
        }

        static void EnsureLoaded()
        {
            if (list == null) list = LoadAll();
        }

        static PairedDevice FindLocked(string key)
        {
            foreach (PairedDevice d in list) if (d.Key == key) return d;
            return null;
        }

        static List<PairedDevice> LoadAll()
        {
            var result = new List<PairedDevice>();
            try
            {
                using (RegistryKey root = Registry.CurrentUser.OpenSubKey(Root))
                {
                    if (root != null)
                    {
                        foreach (string name in root.GetSubKeyNames())
                        {
                            try
                            {
                                using (RegistryKey k = root.OpenSubKey(name))
                                {
                                    PairedDevice d = Read(k, name);
                                    if (d != null) result.Add(d);
                                }
                            }
                            catch (Exception e)
                            {
                                Log.Write("device read " + name + ": " + e.Message);
                            }
                        }
                    }
                }
                if (result.Count == 0) MigrateLegacy(result);
            }
            catch (Exception e)
            {
                Log.Write("devices load: " + e.Message);
            }
            return result;
        }

        static PairedDevice Read(RegistryKey k, string name)
        {
            if (k == null) return null;
            var d = new PairedDevice();
            d.Key = name;
            d.PcId = k.GetValue("PcId") as string;
            d.Name = k.GetValue("Name") as string;
            d.Model = k.GetValue("Model") as string ?? "";
            d.Kind = k.GetValue("Kind") as string;
            object port = k.GetValue("Port");
            d.Port = port is int ? (int)port : 0;
            d.Addresses = (k.GetValue("Addresses") as string ?? "").Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            object seen = k.GetValue("LastSeen");
            d.LastSeen = seen is long ? (long)seen : 0;
            byte[] secret = k.GetValue("Secret") as byte[];
            if (d.PcId == null || d.Port <= 0 || secret == null) return null;
            d.Secret = ProtectedData.Unprotect(secret, null, DataProtectionScope.CurrentUser);
            return d;
        }

        /// <summary>
        /// Агент 1.6 хранил одно устройство в ключе Tablet без идентификатора: переносим его в общий список,
        /// чтобы пункт меню работал до первого подключения уже обновлённого приложения.
        /// </summary>
        static void MigrateLegacy(List<PairedDevice> result)
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(Legacy))
                {
                    if (k == null) return;
                    var d = new PairedDevice();
                    d.Key = PairedDevice.LegacyKey;
                    d.PcId = k.GetValue("DeviceId") as string;
                    d.Model = k.GetValue("Name") as string ?? "";
                    d.Name = PairedDevice.DefaultName(null, d.Model);
                    object port = k.GetValue("Port");
                    d.Port = port is int ? (int)port : 0;
                    d.Addresses = (k.GetValue("Addresses") as string ?? "").Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
                    byte[] secret = k.GetValue("Secret") as byte[];
                    if (d.PcId == null || d.Port <= 0 || secret == null) return;
                    d.Secret = ProtectedData.Unprotect(secret, null, DataProtectionScope.CurrentUser);
                    result.Add(d);
                    Write(d);
                    Registry.CurrentUser.DeleteSubKeyTree(Legacy, false);
                    Log.Write("legacy tablet migrated: " + d.Name);
                }
            }
            catch (Exception e)
            {
                Log.Write("legacy migrate: " + e.Message);
            }
        }

        static void Write(PairedDevice d)
        {
            try
            {
                using (RegistryKey root = Registry.CurrentUser.CreateSubKey(Root))
                using (RegistryKey k = root.CreateSubKey(PairedDevice.SafeName(d.Key)))
                {
                    k.SetValue("PcId", d.PcId ?? "");
                    k.SetValue("Name", d.Name ?? "");
                    k.SetValue("Model", d.Model ?? "");
                    k.SetValue("Kind", d.Kind ?? "");
                    k.SetValue("Port", d.Port, RegistryValueKind.DWord);
                    k.SetValue("Addresses", string.Join(" ", d.Addresses ?? new string[0]));
                    k.SetValue("LastSeen", d.LastSeen, RegistryValueKind.QWord);
                    if (d.Secret != null) k.SetValue("Secret", ProtectedData.Protect(d.Secret, null, DataProtectionScope.CurrentUser), RegistryValueKind.Binary);
                }
            }
            catch (Exception e)
            {
                Log.Write("device save " + d.Key + ": " + e.Message);
            }
        }

        static void DeleteKey(string key)
        {
            try
            {
                using (RegistryKey root = Registry.CurrentUser.OpenSubKey(Root, true))
                {
                    if (root != null) root.DeleteSubKeyTree(PairedDevice.SafeName(key), false);
                }
            }
            catch (Exception e)
            {
                Log.Write("device delete " + key + ": " + e.Message);
            }
        }
    }
}
