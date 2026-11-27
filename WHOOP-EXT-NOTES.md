# WHOOP 4.0 – tryb rozszerzony (spike): notatki

Status: spike. Nowe pliki: `WhoopExtProtocol.kt`, `WhoopExtDiagActivity.kt` (pakiet `pl.forma.app`). Żaden istniejący plik nie został zmieniony.
Research z dnia 2026-11-26. Część repozytoriów (OpenStrap/protocol, OpenStrap/research, dannymcc/edge, ujix/noop, bWanShiTong przez gh) była nieczytelna z tej sesji (brak dostępu `gh`, odmowa/timeout zgody dla WebFetch); patrz kolumna "Jak czytane".

## 1. Projekty: licencja, dane, pewność

| Projekt | Licencja | Jak czytane | Dane (deklarowane) | Pewność | Działa bez członkostwa? |
|---|---|---|---|---|---|
| bWanShiTong/reverse-engineering-whoop-post (+ mintlify) | Brak licencji na stronie (traktuj jako: tylko fakty, bez kopiowania) | README + 10 podstron mintlify | UUID-y, ramka, przykłady poleceń, HR+RR na żywo, rekord z pamięci (31 z ~96 B), sync | Ramka, UUID, przykłady poleceń: wysoka (sprawdzone, patrz 2). Opis CRC w tekście: błędny/mylący. Reszta rekordu: nierozpoznana | Tak, używa gatttool bez konta |
| OpenStrap (org): protocol, edge, research, analytics, backend | MIT (strona organizacji wymienia MIT dla 5 repo; pliku LICENSE nie otwarto) | Strona org + README edge + lustro protocol (niezweryfikowane lustro `bulgariahome.duckdns.org`) | HR (zweryfikowane), RR, temp. skóry, SpO2, akcelerometr (empiryczne), resting HR, sen, strain | HR wysoka; temp/SpO2/accel "empiryczne" wg autora (wnioskowane z zachowania bajtów, nie potwierdzone) | Tak: "używaj urządzeń poza subskrypcją", nie łączy się z serwerami WHOOP |
| dannymcc/edge | Nieczytelna (LICENSE + NOTICE.md istnieją) | README (wygląda na fork OpenStrap/edge) | Jak OpenStrap | j.w. | Tak (j.w.) |
| Markus-Wildgruber/edge_whoop | Nieczytelna (plik LICENSE istnieje) | README | Flutter, drain GATT z potwierdzeniami, HR, sen, strain, HRV, SpO2 (beta) | Szacunki oznaczone poziomem pewności | Tak, "dla opaski, którą posiadasz" |
| christianmeurer/whoop-reader | MIT (README + LICENSE) | README | HR, RR, SpO2, temp. (rzekomo "decoded"), accel "candidate (unconfirmed)", bateria i info osobnymi poleceniami | NISKA: układ 96-bajtowego pakietu (HR bajt 1-2, RR 3-4, SpO2 5, temp 6) jest sprzeczny z zweryfikowaną ramką i z resztą źródeł; opis CRC (init 0xFFFFFFFF) nie zgadza się z wektorami testowymi. Nie używać jako źródła offsetów | Tak ("nie obchodzi paywalla") |
| ujix/noop (NOOP) | PolyForm Noncommercial 1.0.0 (nie OSI, nie permisywna). Kodu NIE kopiować | Wiki FAQ | WHOOP: HR na żywo, bateria, grawitacja do sen. Temp/SpO2 głównie Oura; dla WHOOP 5.0/MG SpO2 "nie do odzyskania offline" | Średnia; deklaruje, że fakty protokołu wolno wykorzystać | Tak ("WHOOP use requires no account") |
| cs-balazs/gowhoop | Brak widocznej licencji (= wszystkie prawa zastrzeżone; nie kopiować) | README | HR i accel "prawdopodobnie poprawne", brak dokumentacji | Niska | Nie dotyczy |

