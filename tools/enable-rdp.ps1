# Включает приём входящих RDP-подключений на этом ПК (нужны права администратора).
#  - разрешает подключения к удалённому рабочему столу
#  - оставляет включённой проверку подлинности на уровне сети (NLA)
#  - открывает TCP/UDP 3389 в брандмауэре только для локальной подсети
#  - запускает службу TermService и ставит ей автозапуск
$ErrorActionPreference = 'Stop'

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not ([Security.Principal.WindowsPrincipal]$id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Запустите скрипт от имени администратора.'
}

$ts = 'HKLM:\SYSTEM\CurrentControlSet\Control\Terminal Server'
Set-ItemProperty -Path $ts -Name fDenyTSConnections -Value 0
Set-ItemProperty -Path "$ts\WinStations\RDP-Tcp" -Name UserAuthentication -Value 1

# Правила ищем по внутренним именам, а не по локализованной группе.
$rules = Get-NetFirewallRule -Name 'RemoteDesktop-*' -ErrorAction SilentlyContinue
if (-not $rules) { $rules = Get-NetFirewallRule -DisplayGroup 'Remote Desktop' }
$rules | Set-NetFirewallRule -Enabled True -Profile Any -RemoteAddress LocalSubnet

Set-Service -Name TermService -StartupType Automatic
Start-Service -Name TermService

'--- Результат'
'fDenyTSConnections = ' + (Get-ItemProperty $ts).fDenyTSConnections
'NLA                = ' + (Get-ItemProperty "$ts\WinStations\RDP-Tcp").UserAuthentication
(Get-Service TermService | Select-Object Name, Status, StartType | Format-Table -AutoSize | Out-String)
($rules | Select-Object Name, Enabled, Profile | Format-Table -AutoSize | Out-String)
Get-NetTCPConnection -LocalPort 3389 -State Listen -ErrorAction SilentlyContinue | Select-Object LocalAddress, LocalPort, State
