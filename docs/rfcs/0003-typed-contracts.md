# RFC 0003 — Contratos tipados nos seams (DTOs em vez de Map)

- **Status:** aceita
- **Data:** 2026-07-17
- **Grilling:** discutido em sessão em 2026-07-17

## Contexto e problema

A maioria dos endpoints devolve `Map<String, Object>` (ou `List<Map<String, Object>>`)
montado direto das linhas do JDBC, e os endpoints de escrita recebem `Map<String, Object>`
como body. O shape real de cada resposta só existe implícito no SQL e no consumo do
frontend — que por sua vez faz casts sobre `res.json()`. Renomear uma chave no backend não
quebra compilação em lugar nenhum; quebra em produção.

O repositório já tem o padrão bom em quatro lugares: `MetricsResponse`, `FacetsResponse`,
`EventsResponse` e `StatusResponse` são records que documentam o contrato. Esta RFC
generaliza esse padrão, sem exigir que toda linha SQL intermediária vire classe.

## Proposta

**Regra:** dados que atravessam um seam estável — a fronteira HTTP do `ApiController` ou a
interface pública de um módulo — usam Java records (e tipos TypeScript correspondentes no
frontend). `Map<String, Object>` continua permitido:

- dentro da implementação JDBC (linhas intermediárias, agregações internas);
- para dados genuinamente dinâmicos (ex.: `raw_json` repassado como veio).

A migração é incremental, endpoint a endpoint, começando pelos requests de escrita e pelos
quatro relatórios compostos (`/api/patterns`, `/api/youtube`, `/api/site`,
`/api/topics/report`). A tipagem deve chegar ao frontend: um pequeno cliente `api.ts` com
os tipos de resposta, eliminando casts sobre `res.json()`.

### Entradas e saídas

Nenhum shape de JSON muda — a RFC tipa contratos existentes, não os altera. Cada endpoint
migrado ganha um record cujos componentes espelham as chaves atuais (mesmos nomes, mesmos
tipos), de modo que o frontend não percebe a migração.

## Invariantes e casos de borda

- Migrar um endpoint NÃO pode mudar o JSON produzido (nomes de chave, presença/ausência de
  campos, `null` vs omitido). Jackson serializa records por nome de componente; campos hoje
  omitidos quando ausentes precisam manter esse comportamento.
- O gate arquitetural (`TypedContractsTest`) é uma catraca: a allowlist de endpoints que
  ainda usam `Map` só encolhe. Endpoint novo com `Map` no contrato = teste vermelho.
- Limitação conhecida do gate: `ResponseEntity<?>` apaga o tipo do payload por reflexão
  (`browseFiles`, `importTakeout`, `reset`, `youtubeVideo`, `topicReport`, `onThisDay`,
  `day`). Esses ficam sob o checklist abaixo, não sob o teste.

## Alternativas consideradas

- **Não fazer nada:** o custo já apareceu — o shape de `/api/patterns` só é descobrível
  lendo SQL. Rejeitada.
- **DTO para tudo, num diff só:** big-bang de ~37 endpoints, alto risco de mudar shape sem
  perceber e diff impossível de revisar. Rejeitada em favor da catraca incremental.
- **ArchUnit para o gate:** dependência nova para o que um teste JUnit de reflexão resolve
  em 50 linhas. Rejeitada.
- **Issue no GitHub para o plano:** o repositório é local e de uma pessoa; o checklist vive
  nesta RFC e é atualizado nos mesmos commits que executam cada etapa.

## Plano de testes

`TypedContractsTest` roda no `mvn verify` e falha em duas direções: endpoint novo usando
`Map` no contrato, ou entrada da allowlist que já foi migrada e não foi removida. Cada
migração de endpoint mantém os testes de integração existentes verdes (mesmo JSON).

## Plano de migração (checklist — atualizar aqui a cada etapa)

- [ ] Tipar os requests de escrita (tags, coleções, notas, tópicos, filtros salvos).
- [ ] Tipar os quatro relatórios compostos (`/api/patterns`, `/api/youtube`, `/api/site`, `/api/topics/report`).
- [ ] Criar `EventView` (record) para as projeções de eventos (`RECALL_COLUMNS`).
- [ ] Criar cliente `api.ts` no frontend com os tipos de resposta correspondentes.
- [ ] Remover casts sobre `res.json()` no frontend.
- [ ] Tipar os endpoints `ResponseEntity<?>` e estender o gate a eles.
- [ ] Esvaziar `MAP_CONTRACT_ALLOWLIST` e trocar a allowlist por proibição direta.

## Perguntas em aberto

Nenhuma.
