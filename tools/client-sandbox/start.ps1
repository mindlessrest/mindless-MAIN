$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$url = 'http://127.0.0.1:8766/tools/client-sandbox/'

Set-Location $repoRoot
Start-Process $url

if (Get-Command py -ErrorAction SilentlyContinue) {
    py -3 -m http.server 8766 --bind 127.0.0.1
} else {
    python -m http.server 8766 --bind 127.0.0.1
}
