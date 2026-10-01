param([switch]$KeepMethods)

$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$envPath = Join-Path $repositoryRoot '.env.payment-sim'
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Run node scripts/sandbox/create-sim-env.mjs first.'
}

$settings = @{}
foreach ($line in [IO.File]::ReadAllLines($envPath)) {
    $parts = $line.Split('=', 2)
    if ($parts.Length -eq 2) { $settings[$parts[0]] = $parts[1] }
}
if (-not $settings.STRIPE_SANDBOX_SECRET_KEY.StartsWith('sk_test_sim_') -or
    -not $settings.PAYPAL_SANDBOX_CLIENT_ID.StartsWith('sim_client_')) {
    throw 'The local payment smoke requires synthetic credentials.'
}

$baseUrl = 'http://127.0.0.1:18080'
$loginBody = @{ username = $settings.ADMIN_USERNAME; password = $settings.ADMIN_PASSWORD } | ConvertTo-Json -Compress
$login = Invoke-RestMethod -Uri "$baseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

function Post-Json($path, $body, $requestHeaders) {
    return Invoke-RestMethod -Uri "$baseUrl$path" -Method Post -ContentType 'application/json' `
        -Headers $requestHeaders -Body ($body | ConvertTo-Json -Compress)
}

$email = "sim-$([Guid]::NewGuid().ToString('N'))@example.test"
$user = Post-Json '/api/users' @{ email = $email; firstName = 'Payment'; lastName = 'Simulation'; role = 'OPERATOR' } $headers
$stripeGateway = Post-Json '/api/payment-gateways' @{ provider = 'STRIPE'; displayName = 'Simulated Stripe' } $headers
$paypalGateway = Post-Json '/api/payment-gateways' @{ provider = 'PAYPAL'; displayName = 'Simulated PayPal' } $headers

$stripeHeaders = @{ Authorization = $headers.Authorization; 'Idempotency-Key' = [Guid]::NewGuid().ToString() }
$stripe = Post-Json '/api/payment-methods' @{
    userId = $user.id; gatewayId = $stripeGateway.id
    providerReference = 'pm_simulatedvisa001'; displayLabel = 'Local Stripe fixture'
} $stripeHeaders
$paypalHeaders = @{ Authorization = $headers.Authorization; 'Idempotency-Key' = [Guid]::NewGuid().ToString() }
$paypal = Post-Json '/api/payment-methods' @{
    userId = $user.id; gatewayId = $paypalGateway.id
    providerReference = 'VAULT_SIM_001'; displayLabel = 'Local PayPal fixture'
} $paypalHeaders

if ($stripe.status -ne 'ACTIVE' -or $stripe.displayMetadata -ne 'visa •••• 4242') {
    throw "Stripe simulation failed: status=$($stripe.status), display=$($stripe.displayMetadata)"
}
if ($paypal.status -ne 'ACTIVE' -or $paypal.displayMetadata -ne 'VISA •••• 1111') {
    throw "PayPal simulation failed: status=$($paypal.status), display=$($paypal.displayMetadata)"
}

if (-not $KeepMethods) {
    foreach ($method in @($stripe, $paypal)) {
        $deleteHeaders = @{ Authorization = $headers.Authorization; 'Idempotency-Key' = [Guid]::NewGuid().ToString() }
        $deleted = Invoke-WebRequest -Uri "$baseUrl/api/payment-methods/$($method.id)" -Method Delete -Headers $deleteHeaders
        if ($deleted.StatusCode -ne 204) { throw "Method deletion failed for $($method.provider): $($deleted.StatusCode)" }
    }
}

$action = if ($KeepMethods) { 'ACTIVE and retained' } else { 'ACTIVE then deleted' }
Write-Output "Local payment simulation passed: user=$($user.id), Stripe $action, PayPal $action."
