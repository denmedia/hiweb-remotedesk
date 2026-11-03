using System;
using System.Collections.Generic;
using Microsoft.Win32;

namespace HiWebAgent
{
    /// <summary>
    /// Настройки доступа к файлам с планшета (выключатель и открытые папки) в <c>HKCU\Software\HiWebAgent</c>, значение
    /// <c>Files</c> (JSON, см. <see cref="ShareConfig"/>). Устройства получают их в <c>caps</c> без путей на ПК. По умолчанию
    /// доступ выключен.
    /// </summary>
    static class FileSharing
    {
        const string ValueName = "Files";

        static readonly object sync = new object();
        static ShareConfig current;

        /// <summary>Настройки изменились и сохранены. Вызывается в потоке, который их менял.</summary>
        public static event Action Changed;

        /// <summary>Копия настроек: её можно менять, пока она не передана в <see cref="Set"/>.</summary>
        public static ShareConfig Current()
        {
            lock (sync)
            {
                EnsureLoaded();
                return current.Clone();
            }
        }

        /// <summary>Значение поля <c>fs</c> в caps.</summary>
        public static Dictionary<string, object> CapsValue()
        {
            lock (sync)
            {
                EnsureLoaded();
                return current.CapsValue();
            }
        }

        /// <summary>Запоминает настройки и сообщает об изменении.</summary>
        public static void Set(ShareConfig config)
        {
            lock (sync)
            {
                current = config.Clone();
                Save();
            }
            Action handler = Changed;
            if (handler != null) handler();
        }

        static void EnsureLoaded()
        {
            if (current != null) return;
            string text = null;
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(Installer.SettingsKey))
                {
                    if (k != null) text = k.GetValue(ValueName) as string;
                }
            }
            catch (Exception e)
            {
                Log.Write("files settings read: " + e.Message);
            }
            current = ShareConfig.FromJson(text);
        }

        static void Save()
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.CreateSubKey(Installer.SettingsKey))
                {
                    k.SetValue(ValueName, current.ToJson());
                }
            }
            catch (Exception e)
            {
                Log.Write("files settings save: " + e.Message);
            }
        }
    }
}
