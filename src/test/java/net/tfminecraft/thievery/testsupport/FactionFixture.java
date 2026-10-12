package net.tfminecraft.thievery.testsupport;

import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.UUID;
import net.tfminecraft.thievery.utils.FactionAccess;
import net.tfminecraft.tlibs.character.CharacterRef;
import org.bukkit.entity.Player;
import org.mockito.MockedStatic;

/** Ready single-character accounts for existing interaction tests; identity behavior has separate tests. */
public final class FactionFixture implements AutoCloseable {
    private final MockedStatic<FactionAccess> access = mockStatic(FactionAccess.class);
    public FactionFixture() {
        access.when(FactionAccess::ready).thenReturn(true);
        access.when(() -> FactionAccess.heir(any(UUID.class))).thenAnswer(call -> {
            UUID owner=call.getArgument(0); return Optional.of(new CharacterRef(owner,owner));
        });
        access.when(() -> FactionAccess.owns(any(Player.class),any(UUID.class))).thenAnswer(call ->
                ((Player)call.getArgument(0)).getUniqueId().equals(call.getArgument(1)));
    }
    public void guild(UUID owner, net.tfminecraft.simplefactions.guild.Guild guild) {
        access.when(() -> FactionAccess.ownerGuild(owner)).thenReturn(guild);
    }
    @Override public void close() { access.close(); }
}
