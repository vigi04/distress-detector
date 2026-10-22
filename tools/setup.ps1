param(
    [Parameter(Mandatory = $true)]
    [string] $ProjectRoot
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$localConfig = Join-Path $ProjectRoot '.local-toolchain.mk'

function Get-FirstExistingDirectory([string[]] $Candidates) {
    foreach ($candidate in $Candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Get-JavaHome {
    $homes = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) { $homes.Add($env:JAVA_HOME) }
    $homes.Add((Join-Path $env:LOCALAPPDATA 'Programs\Temurin\jdk-17.0.20.1+1'))

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $javaPath = if ($javaCommand.Source) { $javaCommand.Source } else { $javaCommand.Definition }
        if ($javaPath -match '^(.*?)[\\/]bin[\\/]java\.exe$') { $homes.Add($Matches[1]) }
    }

    foreach ($registeredPath in @(
        [Environment]::GetEnvironmentVariable('Path', 'User'),
        [Environment]::GetEnvironmentVariable('Path', 'Machine')
    )) {
        foreach ($entry in ($registeredPath -split ';')) {
            $entry = $entry.Trim().Trim('"')
            if ($entry -match '^(.*?)[\\/]bin$') {
                $homes.Add($Matches[1])
            }
        }
    }

    $registryRoots = @(
        'HKLM:\SOFTWARE\JavaSoft',
        'HKLM:\SOFTWARE\Eclipse Adoptium',
        'HKLM:\SOFTWARE\Microsoft\JDK',
        'HKLM:\SOFTWARE\Amazon Corretto',
        'HKLM:\SOFTWARE\Azul Systems\Zulu'
    )
    foreach ($registryRoot in $registryRoots) {
        if (Test-Path $registryRoot) {
            foreach ($key in (Get-ChildItem $registryRoot -Recurse -ErrorAction SilentlyContinue)) {
                $properties = Get-ItemProperty $key.PSPath -ErrorAction SilentlyContinue
                foreach ($propertyName in @('JavaHome', 'InstallLocation', 'Path')) {
                    $value = $properties.$propertyName
                    if ($value -is [string] -and $value) { $homes.Add($value) }
                }
            }
        }
    }

    $directoryPatterns = @(
        "$env:ProgramFiles\Java\*",
        "$env:ProgramFiles\Eclipse Adoptium\*",
        "$env:LOCALAPPDATA\Programs\Temurin\jdk-*",
        "$env:ProgramFiles\Temurin\jdk-*",
        "$env:ProgramFiles\Microsoft\jdk-*",
        "$env:ProgramFiles\Amazon Corretto\*",
        "$env:ProgramFiles\Zulu\*",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\*",
        "$env:LOCALAPPDATA\Programs\Microsoft\jdk-*",
        "$env:LOCALAPPDATA\Programs\Amazon Corretto\*",
        "$env:LOCALAPPDATA\Programs\Zulu\*",
        "$env:USERPROFILE\.jdks\*"
    )
    foreach ($pattern in $directoryPatterns) {
        foreach ($directory in (Get-ChildItem $pattern -Directory -ErrorAction SilentlyContinue)) {
            $homes.Add($directory.FullName)
        }
    }

    foreach ($javaHomeCandidate in $homes) {
        $javaExe = Join-Path $javaHomeCandidate 'bin\java.exe'
        if (Test-Path -LiteralPath $javaExe -PathType Leaf) { return (Resolve-Path -LiteralPath $javaHomeCandidate).Path }
    }
    return $null
}

$localProperties = Join-Path $ProjectRoot 'local.properties'
$sdkFromProject = $null
if (Test-Path $localProperties) {
    $sdkLine = Get-Content $localProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
    if ($sdkLine) { $sdkFromProject = ($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '').Trim().Replace('/', '\') }
}
$sdkHome = Get-FirstExistingDirectory @(
    $env:ANDROID_HOME,
    $env:ANDROID_SDK_ROOT,
    $sdkFromProject,
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
)

$gradleCandidates = @(
    (Join-Path $env:LOCALAPPDATA 'Programs\Gradle\gradle-8.13\bin\gradle.bat'),
    $(if ($env:GRADLE_HOME) { Join-Path $env:GRADLE_HOME 'bin\gradle.bat' }),
    $(if (Get-Command gradle.bat -ErrorAction SilentlyContinue) { (Get-Command gradle.bat).Source })
)
$gradlePath = $gradleCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -First 1
$javaHome = Get-JavaHome

function Convert-ToMakePath([string] $Value) {
    return $Value.Replace('\', '/')
}

$configLines = @()
if ($javaHome) { $configLines += "JAVA_HOME := $(Convert-ToMakePath $javaHome)" } else { $configLines += 'JAVA_HOME :=' }
if ($sdkHome) { $configLines += "SDK := $(Convert-ToMakePath $sdkHome)" }
if ($gradlePath) { $configLines += "GRADLE := `"$(Convert-ToMakePath $gradlePath)`"" }
Set-Content -LiteralPath $localConfig -Value $configLines -Encoding ASCII

Write-Host "Saved local tool paths to $localConfig"
if ($sdkHome) { Write-Host "Android SDK: $sdkHome" } else { Write-Error 'Android SDK was not found. Set ANDROID_HOME or correct local.properties.' }
if ($gradlePath) { Write-Host "Gradle: $gradlePath" } else { Write-Error 'Gradle was not found. Install Gradle 8.13 or set GRADLE_HOME.' }
if ($javaHome) {
    Write-Host "Java: $javaHome"
} else {
    Write-Error 'JDK was not found. Install JDK 17, then rerun make setup; it will save JAVA_HOME even if PATH is reset.'
}

$androidUserHome = Join-Path $ProjectRoot '.android-user'
$androidSdkHome = Join-Path $ProjectRoot '.android-sdk-home'
$projectTemp = Join-Path $ProjectRoot '.tmp'
New-Item -ItemType Directory -Path $androidUserHome, $androidSdkHome, $projectTemp -Force | Out-Null

if (-not $sdkHome -or -not $gradlePath -or -not $javaHome) { exit 1 }

Write-Host 'Tool paths are configured for subsequent Make targets.'
