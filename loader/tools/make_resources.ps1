param()
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$root = "$PSScriptRoot\.."
$src  = "$root\assets\logo.png"
$ico  = "$root\assets\logo.ico"
$rc   = "$root\src\resources.rc"
$rh   = "$root\src\resource.h"

# ── 1. PNG → ICO (multi-size: 256,64,48,32,16) ────────────────────────
Add-Type -AssemblyName System.Drawing

function Write-Ico {
    param([System.Drawing.Bitmap[]]$bitmaps, [string]$path)
    $ms = [System.IO.MemoryStream]::new()
    $bw = [System.IO.BinaryWriter]::new($ms)
    $bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$bitmaps.Count)
    $blobs = $bitmaps | ForEach-Object {
        $t = [System.IO.MemoryStream]::new()
        $_.Save($t, [System.Drawing.Imaging.ImageFormat]::Png)
        ,$t.ToArray()
    }
    $offset = 6 + $bitmaps.Count * 16
    for ($i = 0; $i -lt $bitmaps.Count; $i++) {
        $b = $bitmaps[$i]; $d = $blobs[$i]
        $sz = if ($b.Width -ge 256) { [byte]0 } else { [byte]$b.Width }
        $bw.Write($sz); $bw.Write($sz)
        $bw.Write([byte]0); $bw.Write([byte]0)
        $bw.Write([uint16]0); $bw.Write([uint16]32)
        $bw.Write([uint32]$d.Length); $bw.Write([uint32]$offset)
        $offset += $d.Length
    }
    $blobs | ForEach-Object { $bw.Write($_) }
    $bw.Flush()
    [System.IO.File]::WriteAllBytes($path, $ms.ToArray())
}

$png    = [System.Drawing.Image]::FromFile((Resolve-Path $src).Path)
$bmps   = @(256,64,48,32,16) | ForEach-Object { [System.Drawing.Bitmap]::new($png, $_, $_) }
Write-Ico $bmps $ico
$png.Dispose(); $bmps | ForEach-Object { $_.Dispose() }
Write-Host "  ico  -> $ico  ($((Get-Item $ico).Length) bytes)"

# ── 2. resource.h ─────────────────────────────────────────────────────
@"
#pragma once
#define IDI_APPICON  101
"@ | Set-Content $rh -Encoding UTF8
Write-Host "  rh   -> $rh"

# ── 3. resources.rc ───────────────────────────────────────────────────
@"
#include "resource.h"
IDI_APPICON ICON "assets/logo.ico"
"@ | Set-Content $rc -Encoding UTF8
Write-Host "  rc   -> $rc"

Write-Host "`nDone. Run cmake --preset windows-clang to pick up the new files."
