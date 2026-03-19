[CmdletBinding()]
param(
    [string]$Message = "sync: local is source of truth",
    [switch]$Force
)

$scriptPath = Join-Path $PSScriptRoot ".local-tools\sync-local-truth.ps1"

& $scriptPath -Message $Message -Force:$Force
exit $LASTEXITCODE
