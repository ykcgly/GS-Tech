# GS (GouSiJiYi) -- local build script. javac + jar only, no Gradle, no network.
#
# WHY NO GRADLE: this machine has no Gradle cache, so the wrapper would first have to
# download a 130 MB distribution plus every dependency. Everything needed at compile
# time (paper-api / Slimefun4 / Guava / GraalJS) already exists as jars on this server.
# A Gradle project can be added later for other people to build from source.
#
# WHY THIS FILE IS PURE ASCII: Windows PowerShell 5.1 reads .ps1 files using the system
# ANSI code page (GBK here) unless the file starts with a UTF-8 BOM. Any non-ASCII byte
# in a comment then corrupts parsing. Keeping this file ASCII removes that failure mode
# entirely. The Chinese rationale for the build lives in ../BUILD-NOTES.md instead.
#
# Two javac quirks this script works around:
#   1) @argfile paths are resolved against the WORKING DIRECTORY and read with the
#      platform default encoding -- so we pass paths RELATIVE to the project root,
#      because the project path itself contains CJK characters.
#   2) javac diagnostics are printed in the system code page (GBK) and come out as
#      mojibake. JAVA_TOOL_OPTIONS forces English, but the JVM then writes a
#      "Picked up JAVA_TOOL_OPTIONS" line to stderr, which ErrorActionPreference=Stop
#      would treat as a fatal NativeCommandError -- hence the temporary relaxation.
#
# USAGE:  powershell -ExecutionPolicy Bypass -File build.ps1 [-Clean]
#     or:  build.cmd
[CmdletBinding()]
param([switch]$Clean)

$ErrorActionPreference = 'Stop'

# In PS 5.1, (empty result).FullName throws; always coerce to an array and expand.
function Get-Jars([string]$dir) {
    if (-not (Test-Path -LiteralPath $dir)) { return @() }
    return @(Get-ChildItem -LiteralPath $dir -Filter *.jar -File -ErrorAction SilentlyContinue |
             Select-Object -ExpandProperty FullName)
}

$Root = $PSScriptRoot
if (-not $Root) { $Root = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $Root) { throw 'cannot determine script root' }
Set-Location -LiteralPath $Root
$RootLen = $Root.Length

$JavaHome = 'C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'
$Javac = Join-Path $JavaHome 'bin\javac.exe'
$JarExe = Join-Path $JavaHome 'bin\jar.exe'
foreach ($exe in @($Javac, $JarExe)) {
    if (-not (Test-Path -LiteralPath $exe)) { throw "not found: $exe" }
}

if ($Clean -and (Test-Path -LiteralPath 'build')) {
    Remove-Item -LiteralPath 'build' -Recurse -Force
}
New-Item -ItemType Directory -Path 'build\classes' -Force | Out-Null

# ---- compile classpath ----
# PHASE 2 DONE: libs/graal/ (the 15 GraalJS runtime jars) is no longer needed --
# every script is pure Java now, so nothing on the compile path touches Graal.
$cp = @()
$cp += Get-Jars 'libs'
if ($cp.Count -eq 0) { throw 'libs/ is empty -- run _check\_gs_setup_libs.py first' }
$CpStr = ($cp -join ';')

# ---- sources, as paths relative to $Root (no CJK ever reaches javac's argv) ----
$srcFiles = @(Get-ChildItem -LiteralPath 'src\main\java' -Recurse -Filter *.java -File -ErrorAction SilentlyContinue)
if ($srcFiles.Count -eq 0) { throw 'no .java under src/main/java' }
$sources = @($srcFiles | ForEach-Object { $_.FullName.Substring($RootLen + 1).Replace('\', '/') })

Write-Host ("[1/4] javac: {0} sources, {1} jars on classpath" -f $sources.Count, $cp.Count)
Set-Content -LiteralPath 'build\sources.txt' -Value $sources -Encoding ASCII
# javac prints diagnostics in the system code page (GBK) -> mojibake; JAVA_TOOL_OPTIONS
# forces English. The JVM then writes "Picked up JAVA_TOOL_OPTIONS" to stderr, which
# ErrorActionPreference=Stop would treat as a fatal NativeCommandError, so relax it and
# rely on $LASTEXITCODE. Capture to a file so diagnostics can never be lost in a pipeline.
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
# Run javac through cmd.exe: PowerShell would otherwise turn the JVM's
# "Picked up JAVA_TOOL_OPTIONS" stderr line into a NativeCommandError record.
& cmd /c "`"$Javac`" -encoding UTF-8 --release 21 -nowarn -proc:none -cp `"$CpStr`" -d build\classes @build\sources.txt > build\javac.log 2>&1"
$code = $LASTEXITCODE
$ErrorActionPreference = $prevEap
$env:JAVA_TOOL_OPTIONS = $null
if (Test-Path -LiteralPath 'build\javac.log') {
    Get-Content -LiteralPath 'build\javac.log' -Encoding utf8 |
        Where-Object { $_ -notmatch 'Picked up JAVA_TOOL_OPTIONS' } |
        ForEach-Object { "    $_" }
}
if ($code -ne 0) { throw "javac failed (exit $code) -- see build\javac.log" }

Write-Host '[2/4] resources'
Copy-Item -Path 'src\main\resources\*' -Destination 'build\classes' -Recurse -Force

Write-Host '[3/4] content'
if (Test-Path -LiteralPath 'content') {
    New-Item -ItemType Directory -Path 'build\classes\content' -Force | Out-Null
    Copy-Item -Path 'content\*' -Destination 'build\classes\content' -Recurse -Force
} else {
    Write-Host '      (no content/ -- skipped)'
}
# PHASE 2 DONE: the GraalJS runtime jars used to be bundled here under lib/ and
# unpacked to plugins/GS/lib/ on first run. All 18 scripts are now pure Java, so
# that whole path is gone -- the jar no longer embeds any JS engine (was +70.8 MB).

Write-Host '[4/4] jar'
if (Test-Path -LiteralPath 'build\GS.jar') { Remove-Item -LiteralPath 'build\GS.jar' -Force }
& $JarExe --create --file 'build\GS.jar' -C 'build\classes' .
if ($LASTEXITCODE -ne 0) { throw "jar failed (exit $LASTEXITCODE)" }

$fi = Get-Item -LiteralPath 'build\GS.jar'
Write-Host ''
Write-Host ("OK  {0}" -f $fi.FullName)
Write-Host ("    {0:N0} B" -f $fi.Length)
