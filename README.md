# Memoria

Aplicação local, de usuário único, para importar e explorar um export do **Google Takeout**
(especificamente exports em pt-BR — caminhos de arquivo e datas são reconhecidos em português).
Tudo roda na sua máquina: um JAR Spring Boot (Java 21) faz o parse do Takeout para SQLite +
FTS5, e uma SPA React 19 embarcada no JAR apresenta os dados. Nenhum dado sai do seu computador.

## O que ela responde

O Takeout é um despejo de milhares de arquivos HTML/JSON impossível de ler à mão. A Memoria
normaliza tudo (My Activity, histórico do Chrome, Gmail, Maps, Calendar, YouTube, Google Pay,
Play Store, Tasks, NotebookLM, Blogger…) em uma linha do tempo única e pesquisável, e responde
perguntas como:

- **Explorar** — busca full-text com mini-DSL (`site:youtube.com type:comment after:2021-01-01`),
  filtros por fonte/tipo/site/período, tags, coleções e notas por evento.
- **Memórias** — "neste dia" ao longo dos anos + reconstrução cronológica de qualquer dia.
- **Padrões** — rotina hora × dia da semana, sequências de dias ativos, fases ("jul–set/2023:
  fase Wikipedia"), buscas repetidas, páginas sempre revisitadas.
- **YouTube** — vídeos e canais mais vistos, trajetória de canais (acompanhou → abandonou),
  primeira/última vez de cada vídeo, com contagem que separa "assistiu" de "interagiu".
- **Assuntos** — tópicos de interesse definidos por palavras-chave (ex.: "mangá, manhwa,
  webtoon") cruzando todas as fontes de uma vez.
- **Calendário / Timeline / Site** — heatmap anual, drill-down por dia e análise focada de um
  domínio.
- **Exportar** — CSV, JSON ou PDF do recorte filtrado.

Modo privacidade (um clique) mascara títulos, URLs e thumbnails para usar em tela compartilhada.

## Rodando com Docker (modo mais simples)

Por padrão, o container monta a pasta atual do projeto como `/imports`:

```bash
sudo docker compose up --build
# abra http://localhost:8787
```

Se seus Takeouts estiverem em outra pasta, monte a pasta pai:

```bash
IMPORT_ROOT=/home/usuario/Downloads sudo docker compose up --build
```

Na interface, use o botão de pasta no painel de importação e navegue a partir de `/imports`.
Aceita uma pasta Takeout ou um arquivo `.zip`, `.tgz`, `.tar.gz` ou `.tar`. Reimportar é
seguro: a importação mescla por uma chave estável de evento, então rodar de novo só adiciona
registros novos e preserva tags, coleções e notas.

## Desenvolvimento

Requisitos: Java 21, Maven, Node 20+.

```bash
npm install     # também ativa os git hooks do repo
npm run dev     # backend (:8787) + Vite (:5173) juntos
```

Abra `http://localhost:5173` (o Vite faz proxy de `/api` para o backend). Guia completo de
desenvolvimento, testes e arquitetura em **[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)**.

## Qualidade

O repo tem quality gates em três camadas — git hooks (`.githooks/`, ativados pelo
`npm install`), limites-catraca (ESLint, Checkstyle, CPD) e CI:

```bash
npm run gate        # eslint + tsc (roda no pre-commit)
mvn -B verify       # spotless + checkstyle + testes + duplicação (pre-push e CI)
mvn spotless:apply  # corrige formatação Java automaticamente
```

Todo commit passa antes por uma trava de dados sensíveis (`.githooks/check-sensitive.sh`):
nomes proibidos (exports do Takeout, `.mbox`, `.db`, `.env`, chaves), arquivos >5 MB,
marcadores pessoais e scan de segredos com [gitleaks](https://github.com/gitleaks/gitleaks)
(instale o binário no PATH — a trava falha fechada sem ele). O `pre-push` re-escaneia o
histórico inteiro. Exceção consciente: `ALLOW_SENSITIVE=1 git commit ...`.

Mudanças não-triviais começam por uma RFC — processo e specs em [`docs/rfcs/`](docs/rfcs/).
