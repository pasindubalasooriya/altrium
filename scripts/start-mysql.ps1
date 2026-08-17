# Starts the local Altrium MySQL 8.4 instance.
#
# This instance runs as a plain user process, not a Windows service, because
# service installation requires administrator rights that the dev account here
# does not have. That means it does NOT survive a reboot - run this script
# again after restarting.
#
# Usage:  .\scripts\start-mysql.ps1

$ErrorActionPreference = 'Stop'

$mysqld = 'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe'
$config = "$env:LOCALAPPDATA\Altrium\my.ini"

if (-not (Test-Path $mysqld)) {
    throw "MySQL Server 8.4 not found at $mysqld. Install it with: winget install --id Oracle.MySQL"
}
if (-not (Test-Path $config)) {
    throw "Config not found at $config. See docs/local-setup.md to initialise the data directory."
}

if (Get-Process mysqld -ErrorAction SilentlyContinue) {
    Write-Host 'MySQL is already running.' -ForegroundColor Yellow
    return
}

Start-Process -FilePath $mysqld -ArgumentList "--defaults-file=$config" -WindowStyle Hidden
Start-Sleep -Seconds 6

$probe = Test-NetConnection -ComputerName 127.0.0.1 -Port 3306 -WarningAction SilentlyContinue
if ($probe.TcpTestSucceeded) {
    Write-Host 'MySQL is up on 127.0.0.1:3306' -ForegroundColor Green
} else {
    throw "MySQL did not come up. Check the error log at $env:LOCALAPPDATA\Altrium\mysql-error.log"
}
