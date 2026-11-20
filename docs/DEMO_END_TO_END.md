# Demo ponta a ponta da API

Este roteiro demonstra o caminho **login → execução → revisão → aprovação** com os dados criados
automaticamente pelo profile `dev`.

## Preparação

1. Defina uma `JWT_SECRET` local com ao menos 32 caracteres.
2. Inicie a API no profile `dev`:

   ```powershell
   $env:JWT_SECRET = 'troque-por-uma-chave-local-com-32-caracteres'
   .\mvnw.cmd spring-boot:run
   ```

3. Em outro terminal, aguarde `http://localhost:8080/actuator/health` retornar `UP`.

O profile `dev` usa H2 em memória e executa o `DevDemoSeed`. Os usuários e as senhas são apenas
locais; veja [DEMO_SEED.md](DEMO_SEED.md) para a lista completa.

## Executar o roteiro

No PowerShell, a partir da raiz do repositório:

```powershell
.\scripts\demo-e2e.ps1
```

Para uma API em outro endereço:

```powershell
.\scripts\demo-e2e.ps1 -BaseUrl 'http://localhost:8080'
```

O script realiza estas etapas:

1. autentica o técnico `joao.tecnico@example.com` e a supervisora `ana.silva@example.com`;
2. localiza a inspeção seed `[Demo] Atribuída — sync mobile`;
3. inicia a inspeção, responde os itens BOOLEAN e conclui a execução;
4. inicia a revisão como supervisora;
5. aprova a inspeção e mostra o identificador final.

As mutações de início e conclusão enviam `X-Operation-Id`, de modo que o roteiro também exercita
o contrato de idempotência de reenvio.

## Resultado esperado

O terminal termina com uma mensagem semelhante a:

```text
Demo concluída com sucesso. Inspeção <uuid> aprovada.
```

No Swagger UI, também é possível verificar a sequência pelas rotas `POST /api/v1/auth/login`,
`GET /api/v1/inspections/my`, `POST /api/v1/inspections/my/{inspectionId}/start`,
`POST /api/v1/inspections/my/{inspectionId}/complete`,
`POST /api/v1/inspections/{inspectionId}/review/start` e
`POST /api/v1/inspections/{inspectionId}/review/approve`.

## Reexecutar

O roteiro consome a inspeção seed `ASSIGNED`. Como H2 é em memória, pare e reinicie a API no
profile `dev` antes de executar o roteiro novamente; o seed será recriado de forma idempotente.

Para validação automatizada do mesmo caminho de negócio, execute:

```powershell
.\mvnw.cmd -q -Dtest=AcReleaseCriticalPathTest,DevDemoSeedTest test
```
