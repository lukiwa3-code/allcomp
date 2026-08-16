# LEGO Allegro — dwie najtańsze oferty

Wtyczka działa lokalnie w Chrome i Edge, bez Allegro API i bez przekazywania danych logowania.

## Instalacja

1. Otwórz `chrome://extensions` (Chrome) albo `edge://extensions` (Edge).
2. Włącz **Tryb dewelopera**.
3. Kliknij **Załaduj rozpakowane**.
4. Wskaż cały folder `lego-allegro-porownywarka`.
5. Wejdź na stronę wyników wyszukiwania Allegro.

Panel pojawi się po prawej stronie. Wpisz numer zestawu LEGO, np. `76476`. Wtyczka wyszuka nowe produkty, wybierze kartę z największą liczbą ofert, otworzy listę ofert tego produktu i pokaże dwie najtańsze ceny.

## Ważne

- Porównywana jest cena produktu bez dostawy, ponieważ koszt dostawy może zależeć od adresu, konta i Allegro Smart!.
- Allegro może okresowo zmieniać wygląd strony. Wtedy selektory w `content.js` mogą wymagać aktualizacji.
- Narzędzie nie wysyła danych na zewnętrzny serwer i nie automatyzuje masowego pobierania stron.
