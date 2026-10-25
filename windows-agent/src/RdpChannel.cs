using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace HiWebAgent
{
    /// <summary>
    /// Динамический виртуальный канал RDP (MS-RDPEDYC) до планшета. Открывается в сеансе, к которому подключён
    /// клиент RDP; клиент без обработчика этого имени отказывает, и открытие возвращает null.
    /// Сообщение — один JSON в UTF-8; границы сообщений сохраняет сам канал.
    /// </summary>
    sealed class RdpChannel
    {
        public const string Name = "HiWeb::Agent";

        const int WTS_CURRENT_SESSION = -1;
        const int WTS_CHANNEL_OPTION_DYNAMIC = 0x1;
        const int WTSVirtualFileHandle = 1;
        const int CHANNEL_FLAG_FIRST = 0x1;
        const int CHANNEL_FLAG_LAST = 0x2;
        const int CHANNEL_PDU_HEADER = 8;
        const int ERROR_IO_PENDING = 997;
        const int READ_BUFFER = 64 * 1024;
        const uint DUPLICATE_SAME_ACCESS = 0x2;

        [DllImport("wtsapi32.dll", SetLastError = true)]
        static extern IntPtr WTSVirtualChannelOpenEx(int sessionId, [MarshalAs(UnmanagedType.LPStr)] string name, int flags);

        [DllImport("wtsapi32.dll", SetLastError = true)]
        static extern bool WTSVirtualChannelQuery(IntPtr channel, int infoClass, out IntPtr buffer, out int bytes);

        [DllImport("wtsapi32.dll")]
        static extern void WTSFreeMemory(IntPtr memory);

        [DllImport("wtsapi32.dll")]
        static extern bool WTSVirtualChannelClose(IntPtr channel);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool DuplicateHandle(IntPtr sourceProcess, IntPtr source, IntPtr targetProcess, out IntPtr target, uint access, bool inherit, uint options);

        [DllImport("kernel32.dll")]
        static extern IntPtr GetCurrentProcess();

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool ReadFile(IntPtr file, IntPtr buffer, int toRead, IntPtr read, IntPtr overlapped);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool WriteFile(IntPtr file, IntPtr buffer, int toWrite, IntPtr written, IntPtr overlapped);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool GetOverlappedResult(IntPtr file, IntPtr overlapped, out int transferred, bool wait);

        [DllImport("kernel32.dll")]
        static extern bool CancelIoEx(IntPtr file, IntPtr overlapped);

        [DllImport("kernel32.dll")]
        static extern bool CloseHandle(IntPtr handle);

        readonly IntPtr channel;
        readonly IntPtr file;
        readonly object writeLock = new object();
        volatile bool closed;

        /// <summary>Получено целое сообщение. Вызывается из потока чтения.</summary>
        public event Action<string> MessageReceived;

        /// <summary>Канал закрыт (клиент отключился или ошибка). Вызывается один раз из потока чтения.</summary>
        public event Action Closed;

        RdpChannel(IntPtr channel, IntPtr file)
        {
            this.channel = channel;
            this.file = file;
        }

        /// <summary>Пытается открыть канал; блокирует поток, пока клиент не ответит.</summary>
        public static RdpChannel TryOpen()
        {
            IntPtr h = WTSVirtualChannelOpenEx(WTS_CURRENT_SESSION, Name, WTS_CHANNEL_OPTION_DYNAMIC);
            if (h == IntPtr.Zero) return null;
            IntPtr buffer;
            int bytes;
            if (!WTSVirtualChannelQuery(h, WTSVirtualFileHandle, out buffer, out bytes) || bytes != IntPtr.Size)
            {
                WTSVirtualChannelClose(h);
                return null;
            }
            IntPtr dup;
            bool ok = DuplicateHandle(GetCurrentProcess(), Marshal.ReadIntPtr(buffer), GetCurrentProcess(), out dup, 0, false, DUPLICATE_SAME_ACCESS);
            WTSFreeMemory(buffer);
            if (!ok)
            {
                WTSVirtualChannelClose(h);
                return null;
            }
            return new RdpChannel(h, dup);
        }

        public void Start()
        {
            var t = new Thread(ReadLoop);
            t.IsBackground = true;
            t.Name = "rdp-channel";
            t.Start();
        }

        /// <summary>Прерывает чтение; освобождение ресурсов и событие Closed — в потоке чтения.</summary>
        public void Close()
        {
            closed = true;
            CancelIoEx(file, IntPtr.Zero);
        }

        public bool Send(string json)
        {
            byte[] data = Encoding.UTF8.GetBytes(json);
            lock (writeLock)
            {
                if (closed) return false;
                using (var evt = new ManualResetEvent(false))
                {
                    IntPtr buffer = Marshal.AllocHGlobal(data.Length);
                    IntPtr ov = NewOverlapped(evt);
                    try
                    {
                        Marshal.Copy(data, 0, buffer, data.Length);
                        int written;
                        if (!WriteFile(file, buffer, data.Length, IntPtr.Zero, ov) && Marshal.GetLastWin32Error() != ERROR_IO_PENDING) return false;
                        return GetOverlappedResult(file, ov, out written, true) && written == data.Length;
                    }
                    finally
                    {
                        Marshal.FreeHGlobal(ov);
                        Marshal.FreeHGlobal(buffer);
                    }
                }
            }
        }

        void ReadLoop()
        {
            IntPtr buffer = Marshal.AllocHGlobal(READ_BUFFER);
            var evt = new ManualResetEvent(false);
            IntPtr ov = NewOverlapped(evt);
            var message = new MemoryStream();
            byte[] chunk = new byte[READ_BUFFER];
            try
            {
                while (!closed)
                {
                    evt.Reset();
                    int read;
                    if (!ReadFile(file, buffer, READ_BUFFER, IntPtr.Zero, ov) && Marshal.GetLastWin32Error() != ERROR_IO_PENDING) break;
                    if (!GetOverlappedResult(file, ov, out read, true)) break;
                    if (read < CHANNEL_PDU_HEADER) continue;
                    Marshal.Copy(buffer, chunk, 0, read);
                    // Каждое чтение — один фрагмент с заголовком CHANNEL_PDU_HEADER (полная длина, флаги).
                    int flags = BitConverter.ToInt32(chunk, 4);
                    if ((flags & CHANNEL_FLAG_FIRST) != 0) message.SetLength(0);
                    message.Write(chunk, CHANNEL_PDU_HEADER, read - CHANNEL_PDU_HEADER);
                    if ((flags & CHANNEL_FLAG_LAST) == 0) continue;
                    string text = Encoding.UTF8.GetString(message.GetBuffer(), 0, (int)message.Length);
                    message.SetLength(0);
                    var handler = MessageReceived;
                    if (handler != null)
                    {
                        try { handler(text); } catch (Exception e) { Log.Write("message handler: " + e); }
                    }
                }
            }
            finally
            {
                lock (writeLock)
                {
                    closed = true;
                    CloseHandle(file);
                    WTSVirtualChannelClose(channel);
                }
                Marshal.FreeHGlobal(ov);
                Marshal.FreeHGlobal(buffer);
                evt.Close();
                var handler = Closed;
                if (handler != null) handler();
            }
        }

        /// <summary>OVERLAPPED в неуправляемой памяти: адрес не должен меняться, пока операция идёт.</summary>
        static IntPtr NewOverlapped(ManualResetEvent evt)
        {
            var o = new NativeOverlapped();
            o.EventHandle = evt.SafeWaitHandle.DangerousGetHandle();
            IntPtr p = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(NativeOverlapped)));
            Marshal.StructureToPtr(o, p, false);
            return p;
        }
    }
}
