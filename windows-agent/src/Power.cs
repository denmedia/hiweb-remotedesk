using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;

namespace HiWebAgent
{
    /// <summary>
    /// Выключение, перезагрузка, сон и гибернация средствами Windows API. Какие действия доступны, решает
    /// GetPwrCapabilities: сон — только при S1–S3, гибернация — при S4 и созданном hiberfil.sys.
    /// </summary>
    static class Power
    {
        public const string Shutdown = "shutdown";
        public const string Restart = "restart";
        public const string Sleep = "sleep";
        public const string Hibernate = "hibernate";

        [DllImport("powrprof.dll", SetLastError = true)]
        static extern bool GetPwrCapabilities(IntPtr capabilities);

        [DllImport("powrprof.dll", SetLastError = true)]
        static extern bool SetSuspendState(bool hibernate, bool forceCritical, bool disableWakeEvent);

        [DllImport("user32.dll", SetLastError = true)]
        static extern bool ExitWindowsEx(uint flags, uint reason);

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool LookupPrivilegeValue(string system, string name, out long luid);

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool AdjustTokenPrivileges(IntPtr token, bool disableAll, ref TokenPrivileges state, int length, IntPtr previous, IntPtr returnLength);

        [DllImport("kernel32.dll")]
        static extern IntPtr GetCurrentProcess();

        [DllImport("kernel32.dll")]
        static extern bool CloseHandle(IntPtr handle);

        [StructLayout(LayoutKind.Sequential, Pack = 4)]
        struct TokenPrivileges
        {
            public int Count;
            public long Luid;
            public int Attributes;
        }

        // Смещения полей SYSTEM_POWER_CAPABILITIES (все BOOLEAN по байту).
        const int CAP_S1 = 3, CAP_S2 = 4, CAP_S3 = 5, CAP_S4 = 6, CAP_HIBERFILE = 8;

        const uint EWX_SHUTDOWN = 0x1, EWX_REBOOT = 0x2, EWX_POWEROFF = 0x8, EWX_FORCEIFHUNG = 0x10;
        const uint SHTDN_REASON_MAJOR_OTHER = 0x0, SHTDN_REASON_FLAG_PLANNED = 0x80000000;

        /// <summary>Доступные сейчас действия (выключение и перезагрузка доступны всегда).</summary>
        public static List<string> Available()
        {
            var list = new List<string> { Shutdown, Restart };
            IntPtr buffer = Marshal.AllocHGlobal(256);
            try
            {
                for (int i = 0; i < 256; i++) Marshal.WriteByte(buffer, i, 0);
                if (GetPwrCapabilities(buffer))
                {
                    bool sleep = Marshal.ReadByte(buffer, CAP_S1) != 0 || Marshal.ReadByte(buffer, CAP_S2) != 0 || Marshal.ReadByte(buffer, CAP_S3) != 0;
                    bool hibernate = Marshal.ReadByte(buffer, CAP_S4) != 0 && Marshal.ReadByte(buffer, CAP_HIBERFILE) != 0;
                    if (sleep) list.Add(Sleep);
                    if (hibernate) list.Add(Hibernate);
                }
            }
            finally
            {
                Marshal.FreeHGlobal(buffer);
            }
            return list;
        }

        static string requested;
        static DateTime requestedAt;

        /// <summary>
        /// Действие, запрошенное через агент за последнюю минуту, или null. Windows не говорит, куда уходит ПК
        /// (сон или гибернация, выключение или перезагрузка), а запрошенное нами действие известно точно.
        /// </summary>
        public static string Requested
        {
            get { lock (typeof(Power)) return requested != null && DateTime.UtcNow - requestedAt < TimeSpan.FromMinutes(1) ? requested : null; }
        }

        /// <summary>Выполняет действие; при ошибке бросает Win32Exception с текстом Windows.</summary>
        public static void Run(string action)
        {
            lock (typeof(Power))
            {
                requested = action;
                requestedAt = DateTime.UtcNow;
            }
            switch (action)
            {
                case Shutdown:
                    Exit(EWX_SHUTDOWN | EWX_POWEROFF | EWX_FORCEIFHUNG);
                    break;
                case Restart:
                    Exit(EWX_REBOOT | EWX_FORCEIFHUNG);
                    break;
                case Sleep:
                    if (!SetSuspendState(false, false, false)) throw new Win32Exception();
                    break;
                case Hibernate:
                    if (!SetSuspendState(true, false, false)) throw new Win32Exception();
                    break;
                default:
                    throw new ArgumentException("неизвестное действие " + action);
            }
        }

        static void Exit(uint flags)
        {
            EnableShutdownPrivilege();
            if (!ExitWindowsEx(flags, SHTDN_REASON_MAJOR_OTHER | SHTDN_REASON_FLAG_PLANNED)) throw new Win32Exception();
        }

        /// <summary>Право SeShutdownPrivilege есть у пользователя, но в токене процесса выключено.</summary>
        static void EnableShutdownPrivilege()
        {
            const uint TOKEN_ADJUST_PRIVILEGES = 0x20, TOKEN_QUERY = 0x8;
            const int SE_PRIVILEGE_ENABLED = 0x2;
            IntPtr token;
            if (!OpenProcessToken(GetCurrentProcess(), TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, out token)) throw new Win32Exception();
            try
            {
                var tp = new TokenPrivileges();
                tp.Count = 1;
                tp.Attributes = SE_PRIVILEGE_ENABLED;
                if (!LookupPrivilegeValue(null, "SeShutdownPrivilege", out tp.Luid)) throw new Win32Exception();
                if (!AdjustTokenPrivileges(token, false, ref tp, 0, IntPtr.Zero, IntPtr.Zero)) throw new Win32Exception();
            }
            finally
            {
                CloseHandle(token);
            }
        }
    }
}
