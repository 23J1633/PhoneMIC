[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._-]+$')][string]$HostAlias = 'ovita4090',
    [ValidatePattern('^/home/dong/projects/[A-Za-z0-9/_-]+$')][string]$RemoteRoot = '/home/dong/projects/phonemic'
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Push-Location -LiteralPath $projectRoot
try {
    New-Item -ItemType Directory -Force -Path .cache,artifacts,.signing | Out-Null
    & node scripts/fetch-speech.mjs
    if ($LASTEXITCODE -ne 0) { throw 'Speech assets download failed' }
    & tar.exe --exclude=.cache --exclude=node_modules --exclude=.git --exclude=ios --exclude=artifacts --exclude=.tools --exclude=.signing --exclude=android/.gradle --exclude=android/app/build --exclude=android/build '--exclude=*.pending' -czf .cache/source.tar.gz .
    if ($LASTEXITCODE -ne 0) { throw 'Source archive failed' }
    & ssh -T $HostAlias "mkdir -p '$RemoteRoot/.cache'"
    if ($LASTEXITCODE -ne 0) { throw 'Remote connection failed' }
    & scp .cache/source.tar.gz "${HostAlias}:$RemoteRoot/.cache/source.tar.gz"
    if ($LASTEXITCODE -ne 0) { throw 'Source upload failed' }
    & ssh -T $HostAlias "cd '$RemoteRoot' && tar -xzf .cache/source.tar.gz && bash scripts/bootstrap-ovita.sh && bash scripts/build-ovita.sh"
    if ($LASTEXITCODE -ne 0) { throw 'Remote build failed' }
    foreach ($artifactPath in @('artifacts/PhoneMIC-1.0.0-arm64.apk','artifacts/PhoneMIC-Desktop.exe','artifacts/SHA256SUMS.txt','artifacts/models.json','.signing/release.keystore','.signing/key.properties')) {
        $localDestination = if ($artifactPath -eq 'artifacts/PhoneMIC-Desktop.exe') { '.cache/PhoneMIC-Desktop.download.exe' } elseif ($artifactPath.StartsWith('.signing/')) { '.signing/' } else { 'artifacts/' }
        & scp -o ServerAliveInterval=15 -o ServerAliveCountMax=3 "${HostAlias}:$RemoteRoot/$artifactPath" $localDestination
        if ($LASTEXITCODE -ne 0) { throw "Artifact download failed: $artifactPath" }
    }
    foreach ($name in @('PhoneMIC-1.0.0-arm64.apk','PhoneMIC-Desktop.exe')) {
        $checksumLine = Get-Content -LiteralPath artifacts/SHA256SUMS.txt | Where-Object { $_.EndsWith("artifacts/$name") }
        $expectedHash = ($checksumLine -split '\s+')[0]
        $downloadPath = if ($name -eq 'PhoneMIC-Desktop.exe') { '.cache/PhoneMIC-Desktop.download.exe' } else { "artifacts/$name" }
        $actualHash = (Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $expectedHash) { throw "Artifact checksum mismatch: $name" }
    }
    $receiverPath = Join-Path $projectRoot 'artifacts/PhoneMIC-Desktop.exe'
    $receivers = @(Get-CimInstance Win32_Process -Filter "Name='PhoneMIC-Desktop.exe'" | Where-Object { $_.ExecutablePath -eq $receiverPath })
    foreach ($receiver in $receivers) {
        Stop-Process -Id $receiver.ProcessId -ErrorAction Stop
        Wait-Process -Id $receiver.ProcessId -Timeout 5 -ErrorAction SilentlyContinue
    }
    try { Copy-Item -LiteralPath '.cache/PhoneMIC-Desktop.download.exe' -Destination $receiverPath -Force }
    finally { if ($receivers.Count -gt 0) { Start-Process -FilePath $receiverPath -WindowStyle Hidden } }
    Write-Host 'Build complete. Artifacts are in the artifacts folder.'
} finally { Pop-Location }
