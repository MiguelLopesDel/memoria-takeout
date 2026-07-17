## O que muda

<!-- Resumo curto do que este PR faz e por quê. -->

## RFC / especificação

<!-- Mudança não-trivial (nova aba, novo parser, mudança de schema, novo endpoint)?
     Linke a RFC em docs/rfcs/. Mudança pequena/óbvia? Escreva "N/A" e justifique em uma linha. -->

## Checklist

- [ ] `npm run gate` e `mvn -B verify` passam localmente (os hooks já garantem isso)
- [ ] Mudanças de comportamento têm teste cobrindo (ou justificativa de por que não)
- [ ] Se mexi em linhas de `events` fora do importer: FTS rebuild + colunas locais recomputadas (ver CLAUDE.md "Things that will bite you")
- [ ] Entendo cada regra de negócio deste diff (não só "looks good to me" — se a IA gerou, fui grilado sobre ela)
