# Cinema Microservices

Projekat: mikroservisni sistem za rezervaciju
i prodaju bioskopskih karata.

## Opis sistema

Korisnik pregleda filmove i projekcije, bira slobodna sedišta
i kreira rezervaciju. Sedišta se privremeno zauzimaju do
isteka roka za plaćanje.

Uspešno simulirano plaćanje potvrđuje rezervaciju i pokreće
izdavanje karata. Istek nepotvrđene rezervacije oslobađa sedišta.
Karta se proverava pri ulasku i može se iskoristiti samo jednom.

## Obim

- Jedan bioskop sa više sala.
- Numerisana sedišta.
- Fiksna cena karte po projekciji.
- Simulirano plaćanje.
- Bez prodaje hrane i pića i programa lojalnosti.

## Planirani poslovni mikroservisi

| movie-service | Katalog filmova |
| screening-service | Sale, raspored sedišta i projekcije |
| seat-inventory-service | Dostupnost i zauzimanje sedišta po projekciji |
| reservation-service | Rezervacije i njihov životni ciklus |
| payment-service | Simulacija naplate i povraćaja novca |
| ticket-service | Izdavanje i validacija karata |

## Tehnologije

- Java 21
- Spring Boot i Spring Cloud
- Maven
- Docker i Docker Compose
- Git

## Status

Pripremljeno okuženje. Implementacija u toku.