#Requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Generate', 'Restore', 'BuildRelease')][string]$Action,
    [string]$LocalDirectory = '.private/apk-signing'
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$privateRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '.private'))
$privateDirectory = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $LocalDirectory))
if (!$privateDirectory.StartsWith($privateRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'LocalDirectory must be a directory inside this repository .private directory.'
}
$configurationPath = Join-Path $privateDirectory 'signing.local.json'
$keystorePath = Join-Path $privateDirectory 'carplay-release.p12'
$backupPath = Join-Path $repositoryRoot 'signing/carplay-release.p12.enc'
$certificatePath = Join-Path $repositoryRoot 'signing/carplay-release.cer'
$environmentPath = Join-Path $repositoryRoot 'agent_docs/build-environment.local.json'
$associatedData = [Text.Encoding]::UTF8.GetBytes('CarPlay-Preface APK signing backup v1')

function Get-Keytool {
    if (Test-Path -LiteralPath $environmentPath) {
        $buildEnvironment = Get-Content -LiteralPath $environmentPath -Raw | ConvertFrom-Json
        $executable = Join-Path $buildEnvironment.javaHome ('bin/keytool' + $(if ($IsWindows) { '.exe' } else { '' }))
        if (Test-Path -LiteralPath $executable) { return $executable }
    }
    $available = Get-Command keytool -ErrorAction SilentlyContinue
    if (!$available) { throw 'JDK keytool is missing. Provide an installed JDK and cache its path first.' }
    return $available.Source
}

function New-PrivateDirectory {
    New-Item -ItemType Directory -Path $privateDirectory -Force | Out-Null
    if ($IsWindows) {
        $ownerSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls.exe $privateDirectory /inheritance:r /grant:r ('*' + $ownerSid + ':(OI)(CI)F') | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Unable to restrict access to the local signing directory.' }
    }
}

function Write-Json([string]$Path, $Value) {
    $text = ($Value | ConvertTo-Json).Replace("`r`n", "`n") + "`n"
    [IO.File]::WriteAllText($Path, $text, [Text.UTF8Encoding]::new($false))
}

function Save-Configuration($material, [string]$backupPassword) {
    Write-Json $configurationPath ([ordered]@{
        keystorePath = $keystorePath
        keystoreType = 'PKCS12'
        keyAlias = $material.keyAlias
        storePassword = $material.storePassword
        keyPassword = $material.keyPassword
        backupPassword = $backupPassword
    })
}

function Invoke-Keytool([string[]]$Arguments, [string]$Password) {
    $previous = $env:CARPLAY_SIGNING_STORE_PASSWORD
    try {
        $env:CARPLAY_SIGNING_STORE_PASSWORD = $Password
        & (Get-Keytool) @Arguments -storepass:env CARPLAY_SIGNING_STORE_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'keytool failed.' }
    } finally {
        $env:CARPLAY_SIGNING_STORE_PASSWORD = $previous
    }
}

