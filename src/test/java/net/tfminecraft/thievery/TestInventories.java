package net.tfminecraft.thievery;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import org.bukkit.inventory.Inventory;

/** MockBukkit leaves Paper's non-snapshot getHolder(boolean) unimplemented; the plugin uses it on hot paths. */
public final class TestInventories {

    private TestInventories() {
    }

    /** A spy of {@code inventory} whose getHolder(boolean) answers with the wrapped inventory's holder. */
    public static Inventory withHolderLookup(Inventory inventory) {
        Inventory spied = spy(inventory);
        doAnswer(call -> inventory.getHolder()).when(spied).getHolder(anyBoolean());
        return spied;
    }
}
