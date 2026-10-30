# Grand Exchange

Automatic Grand Exchange: offers fill instantly at the guide price when the offer price allows it.

- Buy offers fill when the offer price is at or above the guide price; the difference is refunded. Sell offers fill when the offer price is at or below it, paying the guide price minus the 2% tax (capped at 5,000,000 per item, bonds exempt). Other offers stay pending until cancelled or until a later `refresh` finds them fillable.
- Coins or items are taken when the offer is placed and returned on cancellation, so every unit is accounted for. Collection is partial when the inventory is full or the coin stack would overflow, and a slot clears only when nothing remains to collect.
- State lives in permanent varps (`ge_slot_N_*`, 8 slots) and saves with the character.
- Guide prices and buy limits are the cache enums `ge_guide_price` and `ge_buy_limit`, generated from prices.runescape.wiki on 2026-10-30. Items above 2,147,483,647 gp are capped.

Front-end: Grand Exchange clerks (Talk-to, Exchange, Collect) and the booth loc open a dialogue menu with buy, sell, collect, view and cancel. The native Grand Exchange interface (`ge_offers`, `ge_collect`) and the stock-market slot packet are not wired.

Not implemented: 4-hour buy limits, partial fills, price movement, the native interface, history, item sets, Ironman restriction enforcement, and the booth loc id (`loc.exchange_bank_wall_exchange`) has not been confirmed in a client.
