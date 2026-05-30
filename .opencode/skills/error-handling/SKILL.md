---
name: error-handling
description: Linee guida per la gestione degli errori e creazione automatica di issue GitHub in caso di malfunzionamenti
---

# Skill: Error Handling

Regole per la gestione degli errori e la creazione automatica di issue GitHub.

## Principi generali

1. **Ogni nuova funzionalità DEVE prevedere la gestione degli errori.**
2. **Ogni errore a runtime DEVE essere segnalato** tramite `GitHubIssueReporter.reportError()`.
3. **Gli errori non devono mai essere silenziosi** — se non è possibile creare una issue, loggare almeno con `Log.e()`.
4. **I messaggi di errore devono essere descrittivi** e includere il contesto (versione app, timestamp, azione che ha causato l'errore).

## Pattern per la segnalazione errori

### 1. Uso di GitHubIssueReporter

La classe `GitHubIssueReporter` (in `app/src/main/java/com/uscrooge/app/integration/`) è il punto centrale per la creazione automatica di issue.

```kotlin
gitHubIssueReporter.reportError(
    title = "Titolo descrittivo dell'errore",
    body = buildString {
        appendLine("## Contesto Errore")
        appendLine()
        appendLine("**Errore:** $errorMessage")
        appendLine("**Versione App:** ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("**Timestamp:** $timestamp")
    },
    labels = listOf("bug", "auto-reported")
)
```

### 2. Labels predefinite

| Label | Quando usarla |
| --- | --- |
| `bug` | Sempre, per errori a runtime |
| `auto-reported` | Sempre, per issue create automaticamente |
| `update-check` | Errori nel controllo aggiornamenti |
| `trading` | Errori durante l'esecuzione di trading |
| `api` | Errori di connessione API (Kraken, Alpaca, GitHub) |
| `circuit-breaker` | Quando scatta il Circuit Breaker |

### 3. Dove implementare la segnalazione

- **ViewModel**: nei blocchi `onFailure` / `catch` delle coroutine
- **Worker**: nel `try/catch` di `doWork()` (usare il costruttore con `GitHubIssueReporter` iniettato via Hilt)
- **Repository**: propagare l'errore al chiamante, non gestirlo direttamente
- **Service / ApiClient**: propagare l'errore con `Result.failure()`

### 4. Pattern Result

Preferire `Result<T>` per le chiamate che possono fallire:

```kotlin
fun operation(): Result<SuccessType> {
    return try {
        // operazione
        Result.success(value)
    } catch (e: Exception) {
        Log.e(TAG, "Contesto operazione fallita", e)
        Result.failure(e)
    }
}
```

### 5. Errori utente vs errori interni

- **Errori utente**: mostrare un messaggio chiaro nell'interfaccia (es. `UpdateCheckState.Error` con testo descrittivo)
- **Errori interni**: creare issue GitHub + loggare con `Log.e()`

### 6. Esempio completo

```kotlin
fun performAction() {
    viewModelScope.launch {
        try {
            _uiState.value = UiState.Loading
            val result = repository.call()
            _uiState.value = UiState.Success(result)
        } catch (e: Exception) {
            _uiState.value = UiState.Error(e.message ?: "Errore sconosciuto")
            Log.e(TAG, "performAction failed", e)
            gitHubIssueReporter.reportError(
                title = "performAction failed",
                body = "Error: ${e.message}\nVersion: ${BuildConfig.VERSION_NAME}",
                labels = listOf("bug", "auto-reported")
            )
        }
    }
}
```

## Struttura issue GitHub

Le issue create automaticamente DEVONO seguire questo formato:

```text
## Contesto Errore

**Errore:** <messaggio errore>
**Versione App:** <versione> (<codice build>)
**Timestamp:** <data e ora>

## Dettagli

<informazioni aggiuntive>

---

_Issue creata automaticamente dal sistema di error handling._
```

## Verifica

- Verificare sempre che il `GitHubIssueReporter` sia correttamente inizializzato tramite Hilt
- Il token GitHub (`REPORTING_GH_TOKEN`) deve essere configurato nelle variabili d'ambiente o in `local.properties`
- Testare la segnalazione errori simulando un fallimento
