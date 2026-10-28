# vivo-rms.ps1 - rootless high refresh rate (144 Hz and up) for vivo phones
#
# Windows PowerShell port of vivo-rms.sh. Same commands, same RmsVote.dex.
#
# usage:  .\vivo-rms.ps1 build                  javac + d8 -> RmsVote.dex
#         .\vivo-rms.ps1 get                    current rate (tx 26)
#         .\vivo-rms.ps1 set  <fps> [opts]      vote; stays until released/reboot
#         .\vivo-rms.ps1 hold <fps> [opts]      vote and keep it until Ctrl-C
#         .\vivo-rms.ps1 release 0 900001       withdraw a `set` vote
#         .\vivo-rms.ps1 modes                  refresh rates the panel offers
#         .\vivo-rms.ps1 reason                 which request the vendor applies
# opts:   --caller <pid> --scene <name> --prio <n> --dur <n> --flags <n>
#         --display <n> --handle <n> --v1   (defaults: see RmsVote.java)
# An app that asks for its own display mode still wins while it is in front.

$ErrorActionPreference = 'Stop'

$LocalDex = Join-Path $PSScriptRoot 'RmsVote.dex'
$Dex = '/data/local/tmp/RmsVote.dex'

function Die([string]$msg) {
    [Console]::Error.WriteLine("error: $msg")
    exit 1
}

function Build {
    if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { Die 'javac not found (install JDK 17)' }
    if (-not (Get-Command d8 -ErrorAction SilentlyContinue)) { Die 'd8 not found (add Android SDK build-tools to PATH)' }
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ([IO.Path]::GetRandomFileName())
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        & javac -nowarn --release 8 -d $tmp (Join-Path $PSScriptRoot 'RmsVote.java')
        if ($LASTEXITCODE -ne 0) { Die 'javac failed' }
        # @() keeps a single class an array; splatting a lone string passes its characters
        $classes = @(Get-ChildItem -Path $tmp -Filter 'RmsVote*.class' | ForEach-Object { $_.FullName })
        & d8 --release --min-api 26 --output $tmp @classes
        if ($LASTEXITCODE -ne 0) { Die 'd8 failed' }
        Move-Item -Force (Join-Path $tmp 'classes.dex') $LocalDex
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
    Write-Output "built $LocalDex"
}

function Pick-Device {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Die 'adb not found' }
    $d = & adb devices | Where-Object { $_ -match "`tdevice$" } | Select-Object -First 1
    if (-not $d) { Die 'no adb device connected' }
    $script:Serial = ($d -split "`t")[0]
}

function Invoke-Adb {
    & adb -s $script:Serial @args
}

function Push-Dex {
    if (-not (Test-Path $LocalDex)) { Build }
    # always push: a stale dex on the phone would run the old argument layout
    Invoke-Adb push $LocalDex $Dex | Out-Null
    if ($LASTEXITCODE -ne 0) { Die 'push failed' }
}

$cmd = if ($args.Count -gt 0) { $args[0] } else { '' }

switch ($cmd) {
    { $_ -in '', '-h', '--help' } {
        Get-Content $PSCommandPath -TotalCount 14 | Select-Object -Skip 1 | ForEach-Object { $_ -replace '^# ?', '' }
        exit 0
    }
    'build' { Build; exit 0 }
    'modes' {
        Pick-Device
        Invoke-Adb shell "dumpsys display | grep -oE 'fps=[0-9.]+' | sort -u"
        Write-Output "private fps: $(Invoke-Adb shell getprop persist.sys.private_fps)"
        Write-Output "public max:  $(Invoke-Adb shell getprop persist.vivo.phone.fps_max)"
        Write-Output "rrm version: $(Invoke-Adb shell getprop debug.rms.refresh_rate_adjuster_version)"
        exit 0
    }
    'reason' {
        Pick-Device
        Invoke-Adb shell settings get secure vivo_rms_active_request_reason
        exit 0
    }
}

Pick-Device
Push-Dex
# -t: Ctrl-C reaches app_process, so `hold` runs its shutdown hook and releases
Invoke-Adb shell -t "CLASSPATH=$Dex" app_process / RmsVote @args
exit $LASTEXITCODE