Wniosek licencyjny: nic nie skopiowano. `WhoopExtProtocol.kt` to własna implementacja z faktów protokołu i z naszych własnych testów na opublikowanych wektorach. Gdyby kiedyś wziąć kod z OpenStrap/protocol (MIT), trzeba zachować jego notę licencyjną.

Członkostwo: żadne źródło nie opisuje blokady po stronie opaski wymagającej aktywnego członkostwa; wszystkie opisują bezpośrednie połączenie BLE bez serwerów WHOOP. Zastrzeżenia: (a) wszyscy mówią, że opaska łączy się z jedną aplikacją naraz i że trzeba zamknąć aplikację WHOOP; (b) parowanie z inną aplikacją może zabrać opasce powiązanie z aplikacją WHOOP (NOOP FAQ); (c) nikt nie opisuje opaski nigdy niezaktywowanej; (d) zegar opaski, jeśli nigdy nie zsynchronizowany, może liczyć od 1970 (Hackaday), więc znaczniki czasu mogą być dziwne. Tego nie zmieniamy (ustawianie zegara to zmiana ustawień).

## 2. Protokół (co jest pewne)

Usługa własna: UUID-y kończą się na `-8d6d-82b8-614a-1c8cb0f8dcc6`. Źródła różnią się co do pierwszej grupy usługi (`61080000` vs `61080001`), dlatego kod wykrywa usługę po końcówce UUID, a charakterystyki po pierwszych 8 znakach.

| Charakterystyka | Rola | Źródło |
|---|---|---|
| 61080002 CMD_TO_STRAP | jedyna do zapisu | post/mintlify |
| 61080003 CMD_FROM_STRAP | powiadomienia: odpowiedzi na polecenia | post/mintlify |
| 61080004 EVENTS_FROM_STRAP | powiadomienia: zdarzenia (whoop-reader nazywa ją strumieniem danych; subskrybujemy wszystkie) | post/mintlify |
| 61080005 DATA_FROM_STRAP | powiadomienia: dane na żywo i z pamięci | post/mintlify |
| 61080007 MEMFAULT | powiadomienia: diagnostyka, cel nieznany | post/mintlify |

Ramka (nasz własny test: wszystkie 5 opublikowanych poleceń z CRC oraz 5 opublikowanych powiadomień z opaski dają zgodne CRC):

```
0xAA | długość u16 LE | crc8(2 bajty długości) | ładunek | crc32(ładunek) u32 LE
długość = rozmiar ładunku + 4
crc8   = wielomian 0x07, init 0, bez odbicia
crc32  = standardowy CRC-32 (zlib/IEEE), tylko po ładunku
ładunek = typ(1) | licznik(1) | polecenie lub dane...
```

Opis w źródłach ("wielomian 0x04C11DB7, init 0, odbicie wejścia i wyjścia, xorout 0xF43F44AC, po całej ramce wraz z nagłówkiem") to ten sam wynik opisany inaczej; wektory testowe zgadzają się tylko ze standardowym CRC-32 po ładunku. Test: `aa0800a8238c0301` + `7d5ec627`, `aa0800a8238d0300` + `dc040351`, `aa0800a823080e01` + `6c935474` (pierwsze 4 bajty ramki = `aa 08 00 a8`; `a8` to crc8 z `08 00`). `WhoopExtProtocol.selfTest()` sprawdza dwa z nich przy każdym uruchomieniu ekranu.

Typy ładunku: `0x23` polecenie, `0x24` odpowiedź na polecenie (z pamięci innych notatek, niezweryfikowane), `0x28` dane na żywo, `0x2F` rekord z pamięci, `0x30` zdarzenie, `0x31` status synchronizacji.

Pakiety są dłuższe niż domyślne 20 bajtów powiadomienia: ekran żąda MTU 247 i składa ramki z fragmentów (`FrameAssembler`).

### Układ pakietów (indeksy w ładunku)

