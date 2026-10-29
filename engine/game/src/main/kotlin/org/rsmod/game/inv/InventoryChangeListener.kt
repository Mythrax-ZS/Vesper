package org.rsmod.game.inv

/**
 * Observes committed inventory contents synchronously, before another gameplay action can run.
 * Listeners must not mutate inventories or throw; notification cannot undo a committed change.
 */
public fun interface InventoryChangeListener {
    public fun onInventoryChanged(inventory: Inventory)
}
