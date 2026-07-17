# RFC 0002 — Decompor o DatabaseService e internalizar invariantes

- **Status:** aceita
- **Data:** 2026-07-17

## Contexto e problema

`DatabaseService` reunia 65 métodos públicos e cinco responsabilidades: schema e migrações,
persistência de importação, o DSL de filtros, relatórios analíticos e CRUD de anotações. Além
do custo de localidade, sua interface incluía duas regras invisíveis nas assinaturas: mutações
de campos indexados precisavam reconstruir `events_fts`, e mudanças de `timestamp` precisavam
recalcular `year_month`, `local_day`, `local_hour` e `local_weekday`.

## Decisão

Substituir `DatabaseService` por quatro módulos concretos, sem criar interfaces Java para as
quais só existe um adapter:

- `EventStore`: schema, migrações, importação, backfills e toda mutação de `events`;
- `AnalyticsService`: busca, métricas e relatórios gerais;
- `YouTubeService`: relatórios específicos do ecossistema YouTube;
- `AnnotationsService`: tópicos, tags, coleções, notas e filtros salvos.

`EventQueries` é um seam interno, package-private, compartilhado pelos dois módulos analíticos.
Ele concentra o DSL de filtros e as agregações SQL reutilizadas sem ampliar a interface externa.

`EventStore` é o único módulo autorizado a escrever em `events`. Mutações avulsas passam por um
funil transacional que reconstrói FTS. O ciclo incremental inteiro passa por `mergeImport`, que
finaliza o índice em `finally`, inclusive quando o parser falha. As colunas locais são calculadas
pelo próprio `EventStore` a partir do timestamp tanto na inserção quanto no backfill; elas não
fazem mais parte de `EventRecord`.

## Alternativas consideradas

- Manter a classe única até atingir a catraca de 2400 linhas preservaria o problema e tornaria a
  divisão futura mais arriscada.
- Criar uma interface de storage para o importer adicionaria um seam hipotético: produção e
  testes usam o mesmo adapter SQLite, com banco real em diretório temporário.
- Recriar FTS a cada batch tornaria a importação segura, mas quadrática; controlar o ciclo completo
  permite uma única reconstrução e preserva o desempenho.

## Consequências

Mudanças de relatórios ficam locais ao módulo da área, enquanto toda escrita em `events` é revisada
num só lugar. O teste de integração monta os quatro módulos sobre o mesmo SQLite e verifica tanto
a reconstrução de FTS após falha quanto o recálculo das colunas locais.

