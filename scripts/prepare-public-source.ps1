param(
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot ('.opensource/aye-aye-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$outputRoot = [System.IO.Path]::GetFullPath($OutputDirectory)
$exportParent = [System.IO.Path]::GetFullPath((Join-Path $repoRoot '.opensource'))
if (!$outputRoot.StartsWith($exportParent + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output must be a new directory inside this repository''s .opensource directory.'
}
if (Test-Path -LiteralPath $outputRoot) {
    throw 'Output directory already exists. Choose a new directory; existing exports are never overwritten.'
}

Push-Location $repoRoot
try {
    # Include local edits and new source files without importing any Git history.
    $files = @(git -c core.quotepath=false ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read repository file list.' }
    $files = @($files | Sort-Object -Unique | Where-Object {
        Test-Path -LiteralPath (Join-Path $repoRoot $_) -PathType Leaf
    })
    $findings = @()
    $textPattern = '-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----|sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16}'
    foreach ($file in $files) {
        if ($file -match '(^|/)(\.git|\.agents|\.codex|\.aws|\.aye[^/]*|node_modules|target|dist|out|\.opensource)(/|$)' -or
            ($file -match '(^|/)\.env($|\.)' -and $file -notmatch '\.env\.example$') -or
            $file -match '(^|/)(\.master_key|credentials\.json)$' -or
            $file -match '\.(db|db-wal|db-shm|pem|key|p12|pfx|log|jar|zip|tgz|gz|7z|exe|dll)$' -or
            ((Get-Item -LiteralPath (Join-Path $repoRoot $file)).Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
            $findings += "$file [excluded data or credential file]"
            continue
        }
        if ($file -notmatch '\.(png|ico|icns|jpg|jpeg|gif|webp)$') {
            $content = [System.IO.File]::ReadAllText((Join-Path $repoRoot $file))
            if ($content -match $textPattern) { $findings += "$file [possible secret]" }
        }
    }
    if ($findings.Count -gt 0) {
        $findings | ForEach-Object { Write-Output $_ }
        throw 'Resolve flagged files before creating a public source export. Secret values are not printed.'
    }

    foreach ($file in $files) {
        $source = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $file))
        $destination = [System.IO.Path]::GetFullPath((Join-Path $outputRoot $file))
        if (!$source.StartsWith($repoRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
            !$destination.StartsWith($outputRoot + [System.IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'A file path escapes the source or output directory.'
        }
        New-Item -ItemType Directory -Path ([System.IO.Path]::GetDirectoryName($destination)) -Force | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination
    }
    Write-Output "Prepared $($files.Count) files at $outputRoot"
    Write-Output 'No Git history included. This automated check does not replace manual review or credential rotation.'
} finally {
    Pop-Location
}
