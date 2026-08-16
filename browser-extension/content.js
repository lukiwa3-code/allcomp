(() => {
  "use strict";
  if (window.__legoPriceComparatorLoaded) return;
  window.__legoPriceComparatorLoaded = true;

  const money = new Intl.NumberFormat("pl-PL", { style: "currency", currency: "PLN" });
  const normalize = (value) => (value || "").replace(/\s+/g, " ").trim();
  const offerCount = (value) => [...normalize(value).matchAll(/(\d[\d\s]*)\s+ofert(?:a|y)?\b/gi)]
    .reduce((max, match) => Math.max(max, Number(match[1].replace(/\s/g, "")) || 0), 0);

  function parsePrice(value) {
    const match = normalize(value).replace(/zł/gi, "").match(/(?:^|\s)(\d{1,3}(?:[ .]\d{3})*|\d+)[,.](\d{2})(?:\s|$)/);
    return match ? Number(`${match[1].replace(/[ .]/g, "")}.${match[2]}`) : null;
  }

  function findProductWithMostOffers() {
    const candidates = [], seen = new Set();
    for (const container of document.querySelectorAll("article, [data-role=product], [data-box-name*=product]")) {
      const count = offerCount(container.innerText);
      const link = container.querySelector('a[href*="/produkt/"], a[href*="product.id"], a[href*="productId"]');
      if (!count || !link) continue;
      const href = new URL(link.href, location.href).href;
      if (seen.has(href)) continue;
      seen.add(href);
      const heading = container.querySelector("h2, h3, [role=heading]");
      candidates.push({ href, count, title: normalize(heading?.textContent || link.textContent) });
    }
    for (const link of document.querySelectorAll("a")) {
      const count = offerCount(link.innerText);
      if (!count || !/\/produkt\/|product\.id|productId/i.test(link.href)) continue;
      const href = new URL(link.href, location.href).href;
      if (!seen.has(href)) candidates.push({ href, count, title: normalize(link.textContent) });
    }
    return candidates.sort((a, b) => b.count - a.count)[0] || null;
  }

  function findOffers() {
    const seen = new Set(), offers = [];
    for (const link of document.querySelectorAll('a[href*="/oferta/"]')) {
      const href = new URL(link.href, location.href).href.split("?")[0];
      if (seen.has(href)) continue;
      const card = link.closest("article") || link.closest('[data-role="offer"]') || link.parentElement?.parentElement;
      if (!card) continue;
      const price = parsePrice(card.innerText);
      const heading = card.querySelector("h2, h3, [role=heading]");
      const title = normalize(heading?.textContent || link.textContent);
      if (price === null || title.length < 4) continue;
      seen.add(href);
      offers.push({ title, price, href });
    }
    return offers.sort((a, b) => a.price - b.price);
  }

  function escapeHtml(value) {
    const element = document.createElement("div");
    element.textContent = value;
    return element.innerHTML;
  }

  const panel = document.createElement("aside");
  panel.id = "lap-panel";
  panel.innerHTML = `<button id="lap-collapse" title="Zwiń">−</button><h2>LEGO: 2 najtańsze</h2>
    <form id="lap-search"><input id="lap-query" inputmode="numeric" placeholder="np. 76476" aria-label="Numer zestawu LEGO" /><button type="submit">Szukaj</button></form>
    <div id="lap-results">Wpisz numer zestawu.</div><button id="lap-refresh" type="button">Odśwież analizę</button>`;
  document.body.appendChild(panel);
  const results = panel.querySelector("#lap-results");

  function renderOffers() {
    const offers = findOffers();
    if (offers.length < 2) {
      results.textContent = `Na stronie produktu znalazłem ${offers.length} ofert. Poczekaj na wczytanie strony lub przewiń niżej.`;
      return;
    }
    sessionStorage.removeItem("lap_stage");
    const [first, second] = offers;
    const row = (item, label) => `<div class="lap-offer"><div class="lap-label">${label}</div><a href="${item.href}" target="_blank" rel="noopener noreferrer">${escapeHtml(item.title)}</a><strong>${money.format(item.price)}</strong></div>`;
    results.innerHTML = `${row(first, "Najtańsza")}${row(second, "Druga najtańsza")}<div class="lap-difference">Różnica: <strong>${money.format(second.price - first.price)}</strong></div><small>Produkt z największą liczbą ofert; ceny bez dostawy.</small>`;
  }

  function chooseProduct() {
    const product = findProductWithMostOffers();
    if (!product) {
      results.textContent = "Nie znalazłem jeszcze kart produktów z liczbą ofert. Czekam na wczytanie wyników…";
      return;
    }
    results.innerHTML = `Wybrano produkt z <strong>${product.count}</strong> ofertami:<br>${escapeHtml(product.title)}<br>Otwieram listę ofert…`;
    sessionStorage.setItem("lap_stage", "offers");
    const url = new URL(product.href);
    url.searchParams.set("stan", "nowe");
    url.searchParams.set("order", "p");
    setTimeout(() => { location.href = url.href; }, 350);
  }

  panel.querySelector("#lap-search").addEventListener("submit", (event) => {
    event.preventDefault();
    const query = panel.querySelector("#lap-query").value.trim();
    if (!/^\d{3,8}$/.test(query)) { results.textContent = "Podaj numer zestawu, np. 76476."; return; }
    sessionStorage.setItem("lap_stage", "product");
    const url = new URL("https://allegro.pl/listing");
    url.searchParams.set("string", `LEGO ${query}`);
    url.searchParams.set("stan", "nowe");
    location.href = url.href;
  });

  const runStage = () => sessionStorage.getItem("lap_stage") === "product" ? chooseProduct() : renderOffers();
  panel.querySelector("#lap-refresh").addEventListener("click", runStage);
  panel.querySelector("#lap-collapse").addEventListener("click", () => panel.classList.toggle("lap-collapsed"));
  if (sessionStorage.getItem("lap_stage") === "product") results.textContent = "Szukam produktu z największą liczbą ofert…";
  setTimeout(runStage, 1500);
  setTimeout(runStage, 4000);
})();
