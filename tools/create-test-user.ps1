# Создаёт временного локального пользователя для проверки RDP-клиента (нужны права администратора).
# Пароль спрашивается при запуске и нигде не сохраняется.
# Удалить после проверки:  net user rdptest /delete
$ErrorActionPreference = 'Stop'
$pwd = Read-Host -AsSecureString 'Пароль для rdptest'
New-LocalUser -Name 'rdptest' -Password $pwd -PasswordNeverExpires -AccountNeverExpires -Description 'Временная учётная запись для отладки RemoteDesk' | Out-Null
# Группа «Пользователи удалённого рабочего стола» по SID, чтобы не зависеть от языка системы.
Add-LocalGroupMember -SID 'S-1-5-32-555' -Member 'rdptest'
'Создан пользователь rdptest'
