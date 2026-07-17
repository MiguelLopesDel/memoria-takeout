# Guia de desenvolvimento

Documentação técnica da Memoria: como rodar, testar, os quality gates e um mapa da
arquitetura. O contexto operacional completo usado pelas sessões de IA vive em
[`CLAUDE.md`](../CLAUDE.md); este guia é o resumo para humanos.

## Requisitos

- **Java 21** (records, switch expressions e text blocks são usados em todo o backend)
- **Maven 3.9+**
- **Node 20+** / npm
- **gitleaks** no PATH (`~/.local/bin` serve) — a trava de dados sensíveis falha fechada sem ele
- Docker (opcional, só para o modo produção)

## Rodando

### Desenvolvimento (dois processos)

```bash
npm install                 # primeira vez; também ativa os git hooks (prepare script)
npm run dev                 # concurrently: `mvn spring-boot:run` + Vite em :5173
```

Ou separado:

```bash
mvn spring-boot:run         # backend em :8787
npm run dev:web             # Vite em :5173, proxy de /api -> :8787
```

Abra `http://localhost:5173`. Acessar `:8787` direto só serve UI se o frontend tiver sido
buildado para `src/main/resources/static` (isso só acontece no build Docker).

### Produção local (Docker)

```bash
sudo docker compose up --build                    # :8787, monta ./ como /imports
IMPORT_ROOT=/pasta/com/takeouts sudo docker compose up --build
```

### Build do JAR

```bash
mvn -B package              # target/memoria-0.1.0.jar (roda os testes)
```

## Configuração (variáveis de ambiente)

| Variável | Default | O que faz |
| --- | --- | --- |
| `MEMORIA_DATA_DIR` | `.memoria` | onde vive o `memoria.db` (SQLite) |
| `MEMORIA_DEFAULT_TAKEOUT` | `/app/Takeout` | caminho importado quando a UI não envia um |
| `MEMORIA_IMPORT_ROOTS` | — | diretórios (separados por vírgula) aos quais o navegador de arquivos e caminhos relativos ficam confinados (guarda contra path traversal) |
| `MEMORIA_TIMEZONE` | `America/Sao_Paulo` | zona IANA usada para derivar dia/hora/dia-da-semana/mês locais de cada evento na importação |

## Testes

```bash
mvn test                    # todos os testes do backend
mvn test -Dtest=TakeoutImporterIntegrationTest                       # uma classe
mvn test -Dtest=TakeoutImporterIntegrationTest#nomeDoTeste           # um teste
npm run check               # tsc --noEmit
```

Os testes de integração montam um Takeout representativo em disco (HTML + JSON, pt-BR e
inglês) e importam para um SQLite real em diretório temporário — sem mocks de banco.
`TypedContractsTest` é um teste arquitetural: falha se um endpoint novo usar
`Map<String, Object>` no contrato (ver RFC 0003).

## Quality gates

Três camadas; os limites são uma **catraca** — nunca afrouxar, sempre corrigir o código:

1. **`pre-commit`** (rápido): trava de dados sensíveis → `npm run gate` (eslint com
   `--max-warnings 7` + tsc) → spotless + checkstyle nos Java staged.
2. **`pre-push`** (completo): gitleaks no histórico inteiro → `mvn -B verify` (spotless,
   checkstyle `FileLength 2400`/`MethodLength 150`, testes, CPD ≥200 tokens) → `vite build`.
3. **CI** (`.github/workflows/ci.yml`): os mesmos comandos, mais um job de gitleaks.

A trava de dados sensíveis (`.githooks/check-sensitive.sh`) bloqueia: nomes de arquivo
proibidos (exports do Takeout, `.mbox`, `.db`, `.env`, material de chave), arquivos >5 MB,
marcadores pessoais (`.githooks/sensitive-patterns.txt`) em linhas adicionadas e segredos
via gitleaks. Exceção deliberada = `ALLOW_SENSITIVE=1 git commit ...` — nunca `--no-verify`.

