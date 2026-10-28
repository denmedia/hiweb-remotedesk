@echo off
rem Builds HiWeb Agent with the C# compiler bundled with Windows (.NET Framework 4.8): no Visual Studio or SDK needed.
rem Output goes to the Android app assets: app/src/main/assets/agent/HiWebAgent.exe and version.txt.
setlocal
cd /d "%~dp0"
set /p VER=<VERSION
set FW=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319
set WM=%WINDIR%\System32\WinMetadata
set OUT=..\app\src\main\assets\agent
if not exist obj mkdir obj
if not exist "%OUT%" mkdir "%OUT%"
> obj\BuildInfo.cs echo namespace HiWebAgent { static class BuildInfo { public const string Version = "%VER%"; } }
"%FW%\csc.exe" -nologo -target:winexe -optimize+ -win32manifest:app.manifest -win32icon:app.ico -resource:laptop.png,HiWebAgent.laptop.png -out:"%OUT%\HiWebAgent.exe" ^
  -r:System.Windows.Forms.dll -r:System.Drawing.dll -r:"%FW%\System.Web.Extensions.dll" -r:System.Core.dll -r:System.Security.dll ^
  -r:"%FW%\WPF\UIAutomationClient.dll" -r:"%FW%\WPF\UIAutomationTypes.dll" ^
  -r:"%FW%\System.Runtime.dll" -r:"%WM%\Windows.Foundation.winmd" -r:"%WM%\Windows.Data.winmd" -r:"%WM%\Windows.UI.winmd" -r:"%WM%\Windows.ApplicationModel.winmd" ^
  src\*.cs obj\BuildInfo.cs
if errorlevel 1 exit /b 1
<nul set /p =%VER%> "%OUT%\version.txt"
echo HiWebAgent %VER% -^> %OUT%
