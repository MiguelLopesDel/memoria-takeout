# RFC 0001 — Quality gates do repositório

- **Status:** aceita
- **Data:** 2026-07-17
- **Grilling:** feito em 2026-07-17

## Contexto e problema

Boa parte do código deste repo é gerada por IA em sessões longas, sem revisão linha a
linha. Sem gates automáticos, código ruim (duplicação, funções gigantes, tipos furados,
formatação inconsistente) prolifera silenciosamente e o entendimento do sistema se perde.

## Proposta

Três camadas de gate, da mais rápida para a mais completa:

| Camada | Quando roda | O que roda |
| --- | --- | --- |
| `pre-commit` | todo commit | `npm run gate` (eslint + tsc); se há `.java` staged, `spotless:check` + `checkstyle:check` |
| `pre-push` | todo push | tudo acima + `vite build` + `mvn -B verify` (testes + CPD) |
| CI (`.github/workflows/ci.yml`) | PR e push em `main` | os mesmos comandos, em jobs `frontend` e `backend` |

Gate local e CI executam **os mesmos comandos** — CI nunca reprova algo que passou
localmente.

### Entradas e saídas

- **Frontend:** `npm run lint` = `eslint . --max-warnings 7`; `npm run check` = `tsc --noEmit`;
  `npm run gate` = ambos. Config em `eslint.config.js` (typescript-eslint recommended +
  react-hooks; `no-explicit-any` e `set-state-in-effect` desligadas com justificativa no config).
- **Backend:** `mvn -B verify` encadeia Spotless (palantir-java-format, `mvn spotless:apply`
  para corrigir), Checkstyle (`config/checkstyle.xml`), Surefire (testes) e CPD
  (duplicação, mínimo 200 tokens).
- **Hooks:** versionados em `.githooks/`, ativados por `git config core.hooksPath .githooks`
  (o script `prepare` do npm faz isso em todo `npm install`).

## Invariantes e casos de borda

- **Catraca (ratchet):** os limites numéricos (`max-warnings 7`, `FileLength 2400`,
  `MethodLength 150`, CPD 200 tokens) foram calibrados no estado atual do código.
  Podem **descer** quando o código melhora; **nunca sobem** para acomodar código novo.
  Estourou o limite? Modularize, não afrouxe a régua.
- Formatação nunca é discutida em review: `spotless:apply` é a resposta canônica.
- CPD já pagou o próprio ingresso: na calibração encontrou o cálculo de séries
  mensais/pico duplicado entre Padrões e YouTube, extraído para
  `DatabaseService.monthlyPhases`.

## Alternativas consideradas

- **Husky/lint-staged** em vez de `.githooks/`: mais dependências npm para o mesmo
  resultado; `core.hooksPath` + script `prepare` cobre o caso com zero deps.
- **Error Prone/SpotBugs:** valor real, mas setup mais frágil com Java 21; fica para uma
  RFC futura se Checkstyle + CPD se mostrarem insuficientes.
- **`set-state-in-effect` como erro:** condenaria o padrão de data-fetching do app
  inteiro; só faz sentido junto de uma migração para TanStack Query (RFC futura).

## Plano de testes

O gate testa a si mesmo: `mvn -B verify` e `npm run gate` verdes no repo limpo; um commit
com violação proposital (função de 200 linhas, import não usado) deve ser bloqueado.

## Perguntas em aberto

Nenhuma.
