@echo off
rem Builds and runs the agent guard-rule checks (src\GuardRules.cs) with the C# compiler bundled with Windows (.NET Framework 4.8).
setlocal
cd /d "%~dp0"
set FW=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319
if not exist obj mkdir obj
"%FW%\csc.exe" -nologo -out:obj\GuardRulesTests.exe GuardRulesTests.cs ..\src\GuardRules.cs
if errorlevel 1 exit /b 1
obj\GuardRulesTests.exe
