# Dowody: pipeline CI/CD do review kodu (M5L2–L3)

Repo: `wbiniecki/NextSlope-10x` (prywatne)
Workflow: `.github/workflows/review.yml` — "AI code review", job `Review`
Agent: `packages/code-reviewer` (Claude Agent SDK), opakowany w composite action `.github/actions/ai-reviewer`

Cała trójka dowodów pochodzi z **jednego PR-a**, więc układa się w spójną historię:
**PR #38 — "TEST: negative verification of the AI reviewer (DO NOT MERGE)"**, celowo z zasadzonym
defektem (edycja już zaaplikowanej migracji `V1__create_users.sql`), żeby udowodnić, że agent potrafi
nie tylko przepuścić, ale też **zablokować** zmianę.

Ścieżka nawigacji jest zgodna z aktualną dokumentacją GitHuba
([View workflow run history](https://docs.github.com/en/actions/how-tos/monitor-workflows/view-workflow-run-history),
[Use workflow run logs](https://docs.github.com/en/actions/how-tos/monitor-workflows/use-workflow-run-logs)).

---

## 1. Widok pipeline'u z co najmniej jednym widocznym jobem

**Nawigacja (wg dokumentacji):** repozytorium → zakładka **Actions** → w lewym pasku bocznym wybierz
workflow **AI code review** → z listy uruchomień kliknij nazwę runu. Otwiera się *workflow run summary*.

Skrót bezpośredni: https://github.com/wbiniecki/NextSlope-10x/actions/runs/31276149189

Co ma być w kadrze:
- nazwa workflow **AI code review**, trigger `pull_request`, branch `test/negative-verification`
- sekcja **Jobs** (lewa kolumna) *albo* **visualization graph** na środku — w obu widać job **Review**
  ze statusem success; to jest wymagany "co najmniej jeden widoczny job"
- **Bonus, mocno warty tego samego zrzutu:** na tej samej stronie renderuje się **job summary** —
  pełny raport code review w Markdownie (werdykt, tabela ocen kryteriów, findings). Zapisuje go krok
  *Publish the report to the job summary* przez `$GITHUB_STEP_SUMMARY`, a GitHub pokazuje go
  **na stronie podsumowania runu, nie wewnątrz widoku joba**. Jeden zrzut załatwia więc "widok
  pipeline'u z jobem" i pokazuje treść review.

Widok listy wszystkich uruchomień (opcjonalny drugi zrzut — dowód, że pipeline chodzi rutynowo na
każdym PR): https://github.com/wbiniecki/NextSlope-10x/actions/workflows/review.yml

## 2. Logi z joba podczas wykonywania operacji code review

**Nawigacja:** ze strony runu, pod **Jobs** (lub w grafie) kliknij job **Review** → rozwiń krok
**"Review the diff"** (krok nr **5**).

Skrót bezpośredni: https://github.com/wbiniecki/NextSlope-10x/actions/runs/31276149189/job/93149875161

Trzy udogodnienia z dokumentacji, które warto wykorzystać:

- **Search logs** — pole wyszukiwania w prawym górnym rogu okna logu. Wpisz `verdict` albo `Blocked`,
  żeby wciągnąć kluczowe linie w kadr bez scrollowania. Uwaga: przeszukiwane są **tylko rozwinięte
  kroki**, więc najpierw rozwiń *Review the diff*.
- **Permalink do konkretnej linii** — kliknij numer linii w logu; adres w pasku przeglądarki zmieni
  się na trwały link z kotwicą (`...#step:5:<linia>`). To lepszy dowód niż sam zrzut, bo prowadzi do
  źródła; można go wkleić obok screenshota.
- **Download log archive** — rozwijane menu w prawym górnym rogu logu. Archiwum jest już pobrane
  offline jako `pr-38-run-31276149189-logs.zip` (odpowiednik przez API/CLI).

Kluczowy fragment (pełny log: `pr-38-run-31276149189-full.log`, wyciąg: `excerpt-review-operation.log`):

```
diff: /home/runner/work/_temp/pr.diff (119249 bytes, limit 200000)
configured model: claude-sonnet-5, budget: $0.50, max turns: 3
fail-on: high
resolved model: claude-sonnet-5 (2.1.224)
Blocked — 1 finding(s) at or above high:
turns: 3, total cost: $0.4558
  - critical: flyway-forward-only at src/main/resources/db/migration/V1__create_users.sql:6 — ...
Wrote /home/runner/work/_temp/ai-reviewer/review.json and /home/runner/work/_temp/ai-reviewer/review.md
Reviewer exited 3 -> verdict=blocked
```

To jest dokładnie "operacja code review w trakcie wykonywania": wejście (diff + limit), konfiguracja
modelu i budżetu, wynik agenta, mapowanie kodu wyjścia `3` na werdykt `blocked`.

**Uwaga do numeracji kroków na zrzucie** (żeby nie wyglądała na dziurawą): GitHub sam dokłada do
każdego joba kroki *Set up job* (1) i *Complete job*, krok 2 (*Consume the ai-cr:review retry label*)
jest `skipped`, bo ten run nie był wywołany etykietą, a kroki *Post Review the diff* / *Post Checkout*
to automatyczne sprzątanie po akcjach. Właściwy pipeline to kroki 3–8.

## 3. Działanie na PR — komentarz code review od agenta

Zrzut z: https://github.com/wbiniecki/NextSlope-10x/pull/38#issuecomment-5227908690

Co ma być w kadrze:
- autor komentarza **github-actions[bot]**
- nagłówek **"NextSlope code review"** i werdykt **"Blocked — 1 blocking finding at or above `high`"**
- sekcja **Blocking reasons** + tabela **Criterion scores** + sekcja **Findings** z plikiem i linią
- stopka `Reviewed commit d7e2299 · workflow run` (link z powrotem do runu z punktów 1 i 2)

W tym samym PR jest drugi komentarz (https://github.com/wbiniecki/NextSlope-10x/pull/38#issuecomment-5227919216)
po commicie cofającym defekt — werdykt **Passed**, co pokazuje, że etykieta `ai-cr:failed` wraca na
`ai-cr:passed`. Warto zrobić zrzut całej osi PR-a, żeby oba komentarze i zmiana etykiety były widoczne.

Przykład z realnego PR-a z feature'em (nie testowego):
https://github.com/wbiniecki/NextSlope-10x/pull/40#issuecomment-5243312615

---

## Pliki offline w tym folderze

| Plik | Zawartość |
| --- | --- |
| `pr-38-run-31276149189-full.log` | pełny log joba `Review` (474 linie) — `gh run view --job 93149875161 --log` |
| `pr-38-run-31276149189-logs.zip` | oficjalne archiwum logów runu (odpowiednik *Download log archive* z UI) |
| `excerpt-review-operation.log` | wyciąg z kroku *Review the diff* — sama operacja code review |
| `pr-38-agent-comments.md` | oba komentarze agenta z PR #38 (blocked + passed) |
| `pr-40-agent-comment.md` | komentarz agenta z realnego PR-a #40 |

Odtworzenie z CLI (udokumentowane komendy `gh`):

```bash
gh run list --workflow review.yml                      # lista uruchomień
gh run view 31276149189 --verbose                      # run + kroki jobów
gh run view --job 93149875161 --log                    # pełny log joba
gh api repos/wbiniecki/NextSlope-10x/actions/runs/31276149189/logs > logs.zip
```

## Kontekst projektowy (gdyby był potrzebny opis, nie tylko zrzuty)

- Specyfikacja i decyzje: `context/archive/2026-08-08-ci-cd-code-review/requirements.md`
- Definicja workflow: `.github/workflows/review.yml`
- Agent recenzujący: `packages/code-reviewer/`
- Świadoma decyzja: workflow jest **oddzielny od `ci.yml` i nie jest required check** — bramki Gradle
  są deterministyczne, werdykt LLM probabilistyczny, więc nie może blokować poprawnego kodu.