Na żywo `0x28` (20 B): `[2..5]` czas unix, `[8]` HR, `[9]` liczba RR, `[10..17]` 4 x u16 RR w ms. Brak temperatury, SpO2 i ruchu w tym typie pakietu (żadne źródło ich tam nie umieszcza).

Rekord z pamięci `0x2F` (1 Hz, ok. 88-89 B): `[3..6]` licznik, `[7..10]` czas unix, `[17]` HR (zweryfikowane przez OpenStrap), `[18]` liczba RR, `[19..26]` RR u16 ms; EMPIRYCZNIE (OpenStrap): `[36..47]` 3 x float32 LE przyspieszenie w g, `[70]` temperatura skóry = surowa/4 °C, `[72]` SpO2 %, `[88]` tętno spoczynkowe. Uwaga: opublikowany przykład ma 88-bajtowy ładunek, a OpenStrap oczekuje >= 89 (możliwe przesunięcie o 1 lub inne oprogramowanie); CSV z opaski użytkownika to rozstrzygnie.

Status sync `0x31` (przykład `aa1c00ab31...`): `[3..6]` czas, `[13..16]` numer paczki (w przykładzie 0x0001472E = 83758). Odesłanie tego numeru poleceniem `0x17` to potwierdzenie paczki; my go NIE wysyłamy.

Temperatura: jednostki to według OpenStrap surowa wartość/4 w °C. Hackaday: odczyty analogowe (np. temperatura otoczenia) to względne wartości ADC wymagające kalibracji. Dlatego pokazujemy surową wartość obok °C i filtrujemy do przedziału 20 do 45 °C.

## 3. Lista poleceń (stan po teście na prawdziwej opasce, 2026-11-27)

Wymuszone typem: `WhoopExtProtocol.SafeCommand` to enum z 4 pozycjami plus jedno polecenie z bramką (ACK 0x17). Innych poleceń nie da się zbudować. Format: ładunek `23 seq id wartość`.

| Polecenie | ID/wartość | Klasa | Wynik na prawdziwej opasce |
|---|---|---|---|
| Start odczytu na żywo | 0x03 / 0x01 | SAFE-READ (przełącznik strumienia) | Odpowiedź 0x24, status 2; potem pakiety 0x28 co ok. 1 s |
| Stop | 0x03 / 0x00 | SAFE-READ | Odpowiedź 0x24, status 2 |
| Informacje o opasce (HELLO) | 0x23 / 0x00 | SAFE-READ | Sprawdzone na opasce: BRAK odpowiedzi (3 razy w 3 sesjach). Przycisk zostaje, ale nie liczymy na niego |
| Prośba o dane z pamięci | 0x16 / 0x00 | SAFE-READ (bez ACK nic się nie zwalnia) | Odpowiedź 0x24 status 2 (dane `0b 00 00`), potem 0x31, 0x32, 0x31 koniec paczki. Bez ACK opaska powtarza tę samą paczkę |
| Potwierdzenie paczki (ACK) | 0x17 / patrz 8 | RYZYKOWNE, tylko z bramki synchronizacji | Zgoda użytkownika 2026-11-27: zwalnia pamięć opaski. Budowane wyłącznie po zapisie i fsync paczki |
| ~~Odczyt baterii~~ | 0x1A | usunięte | Brak odpowiedzi w 9 próbach, więc ID nie działa na tej opasce; inne źródła podają to samo ID (patrz 9) |

Stała `HISTORY_PULL_ENABLED = true` w `WhoopExtProtocol.kt`; ustaw `false`, a przyciski podglądu i synchronizacji znikną, a polecenie 0x16 i ACK przestaną być budowalne.

Ryzykowne, NIE zaimplementowane (brak stałych w kodzie):

