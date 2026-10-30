# Grand Exchange

Automatic Grand Exchange: offers fill instantly at the guide price when the offer price allows it.

- Buy offers fill when the offer price is at or above the guide price; the difference is refunded. Sell offers fill when the offer price is at or below it, paying the guide price minus the 2% tax (capped at 5,000,000 per item, bonds exempt). Other offers stay pending until cancelled or until a later `refresh` finds them fillable.
- Coins or items are taken when the offer is placed and returned on cancellation, so every unit is accounted for. Collection is partial when the inventory is full or the coin stack would overflow, and a slot clears only when nothing remains to collect.
- State lives in permanent varps (`ge_slot_N_*`, 8 slots) and saves with the character.
- Guide prices and buy limits are the cache enums `ge_guide_price` and `ge_buy_limit`, generated from prices.runescape.wiki on 2026-10-30. Items above 2,147,483,647 gp are capped.

Front-end: Grand Exchange clerks (Talk-to, Exchange, Collect) and the booth loc open a dialogue menu with buy, sell, collect, view and cancel. The native Grand Exchange interface (`ge_offers`, `ge_collect`) and the stock-market slot packet are not wired.

Not implemented: 4-hour buy limits, partial fills, price movement, the native interface, history, item sets, Ironman restriction enforcement, and the booth loc id (`loc.exchange_bank_wall_exchange`) has not been confirmed in a client.

## Native interface research (not implemented)

Findings from the revision-241 CS2 dumps ([Joshua-F/osrs-dumps](https://github.com/Joshua-F/osrs-dumps)) and rsprot:

- Interfaces: `interface.ge_offers` (465) on the main modal, `interface.ge_offers_side` (467) as the inventory side panel, `interface.ge_collect` (402), `interface.ge_history` (383). Frame components include `history`, `back`, `index`, `collectall`, `details_*`, `setup`, `setup_confirm` (op1 "Confirm", `transmitop`).
- Slot state is client-native: scripts read it with `stockmarket_isofferempty/getoffertype/getofferitem/getofferprice/getoffercompletedcount/isofferfinished`. The server feeds it with rsprot `UpdateStockMarketSlotV2` (`SetStockMarketSlot` / `ResetStockMarketSlot`). `inv.ge_offer_N` and `inv.ge_collect_N` hold the collectable items, and `enum_150` maps slot index to inv.
- Offer setup is client-predicted: `ge_offers_setup_changequantity`/`changeprice` write `%ge_newoffer_quantity`, `%ge_newoffer_price` (varp 5753, long), `%ge_newoffer_type` and `%tradingpost_search` locally, then the server must mirror the same arithmetic when it receives the button op. The server owns "Choose item", "Enter quantity", "Enter price", "Guide price" and "Confirm".
- Dynamic setup buttons are created in `ge_offers_setup_init` as sequential sub-components of `ge_offers:setup`, so the server needs the exact sub-index to op mapping. Taxes use `varbit.ge_transmit_taxrate`.
- Verify with the OpenRune Developer Tools client (`dump_interface`, `click_component`, `get_var_history`); none was available when this was written.
