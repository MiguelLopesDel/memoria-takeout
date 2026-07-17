# Memoria

Interface local para importar e explorar dados do Google Takeout.

## Rodando com Docker

Por padrão, o container monta a pasta atual do projeto como `/imports`.
Isso permite colocar uma ou várias pastas Takeout dentro do projeto e escolher pela interface:

```bash
sudo docker compose up --build
```

Abra:

```text
http://localhost:8787
```

Se seus Takeouts estiverem em outra pasta, monte a pasta pai:

```bash
IMPORT_ROOT=/home/usuario/Downloads sudo docker compose up --build
```

Dentro da interface, clique no botão de pasta no painel de importação e navegue a partir de `/imports`.
Você pode selecionar uma pasta Takeout ou um arquivo `.zip`, `.tgz`, `.tar.gz` ou `.tar`.

## Desenvolvimento local

```bash
npm install
mvn spring-boot:run
```

Em outro terminal:

```bash
npm run dev
```

O frontend roda em `http://localhost:5173` e usa proxy para o backend em `http://localhost:8787`.

## Qualidade

O repo tem quality gates em três camadas (hooks de git em `.githooks/`, ativados pelo
`npm install`; CI em `.github/workflows/ci.yml`):

```bash
npm run gate        # eslint + tsc (roda no pre-commit)
mvn -B verify       # spotless + checkstyle + testes + duplicação (roda no pre-push e no CI)
mvn spotless:apply  # corrige formatação Java automaticamente
```

Todo commit passa antes por uma trava de dados sensíveis (`.githooks/check-sensitive.sh`):
nomes proibidos (exports do Takeout, `.mbox`, `.db`, `.env`, chaves), arquivos >5 MB,
marcadores pessoais e scan de segredos com [gitleaks](https://github.com/gitleaks/gitleaks)
(instale o binário no PATH — a trava falha fechada sem ele). O `pre-push` re-escaneia o
histórico inteiro. Exceção consciente: `ALLOW_SENSITIVE=1 git commit ...`.

Mudanças não-triviais começam por uma RFC — veja `docs/rfcs/README.md`.
