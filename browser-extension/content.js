(() => {
  "use strict";

  if (window.__legoPriceComparatorLoaded) return;
  window.__legoPriceComparatorLoaded = true;

  const money = new Intl.NumberFormat("pl-PL", { style: "currency", currency: "PLN" });

  const normalize = (value) => value.replace(/\s+/g, " ").trim();

  function parsePrice(value) {
    const match = normalize(value)
      .replace(/zł/gi, "")
      .match(/(?:^|\s)(\d{1,3}(?:[ .]\d{3})*|\d+)[,.](\d{2})(?:\s|$)/);
    if (!match) return null;
    const amount = Number(`${match[1].replace(/[ .]/g, "")}.${match[2]}`);
    return Number.isFinite(amount) ? amount : null;
  }

  function findCards() {
    const links = [...document.querySelectorAll('a[href*="/oferta/"]')];
    const seen = new Set();
    const offers = [];

    for (const link of links) {
      const href = new URL(link.href, location.href).href.split("?")[0];
      if (seen.has(href)) continue;

      const card = link.closest("article") || link.closest('[data-role="offer"]') || link.parentElement?.parentElement;
      if (!card) continue;
      const text = normalize(card.innerText || "");
      const price = parsePrice(text);
      if (price === null) continue;

      const heading = card.querySelector("h2, h3, [role=heading]");
      const title = normalize(heading?.textContent || link.textContent || "Oferta Allegro");
      if (!title || title.length < 4) continue;

      seen.add(href);
      offers.push({ title, price, href });
    }
    return offers.sort((a, b) => a.price - b.price);
  }

  function offerHtml(offer, label) {
    return `<div class="lap-offer">
      <div class="lap-label">${label}</div>
      <a href="${offer.href}" target="_blank" rel="noopener noreferrer">${escapeHtml(offer.title)}</a>
      <strong>${money.format(offer.price)}</strong>
    </div>`;
  }

  function escapeHtml(value) {
    const el = document.createElement("div");
    el.textContent = value;
    return el.innerHTML;
  }

  const panel = document.createElement("aside");
  panel.id = "lap-panel";
  panel.innerHTML = `
    <button id="lap-collapse" title="Zwiń">−</button>
    <h2>LEGO: 2 najtańsze</h2>
    <form id="lap-search">
      <input id="lap-query" inputmode="numeric" placeholder="np. 76476" aria-label="Numer zestawu LEGO" />
      <button type="submit">Szukaj</button>
    </form>
    <div id="lap-results">Odczytuję oferty…</div>
    <button id="lap-refresh" type="button">Odśwież wyniki</button>`;
  document.body.appendChild(panel);

  function render() {
    const results = panel.querySelector("#lap-results");
    const offers = findCards();
    if (offers.length < 2) {
      results.innerHTML = `<p>Znalazłem ${offers.length} ofert. Przewiń stronę lub sprawdź, czy są wyniki.</p>`;
      return;
    }
    const [first, second] = offers;
    const difference = second.price - first.price;
    results.innerHTML = `${offerHtml(first, "Najtańsza")}${offerHtml(second, "Druga najtańsza")}
      <div class="lap-difference">Różnica: <strong>${money.format(difference)}</strong></div>
      <small>Ceny produktów; dostawa może zależeć od konta i Smart!.</small>`;
  }

  panel.querySelector("#lap-search").addEventListener("submit", (event) => {
    event.preventDefault();
    const query = panel.querySelector("#lap-query").value.trim();
    if (!/^\d{3,8}$/.test(query)) {
      panel.querySelector("#lap-results").innerHTML = "Podaj numer zestawu, np. 76476.";
      return;
    }
    const url = new URL("https://allegro.pl/listing");
    url.searchParams.set("string", `LEGO ${query}`);
    url.searchParams.set("stan", "nowe");
    url.searchParams.set("order", "p");
    location.href = url.href;
  });
  panel.querySelector("#lap-refresh").addEventListener("click", render);
  panel.querySelector("#lap-collapse").addEventListener("click", () => panel.classList.toggle("lap-collapsed"));

  setTimeout(render, 1200);
  setTimeout(render, 3500);
})();
