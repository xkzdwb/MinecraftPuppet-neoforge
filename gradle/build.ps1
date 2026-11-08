# 一键构建 Puppet (NeoForge 26.2.0.88 / MC 26.2)
#
# 对普通环境（能直连 maven.neoforged.net）来说，直接 `gradle build` 就行，
# 本脚本只是多做了几件省事的事：
#
#   1. 自动找 JDK 25（MC 26.2 要求 Java 25 编译）+ 一个 JDK 21/22 来跑 Gradle 本体；
#   2. 可选：启动本地 Maven 代理（tools/neoforge-mirror-proxy.mjs）。
#      只在 maven.neoforged.net 连不上时才需要 —— 见 README「本机网络修补」；
#   3. 可选：生成 net.neoforged:minecraft-dependencies 到 .build-mcdeps。
#      同样只在镜像缺这个构件时才需要。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File gradle\build.ps1
#   powershell -ExecutionPolicy Bypass -File gradle\build.ps1 -Task runClient
#   powershell -ExecutionPolicy Bypass -File gradle\build.ps1 -Offline        # 强制用本机修补
#   powershell -ExecutionPolicy Bypass -File gradle\build.ps1 -Plain          # 不用任何修补
#
param(
    [string]$Task = 'build',
    [string]$Jdk25 = '',
    [switch]$Offline,
    [switch]$Plain
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Find-Jdk([int[]]$majors) {
    # 覆盖常见安装位置；P:\mc-jdk25 是本机约定位置
    $searchRoots = @(
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Zulu",
        "${env:ProgramFiles(x86)}\Java",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium",
        "$env:USERPROFILE\.jdks"
    ) | Where-Object { Test-Path $_ }

    $dirs = @()
    foreach ($r in $searchRoots) {
        $dirs += Get-ChildItem $r -Directory -ErrorAction SilentlyContinue
    }
    # 约定位置：<盘符>\mc-jdk25、<任意>\jdk-25* 直接扫描
    foreach ($guess in @('P:\mc-jdk25')) {
        if (Test-Path "$guess\bin\javac.exe") { $dirs += Get-Item $guess }
    }

    foreach ($m in $majors) {
        $hit = $dirs | Where-Object {
            $_.Name -match "jdk-?$m(\.|-|$)" -and (Test-Path "$($_.FullName)\bin\javac.exe")
        } | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

# ---------------------------------------------------------------- JDK 25
if (-not $Jdk25) {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\release")) {
        $rel = Get-Content "$env:JAVA_HOME\release" -Raw
        if ($rel -match 'JAVA_VERSION="25\.') { $Jdk25 = $env:JAVA_HOME }
    }
}
if (-not $Jdk25) { $Jdk25 = Find-Jdk @(25) }
if (-not $Jdk25 -or -not (Test-Path "$Jdk25\bin\javac.exe")) {
    throw @"
找不到 JDK 25。MC 26.2 的编译目标就是 Java 25，必须有它。
  下载: https://adoptium.net/temurin/releases/?version=25
  或传参: gradle\build.ps1 -Jdk25 'C:\jdk-25'
"@
}
if ($Jdk25 -match '[^\x00-\x7F]') {
    Write-Warning "JDK 25 路径含非 ASCII 字符（$Jdk25）。已知该 JDK 版本在非 ASCII 路径下会拒绝所有文件写入，建议换到纯英文路径。"
}

# ---------------------------------------------------------------- Gradle
# 优先用本机已安装的 Gradle（wrapper 需要联网下载发行包，受限网络下会超时）
$gradle = (Get-Command gradle -ErrorAction SilentlyContinue).Source
if (-not $gradle -and $env:GRADLE_HOME) {
    $candidate = Join-Path $env:GRADLE_HOME 'bin\gradle.bat'
    if (Test-Path $candidate) { $gradle = $candidate }
}
if (-not $gradle) {
    # 常见自定义位置：<工程>\.toolchain\gradle-*\bin\gradle.bat，或 <父目录>\.toolchain\gradle[-\*]
    $probes = @(
        (Join-Path $root '.toolchain'),
        (Join-Path (Split-Path -Parent $root) '.toolchain')
    )
    foreach ($probe in $probes) {
        if (-not (Test-Path $probe)) { continue }
        $hits = @()
        $hits += Get-ChildItem $probe -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'gradle-*' -or $_.Name -eq 'gradle' }
        foreach ($h in $hits) {
            $bat = Join-Path $h.FullName 'bin\gradle.bat'
            if (Test-Path $bat) { $gradle = $bat; break }
        }
        if ($gradle) { break }
    }
}
if (-not $gradle -and (Test-Path '.\gradlew.bat')) { $gradle = '.\gradlew.bat' }
if (-not $gradle) {
    throw '找不到 Gradle（NeoForge 26.2 的 ModDevGradle 需要 Gradle 8.14+）。装 Gradle 9.x，或先用 .\gradlew.bat build。'
}

# Gradle 本体用 JDK 21 跑；JDK 21 找不到就直接用 25
$gradleJdk = Find-Jdk @(21, 22)
if ($gradleJdk) {
    $env:JAVA_HOME = $gradleJdk
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}

# ---------------------------------------------------------------- 本机修补（可选）
function Find-Node {
    $n = (Get-Command node -ErrorAction SilentlyContinue).Source
    if ($n) { return $n }
    foreach ($guess in @(
            'C:\Program Files\nodejs\node.exe',
            "${env:ProgramFiles(x86)}\nodejs\node.exe",
            "$env:LOCALAPPDATA\nvs\node.exe",
            "$env:LOCALAPPDATA\Programs\nodejs\node.exe")) {
        if (Test-Path $guess) { return $guess }
    }
    # DSH / 其它随包带的 node
    $hit = Get-ChildItem "$env:USERPROFILE\.dsh" -Recurse -Filter 'node.exe' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}

$usePatch = $Offline -or (Test-Path '.build-tls\truststore.p12')
if (-not $Plain -and $usePatch) {
    # Gradle 用户目录放在工程内，避开 ~/.gradle/caches/neoformruntime 的锁文件问题
    $env:GRADLE_USER_HOME = Join-Path $root '.gradle-home'
    $env:GRADLE_OPTS = "-Djavax.net.ssl.trustStore=$root\.build-tls\truststore.p12 " +
                       "-Djavax.net.ssl.trustStorePassword=changeit -Djava.net.preferIPv4Stack=true"

    $node = Find-Node

    $listening = (Test-NetConnection -ComputerName 127.0.0.1 -Port 443 -WarningAction SilentlyContinue).TcpTestSucceeded
    if (-not $listening) {
        if ($node) {
            Write-Host '== 启动本地 NeoForge Maven 代理 ==' -ForegroundColor Cyan
            Start-Process -FilePath $node -ArgumentList 'tools\neoforge-mirror-proxy.mjs --port 443' `
                -WorkingDirectory $root -WindowStyle Hidden
            for ($i = 0; $i -lt 20; $i++) {
                Start-Sleep -Milliseconds 500
                if ((Test-NetConnection -ComputerName 127.0.0.1 -Port 443 -WarningAction SilentlyContinue).TcpTestSucceeded) { break }
            }
        } else {
            Write-Warning '找不到 node.exe，本地代理未启动（构建很可能因为连不上 maven.neoforged.net 而失败）'
        }
    }

    if (-not (Test-Path '.build-mcdeps\net\neoforged\minecraft-dependencies')) {
        if ($node) {
            Write-Host '== 生成 minecraft-dependencies ==' -ForegroundColor Cyan
            & $node tools\gen-mc-deps.mjs 26.2 .build-mcdeps
            if ($LASTEXITCODE -ne 0) { Write-Warning 'minecraft-dependencies 生成失败' }
        } else {
            Write-Warning '找不到 node.exe，无法生成 minecraft-dependencies（构建会因缺少该构件而失败）'
        }
    }
}

# ---------------------------------------------------------------- 构建
Write-Host "== gradle $Task ==" -ForegroundColor Cyan
Write-Host "   JDK25: $Jdk25"
& $gradle $Task --console=plain "-Porg.gradle.java.installations.paths=$Jdk25"
exit $LASTEXITCODE
