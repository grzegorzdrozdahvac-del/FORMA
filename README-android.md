# Forma na Androida

Forma to aplikacja HRV (regeneracja, sen, trening) z pomiarem nocnym z opaski WHOOP 4.0 przez Bluetooth.

## Instalacja z GitHub Releases

1. Na telefonie otwórz stronę repozytorium na GitHubie, zakładka **Releases**, wydanie **Forma (Android) – najnowsza wersja** (tag `android-latest`).
2. Pobierz plik `forma-<numer>.apk`.
3. Otwórz pobrany plik. Android zapyta o zgodę na instalację z nieznanych źródeł: wybierz **Ustawienia** i włącz **Zezwalaj z tego źródła** dla przeglądarki (lub menedżera plików), z którego instalujesz. Potem wróć i potwierdź **Zainstaluj**.
4. Aktualizacja: pobierz nowszy plik i zainstaluj go na wierzchu. Aplikacja jest podpisana stałym kluczem, więc dane zostają.

Przy pierwszym uruchomieniu zezwól na **Urządzenia w pobliżu** (na Androidzie 11 i starszym: **Lokalizacja**) oraz na **Powiadomienia**.

## Włączenie HR Broadcast w WHOOP

Forma czyta tętno i odstępy między uderzeniami (RR) ze standardowej usługi tętna, którą WHOOP udostępnia tylko po włączeniu nadawania.

1. Otwórz aplikację WHOOP, zakładka urządzenia (ikona opaski) i **Ustawienia urządzenia**.
2. Włącz **HR Broadcast** (nadawanie tętna).
3. W Formie na ekranie **Dziś** wybierz **Połącz z opaską** i wskaż opaskę na liście.

Opaska łączy się z jednym urządzeniem naraz. Jeśli aplikacja WHOOP trzyma połączenie, Forma może nie zobaczyć opaski: zamknij aplikację WHOOP i spróbuj ponownie.

## Oszczędzanie baterii (ważne dla pomiaru nocnego)

Producenci telefonów potrafią zabijać usługi działające w tle. Żeby noc nie została przerwana:

1. Na ekranie **Dziś**, w karcie **Noc**, naciśnij **Pozwól działać w tle** i zezwól Formie.
2. Dodatkowo w ustawieniach telefonu: **Aplikacje, Forma, Bateria**, ustaw **Bez ograniczeń** (nazwa zależy od producenta; w razie kłopotów zobacz dontkillmyapp.com).
3. Nie zamykaj Formy z listy ostatnich aplikacji w trakcie nocy i nie włączaj trybu oszczędzania baterii.

## Jak działa noc

1. Wieczorem załóż WHOOP, połącz go z Formą, ustaw godzinę pobudki i naciśnij **Start nocy**. W powiadomieniu widać tętno, liczbę uderzeń i baterię opaski.
2. O ustawionej godzinie pomiar kończy się sam (albo naciśnij **Zakończ noc** lub **Zakończ** w powiadomieniu).
3. Rano dostajesz powiadomienie **Wynik nocy gotowy**. Po otwarciu Formy noc jest przeliczana (okna 5 minut, mediana RMSSD) i zapisana jako dzisiejszy pomiar HRV. Poranny pomiar 2-minutowy, jeśli go zrobisz, zostaje zapisany jako dodatkowy.
4. Sen jest liczony z czasu startu i pobudki, nie z czujnika.

Surowe dane nocy są w pamięci aplikacji jako pliki CSV (jedno uderzenie na wiersz, z czasem odbioru).
