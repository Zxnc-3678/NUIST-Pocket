param([switch]$Check, [switch]$Release)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$bundledJdkRoot = Join-Path $projectRoot '.tools\jdk21'
$jdkFolder = Get-ChildItem -LiteralPath $bundledJdkRoot -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($jdkFolder) { $env:JAVA_HOME = $jdkFolder.FullName }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\java.exe")) {
    throw 'Set JAVA_HOME to JDK 17 or 21, or install the project JDK in .tools/jdk21.'
}
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-home'
if (-not $env:ANDROID_HOME) {
    if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
    elseif ($env:LOCALAPPDATA) {
        $defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        if (Test-Path -LiteralPath $defaultSdk) { $env:ANDROID_HOME = $defaultSdk }
    }
    # Otherwise Gradle reads the developer's untracked local.properties.
}
New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot '.gradle') | Out-Null
('java.home=' + $env:JAVA_HOME.Replace('\', '/').Replace(':', '\:')) | Set-Content -LiteralPath (Join-Path $projectRoot '.gradle/config.properties') -Encoding utf8
Push-Location $projectRoot
try {
    if ($Check) {
        if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
            throw 'Node.js is required for the WebView script checks. Install Node.js, or omit -Check to build only.'
        }
        $scriptTests = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'scripts/tests') -Filter '*.test.cjs' | ForEach-Object FullName)
        & node --test @scriptTests
        if ($LASTEXITCODE -ne 0) { throw 'WebView script checks failed.' }
    }
    $variant = if ($Release) { 'Release' } else { 'Debug' }
    [string[]]$tasks = if ($Check) { @("test${variant}UnitTest", "lint$variant", "assemble$variant") } else { @("assemble$variant") }
    & '.\gradlew.bat' @tasks --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Build failed ($LASTEXITCODE)" }
} finally { Pop-Location }
