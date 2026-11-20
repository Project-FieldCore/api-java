param(
  [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'

function Invoke-Api {
  param(
    [string]$Method,
    [string]$Path,
    [hashtable]$Headers = @{},
    [object]$Body
  )

  $request = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $Headers }
  if ($null -ne $Body) {
    $request.ContentType = 'application/json'
    $request.Body = ($Body | ConvertTo-Json -Depth 8 -Compress)
  }
  Invoke-RestMethod @request
}

function Login([string]$Email) {
  (Invoke-Api -Method POST -Path '/api/v1/auth/login' -Body @{
    email = $Email
    password = 'DemoPass123!'
  }).accessToken
}

Write-Host 'Autenticando usuários de demonstração...'
$technicianToken = Login 'joao.tecnico@example.com'
$supervisorToken = Login 'ana.silva@example.com'
$technicianHeaders = @{ Authorization = "Bearer $technicianToken" }
$supervisorHeaders = @{ Authorization = "Bearer $supervisorToken" }

$assignments = @(Invoke-Api -Method GET -Path '/api/v1/inspections/my' -Headers $technicianHeaders)
$inspection = $assignments | Where-Object { $_.title -eq '[Demo] Atribuída — sync mobile' -and $_.status -eq 'ASSIGNED' } | Select-Object -First 1
if ($null -eq $inspection) {
  throw 'A inspeção demo ASSIGNED não foi encontrada. Reinicie a API no profile dev para recriar o banco H2 e o seed.'
}

Write-Host "Iniciando inspeção $($inspection.id)..."
$startHeaders = $technicianHeaders.Clone()
$startHeaders['X-Operation-Id'] = [guid]::NewGuid().ToString()
$started = Invoke-Api -Method POST -Path "/api/v1/inspections/my/$($inspection.id)/start" -Headers $startHeaders -Body @{}

$checklist = @(Invoke-Api -Method GET -Path "/api/v1/inspections/my/$($inspection.id)/checklist" -Headers $technicianHeaders)
$answers = @($checklist | ForEach-Object { $_.items } | ForEach-Object {
  if ($_.responseType -ne 'BOOLEAN') {
    throw "O roteiro demo espera itens BOOLEAN; item recebido: $($_.responseType)"
  }
  @{ itemId = $_.id; valueBoolean = $true }
})
if ($answers.Count -eq 0) { throw 'O checklist demo não possui itens para responder.' }

Write-Host 'Enviando respostas e concluindo inspeção...'
$completeHeaders = $technicianHeaders.Clone()
$completeHeaders['X-Operation-Id'] = [guid]::NewGuid().ToString()
$submitted = Invoke-Api -Method POST -Path "/api/v1/inspections/my/$($inspection.id)/complete" -Headers $completeHeaders -Body @{
  version = $started.version
  answers = $answers
}
if ($submitted.status -ne 'SUBMITTED') { throw "Status esperado SUBMITTED; recebido $($submitted.status)." }

Write-Host 'Iniciando revisão como supervisora...'
$reviewing = Invoke-Api -Method POST -Path "/api/v1/inspections/$($inspection.id)/review/start" -Headers $supervisorHeaders -Body $null
if ($reviewing.status -ne 'UNDER_REVIEW') { throw "Status esperado UNDER_REVIEW; recebido $($reviewing.status)." }

Write-Host 'Aprovando inspeção...'
$approved = Invoke-Api -Method POST -Path "/api/v1/inspections/$($inspection.id)/review/approve" -Headers $supervisorHeaders -Body @{
  comentario = 'Aprovada pelo roteiro de demonstração local.'
}
if ($approved.status -ne 'APPROVED') { throw "Status esperado APPROVED; recebido $($approved.status)." }

Write-Host "Demo concluída com sucesso. Inspeção $($approved.id) aprovada." -ForegroundColor Green
