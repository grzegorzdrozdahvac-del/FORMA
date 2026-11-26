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

## 3. Lista SAFE-READ (jedyne polecenia możliwe do zbudowania)

Wymuszone typem: `WhoopExtProtocol.SafeCommand` to enum z 4 pozycjami; innych poleceń nie da się zbudować. Format: ładunek `23 seq id wartość`.

| Polecenie | ID/wartość | Dlaczego bezpieczne | Pewność ID |
|---|---|---|---|
| Start odczytu na żywo | 0x03 / 0x01 | Przełącznik strumienia 1 Hz (HR+RR), nic nie zapisuje; udokumentowane zatrzymanie 0x03/0x00. Zastrzeżenie: źródła nazywają to "activity tracking", może zmieniać tymczasowy tryb opaski; trwałych skutków nie opisano | Wysoka (post, 3 podstrony) |
| Stop | 0x03 / 0x00 | Jawne zatrzymanie strumienia | Wysoka |
| Odczyt baterii | 0x1A / 0x00 | Zapytanie tylko-do-odczytu | Średnia: ID z pamięci innych notatek społeczności, spójne ze wszystkimi innymi ID, które da się zweryfikować; układ odpowiedzi ZGADYWANY |
| Prośba o dane z pamięci | 0x16 / 0x00 | Prosi o wysłanie zapisanych rekordów. Nie wysyłamy potwierdzenia 0x17, więc opaska nie ma powodu zwalniać pamięci. Zastrzeżenie: źródła nie mówią wprost, że 0x16 niczego nie usuwa; usuwanie opisują tylko jako osobne polecenie 0x19 | Średnia (post: "trigger data retrieval"; wartość 0x00 nieopisana) |

Stała `HISTORY_PULL_ENABLED = true` w `WhoopExtProtocol.kt`; ustaw `false`, a przycisk zniknie i polecenie przestanie być budowalne.

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

1. W Formie zatrzymaj pomiar nocny i pomiar na żywo. Zamknij aplikację WHOOP (nawet jeśli nie masz konta). Załóż opaskę na nadgarstek.
2. Otwórz ekran „Diagnostyka WHOOP – tryb rozszerzony” (ikona „Forma – test WHOOP” albo z ustawień Formy) i naciśnij „Połącz”. Zezwól na Bluetooth. Poczekaj na zielony napis „Gotowe”.
3. Naciśnij „Start odczytu na żywo”, poczekaj ok. 30 sekund, potem „Odczytaj baterię”, potem „Stop”.
4. Naciśnij „Pobierz dane z pamięci (próba)”, potwierdź, poczekaj 1 do 2 minut (licznik „z pamięci” powinien rosnąć), potem „Rozłącz”.
5. Naciśnij „Zapisz plik w Pobranych”, potem „Udostępnij plik” i wyślij plik CSV. Dołącz zrzut ekranu z odczytami i napisz, jaka była temperatura w pokoju i czy opaska była na ręce.

## 6. Co odesłać

Plik `whoop_ext_RRRRMMDD_GGMMSS.csv` (w Pobranych/Forma). Kolumny: czas, dir (RX/TX/INFO), charakterystyka, surowy hex. Wystarczy do ustalenia prawdziwych offsetów.

## 7. Weryfikacja kodu

`WhoopExtProtocol.kt` został skompilowany kompilatorem Kotlin 2.0.21 (dołączonym do Gradle) i uruchomiony na: wektorach CRC, złożeniu ramki z fragmentów 20+8 B, rekordach z postu i 20 000 losowych buforach (bez wyjątków). `WhoopExtDiagActivity.kt` NIE był kompilowany (brak Android SDK); sprawdzono go ręcznie, patrz punkty ryzyka w raporcie.