| ID | Co robi | Powód |
|---|---|---|
| 0x19 | Kasowanie danych (magiczne bajty fe x8) | Nieodwracalne |
| 0x1D | Restart opaski | Przerywa pracę |
| 0x42 (oraz 0x43/0x44 z pamięci) | Ustawienie / uruchomienie alarmu | Zmienia ustawienia, wibruje |
| 0x13 (z pamięci) | Wzór wibracji | Wibruje |
| 0x0E | Nadawanie tętna (HR broadcast) włącz/wyłącz | Zmiana ustawienia; wyłączenie ukrywa opaskę przed skanowaniem i zepsułoby Formę |
| 0x17 | Potwierdzenie paczki sync | Pozwala opasce zwolnić dane, zmienia stan pamięci |
| 0x0A (z pamięci) | Ustaw zegar | Zmiana ustawień |
| 0x4D (z pamięci) | Zmiana nazwy | Zmiana ustawień |
| 0x73, 0x74, 0x05, 0x06, 0x23 | "Włącz funkcje", tryb monitora zdrowia, hello/sync | Skutki nieudokumentowane |
| 0x51, 0x6A, 0x69 (z pamięci) | Surowe dane / tryby IMU | Wysoka częstotliwość, bateria, nieznane skutki |
| 0x4C | Odczyt nazwy | Bezpieczne, ale niepotrzebne, więc pominięte |

## 4. Co jest zgadywane

- Pozycje temp/SpO2/accel w rekordzie z pamięci i skala temperatury (empiryczne wg OpenStrap).
- Układ odpowiedzi na baterię (próbujemy u16 LE przy `[4]` / 10, albo u8 przy `[4]`); w UI "przypuszczalnie".
- ID baterii 0x1A i typ odpowiedzi 0x24 (z pamięci innych notatek).
- Czy 0x16 bez potwierdzenia faktycznie niczego nie zwalnia na opasce.
- Czy opaska bez członkostwa w ogóle chętnie odpowiada na polecenia (to właśnie sprawdza spike).

## 5. Instrukcja testu dla użytkownika (5 kroków)

1. W Formie zatrzymaj pomiar nocny. Zamknij aplikację WHOOP (nawet jeśli nie masz konta). Załóż opaskę.
2. Otwórz „Diagnostyka WHOOP – tryb rozszerzony” i naciśnij „Połącz”. Forma sama odłączy pomiar na żywo. Poczekaj na zielony napis „Gotowe”.
3. Naciśnij „Start odczytu na żywo” (po ok. 30 s „Stop”). Przy każdym przycisku zobaczysz „Wysłano… / Odpowiedź… / Brak odpowiedzi po 5 s”.
4. Naciśnij „Synchronizuj pamięć opaski”, potwierdź i poczekaj, aż pojawi się napis o zakończeniu. Możesz w każdej chwili nacisnąć „Stop synchronizacji” (to bezpieczne, nic się nie traci).
5. Forma sama zapisze pliki w Pobranych/Forma. Naciśnij „Udostępnij…” przy plikach archiwum i wyślij: `whoop_archive_daily.csv`, `whoop_archive_records.jsonl` i surowy plik `whoop_ext_*.csv`. Zachowaj te pliki: po potwierdzeniu opaska zwalnia pamięć i danych nie da się pobrać drugi raz.

## 6. Co odesłać

Pliki z Pobranych/Forma: `whoop_ext_RRRRMMDD_GGMMSS.csv` (surowe pakiety: czas, dir RX/TX/INFO, charakterystyka, hex), `whoop_archive_records.jsonl` i `whoop_archive_daily.csv` (patrz 10).

## 7. Weryfikacja kodu

