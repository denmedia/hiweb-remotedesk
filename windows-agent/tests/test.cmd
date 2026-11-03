@echo off
rem Builds and runs the agent checks with the C# compiler bundled with Windows (.NET Framework 4.8):
rem guard rules (src\GuardRules.cs) and file access (src\FilesPolicy.cs, src\FileService.cs).
setlocal
cd /d "%~dp0"
set FW=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319
if not exist obj mkdir obj
"%FW%\csc.exe" -nologo -out:obj\GuardRulesTests.exe GuardRulesTests.cs ..\src\GuardRules.cs
if errorlevel 1 exit /b 1
obj\GuardRulesTests.exe
if errorlevel 1 exit /b 1
"%FW%\csc.exe" -nologo -out:obj\FilesTests.exe -r:"%FW%\System.Web.Extensions.dll" FilesTests.cs ..\src\FilesPolicy.cs ..\src\FileService.cs
if errorlevel 1 exit /b 1
obj\FilesTests.exe
