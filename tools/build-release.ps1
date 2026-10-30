param(
    [string]$JavaHome = 'D:\JDK',
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$signingDirectory = Join-Path $projectRoot '.signing'
$keyPath = Join-Path $signingDirectory 'release.p12'
$credentialPath = Join-Path $signingDirectory 'credentials.xml'
$java = Join-Path $JavaHome 'bin\java.exe'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'
$signerJar = Join-Path $SdkRoot 'build-tools\37.0.0\lib\apksigner.jar'
$originalSecret = $env:DRIVEDROID_KEY_PASSWORD

New-Item -ItemType Directory -Force -Path $signingDirectory | Out-Null
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetOwner($identity.User)
$acl.SetAccessRuleProtection($true, $false)
$rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
    $identity.User,
    'FullControl',
    'ContainerInherit,ObjectInherit',
    'None',
    'Allow'
)
$acl.AddAccessRule($rule)
$existingAcl = Get-Acl -LiteralPath $signingDirectory
$existingRules = @($existingAcl.Access)
$alreadyPrivate = $existingAcl.AreAccessRulesProtected -and $existingRules.Count -eq 1
if ($alreadyPrivate) {
    $existingIdentity = $existingRules[0].IdentityReference.Translate([System.Security.Principal.SecurityIdentifier])
    $alreadyPrivate = $existingIdentity.Value -eq $identity.User.Value `
        -and $existingRules[0].AccessControlType -eq 'Allow' `
        -and $existingRules[0].FileSystemRights -eq 'FullControl'
}
if (-not $alreadyPrivate) {
    Set-Acl -LiteralPath $signingDirectory -AclObject $acl
}

try {
    if (Test-Path -LiteralPath $credentialPath) {
        $credential = Import-Clixml -LiteralPath $credentialPath
    } else {
        if (Test-Path -LiteralPath $keyPath) {
            throw 'Signing key exists but its credential is missing. Restore the credential; do not replace the key.'
        }
        $randomBytes = New-Object byte[] 32
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $random.GetBytes($randomBytes)
        } finally {
            $random.Dispose()
        }
        $secret = [Convert]::ToBase64String($randomBytes)
        $secureSecret = ConvertTo-SecureString -String $secret -AsPlainText -Force
        $credential = [System.Management.Automation.PSCredential]::new('drivedroid-release', $secureSecret)
        $credential | Export-Clixml -LiteralPath $credentialPath
        $secret = $null
    }
    $env:DRIVEDROID_KEY_PASSWORD = $credential.GetNetworkCredential().Password
    if (-not (Test-Path -LiteralPath $keyPath)) {
        & $keytool -genkeypair -noprompt -keystore $keyPath -storetype PKCS12 `
            -alias drivedroid-release -keyalg RSA -keysize 3072 -validity 10000 `
            -dname 'CN=DriveDroid Reborn Release, O=DriveDroid Reborn' `
            -storepass:env DRIVEDROID_KEY_PASSWORD -keypass:env DRIVEDROID_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) {
            throw 'Release key generation failed.'
        }
    }

    Push-Location $projectRoot
    try {
        $jvmProxy = @()
        if ($env:HTTPS_PROXY) {
            $proxyUri = [Uri]$env:HTTPS_PROXY
            $jvmProxy = @(
                "-Dhttps.proxyHost=$($proxyUri.Host)",
                "-Dhttps.proxyPort=$($proxyUri.Port)",
                "-Dhttp.proxyHost=$($proxyUri.Host)",
                "-Dhttp.proxyPort=$($proxyUri.Port)"
            )
        }
        & $java @jvmProxy -classpath 'gradle\wrapper\gradle-wrapper.jar' `
            org.gradle.wrapper.GradleWrapperMain :app:assembleRelease --no-configuration-cache --console plain
        if ($LASTEXITCODE -ne 0) {
            throw 'Release build failed.'
        }
        $metadata = Get-Content -LiteralPath 'app\build\outputs\apk\release\output-metadata.json' -Raw | ConvertFrom-Json
        $version = $metadata.elements[0].versionName
        $releaseDirectory = Join-Path $projectRoot 'releases'
        New-Item -ItemType Directory -Force -Path $releaseDirectory | Out-Null
        $destination = Join-Path $releaseDirectory "DriveDroid-Reborn-$version-release.apk"
        if (Test-Path -LiteralPath $destination) {
            throw 'Versioned release APK already exists. Increase versionName before creating a new release.'
        }
        $unsignedApk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
        & $java -jar $signerJar sign --ks $keyPath --ks-key-alias drivedroid-release `
            --ks-pass env:DRIVEDROID_KEY_PASSWORD --key-pass env:DRIVEDROID_KEY_PASSWORD `
            --out $destination $unsignedApk
        if ($LASTEXITCODE -ne 0) {
            throw 'Release signing failed.'
        }
        & $java -jar $signerJar verify --verbose --print-certs $destination
        if ($LASTEXITCODE -ne 0) {
            throw 'Release signature verification failed.'
        }
        Get-FileHash -Algorithm SHA256 -LiteralPath $destination
    } finally {
        Pop-Location
    }
} finally {
    [Environment]::SetEnvironmentVariable('DRIVEDROID_KEY_PASSWORD', $originalSecret, 'Process')
}