`WhoopExtProtocol.kt` (razem z `WhoopArchive`) skompilowano kompilatorem Kotlin 2.0.21 dołączonym do Gradle i uruchomiono na: wektorach CRC, składaniu ramek z fragmentów, ramkach z obu plików CSV użytkownika (0x28, 0x24, 0x31, 0x32, 0x30, MEMFAULT: 0 błędnych CRC), budowie ACK (nagłówek `aa10005723` zgodny z postem), 20 000 losowych buforach (bez wyjątków) i syntetycznej dobie (odczyt JSONL, RMSSD, spoczynkowe HR, ruch). `WhoopExtDiagActivity.kt` NIE był kompilowany z prawdziwym Android SDK (brak SDK); przeszedł kontrolę typów i przepływu względem własnych zaślepek API Androida. Różnice między zaślepkami a prawdziwym API to główne ryzyko kompilacji.

## 8. Synchronizacja pamięci opaski: przebieg i ACK

Przebieg (tylko `WhoopExtDiagActivity`): `0x16` -> odpowiedź `0x24` -> pakiety `0x31` (początek), `0x2F` (dane czujników), `0x32` (dziennik tekstowy) -> `0x31` koniec paczki -> zapis wszystkich surowych pakietów paczki do CSV oraz rekordów do JSONL, `flush` + `fsync` obu plików -> dopiero wtedy ACK `0x17` -> opaska wysyła następną paczkę (lub po 8 s wysyłamy `0x16` jeszcze raz) -> koniec, gdy przez 10 s nic nie przychodzi albo użytkownik naciśnie Stop. Przerwanie w dowolnym momencie jest bezpieczne: niepotwierdzona paczka zostaje w opasce i zostanie wysłana ponownie.

Zabezpieczenia: brak potwierdzenia, gdy zapis lub fsync się nie powiedzie, plik przekroczy limit (400 MB surowy, 800 MB JSONL) albo na telefonie zostaje mniej niż 40 MB; po potwierdzeniu, jeśli opaska ponownie wyśle ten sam numer paczki, aplikacja raz próbuje wariantu ACK z ogonem opaski, a przy drugim powtórzeniu zatrzymuje się; limit 500 paczek na sesję.

Pakiet końca paczki (typ 0x31, podtyp 2), indeksy w ładunku: `[3..6]` czas opaski, `[13..16]` numer paczki / "trim" u32 LE. Na prawdziwej opasce: `aa1c00ab31 05 02 2c6b0200 384c32000000 98450100 38000000 00000000 ...` czyli trim = 0x00014598 = 83352.

**ACK (polecenie 0x17), ładunek 12 bajtów:** `23 | seq | 17 | 01 | trim u32 LE | 00 00 00 00`, ramka 20 bajtów, nagłówek `aa 10 00 57`. Dla paczki z opaski użytkownika: `aa100057235017019845010000000000aebac79f` (seq 0x50). Źródła, które się zgadzają: post bWanShiTong (`aa10005723{count}1701{batch}00000000{crc}`, nagłówek identyczny), AStrasler/whoop4-ble (MIT; `[01][LE u32 trim][00 00 00 00]`, trim na bajcie 10 danych metadanych, czyli ładunek[13]; starszy offset 8 powodował pętlę powtórzeń), ZHOOP (`[01] + end_data`). Wariant rezerwowy (zordhalo/whoopsie-protocol, bez licencji, tylko fakty): po `01` całe 8 bajtów znacznika paczki z opaski, czyli `98450100 38000000`: `aa100057235017019845010038000000e06a58aa`. Aplikacja używa go tylko po wykryciu powtórzenia paczki.

Nieznane: znaczenie bajtu `38`/`04` po trim w pakiecie końca paczki; czy opaska odpowiada na ACK pakietem 0x24 (brak odpowiedzi nie przerywa, o powodzeniu świadczy następna paczka); czy po ACK paczka naprawdę jest kasowana (źródła mówią, że bez ACK opaska powtarza ją w nieskończoność).

## 9. Co odkryła opaska użytkownika (z dwóch plików CSV)

