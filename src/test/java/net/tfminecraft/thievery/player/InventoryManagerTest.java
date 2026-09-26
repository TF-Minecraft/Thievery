package net.tfminecraft.thievery.player;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.cache.Cache;
import net.tfminecraft.thievery.category.ItemCategory;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.thievery.utils.Keys;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.*;

class InventoryManagerTest {
    Player player; PlayerData data; InventoryManager manager; InventoryView view; Inventory inventory; List<ItemCategory> categories; Map<String,ItemCategory> byId; int points;
    MockedStatic<Thievery> plugins; MockedStatic<CategoryLoader> loader;
    @BeforeEach void setup() {
        MockBukkit.mock(); points=Cache.categoryPoints; Cache.categoryPoints=30; categories=new ArrayList<>(); byId=new HashMap<>();
        plugins=mockStatic(Thievery.class,RETURNS_DEEP_STUBS); var plugin=mock(Thievery.class); when(plugin.namespace()).thenReturn("thievery"); plugins.when(Thievery::getInstance).thenReturn(plugin);
        player=mock(Player.class); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); data=new PlayerData(player.getUniqueId()); when(Thievery.getPlayerManager().get(player)).thenReturn(data);
        view=mock(InventoryView.class); when(player.getOpenInventory()).thenReturn(view); doAnswer(call->{inventory=call.getArgument(0); when(view.getTopInventory()).thenReturn(inventory); return view;}).when(player).openInventory(any(Inventory.class));
        loader=mockStatic(CategoryLoader.class); loader.when(CategoryLoader::getLoadoutCategories).thenReturn(categories); loader.when(()->CategoryLoader.getById(anyString())).thenAnswer(call->byId.get(call.getArgument(0))); manager=new InventoryManager();
    }
    @AfterEach void close() { loader.close(); plugins.close(); Cache.categoryPoints=points; MockBukkit.unmock(); }
    ItemCategory category(String id,int cost) {
        var category=mock(ItemCategory.class); when(category.getId()).thenReturn(id); when(category.getCost()).thenReturn(cost);
        when(category.getIconItem(anyBoolean())).thenAnswer(call->{var icon=new ItemStack(Material.PAPER); var meta=icon.getItemMeta(); meta.getPersistentDataContainer().set(Keys.categoryId,PersistentDataType.STRING,id); meta.setDisplayName(id+(call.getArgument(0,Boolean.class)?" active":" inactive")); icon.setItemMeta(meta); return icon;});
        categories.add(category); byId.put(id,category); return category;
    }
    InventoryClickEvent click(int slot) { var event=mock(InventoryClickEvent.class); when(event.getWhoClicked()).thenReturn(player); when(event.getView()).thenReturn(view); when(event.getClickedInventory()).thenReturn(inventory); when(event.getSlot()).thenReturn(slot); if(slot>=0 && slot<inventory.getSize()) when(event.getCurrentItem()).thenReturn(inventory.getItem(slot)); return event; }
    @Test void confirmChargesOnlyNewSelectionsAndCancelDiscardsDraft() {
        category("saved",5); category("new",7); data.setActiveCategories(new ArrayList<>(List.of("saved"))); data.setPoints(10);
        manager.openLoadout(player); var holder=(LoadoutHolder)inventory.getHolder(); assertEquals(player.getUniqueId(),holder.getPlayerId()); assertEquals(0,holder.getPage()); assertNull(holder.getInventory()); assertEquals(54,inventory.getSize());
        var event=click(1); manager.onInventoryClick(event); verify(event).setCancelled(true); assertEquals(List.of("saved"),data.getActiveCategories()); assertTrue(inventory.getItem(1).getItemMeta().getDisplayName().endsWith("active"));
        manager.onInventoryClick(click(49)); assertEquals(List.of("saved","new"),data.getActiveCategories()); assertEquals(3,data.getPoints()); verify(player).closeInventory();
        manager.openLoadout(player); manager.onInventoryClick(click(0)); manager.onInventoryClick(click(48)); assertEquals(List.of("saved","new"),data.getActiveCategories()); assertEquals(3,data.getPoints());
    }
    @Test void categoryPaginationRendersFortyFiveEntriesAndStopsAtLastPage() {
        for(int i=0;i<47;i++) category("category"+i,1); manager.openLoadout(player); assertEquals("category0 inactive",inventory.getItem(0).getItemMeta().getDisplayName()); assertEquals(Material.ARROW,inventory.getItem(53).getType());
        manager.onInventoryClick(click(53)); assertEquals(1,((LoadoutHolder)inventory.getHolder()).getPage()); assertEquals("category45 inactive",inventory.getItem(0).getItemMeta().getDisplayName()); assertNull(inventory.getItem(2)); assertEquals(Material.ARROW,inventory.getItem(45).getType());
        manager.onInventoryClick(click(53)); assertEquals(1,((LoadoutHolder)inventory.getHolder()).getPage()); manager.onInventoryClick(click(45)); assertEquals(0,((LoadoutHolder)inventory.getHolder()).getPage()); manager.onInventoryClick(click(45)); assertEquals(0,((LoadoutHolder)inventory.getHolder()).getPage());
    }
    @Test void rejectedSelectionsExplainBankAllocationOrReloadedCategoryErrors() {
        category("expensive",20); category("other",20); data.setPoints(10); manager.openLoadout(player); manager.onInventoryClick(click(0)); verify(player).sendMessage("§cYou do not have enough bank points for that category.");
        data.setActiveCategories(new ArrayList<>(List.of("expensive"))); data.setPoints(30); manager.openLoadout(player); manager.onInventoryClick(click(1)); verify(player).sendMessage("§cYou cannot allocate more than 30 points.");
        byId.remove("other"); manager.onInventoryClick(click(1)); verify(player).sendMessage("§cUnknown category.");
        Cache.categoryPoints=10; manager.onInventoryClick(click(49)); verify(player).sendMessage("§cYou cannot confirm this loadout."); assertEquals(List.of("expensive"),data.getActiveCategories());
    }
    @Test void invalidClicksAndClosedSessionsCannotChangePlayerLoadout() {
        category("one",1); manager.openLoadout(player);
        var outsider=click(0); when(outsider.getWhoClicked()).thenReturn(mock(HumanEntity.class)); manager.onInventoryClick(outsider); verify(outsider,never()).setCancelled(true);
        var outside=click(0); when(outside.getClickedInventory()).thenReturn(null); manager.onInventoryClick(outside); assertTrue(data.getActiveCategories().isEmpty());
        var lower=click(0); when(lower.getClickedInventory()).thenReturn(mock(Inventory.class)); manager.onInventoryClick(lower); assertTrue(data.getActiveCategories().isEmpty());
        var empty=click(0); when(empty.getCurrentItem()).thenReturn(null); manager.onInventoryClick(empty); var untagged=click(0); when(untagged.getCurrentItem()).thenReturn(new ItemStack(Material.STONE)); manager.onInventoryClick(untagged); var noMeta=mock(ItemStack.class); when(untagged.getCurrentItem()).thenReturn(noMeta); manager.onInventoryClick(untagged); manager.onInventoryClick(click(-1)); manager.onInventoryClick(click(46));
        var close=mock(InventoryCloseEvent.class); when(close.getPlayer()).thenReturn(player); when(close.getView()).thenReturn(view); manager.onInventoryClose(close); manager.onInventoryClick(click(0)); manager.onInventoryClick(click(49)); assertTrue(data.getActiveCategories().isEmpty());
    }
    @Test void otherOwnersAndOtherMenusAreIgnoredByClickAndCloseHandlers() {
        category("one",1); manager.openLoadout(player); var event=click(0); var close=mock(InventoryCloseEvent.class); when(close.getPlayer()).thenReturn(player); when(close.getView()).thenReturn(view);
        var other=MockBukkit.getMock().createInventory(new LoadoutHolder(UUID.randomUUID(),0),54); when(view.getTopInventory()).thenReturn(other); manager.onInventoryClick(event); manager.onInventoryClose(close); verify(event,never()).setCancelled(true);
        when(view.getTopInventory()).thenReturn(MockBukkit.getMock().createInventory(null,9)); manager.onInventoryClick(event); manager.onInventoryClose(close); verify(event,never()).setCancelled(true);
        when(close.getPlayer()).thenReturn(mock(HumanEntity.class)); manager.onInventoryClose(close);
        when(view.getTopInventory()).thenReturn(inventory); manager.onInventoryClick(click(0)); manager.onInventoryClick(click(49)); assertEquals(List.of("one"),data.getActiveCategories());
    }
    @Test void missingIconsAndEmptyCategoryListsStillShowWorkingControls() {
        var unavailable=category("missing",1); when(unavailable.getIconItem(anyBoolean())).thenReturn(null); manager.openLoadout(player); assertNull(inventory.getItem(0)); assertEquals(Material.LIME_DYE,inventory.getItem(49).getType());
        categories.clear(); manager.openLoadout(player); assertEquals(0,((LoadoutHolder)inventory.getHolder()).getPage()); manager.onInventoryClick(click(49)); assertTrue(data.getActiveCategories().isEmpty());
    }
    @Test void loadoutDraftPreservesOriginalSelectionsAndEnforcesBothLimits() {
        category("saved",5); category("new",7); data.setActiveCategories(new ArrayList<>(List.of("saved"))); data.setPoints(7); var session=LoadoutSession.from(data);
        assertEquals(5,session.getDraftAllocated()); assertEquals(7,session.getDraftBank()); assertEquals(LoadoutSession.ToggleResult.TOGGLED_OFF,session.toggleCategory("saved")); assertEquals(LoadoutSession.ToggleResult.TOGGLED_ON,session.toggleCategory("saved")); assertEquals(7,session.getDraftBank());
        assertEquals(LoadoutSession.ToggleResult.TOGGLED_ON,session.toggleCategory("new")); assertEquals(0,session.getDraftBank()); assertTrue(session.canConfirm()); assertEquals(List.of("saved"),data.getActiveCategories());
        byId.remove("new"); assertEquals(7,session.getDraftBank()); assertEquals(LoadoutSession.ToggleResult.UNKNOWN_CATEGORY,session.toggleCategory("removed"));
    }
}
