# Provider response samples

- `alpha-vantage/quote.json`, `alpha-vantage/overview.json` (trimmed), `twelve-data/quote.json`: real responses captured on 2026-11-13 with the providers' public `demo` keys.
- `finnhub/*.json`: hand-written from Finnhub's documented response shape (`c`, `t` for the quote; `metric.peTTM`, `metric.dividendYieldIndicatedAnnual`, `metric.marketCapitalization` in millions for fundamentals). Finnhub has no demo key. Replace them with real captures once you have a key.
