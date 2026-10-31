using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Automation;

namespace HiWebAgent
{
    /// <summary>
    /// Сенсорная клавиатура Windows для планшета без физической клавиатуры: после щелчка мыши (касания на
    /// планшете приходят по RDP щелчками) проверяет, получило ли фокус поле ввода, и показывает клавиатуру;
    /// щелчок мимо полей ввода её прячет, щелчки по самой клавиатуре (и по её кнопке «закрыть») не трогают её.
    /// Работает, только пока включено (<see cref="Active"/>); при выключении (например, к планшету подключили
    /// клавиатуру) открытая клавиатура прячется.
    /// </summary>
    sealed class TouchKeyboard : IDisposable
    {
        const int WH_MOUSE_LL = 14;
        const int WM_LBUTTONDOWN = 0x0201;
        const int WM_LBUTTONUP = 0x0202;

        /// <summary>Пауза после щелчка: фокус переходит к полю не сразу.</summary>
        const int SETTLE_MS = 250;

        /// <summary>Как часто обновлять положение клавиатуры, пока щелчков нет.</summary>
        const int REFRESH_MS = 500;

        delegate IntPtr HookProc(int code, IntPtr wParam, IntPtr lParam);

        [DllImport("user32.dll", SetLastError = true)]
        static extern IntPtr SetWindowsHookEx(int id, HookProc proc, IntPtr module, uint threadId);

        [DllImport("user32.dll")]
        static extern bool UnhookWindowsHookEx(IntPtr hook);

        [DllImport("user32.dll")]
        static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr wParam, IntPtr lParam);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
        static extern IntPtr GetModuleHandle(string name);