- Usługa ma UUID `61080001-…`, połączenie stabilne po zwolnieniu łącza Formy.
- Na żywo 0x28: `[2..5]` licznik sekund od startu opaski (ok. 158 000, to czas pracy, nie Unix), `[8]` HR, `[9]` liczba RR, potem u16 RR w ms. Potwierdzone.
- Odpowiedź 0x24: `[1]` licznik, `[2]` id polecenia, `[3]` echo naszego seq, `[4]` status (2 przy 0x03 i 0x16).
- 0x1A (bateria): brak odpowiedzi w 9 próbach. Inne źródła (ZHOOP, whoopsie, OpenStrap) podają to samo ID 26, więc lepszego ID nie ma. ZHOOP pisze, że na tej rodzinie opasek odpowiedź dla "pack" jest zaślepką z zerami. Inne miejsca z baterią według źródeł: odpowiedź HELLO 0x23 (u32 LE / 10, offset niepewny; aplikacja pokazuje kandydatów w dzienniku, nie wartość) oraz zdarzenie 0x03 (u32 / 10), zdarzenie 0x11 to temperatura skóry (i16 / 10 °C). To są odczyty przypuszczalne, oznaczone w ekranie.
- Dziennik 0x32: `[1..2]` licznik rekordu u16, `[4..7]` czas Unix (np. 0x61c54e7f = 2021-12-24), `[13..]` tekst ASCII ucięty na kawałki ok. 46 znaków. W pierwszej paczce 72 rekordy, w tym 50 unikalnych wpisów z `LC709203: error reported, error code 1 severity 1` (układ pomiaru baterii). Ekran pokazuje liczbę takich wpisów.
- Pakiet MEMFAULT: tekst `copley`, `9.9.3.0`, `copley_r14` (prawdopodobnie wersja oprogramowania opaski).
- Obie próby zwróciły 0 rekordów 0x2F (danych czujników): pamięć opaski miała tylko dziennik tekstowy.
- Pakiet 0x31 podtyp 3 (`31 03 03 ...`) przyszedł PRZED początkiem paczki, więc nie oznacza końca; aplikacja traktuje go tylko informacyjnie.

Czas: licznik z 0x28 daje przesunięcie "sekundy opaski -> czas telefonu" (minimum z ostatnich 30 próbek, bo opóźnienie dostarczenia tylko je zwiększa; restart opaski czyści okno). Wartości >= 1 000 000 000 to czas Unix i są używane bezpośrednio; mniejsze to czas od startu opaski i dostają oznaczenie "przybliżony" (rekordy sprzed obecnego uruchomienia opaski nie dadzą się poprawnie zamapować). Zegara opaski NIE ustawiamy (SET_CLOCK 0x0A to zmiana ustawień).

## 10. Archiwum: pliki i stabilne kolumny

Pliki w pamięci aplikacji, kopiowane do `Pobrane/Forma` (istniejący plik o tej samej nazwie jest zastępowany): `whoop_archive_records.jsonl`, `whoop_archive_daily.csv`, `whoop_ext_RRRRMMDD_GGMMSS.csv`. Zapis automatycznie po synchronizacji oraz przyciskiem „Zapisz archiwum w Pobranych/Forma”; przy każdym jest „Udostępnij”.

**`whoop_archive_records.jsonl`**: jeden rekord 0x2F na linię, dopisywany w miarę odbioru (powtórzenia po przerwanej paczce możliwe, usuwane przy liczeniu dni). Pola: `clk` ("unix" | "uptime"), `raw` (czas z rekordu w sekundach), `ctr` (licznik rekordu), `t` (czas telefonu w ms, brak gdy nie dało się zmapować), `rx` (czas odbioru w ms, do późniejszego ponownego mapowania), `hr`, `rr` (lista ms), `temp` (°C), `tr` (surowa wartość temp.), `spo2`, `ax`,`ay`,`az` (g). Pola puste są pomijane.

