package net.tfminecraft.thievery.robbery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.api.*;
import net.tfminecraft.denareconomy.ledger.Ledger;
import net.tfminecraft.thievery.category.DenarMoney;
import net.tfminecraft.thievery.steal.StealBudget;
import net.tfminecraft.tlibs.character.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

class PouchPaymentTest {
    @TempDir Path root;
    Ledger ledger;
    MockedStatic<DenarEconomy> economy;
    MockedStatic<Characters> characters;
    CharacterSession a, b;
    StealBudget budget;
    @BeforeEach void open() {
        MockBukkit.mock(); MockBukkit.createMockPlugin("DenarEconomy");
        a=new CharacterSession(new CharacterRef(UUID.randomUUID(),UUID.randomUUID()),1);
        b=new CharacterSession(new CharacterRef(UUID.randomUUID(),UUID.randomUUID()),2);
        characters=mockStatic(Characters.class);
        characters.when(Characters::available).thenReturn(true);
        characters.when(()->Characters.isCurrent(a)).thenReturn(true);
        characters.when(()->Characters.isCurrent(b)).thenReturn(true);
        economy=mockStatic(DenarEconomy.class);
        economy.when(DenarEconomy::getLedger).thenAnswer(call->ledger);
        reopen(); budget=new StealBudget(100);
        assertEquals(Result.APPLIED,Denars.transfer("seed","staff:seed",Denars.MINT,Denars.pouch(a.ref()),1000));
    }
    void reopen() { ledger=Ledger.open(root.resolve("ledger.db").toFile(),Logger.getAnonymousLogger(),account->false); }
    @AfterEach void close() { ledger.close(); economy.close(); characters.close(); MockBukkit.unmock(); }
    PouchPayment payment(long cents) { return new PouchPayment(root.resolve("intents"),a,b,cents,cents/100.0); }

    @Test void atomicTransferHasSavedIdAndSurvivesLedgerReopenWithoutChargingBudgetAgain() throws Exception {
        var payment=payment(350);
        assertEquals(Result.APPLIED,payment.settle(budget));
        assertEquals(650,Denars.balance(Denars.pouch(a.ref()))); assertEquals(350,Denars.balance(Denars.pouch(b.ref())));
        try(var files=Files.list(root.resolve("intents"))) {
            var stored=Files.readString(files.findFirst().orElseThrow());
            assertTrue(stored.contains("cents=350")); assertTrue(stored.contains(a.ref().characterId().toString()));
        }
        ledger.close(); reopen(); assertEquals(Result.APPLIED,payment.settle(budget));
        assertEquals(3.5,budget.getUsed()); assertEquals(650,Denars.balance(Denars.pouch(a.ref())));
    }
    @Test void committedPaymentWithLostReplyUsesStoredResultOnRetry() throws Exception {
        var payment=payment(350);
        try(var money=mockStatic(DenarMoney.class,CALLS_REAL_METHODS)) {
            money.when(()->DenarMoney.transferPouch(anyString(),eq(a),eq(b),eq(350L))).thenAnswer(call->{
                assertEquals(Result.APPLIED,Denars.transfer(call.getArgument(0),"thievery:robbery",Denars.pouch(a.ref()),Denars.pouch(b.ref()),350));
                return Result.UNAVAILABLE;
            });
            assertEquals(Result.UNAVAILABLE,payment.settle(budget)); assertEquals(0,budget.getUsed());
            ledger.close(); reopen();
            characters.when(()->Characters.isCurrent(b)).thenReturn(false);
            assertEquals(Result.APPLIED,payment.settle(budget)); assertEquals(3.5,budget.getUsed());
            money.verify(()->DenarMoney.transferPouch(anyString(),eq(a),eq(b),eq(350L)),times(1));
        }
        assertEquals(650,Denars.balance(Denars.pouch(a.ref()))); assertEquals(350,Denars.balance(Denars.pouch(b.ref())));
    }
    @Test void insufficientBalanceIsPermanentlyRefusedAndStaleCharacterCannotBeDebited() throws Exception {
        var payment=payment(1001); assertEquals(Result.REFUSED,payment.settle(budget));
        Denars.transfer("more","staff:seed",Denars.MINT,Denars.pouch(a.ref()),1000);
        assertEquals(Result.REFUSED,payment.settle(budget)); assertEquals(0,budget.getUsed());
        characters.when(()->Characters.isCurrent(a)).thenReturn(false);
        assertEquals(Result.REFUSED,payment(500).settle(budget)); assertEquals(2000,Denars.balance(Denars.pouch(a.ref())));
    }
    @Test void unreadyLedgerCreatesNoIntentAndWriteFailureCannotMoveMoney() throws Exception {
        var payment=payment(100); ledger.close(); assertEquals(Result.UNAVAILABLE,payment.settle(budget));
        assertFalse(Files.exists(root.resolve("intents"))); reopen();
        Path file=root.resolve("file"); Files.writeString(file,"block");
        assertThrows(IOException.class,()->new PouchPayment(file,a,b,100,1).settle(budget));
        assertEquals(1000,Denars.balance(Denars.pouch(a.ref()))); assertEquals(0,budget.getUsed());
        var plugin=MockBukkit.getMock().getPluginManager().getPlugin("DenarEconomy");
        MockBukkit.getMock().getPluginManager().disablePlugin(plugin);
        assertEquals(Result.UNAVAILABLE,payment.settle(budget));
    }
    @Test void invalidInputsNeverCreateAnIntent() throws Exception {
        assertEquals(Result.REFUSED,new PouchPayment(root,a,null,100,1).settle(budget));
        assertEquals(Result.REFUSED,new PouchPayment(root,null,b,100,1).settle(budget));
        assertEquals(Result.REFUSED,payment(0).settle(budget));
        assertEquals(Result.REFUSED,new PouchPayment(root,a,b,100,Double.NaN).settle(budget));
        assertEquals(Result.REFUSED,new PouchPayment(root,a,b,100,-1).settle(budget));
        assertEquals(0,budget.getUsed());
    }
}