Reclamação de formatação Java tem uma única resposta: `mvn spotless:apply`.

## Arquitetura

### Backend (`src/main/java/app/memoria/`)

Fluxo: `ApiController` → serviços de leitura ou `EventStore` → SQLite (WAL) + FTS5.

- **`ApiController`** — um controller REST plano. Endpoints de listagem/métricas recebem os
  mesmos 7 query params de filtro (`q, source, type, domain, from, to, product`) via
  `FilterParams`. Também exporta CSV/PDF/JSON.
- **`TakeoutImporter`** — o núcleo de parsing. Resolve pasta ou arquivo compactado, percorre
  deterministicamente e despacha por arquivo para parsers específicos de formato. Aceita
  caminhos e datas em pt-BR e inglês. Emite `EventRecord`s em lotes de 10k.
- **`ImportJobService`** — importações em um executor de thread única, um job por vez, com
  status consultável em `/api/import/status`.
- **Módulos de banco** (RFC 0002): **`EventStore`** é o único que escreve na tabela `events`
  — schema, migrações, importação, backfills; um funil transacional garante que toda mutação
  reconstrói o índice FTS e que timestamp nunca muda sem recalcular as colunas locais.
  **`AnalyticsService`** (relatórios gerais e busca), **`YouTubeService`** (mergulho no
  ecossistema YouTube) e **`AnnotationsService`** (tags, coleções, notas, assuntos, filtros
  salvos) são somente leitura. **`EventQueries`** (package-private) compartilha o DSL de
  filtros entre os módulos analíticos.
- **`FileBrowserService`** — navegador de pastas da UI, confinado a `MEMORIA_IMPORT_ROOTS`.

Decisões que valem conhecer antes de mexer:

- **Importação incremental por `event_key`**: reimportar mescla (`ON CONFLICT DO UPDATE`);
  IDs e anotações sobrevivem a correções de parser.
- **FTS5 external-content sem triggers**: o índice é reconstruído explicitamente pelo
  `EventStore`; módulos de leitura não podem mutar `events`.
- **Tempo local pré-computado**: `local_day/local_hour/local_weekday/year_month` são
  derivados na persistência com `MEMORIA_TIMEZONE`; agregações nunca convertem timezone em
  query.
- **Busca é um mini-DSL** (`EventQueries.parseQuery`): prefixos `site:`, `type:`, `source:`,
  `before:`, `after:`, `path:`, `title:`, `text:`, flags `has:url`/`has:time`/`missing:time`
  e exclusão com `-palavra`.

### Frontend (`src/`)

React 19 + Vite; Radix UI, Recharts e TanStack Virtual são as únicas libs de UI;
`styles.css` é escrito à mão.

- `main.tsx` — só o entry point.
- `App.tsx` — estado compartilhado de filtros, orquestração de `/api` e layout das abas.
- `views/` — um módulo por aba (Memórias, Padrões, YouTube, Assuntos, Timeline, Calendário,
  Site, Organizar, Exportar).
- `components/` — peças usadas em várias abas (CommandBar, FilterRail, InsightCanvas,
  EventStream, EntityPanel, DayPanel, primitives).
- `types.ts` — shapes das respostas da API; `api.ts` — `readJson` defensivo; `format.ts` —
  formatação pt-BR pura.

## Processo de mudança

Mudanças não-triviais (nova aba, novo parser, schema, endpoint novo/alterado) começam por uma
RFC em [`docs/rfcs/`](rfcs/README.md): spec agnóstica de implementação → grilling até zerar
perguntas em aberto → implementação com a RFC como fonte da verdade. Se a implementação
divergir, a RFC é atualizada no mesmo PR.

Convenções: commits em inglês, sem trailers `Co-Authored-By`; comentários de código em
inglês; contratos que cruzam um seam usam records/DTOs (RFC 0003).