**`whoop_archive_daily.csv`**: kolumny stałe i w tej kolejności:
`date,clock,records,first_ts,last_ts,hr_min,hr_avg,hr_rest_est,night_records,rmssd_ms,ln_rmssd,rr_used,temp_avg_c,spo2_avg,motion_min`
- `date`: data lokalna (strefa telefonu) yyyy-MM-dd; `clock`: "unix" lub "uptime" (zmapowany, przybliżony). Jeden wiersz na parę (date, clock).
- `records`: liczba unikalnych rekordów (klucz: `raw` + `ctr`); `first_ts`,`last_ts`: Unix w sekundach.
- `hr_min`, `hr_avg`: z rekordów z HR 25..230.
- `hr_rest_est`: najniższa 5-minutowa średnia HR w oknie nocnym 00:00-06:00 (przedział musi mieć >= 30 próbek); `night_records`: rekordy w tym oknie.
- `rmssd_ms`, `ln_rmssd`, `rr_used`: RMSSD z RR w oknie nocnym. Czyszczenie: zostaje 300..2000 ms, potem odrzucane jest uderzenie różniące się o więcej niż 20 % od mediany 5 sąsiednich uderzeń (z nim samym); liczone są tylko różnice kolejnych zachowanych uderzeń oddalonych w czasie o <= 3 s; pusto, gdy mniej niż 30 różnic. (Zasady wzorowane na regułach artefaktów z aplikacji; do dostrojenia, jeśli reguły w web-app różnią się.)
- `temp_avg_c`: średnia temperatura skóry, EMPIRYCZNIE (bajt 70 rekordu / 4, zakres 20..45 °C); `spo2_avg`: EMPIRYCZNIE (bajt 72, zakres 50..100).
- `motion_min`: EMPIRYCZNIE: minuta zegarowa (>= 10 próbek) liczy się jako ruch, gdy max - min z |przyspieszenia| przekracza 0,10 g.
Plik jest przeliczany w całości z JSONL (przycisk „Przelicz archiwum dzienne”), więc jest zawsze spójny i bez duplikatów.

## 11. Układ rekordu 0x2F: co jest potwierdzone

Źródła porównane: post/OpenStrap (czas `[7..10]`, HR `[17]`), whoopsie (R10: czas ramka[11..14] czyli `[7..10]`, HR ramka[21] czyli `[17]`), zgodne w trzech miejscach. Temperatura wg whoopsie i AStrasler NIE leży w rekordzie 0x2F, tylko w zdarzeniu 0x11 (temperatura) i 0x03 (bateria); OpenStrap podaje bajt 70 / 4. Pole temperatury z rekordu pozostaje więc EMPIRYCZNE i może być puste; wartość ze zdarzenia jest osobnym źródłem. Opaska użytkownika nie zwróciła jeszcze żadnego rekordu 0x2F, więc offsety czujników nadal nie są potwierdzone na jej oprogramowaniu.

Dodatkowe źródła tej aktualizacji: AStrasler/whoop4-ble (MIT), zordhalo/whoopsie-protocol (bez licencji w repo; tylko fakty), OpenStrap/protocol PR 63 (battery: gen4 u16 LE deci-procent), ZHOOP (zulusierra.co: opis blokowanych poleceń 25, 29, 32, 36-38, 45, 99). Nie otwarto: ujix/noop docs (PROTOCOL_WHOOP4.md, PROTOCOL_SENSORS.md), rusheelraj.com (robots.txt).

## 12. Przeskok zaległości (eksperyment) i "Sprawdź nowe dane"

Stan wyjściowy (trzeci plik CSV, ok. 1 h synchronizacji): 245 paczek potwierdzonych (status 1), 37 136 rekordów, wszystkie to dziennik 0x32 (99,5% to błędy LC709203), 0 rekordów 0x2F, czas wpisów 2021-12-25 03:59 – 18:48, numer paczki rośnie o 0x18 (24) na paczkę (0x014598 -> 0x01F988). Pierwsze 20 potwierdzeń szło z zerowym ogonem (00000000), kolejne z ogonem opaski (38000000); oba warianty dają status 1.

