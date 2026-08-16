# AllComp — porównywarka LEGO na Allegro

Repozytorium zawiera dwa warianty narzędzia działającego bez Allegro API:

- aplikację Android w katalogu `app`,
- rozszerzenie Chrome/Edge w katalogu `browser-extension`.

Oba warianty wyszukują nowe zestawy LEGO, wybierają kartę produktu z największą
liczbą ofert, przechodzą do ofert dokładnie tego produktu, a następnie zwracają
najtańszą i drugą najtańszą cenę. Cena nie obejmuje dostawy ani indywidualnych
korzyści Allegro Smart!.

Aplikacja Android przechowuje listę obserwowanych numerów w wewnętrznym pliku
`sets.txt`. Numery można dodawać i usuwać na ekranie, a następnie sprawdzić całą
listę jednym przyciskiem. Wynik zawiera również cenę oferty sprzedawcy `lukiwa`;
aplikacja przegląda w tym celu kolejne strony ofert produktu.

## Android APK

Workflow `.github/workflows/android.yml` buduje testowe APK automatycznie po
pushu. Gotowy plik znajduje się w artefakcie `lego-allegro-debug-apk` danego
uruchomienia GitHub Actions.

## Rozszerzenie przeglądarkowe

Włącz tryb dewelopera na stronie `chrome://extensions` albo `edge://extensions`,
wybierz **Załaduj rozpakowane** i wskaż katalog `browser-extension`.

## Ograniczenia

Narzędzia analizują aktualną strukturę strony wyników Allegro. Po większej
zmianie interfejsu selektory ofert mogą wymagać aktualizacji.
