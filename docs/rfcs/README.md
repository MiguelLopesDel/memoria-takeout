# RFCs — especificações antes de código

O 80/20 deste repo: **80% do esforço em pensar/especificar, 20% em executar**. Código
(humano ou de IA) sem especificação vira código que ninguém entende — a RFC é a fonte
da verdade que impede a IA de alucinar arquitetura e impede você de perder o
entendimento do próprio sistema.

## Quando escrever uma RFC

Obrigatório para mudanças **não-triviais**:

- Nova aba / nova área da UI
- Novo parser de formato do Takeout
- Mudança de schema do SQLite ou de semântica de `event_key`
- Novo endpoint ou mudança de contrato de um endpoint existente
- Qualquer coisa que mexa em contagem/dedupe/identidade de eventos

**Não** precisa de RFC: correção de bug com comportamento óbvio, ajuste visual,
refactor que não muda contrato, chore de dependência.

## Processo

1. Copie `0000-template.md` para `NNNN-slug-curto.md` (próximo número livre).
2. Escreva a spec **agnóstica de implementação**: domínio, entradas e saídas
   rigorosamente documentadas, invariantes, casos de borda. Uma boa spec permitiria
   reimplementar a feature em outra linguagem sem olhar o código.
3. **Grill:** rode a skill `grilling` do Claude Code sobre o rascunho ("me grile sobre
   esta RFC") até não sobrar decisão sem dono. Este é o "request for comments" de um
   repo de uma pessoa só — a IA faz o papel dos colegas.
4. Abra o PR da RFC (ou commite direto se for você mesmo o único revisor) **antes** de
   começar a implementação.
5. Implemente apontando a IA para a RFC como fonte da verdade do prompt.
6. Divergiu da spec durante a implementação? Atualize a RFC no mesmo PR — spec
   desatualizada é pior que spec nenhuma.

## Status

| Status | Significado |
| --- | --- |
| `rascunho` | em escrita/grilling |
| `aceita` | pronta para implementar / implementada |
| `superada` | substituída por outra RFC (linkar) |