### Polecenie zakresu: GET_DATA_RANGE
- Kod 0x22, jeden bajt argumentu 0x00 (goose PR 26: Gen4 wymaga argumentu). Źródła: zordhalo/whoopsie-protocol ("zakres zapisanych danych", przykład ramki), AStrasler/whoop4-ble ("zwraca bieżące przesunięcie trim i zakres paczek"), b-nnett/goose PR 26. Wszystkie trzy opisują je jako odczyt. Nie ma w nich opisanego układu odpowiedzi. Goose wspomina tylko, że "last_synced" jest w okolicy payload[10..14] i że istnieje druga wartość (page_current), np. 34274 i 61458 u nich.
- Ramka dla seq 0x02: aa0800a823022200824df537 (zgodna z przykładem whoopsie, CRC przeliczone naszym kodem). Seq 0x41: aa0800a8234122001b7e2945.
- Pewność: kod i ramka wysoka; układ odpowiedzi niska; zachowanie opaski po potwierdzeniu "z wyprzedzeniem" nieznane (żadne źródło go nie opisuje; 0x21 SET_READ_POINTER i 0x19 FORCE_TRIM są wyłączone na stałe).
- Odpowiedź nie jest czytana wg założonego układu. Aplikacja szuka w danych dwóch sąsiednich liczb u32 LE: jednej równej naszemu ostatniemu potwierdzonemu numerowi (do +48) i drugiej większej (< 0x10000000). Większa = najnowszy rekord. Gdy nie ma dokładnie jednej takiej pary, przeskok się nie odbywa i pokazuje się "Opaska nie podała zakresu – przeskok niemożliwy". Wszystkie liczby z odpowiedzi trafiają do CSV (wiersze NOTE).

### Przebieg przycisku "Przeskocz zaległość (usuń stary dziennik)"
1. Dwa okna z ostrzeżeniem o nieodwracalności.
2. 0x22 (odczyt zakresu). Brak odpowiedzi lub nierozpoznany układ: stop, nic nie zmieniono.
3. Zakres do dziennika i CSV (fsync), potem JEDNO potwierdzenie 0x17 z numerem = najnowszy z zakresu, zerowy ogon: payload 23 seq 17 01 <trim u32 LE> 00000000. Przykład (trim 129416, seq 0x50): aa1000572350170188f901000000000051aea904.
4. Czeka na 0x24 dla 0x17. Status inny niż 1 lub brak odpowiedzi po 5 s: stop, bez ponawiania, bez zgadywania.
5. Status 1: jedna prośba 0x16 BEZ potwierdzenia ("podgląd"): liczba rekordów 0x2F/0x32, zakresy czasu, pierwsze rekordy. Wpisy sprzed 2022 r. po przeskoku oznaczają, że przeskok nie zadziałał.
- Nigdy nie wysyłamy 0x19, 0x21 ani żadnego polecenia spoza listy. Walidacja kodu: sekcja 7 plus testy ramek 0x22 i 0x17 z dowolnym numerem (trim poza 1..0x0FFFFFFF jest odrzucany).

### "Sprawdź nowe dane"
Jedno 0x16 bez potwierdzenia, zlicza 0x2F i 0x32 i pokazuje najnowsze czasy. Bezpieczne, można powtarzać (dane zostają w opasce).

### Kroki dla użytkownika
1. Połącz z opaską (Forma zwolni własne połączenie), poczekaj na "gotowe".
2. Dół ekranu: "Przeskocz zaległość (usuń stary dziennik)", potwierdź dwa okna.
3. Przeczytaj wynik w ramce. Jeśli "przeskok niemożliwy" lub status inny niż 1, nic się nie stało, wyślij plik CSV.
4. Noś opaskę noc, rano "Sprawdź nowe dane". Jeśli są dane 0x2F: "Synchronizuj pamięć opaski".
5. Odeślij surowy plik CSV (zawiera zakres z opaski w wierszach NOTE).

