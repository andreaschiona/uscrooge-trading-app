---
name: version-management
description: "Use when modifying code, creating branches, writing commits, or preparing pull requests. Enforces the mandatory workflow: GitHub Issue -> feature branch -> conventional commits -> pull request. The commit message type determines the next MAJOR/MINOR/PATCH version bump. Use ONLY when the task involves code changes; not needed for pure questions or documentation."
---

# Version Management

Regole per gestire il versionamento semantico del progetto tramite conventional commit.

## Workflow obbligatorio

### 1. Issue GitHub

Ogni modifica al codice DEVE partire da una issue GitHub.
- Se l'utente non ha già fornito una issue, creala tu stesso su GitHub.
- Annota nella issue eventuali scelte architetturali o funzionali importanti prima di procedere con l'implementazione.
- Il titolo della issue deve essere in italiano.
- Se non è specificata una milestone, non impostarla.

### 2. Feature branch

- Crea un feature branch con nome `issue<numero>-<breve-descrizione>` (es. `issue42-add-login`).
- Il branch DEVE partire da `main`.

### 3. Commit

I messaggi di commit DEVONO seguire il **Conventional Commits** standard, perché il workflow di release usa il messaggio per determinare il bump di versione:

| Pattern nel messaggio | Bump versione |
|---|---|
| `BREAKING CHANGE` o `!:` (es. `feat!:`) | **MAJOR** |
| `feat:` | **MINOR** |
| `fix:`, `chore:`, `docs:`, `refactor:`, `test:`, `perf:`, `style:`, `ci:`, `build:` | **PATCH** |

Esempi:
- `feat: add login screen` → MINOR bump alla prossima release
- `fix: handle null pointer in parser` → PATCH bump
- `feat!: change API response format` → MAJOR bump
- `refactor: extract auth module` → PATCH bump

Se un commit contiene `BREAKING CHANGE` nel body o `!:` dopo il tipo, l'intera release sarà MAJOR.

### 4. Pull Request

- Alla fine delle modifiche, crea una Pull Request su GitHub.
- Il titolo della PR dovrebbe iniziare con il tipo conventional commit (es. `feat:`, `fix:`, `refactor:`).
- La descrizione della PR DEVE includere `Closes #<numero-issue>` per collegare automaticamente la issue.
- Il base branch DEVE essere `main`.

## Struttura versione

Le proprietà di versione sono in `gradle.properties`:
```
appVersionMajor=1
appVersionMinor=2
appVersionPatch=18
appVersionCode=25
```

Il workflow di release (`release.yml`) legge da lì e incrementa automaticamente il componente corretto in base ai messaggi di commit.
Non modificare mai le versioni a mano nei commit regolari: lascia che sia il workflow di release a gestirle.
