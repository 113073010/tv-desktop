$ErrorActionPreference = 'Continue'
$root   = Split-Path $PSScriptRoot -Parent
$proj   = "$root\plugin-live"
$sdk    = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$root\tools\android-sdk" }
$bt     = "$sdk\build-tools\34.0.0"
$jdk    = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "$root\tools\jdk\jdk-17.0.20.1+1" }
$androidJar = "$sdk\platforms\android-34\android.jar"
$ijkJar = "$root\ijk\ijkplayer-classes.jar"
$ijkLib = "$root\ijk\lib"
$out    = "$proj\build"
$tmp    = "$root\tmp"
$env:TMP = $tmp; $env:TEMP = $tmp; $env:JAVA_HOME = $jdk
New-Item -ItemType Directory -Force -Path $out | Out-Null
$stubCls  = "$out\stub-classes"
$plugCls  = "$out\classes"
$dexDir   = "$out\dex"
foreach ($d in @($stubCls,$plugCls,$dexDir)) { if (Test-Path $d) { Remove-Item $d -Recurse -Force }; New-Item -ItemType Directory -Force -Path $d | Out-Null }
Write-Host "== 1/6 compile stubs =="
$stubSrc = Get-ChildItem "$proj\stubs" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn -d $stubCls -cp $androidJar @stubSrc 2>&1 | Where-Object { $_ -notmatch 'bootstrap|source value 8|target value 8' }
& "$jdk\bin\jar.exe" cf "$out\stubs.jar" -C $stubCls .
Write-Host "   stubs.jar ok"
Write-Host "== 2/6 compile plugin =="
$plugSrc = Get-ChildItem "$proj\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn -d $plugCls -cp "$androidJar;$out\stubs.jar;$ijkJar" @plugSrc 2>&1 | Where-Object { $_ -notmatch 'bootstrap|source value 8|target value 8|deprecat' }
if (-not (Test-Path "$plugCls\com\dsh\tvdesk\live\LiveFragment.class")) { throw "javac failed" }
Write-Host "   classes ok"
Write-Host "== 3/6 d8 (plugin + ijk) =="
& "$jdk\bin\jar.exe" cf "$out\plugin.jar" -C $plugCls .
& "$bt\d8.bat" --min-api 21 --lib $androidJar --lib "$out\stubs.jar" --output $dexDir "$out\plugin.jar" $ijkJar 2>&1 | Select-Object -Last 3
if (-not (Test-Path "$dexDir\classes.dex")) { throw "d8 failed" }
Write-Host ("   classes.dex: {0:N0} bytes" -f (Get-Item "$dexDir\classes.dex").Length)
Write-Host "== 4/6 aapt2 link =="
$base = "$out\base.apk"
if (Test-Path $base) { Remove-Item $base -Force }
& "$bt\aapt2.exe" link -o $base --manifest "$proj\AndroidManifest.xml" -I $androidJar --min-sdk-version 21 --target-sdk-version 21 2>&1
if (-not (Test-Path $base)) { throw "aapt2 link failed" }
Write-Host "== 5/6 add dex + so, align =="
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($base, 'Update')
$old = $zip.GetEntry('classes.dex'); if ($old) { $old.Delete() }
$entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
$in = [System.IO.File]::OpenRead("$dexDir\classes.dex"); $es = $entry.Open()
$in.CopyTo($es); $es.Close(); $in.Close()
Get-ChildItem $ijkLib -Filter *.so | ForEach-Object {
    $name = "lib/armeabi-v7a/" + $_.Name
    $o = $zip.GetEntry($name); if ($o) { $o.Delete() }
    $ne = $zip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::NoCompression)
    $fs = [System.IO.File]::OpenRead($_.FullName); $os = $ne.Open()
    $fs.CopyTo($os); $os.Close(); $fs.Close()
    Write-Host ("   + {0} ({1:N1} MB)" -f $name, ($_.Length/1MB))
}
$zip.Dispose()
$unsigned = "$out\unsigned.apk"
if (Test-Path $unsigned) { Remove-Item $unsigned -Force }
& "$bt\zipalign.exe" -f -p 4 $base $unsigned 2>&1
if (-not (Test-Path $unsigned)) { throw "zipalign failed" }
Write-Host "== 6/6 sign =="
$ks = "$out\tvdesk.jks"
    if (-not (Test-Path $ks)) {
        Write-Host "   生成签名密钥（仅本地调试用）..."
        & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android `
            -alias tvdesk -keyalg RSA -keysize 2048 -validity 10000 `
            -dname "CN=tvdesk, OU=dev, O=dev, L=NA, S=NA, C=CN" 2>&1 | Out-Null
    }
$signed = "$out\live.apk"
if (Test-Path $signed) { Remove-Item $signed -Force }
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
   --min-sdk-version 21 --out $signed $unsigned 2>&1
if (-not (Test-Path $signed)) { throw "apksigner failed" }
Write-Host ""
Write-Host "== output =="
Get-Item $signed | ForEach-Object { "{0}  {1:N0} bytes" -f $_.FullName, $_.Length }
& "$bt\aapt2.exe" dump badging $signed 2>&1 | Select-String -Pattern "^package:" | ForEach-Object { $_.Line }
