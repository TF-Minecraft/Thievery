package net.tfminecraft.thievery.key;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class KeychainHandlerTest {

    @Test
    void templateLoreStopsAtPreviousKeyHeader() {
        List<String> existing = List.of("§cKey", "", "§7§oCan hold up to five keys.",
                "§fKeys §x§d§6§c§f§6§90/5", "§fKeys §x§d§6§c§f§6§92/5", "§fCopper Key");

        assertEquals(List.of("§cKey", "", "§7§oCan hold up to five keys."),
                KeychainHandler.templateLore(existing, 4));
    }

    @Test
    void templateLoreKeepsReservedLinesWithoutHeader() {
        List<String> existing = List.of("a", "b", "c", "d", "e");

        assertEquals(List.of("a", "b", "c", "d"), KeychainHandler.templateLore(existing, 4));
    }

    @Test
    void templateLoreHandlesMissingLore() {
        assertEquals(List.of(), KeychainHandler.templateLore(null, 4));
    }
}