        [DllImport("user32.dll")]
        static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll")]
        static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);

        [DllImport("user32.dll")]
        static extern bool GetGUIThreadInfo(uint threadId, ref GuiThreadInfo info);

        [DllImport("user32.dll")]
        static extern IntPtr GetDesktopWindow();

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern int GetClassName(IntPtr hwnd, StringBuilder name, int max);

        [StructLayout(LayoutKind.Sequential)]
        struct Point
        {
            public int X, Y;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct Rect
        {
            public int Left, Top, Right, Bottom;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct MouseHook
        {
            public Point Pt;
            public uint MouseData, Flags, Time;
            public IntPtr Extra;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct GuiThreadInfo
        {
            public int Size;
            public uint Flags;
            public IntPtr Active, Focus, Capture, MenuOwner, MoveSize, Caret;
            public Rect CaretRect;
        }

        /// <summary>Переключатель сенсорной клавиатуры (тот же, что у кнопки на панели задач).</summary>
        [ComImport, Guid("37c994e7-432b-4834-a2f7-dce1f13b834b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface ITipInvocation
        {
            void Toggle(IntPtr desktop);
        }

        /// <summary>Положение панели ввода на экране: пустой прямоугольник — клавиатура скрыта.</summary>
        [ComImport, Guid("5752238B-24F0-495A-82F1-2FD593056796"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IFrameworkInputPane
        {
            [PreserveSig] int Advise([MarshalAs(UnmanagedType.IUnknown)] object window, IntPtr handler, out int cookie);
            [PreserveSig] int AdviseWithHWND(IntPtr hwnd, IntPtr handler, out int cookie);
            [PreserveSig] int Unadvise(int cookie);
            [PreserveSig] int Location(out Rect location);
        }

        static readonly Guid UIHostNoLaunch = new Guid("4ce576fa-83dc-4f88-951c-9d0782b4e376");
        static readonly Guid FrameworkInputPane = new Guid("D5120AA3-46BA-44C5-822D-CA8092C1FC72");

        readonly HookProc hookProc;
        readonly AutoResetEvent clicked = new AutoResetEvent(false);
        readonly Thread worker;
        IntPtr hook;
        volatile bool active;
        volatile bool stopped;
        volatile bool hidePending;
        volatile bool applyMode;
        volatile string mode = "auto";
        // Где сейчас клавиатура (null — скрыта). Окно клавиатуры не находится через WindowFromPoint,
        // поэтому щелчок «по клавиатуре» определяем по её прямоугольнику в момент нажатия кнопки.
        volatile object paneRect;
        volatile bool downOnKeyboard;
        volatile bool clickOnKeyboard;

        /// <summary>
        /// Клавиатура появилась, сдвинулась или скрылась: { left, top, right, bottom } в пикселях экрана или null.
        /// Вызывается из потока клавиатуры.
        /// </summary>
        public event Action<int[]> PaneChanged;

        /// <summary>
        /// Режим по команде планшета (кнопка клавиатуры в панели сеанса): "auto" — клавиатура появляется при щелчке в поле
        /// ввода и прячется при щелчке вне его; "show" — открыта и остаётся, пока её не закроют; "hide" — всегда скрыта
        /// (этот режим задаёт владелец через <see cref="Active"/> = false). Менять до <see cref="Active"/>.
        /// </summary>
        public string Mode
        {
            get { return mode; }
            set
            {
                if (value != "show" && value != "hide") value = "auto";
                if (mode == value) return;
                mode = value;
                applyMode = true;
                clicked.Set();
            }
        }

        public TouchKeyboard()
        {
            hookProc = OnMouse;
            worker = new Thread(Work);
            worker.IsBackground = true;
            worker.Name = "touch-keyboard";
            // COM сенсорной клавиатуры и UI Automation — из STA-потока, как у обычных приложений.
            worker.SetApartmentState(ApartmentState.STA);
            worker.Start();
        }

        /// <summary>Вызывать из потока с циклом сообщений (UI агента): ловушка мыши живёт в нём.</summary>
        public bool Active
        {
            get { return active; }
            set
            {
                if (active == value) return;
                active = value;
                if (value)
                {
                    // Планшет после включения должен узнать положение уже открытой клавиатуры заново.
                    paneRect = null;
                    applyMode = true;
                    clicked.Set();
                    hook = SetWindowsHookEx(WH_MOUSE_LL, hookProc, GetModuleHandle(null), 0);
                    if (hook == IntPtr.Zero) Log.Write("touch keyboard: hook failed " + Marshal.GetLastWin32Error());
                }
                else
                {
                    if (hook != IntPtr.Zero) UnhookWindowsHookEx(hook);
                    hook = IntPtr.Zero;
                    // Прячет поток клавиатуры: COM вызывается только из него.
                    hidePending = true;
                    clicked.Set();
                }
                Log.Write("touch keyboard " + (value ? "on" : "off"));
            }
        }

        IntPtr OnMouse(int code, IntPtr wParam, IntPtr lParam)
        {
            // Ловушка должна отвечать мгновенно: только запоминаем щелчок, проверка — в своём потоке.
            int msg = code >= 0 ? wParam.ToInt32() : 0;
            if (msg == WM_LBUTTONDOWN)
            {
                Point pt = ((MouseHook)Marshal.PtrToStructure(lParam, typeof(MouseHook))).Pt;
                object r = paneRect;
                downOnKeyboard = r != null && Contains((Rect)r, pt);
            }
            else if (msg == WM_LBUTTONUP)
            {
                clickOnKeyboard = downOnKeyboard;
                clicked.Set();
            }
            return CallNextHookEx(IntPtr.Zero, code, wParam, lParam);
        }

        void Work()
        {
            while (true)
            {
                bool click = clicked.WaitOne(REFRESH_MS);
                if (stopped) return;
                try
                {
                    if (hidePending && !active)
                    {
                        hidePending = false;
                        Hide();
                        Publish(null);
                        continue;
                    }
                    if (!active) continue;
                    if (applyMode)
                    {
                        applyMode = false;
                        // «Показана»: открываем сразу, не дожидаясь щелчка в поле ввода.
                        if (mode == "show") Show();
                    }
                    if (click && mode == "auto")
                    {
                        Thread.Sleep(SETTLE_MS);
                        if (!active) continue;
                        // Нажатия клавиш и «закрыть» на самой клавиатуре: фокус остаётся в поле, но открывать
                        // её снова нельзя — пользователь закрыл её сам.
                        if (!clickOnKeyboard)
                        {
                            if (IsTextInputFocused()) Show();
                            else Hide();
                        }
                    }
                    Publish(Location());
                }
                catch (Exception e)
                {
                    Log.Write("touch keyboard: " + e.Message);
                }
            }
        }

        /// <summary>Фокус в поле, куда можно печатать: Edit или документ с текстовым курсором, не только для чтения.</summary>
        static bool IsTextInputFocused()
        {
            // Программы, которые рисуют текст и курсор сами, не отдают UI Automation ни поля ввода, ни системного
            // курсора (Notepad++ и другие на Scintilla, PhpStorm и прочие IDE на Java): узнаём их по классу окна.
            string cls = FocusWindowClass();
            if (IsSelfDrawnEditor(cls)) return true;
            AutomationElement el;
            try { el = AutomationElement.FocusedElement; }
            catch (Exception) { return HasCaret(); }
            if (el == null) return HasCaret();
            ControlType type;
            try { type = el.Current.ControlType; }
            catch (ElementNotAvailableException) { return false; }
            // Страница браузера или окно просмотра тоже «документ»: печатать в них можно, только если есть курсор.
            bool candidate = type == ControlType.Edit || (type == ControlType.Document && HasCaret());
            if (!candidate)
            {
                Log.Write("touch keyboard: not an input, class=" + cls + ", type=" + type.ProgrammaticName);
                return false;
            }
            object pattern;
            if (el.TryGetCurrentPattern(ValuePattern.Pattern, out pattern)) return !((ValuePattern)pattern).Current.IsReadOnly;
            return true;
        }

        /// <summary>Класс окна, которому принадлежит фокус ввода (или активного окна, если фокус не определён).</summary>
        static string FocusWindowClass()
        {
            uint pid;
            IntPtr fg = GetForegroundWindow();
            uint thread = GetWindowThreadProcessId(fg, out pid);
            var info = new GuiThreadInfo();
            info.Size = Marshal.SizeOf(typeof(GuiThreadInfo));
            IntPtr hwnd = thread != 0 && GetGUIThreadInfo(thread, ref info) && info.Focus != IntPtr.Zero ? info.Focus : fg;
            var name = new StringBuilder(256);
            return GetClassName(hwnd, name, name.Capacity) > 0 ? name.ToString() : "";
        }

        /// <summary>
        /// Окна редакторов с собственной отрисовкой текста: Scintilla (Notepad++, SciTE) и Java AWT/Swing (IDE
        /// JetBrains). О том, какой именно элемент в окне Java в фокусе, система не знает: клавиатура покажется
        /// при щелчке в любом месте такого окна, зато печатать в редакторе можно.
        /// </summary>
        static bool IsSelfDrawnEditor(string cls)
        {
            return cls.StartsWith("Scintilla", StringComparison.OrdinalIgnoreCase)
                || cls.StartsWith("SunAwt", StringComparison.OrdinalIgnoreCase);
        }

        static bool HasCaret()
        {
            uint pid;
            uint thread = GetWindowThreadProcessId(GetForegroundWindow(), out pid);
            var info = new GuiThreadInfo();
            info.Size = Marshal.SizeOf(typeof(GuiThreadInfo));
            return thread != 0 && GetGUIThreadInfo(thread, ref info) && info.Caret != IntPtr.Zero;
        }

        void Publish(object rect)
        {
            object old = paneRect;
            paneRect = rect;
            if (Equals(old, rect)) return;
            Action<int[]> handler = PaneChanged;
            if (handler == null) return;
            if (rect == null)
            {
                handler(null);
                return;
            }
            var r = (Rect)rect;
            handler(new[] { r.Left, r.Top, r.Right, r.Bottom });
        }

        static bool Contains(Rect r, Point pt)
        {
            return pt.X >= r.Left && pt.X < r.Right && pt.Y >= r.Top && pt.Y < r.Bottom;
        }

        /// <summary>Прямоугольник клавиатуры на экране или null, если она скрыта.</summary>
        static object Location()
        {
            object pane = null;
            try
            {
                pane = Activator.CreateInstance(Type.GetTypeFromCLSID(FrameworkInputPane));
                Rect r;
                if (((IFrameworkInputPane)pane).Location(out r) == 0 && r.Right > r.Left && r.Bottom > r.Top) return r;
                return null;
            }
            finally
            {
                if (pane != null) Marshal.ReleaseComObject(pane);
            }
        }

        static bool IsVisible()
        {
            return Location() != null;
        }

        static void Show()
        {
            if (IsVisible()) return;
            if (Process.GetProcessesByName("TabTip").Length == 0)
            {
                // Без запущенного TabTip переключатель недоступен; сам запуск обычно и открывает клавиатуру.
                string tabTip = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonProgramFiles), @"microsoft shared\ink\TabTip.exe");
                Process.Start(tabTip);
                Thread.Sleep(800);
                if (IsVisible()) return;
            }
            Toggle();
        }

        static void Hide()
        {
            if (IsVisible()) Toggle();
        }

        static void Toggle()
        {
            object tip = Activator.CreateInstance(Type.GetTypeFromCLSID(UIHostNoLaunch));
            try { ((ITipInvocation)tip).Toggle(GetDesktopWindow()); }
            finally { Marshal.ReleaseComObject(tip); }
        }

        public void Dispose()
        {
            Active = false;
            stopped = true;
            clicked.Set();
        }
    }
}
