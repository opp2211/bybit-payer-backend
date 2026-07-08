$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    .\mvnw.cmd -Dtest=OpenApiDocumentationTest test
}
finally {
    Pop-Location
}
