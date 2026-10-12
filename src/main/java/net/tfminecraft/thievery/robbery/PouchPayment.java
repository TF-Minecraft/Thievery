package net.tfminecraft.thievery.robbery;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.thievery.category.DenarMoney;
import net.tfminecraft.thievery.steal.StealBudget;
import net.tfminecraft.tlibs.character.CharacterSession;

/** A click's two captured pouches and budget charge. Sessions end at restart; ledger results remain. */
public final class PouchPayment {
    private final String opId = "thievery:robbery:" + UUID.randomUUID();
    private final CharacterSession from;
    private final CharacterSession to;
    private final long cents;
    private final double charge;
    private final Path directory;
    private boolean recorded;
    private boolean charged;

    public PouchPayment(Path directory, CharacterSession from, CharacterSession to, long cents, double charge) {
        this.directory = directory.toAbsolutePath();
        this.from = from;
        this.to = to;
        this.cents = cents;
        this.charge = charge;
    }

    public Result settle(StealBudget budget) throws IOException {
        if (charged) return Result.APPLIED;
        if (from == null || to == null || cents <= 0 || !Double.isFinite(charge) || charge < 0)
            return Result.REFUSED;
        if (!recorded) {
            if (!DenarMoney.present() || !Denars.ready()) return Result.UNAVAILABLE;
            Files.createDirectories(directory);
            String content = "opId=" + opId + "\nfrom=" + from.ref().characterId() + "\nto="
                    + to.ref().characterId() + "\ncents=" + cents + "\nbudget=" + charge + "\n";
            try (FileChannel file = FileChannel.open(directory.resolve(opId.substring(opId.lastIndexOf(':') + 1)
                    + ".properties"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            try (FileChannel folder = FileChannel.open(directory, StandardOpenOption.READ)) { folder.force(true); }
            try (FileChannel parent = FileChannel.open(directory.getParent(), StandardOpenOption.READ)) { parent.force(true); }
            recorded = true;
        }
        Result result = Denars.status(opId).orElseGet(() -> DenarMoney.transferPouch(opId, from, to, cents));
        if (result == Result.APPLIED) {
            budget.addUsed(charge);
            charged = true;
        }
        return result;
    }
}
