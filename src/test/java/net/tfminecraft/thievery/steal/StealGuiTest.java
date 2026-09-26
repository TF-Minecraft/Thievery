package net.tfminecraft.thievery.steal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.thievery.Thievery;
import net.tfminecraft.thievery.category.*;
import net.tfminecraft.thievery.loader.CategoryLoader;
import net.tfminecraft.thievery.player.PlayerData;
import net.tfminecraft.thievery.steal.session.HiddenStealSession;
import net.tfminecraft.thievery.utils.Keys;
import net.tfminecraft.tlibs.TLibs;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class StealGuiTest {
    MockedStatic<Thievery> plugin;
    Locale locale;
    @BeforeEach void setup() {
        locale=Locale.getDefault(); Locale.setDefault(Locale.US); MockBukkit.mock();
        plugin=mockStatic(Thievery.class); var instance=mock(Thievery.class); when(instance.getName()).thenReturn("Thievery"); when(instance.namespace()).thenReturn("thievery"); plugin.when(Thievery::getInstance).thenReturn(instance);
        assertNotNull(Keys.stealUnknown);
    }
    @AfterEach void teardown() { plugin.close(); MockBukkit.unmock(); Locale.setDefault(locale); }

    @ParameterizedTest @ValueSource(ints={0,1,9,10,27,28,41,54,60})
    void randomLayoutsAreBijectiveBoundedAndImmutable(int count) {
        var layout=StealGui.Layout.create(count); int expected=count<=9?9:count<=27?27:54;
        assertEquals(count,layout.getLogicalSlotCount()); assertEquals(expected,layout.getGuiSize());
        assertEquals(Math.min(count,expected),layout.getLogicalSlotToGuiSlot().size());
        assertEquals(layout.getLogicalSlotToGuiSlot().size(),new HashSet<>(layout.getLogicalSlotToGuiSlot().values()).size());
        layout.getLogicalSlotToGuiSlot().forEach((logical,gui)-> { assertTrue(gui>=0 && gui<expected); assertEquals(gui.intValue(),layout.getGuiSlotForLogical(logical)); assertEquals(logical,layout.getLogicalForGui(gui)); });
        assertEquals(-1,layout.getGuiSlotForLogical(-1)); assertNull(layout.getLogicalForGui(-1));
        assertThrows(UnsupportedOperationException.class,()->layout.getLogicalSlotToGuiSlot().put(1,2));
    }
    @ParameterizedTest @ValueSource(ints={0,41,53,60})
    void robberyAlwaysReservesPouchSlot(int count) {
        var layout=StealGui.Layout.createRobbery(count); assertEquals(54,layout.getGuiSize());
        assertEquals(Math.min(count,53),layout.getLogicalSlotToGuiSlot().size()); assertFalse(layout.getLogicalSlotToGuiSlot().containsValue(StealGui.ROBBERY_POUCH_GUI_SLOT));
        layout.getLogicalSlotToGuiSlot().forEach((logical,gui)->assertEquals(logical,layout.getLogicalForGui(gui)));
    }
    @Test void hiddenSessionsTrackRevealsWithoutExposingMutableState() {
        var budget=new StealBudget(7); var layout=StealGui.Layout.create(5); var session=new HiddenStealSession(budget,layout,"chest:1");
        assertSame(budget,session.getBudget()); assertSame(layout,session.getLayout()); assertEquals(9,session.getGuiSize()); assertEquals("chest:1",session.getTargetKey());
        assertFalse(session.isRevealed(2)); session.markRevealed(2); session.markRevealed(2); assertTrue(session.isRevealed(2));
        var copy=session.getRevealedGuiSlots(); assertEquals(Set.of(2),copy); copy.clear(); assertTrue(session.isRevealed(2));
    }
    @Test void panesHaveDistinctPersistentMarkersAndInteractionRules() {
        var unknown=StealGui.createUnknownPane(); var filler=StealGui.createFillerPane(); var nothing=StealGui.createNothingPane(); var hidden=StealGui.createHiddenPane();
        assertTrue(StealGui.isUnknownPane(unknown)); assertEquals("???",unknown.getItemMeta().getDisplayName());
        assertTrue(StealGui.isFillerPane(filler)); assertTrue(StealGui.isNothingPane(nothing)); assertTrue(StealGui.isHiddenPane(hidden));
        assertTrue(StealGui.isNonInteractivePane(filler)); assertTrue(StealGui.isNonInteractivePane(nothing)); assertFalse(StealGui.isNonInteractivePane(hidden));
        var vanilla = mock(ItemStack.class);
        when(vanilla.getType()).thenReturn(Material.STONE);
        when(vanilla.hasItemMeta()).thenReturn(false);
        assertFalse(StealGui.isUnknownPane(vanilla));
        assertFalse(StealGui.isUnknownPane(null)); assertFalse(StealGui.isUnknownPane(new ItemStack(Material.STONE))); assertFalse(StealGui.isUnknownPane(hidden));
    }
    @Test void revealDistinguishesEmptyIgnoredAndRepresentableLoot() {
        var budget=new StealBudget(10); var data=new PlayerData(UUID.randomUUID()); var real=new ItemStack(Material.DIAMOND); var display=new ItemStack(Material.EMERALD);
        try(var rules=mockStatic(StealIgnoreRules.class); var items=mockStatic(StealItemDisplay.class)) {
            assertEquals(StealGui.RevealResultType.EMPTY,StealGui.revealSlot(null,budget,data).type());
            assertEquals(StealGui.RevealResultType.EMPTY,StealGui.revealSlot(new ItemStack(Material.AIR),budget,data).type());
            rules.when(()->StealIgnoreRules.isIgnored(real)).thenReturn(true); assertEquals(StealGui.RevealResultType.IGNORED,StealGui.revealSlot(real,budget,data).type());
            rules.when(()->StealIgnoreRules.isIgnored(real)).thenReturn(false); assertEquals(StealGui.RevealResultType.EMPTY,StealGui.revealSlot(real,budget,data).type());
            items.when(()->StealItemDisplay.buildRepresentation(real,10,data,null)).thenReturn(display);
            var result=StealGui.revealSlot(real,budget,data); assertEquals(StealGui.RevealResultType.ITEM,result.type()); assertSame(display,result.display());
            var gui=MockBukkit.getMock().createInventory(null,9); StealGui.placeRevealedSlot(gui,2,real,budget,data); assertEquals(display,gui.getItem(2));
        }
    }
    @Test void hiddenGuiAssignsUnknownAndFillerPanesFromLayout() {
        var holder=new StealGuiHolder(UUID.randomUUID(),StealGuiHolder.Kind.CHEST); var layout=StealGui.Layout.create(4);
        var gui=StealGui.buildHiddenGui(holder,layout,"Search"); assertSame(holder,gui.getHolder()); assertEquals(9,gui.getSize());
        assertNull(holder.getInventory());
        for(int i=0;i<9;i++) assertEquals(layout.getLogicalForGui(i)!=null,StealGui.isUnknownPane(gui.getItem(i)));
        assertEquals(5,Arrays.stream(gui.getContents()).filter(StealGui::isFillerPane).count());
    }
    @Test void pouchUsesConfiguredIconClonesTemplateAndFallsBackWhenMissing() {
        try(var libs=mockStatic(TLibs.class,RETURNS_DEEP_STUBS); var categories=mockStatic(CategoryLoader.class)) {
            when(TLibs.getItemAPI().getCreator().getItemFromPath("m.currency.pouch_of_coins")).thenReturn(null);
            var pouch=StealGui.createRobberyPouchPane(12.5); assertEquals(Material.GOLD_INGOT,pouch.getType()); assertTrue(StealGui.isRobberyPouchPane(pouch)); assertTrue(pouch.getItemMeta().getLore().getFirst().contains("12.50d"));
            var money=mock(ItemCategory.class); when(money.getIcon()).thenReturn("v.emerald"); categories.when(CategoryLoader::getMoneyCategory).thenReturn(money);
            var template=new ItemStack(Material.EMERALD); when(TLibs.getItemAPI().getCreator().getItemFromPath("v.emerald")).thenReturn(template);
            var custom=StealGui.createRobberyPouchPane(5); assertEquals(Material.EMERALD,custom.getType()); assertTrue(StealGui.isRobberyPouchPane(custom)); assertFalse(template.getItemMeta().hasDisplayName()); assertFalse(StealGui.isRobberyPouchPane(template));
            when(TLibs.getItemAPI().getCreator().getItemFromPath("v.emerald")).thenReturn(new ItemStack(Material.AIR)); assertEquals(Material.AIR,StealGui.createRobberyPouchPane(5).getType());
        }
    }
    @Test void pouchVisibleOnlyWithMoneyPermissionAndPositiveBalance() {
        var gui=MockBukkit.getMock().createInventory(null,9); var data=new PlayerData(UUID.randomUUID()); var player=mock(Player.class); var budget=new StealBudget(10);
        try(var money=mockStatic(DenarMoney.class); var libs=mockStatic(TLibs.class,RETURNS_DEEP_STUBS)) {
            when(TLibs.getItemAPI().getCreator().getItemFromPath(anyString())).thenReturn(null);
            StealGui.placeRobberyPouchSlot(gui,player,data,budget); assertTrue(StealGui.isFillerPane(gui.getItem(8)));
            money.when(()->DenarMoney.canStealPouch(data)).thenReturn(true); StealGui.placeRobberyPouchSlot(gui,player,data,budget); assertTrue(StealGui.isFillerPane(gui.getItem(8)));
            money.when(()->DenarMoney.getPouchBalance(player)).thenReturn(20.0); StealGui.placeRobberyPouchSlot(gui,player,data,budget); assertTrue(StealGui.isRobberyPouchPane(gui.getItem(8)));
        }
    }
    @Test void robberyGuiDisplaysOnlyEligibleRepresentationsAndFillsOtherSlots() {
        var victim=mock(Player.class); var data=new PlayerData(UUID.randomUUID()); var budget=new StealBudget(10); var layout=StealGui.Layout.createRobbery(5);
        var air=new ItemStack(Material.AIR); var ignored=new ItemStack(Material.STICK); var hidden=new ItemStack(Material.COAL); var loot=new ItemStack(Material.DIAMOND); var display=new ItemStack(Material.EMERALD);
        try(var slots=mockStatic(PlayerSlotMap.class); var rules=mockStatic(StealIgnoreRules.class); var items=mockStatic(StealItemDisplay.class); var money=mockStatic(DenarMoney.class)) {
            slots.when(()->PlayerSlotMap.getItem(victim,1)).thenReturn(air); slots.when(()->PlayerSlotMap.getItem(victim,2)).thenReturn(ignored); slots.when(()->PlayerSlotMap.getItem(victim,3)).thenReturn(hidden); slots.when(()->PlayerSlotMap.getItem(victim,4)).thenReturn(loot);
            rules.when(()->StealIgnoreRules.isIgnored(ignored)).thenReturn(true); items.when(()->StealItemDisplay.buildRepresentation(loot,10,data)).thenReturn(display);
            var gui=StealGui.buildRobberyGui(new StealGuiHolder(UUID.randomUUID(),StealGuiHolder.Kind.ROBBERY),layout,"Robbery",victim,budget,data);
            assertEquals(display,gui.getItem(layout.getGuiSlotForLogical(4))); assertEquals(53,Arrays.stream(gui.getContents()).filter(StealGui::isFillerPane).count());
        }
    }
    @Test void titleOptionsHandleMissingSectionsAndJoinPresentSections() {
        assertEquals(" ",StealGui.formatTitle(new StealGui.TitleOptions(null,null,null,null,null)));
        assertEquals(" ",StealGui.formatTitle(new StealGui.TitleOptions(.2,null,null,null,null)));
        assertEquals(" ",StealGui.formatTitle(new StealGui.TitleOptions(0.0,0.0,null,null,null)));
        assertEquals("§6Break: 25%",StealGui.formatTitle(new StealGui.TitleOptions(null,null,null,null,.25)));
        var budget=new StealBudget(10.4); budget.addUsed(2.6);
        assertEquals("§a3/10",StealGui.formatTitle(new StealGui.TitleOptions(null,null,null,budget,null)));
        String title=StealGui.formatTitle(new StealGui.TitleOptions(.2,.1,65_000L,budget,.25));
        assertTrue(title.contains("Risk: §720%")); assertTrue(title.contains("Crit: §710%")); assertTrue(title.contains(" §6Break: 25%")); assertTrue(title.endsWith(" §a3/10"));
        assertTrue(StealGui.forRobbery(65_000,budget).endsWith(" §a3/10"));
        assertFalse(StealGui.formatTitle(new StealGui.TitleOptions(null,null,65_000L,null,null)).isBlank());
        var data=mock(PlayerData.class); when(data.getRisk()).thenReturn(.2); when(data.getCriticalChance(20,0)).thenReturn(.1); when(data.getCriticalChance(20,.5)).thenReturn(.15);
        assertTrue(StealGui.forPickpocket(data,20,budget).contains("Crit: §710%"));
        assertTrue(StealGui.forChest(data,20,.5,budget,.8,false,true).contains("Break: 20%"));
        String broken=StealGui.forChest(data,20,.5,budget,.8,true,false); assertFalse(broken.contains("Break:")); assertFalse(broken.contains("Crit:"));
    }
    @Test void titleUpdatesOnlyMatchingActiveInventoryAndOnlyWhenChanged() {
        UUID id=UUID.randomUUID(); var holder=new StealGuiHolder(id,StealGuiHolder.Kind.CHEST); var player=mock(Player.class,RETURNS_DEEP_STUBS);
        StealGui.updateTitle(null,holder,"new"); StealGui.updateTitle(player,holder,"new"); verify(player,never()).getOpenInventory();
        when(player.isOnline()).thenReturn(true); StealGui.updateTitle(player,null,"new"); StealGui.updateTitle(player,holder,null); verify(player,never()).getOpenInventory();
        var view=player.getOpenInventory(); var top=view.getTopInventory(); when(top.getHolder()).thenReturn(null); StealGui.updateTitle(player,holder,"new"); verify(view,never()).setTitle(anyString());
        when(top.getHolder()).thenReturn(new StealGuiHolder(UUID.randomUUID(),StealGuiHolder.Kind.CHEST)); StealGui.updateTitle(player,holder,"new"); verify(view,never()).setTitle(anyString());
        when(top.getHolder()).thenReturn(new StealGuiHolder(id,StealGuiHolder.Kind.ROBBERY)); StealGui.updateTitle(player,holder,"new"); verify(view,never()).setTitle(anyString());
        when(top.getHolder()).thenReturn(holder); when(view.getTitle()).thenReturn("new"); StealGui.updateTitle(player,holder,"new"); verify(view,never()).setTitle(anyString());
        when(view.getTitle()).thenReturn("old"); StealGui.updateTitle(player,holder,"new"); verify(view).setTitle("new");
    }
}
