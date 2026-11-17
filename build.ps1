$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$javaHomeLine = (& java -XshowSettings:properties -version 2>&1 | Select-String '^\s*java.home\s*=').ToString()
$jdkRoot = ($javaHomeLine -split '=', 2)[1].Trim()
$javaTool = Join-Path $jdkRoot 'bin/java.exe'
$javacTool = Join-Path $jdkRoot 'bin/javac.exe'
$jarTool = Join-Path $jdkRoot 'bin/jar.exe'
New-Item -ItemType Directory -Force target/classes,target/test-classes | Out-Null
$sources = @(Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object FullName)
& $javacTool --release 21 -encoding UTF-8 -d target/classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
$tests = @(Get-ChildItem -Recurse src/test/java -Filter *.java | ForEach-Object FullName)
if ($tests.Count -gt 0) {
    # Compile together: javac on some Windows installations mishandles a Cyrillic classpath.
    & $javacTool --release 21 -encoding UTF-8 -d target/test-classes $sources $tests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    & $javaTool -cp target/test-classes ru.winkiz.lab4.SelfTest
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
}
& $jarTool --create --file target/lab4-broker-client-1.0.jar --main-class ru.winkiz.lab4.Main -C target/classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed' }