switch ($Action) {
    'Generate' {
        if ((Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $configurationPath) -or
            (Test-Path -LiteralPath $backupPath) -or (Test-Path -LiteralPath $certificatePath)) {
            throw 'Signing material already exists. Restore or reuse it; do not replace a released signing key.'
        }
        New-PrivateDirectory
        New-Item -ItemType Directory -Path (Split-Path -Parent $backupPath) -Force | Out-Null
        $storePassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $backupPassword = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $material = [ordered]@{ keyAlias = 'carplay-release'; storePassword = $storePassword; keyPassword = $storePassword }
        # Persist recovery information before invoking a tool that creates a long-lived key.
        Save-Configuration $material $backupPassword
        Invoke-Keytool @('-genkeypair', '-keystore', $keystorePath, '-storetype', 'PKCS12',
            '-alias', $material.keyAlias, '-keyalg', 'RSA', '-keysize', '4096', '-sigalg', 'SHA256withRSA',
            '-validity', '10000', '-dname', 'CN=CarPlay-Preface,OU=Android,O=CarPlay-Preface', '-noprompt') $storePassword
        Invoke-Keytool @('-exportcert', '-keystore', $keystorePath, '-alias', $material.keyAlias,
            '-file', $certificatePath) $storePassword
        $material.keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))
        $plain = [Text.Encoding]::UTF8.GetBytes(($material | ConvertTo-Json -Compress))
        $salt = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
        $nonce = [Security.Cryptography.RandomNumberGenerator]::GetBytes(12)
        $key = [Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2($backupPassword, $salt, 600000,
            [Security.Cryptography.HashAlgorithmName]::SHA256, 32)
        $ciphertext = [byte[]]::new($plain.Length)
        $tag = [byte[]]::new(16)
        $aes = [Security.Cryptography.AesGcm]::new($key, 16)
        try { $aes.Encrypt($nonce, $plain, $ciphertext, $tag, $associatedData) }
        finally { $aes.Dispose(); [Array]::Clear($plain); [Array]::Clear($key) }
        Write-Json $backupPath ([ordered]@{
            schemaVersion = 1; algorithm = 'AES-256-GCM'; kdf = 'PBKDF2-HMAC-SHA256'; iterations = 600000
            salt = [Convert]::ToBase64String($salt); nonce = [Convert]::ToBase64String($nonce)
            tag = [Convert]::ToBase64String($tag); ciphertext = [Convert]::ToBase64String($ciphertext)
        })
        $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new([IO.File]::ReadAllBytes($certificatePath))
        try {
            Write-Json (Join-Path $repositoryRoot 'signing/key-info.json') ([ordered]@{
                keyAlias = $material.keyAlias; keystoreType = 'PKCS12'; keyAlgorithm = 'RSA'; keySize = 4096
                certificateSha256 = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($certificate.RawData)).ToLowerInvariant()
                subject = $certificate.Subject; validFromUtc = $certificate.NotBefore.ToUniversalTime().ToString('o')
                validUntilUtc = $certificate.NotAfter.ToUniversalTime().ToString('o')
            })
        } finally { $certificate.Dispose() }
        Write-Output 'Signing key generated. Public backup and certificate are in signing/; passwords remain in .private/apk-signing/signing.local.json.'
    }
    'Restore' {
        if ((Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $configurationPath)) {
            throw 'Local signing material already exists; refusing to overwrite it.'
        }
        $backupPassword = $env:CARPLAY_SIGNING_BACKUP_PASSWORD
        if (!$backupPassword) {
            $securePassword = Read-Host 'Signing backup password' -AsSecureString
            $backupPassword = [Net.NetworkCredential]::new('', $securePassword).Password
        }
        $backup = Get-Content -LiteralPath $backupPath -Raw | ConvertFrom-Json
        if ($backup.schemaVersion -ne 1 -or $backup.algorithm -ne 'AES-256-GCM' -or
            $backup.kdf -ne 'PBKDF2-HMAC-SHA256' -or $backup.iterations -ne 600000) { throw 'Unsupported signing backup format.' }
        $salt = [Convert]::FromBase64String($backup.salt)
        $key = [Security.Cryptography.Rfc2898DeriveBytes]::Pbkdf2($backupPassword, $salt, $backup.iterations,
            [Security.Cryptography.HashAlgorithmName]::SHA256, 32)
        $ciphertext = [Convert]::FromBase64String($backup.ciphertext)
        $plain = [byte[]]::new($ciphertext.Length)
        $aes = [Security.Cryptography.AesGcm]::new($key, 16)
        try {
            $aes.Decrypt([Convert]::FromBase64String($backup.nonce), $ciphertext,
                [Convert]::FromBase64String($backup.tag), $plain, $associatedData)
            $material = [Text.Encoding]::UTF8.GetString($plain) | ConvertFrom-Json
        } finally { $aes.Dispose(); [Array]::Clear($plain); [Array]::Clear($key) }
        New-PrivateDirectory
        [IO.File]::WriteAllBytes($keystorePath, [Convert]::FromBase64String($material.keystoreBase64))
        Save-Configuration $material $backupPassword
        Invoke-Keytool @('-list', '-keystore', $keystorePath, '-alias', $material.keyAlias) $material.storePassword
        Write-Output 'Signing backup restored to the local private directory.'
    }
    'BuildRelease' {
        $configuration = Get-Content -LiteralPath $configurationPath -Raw | ConvertFrom-Json
        $variables = @{
            ANDROID_KEYSTORE_PATH = $configuration.keystorePath; ANDROID_KEYSTORE_PASSWORD = $configuration.storePassword
            ANDROID_KEY_ALIAS = $configuration.keyAlias; ANDROID_KEY_PASSWORD = $configuration.keyPassword
        }
        if (Test-Path -LiteralPath $environmentPath) {
            $buildEnvironment = Get-Content -LiteralPath $environmentPath -Raw | ConvertFrom-Json
            $variables.JAVA_HOME = $buildEnvironment.javaHome; $variables.ANDROID_HOME = $buildEnvironment.androidHome
            $variables.JAVA_OPTS = $buildEnvironment.javaOptions -join ' '
            $variables.TEMP = $buildEnvironment.tempDir; $variables.TMP = $buildEnvironment.tempDir
        }
        $previous = @{}
        Push-Location $repositoryRoot
        try {
            foreach ($name in $variables.Keys) {
                $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
                [Environment]::SetEnvironmentVariable($name, $variables[$name], 'Process')
            }
            & $(if ($IsWindows) { '.\gradlew.bat' } else { './gradlew' }) :mobile:assembleRelease
            if ($LASTEXITCODE -ne 0) { throw 'Release APK build failed.' }
        } finally {
            foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
            Pop-Location
        }
    }
}
