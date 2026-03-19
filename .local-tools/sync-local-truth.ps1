[CmdletBinding()]
param(
    [string]$Message = "sync: local is source of truth",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & git @Arguments
    $exitCode = $LASTEXITCODE

    if ($exitCode -ne 0) {
        $joined = $Arguments -join " "
        throw "git $joined failed with exit code $exitCode."
    }
}

function Get-GitOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $output = & git @Arguments
    $exitCode = $LASTEXITCODE

    if ($exitCode -ne 0) {
        $joined = $Arguments -join " "
        throw "git $joined failed with exit code $exitCode."
    }

    return ($output | Out-String).Trim()
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir "..")).Path

Write-Host "Switching to repo root: $repoRoot"
Set-Location -Path $repoRoot

Write-Host "Verifying git repository..."
$insideWorkTree = Get-GitOutput -Arguments @("rev-parse", "--is-inside-work-tree")
if ($insideWorkTree -ne "true") {
    throw "Current directory is not inside a git work tree."
}

$branch = Get-GitOutput -Arguments @("branch", "--show-current")
if ([string]::IsNullOrWhiteSpace($branch)) {
    throw "Unable to determine the current branch."
}
Write-Host "Current branch: $branch"

$safetyTag = "safety-{0}" -f (Get-Date -Format "yyyyMMdd-HHmmss")
Write-Host "Creating safety tag: $safetyTag"
Invoke-Git -Arguments @("tag", $safetyTag)

Write-Host "Staging changes..."
Invoke-Git -Arguments @("add", "-A")

& git diff --cached --quiet
$diffExitCode = $LASTEXITCODE
if ($diffExitCode -eq 1) {
    Write-Host "Staged changes detected. Creating commit..."
    Invoke-Git -Arguments @("commit", "-m", $Message)
} elseif ($diffExitCode -eq 0) {
    Write-Host "No staged changes to commit."
} else {
    throw "git diff --cached --quiet failed with exit code $diffExitCode."
}

if ($Force) {
    Write-Host "Pushing with --force-with-lease to origin/$branch..."
    Invoke-Git -Arguments @("push", "origin", $branch, "--force-with-lease")
} else {
    Write-Host "Pushing to origin/$branch..."
    Invoke-Git -Arguments @("push", "origin", $branch)
}

Write-Host "Sync complete. Local repository remains the source of truth."
