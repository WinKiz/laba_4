$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
New-Item -ItemType Directory -Force target/classes,target/test-classes | Out-Null
$sources = @(Get-ChildItem -Recurse src/main/java -Filter *.java | ForEach-Object FullName)
& javac --release 21 -encoding UTF-8 -d target/classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
$tests = @(Get-ChildItem -Recurse src/test/java -Filter *.java | ForEach-Object FullName)
if ($tests.Count -gt 0) {
    & javac --release 21 -encoding UTF-8 -cp target/classes -d target/test-classes $tests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    & java -cp 'target/classes;target/test-classes' ru.winkiz.lab4.SelfTest
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
}
& jar --create --file target/lab4-broker-client-1.0.jar --main-class ru.winkiz.lab4.Main -C target/classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed' }
