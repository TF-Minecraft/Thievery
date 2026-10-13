package net.tfminecraft.thievery.utils;

import net.tfminecraft.simplefactions.api.SimpleFactionsApi;
import net.tfminecraft.tlibs.character.Characters;

/** Readiness for the account-scoped theft cooldowns (rule 10), which still read guild membership. */
public final class FactionAccess {
    private FactionAccess() {}

    public static boolean ready() { return SimpleFactionsApi.ready() && Characters.legacyHeirsReady(); }
}
