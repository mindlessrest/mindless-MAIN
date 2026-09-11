package mindless.utility;

import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScorePlayerTeam;
import org.junit.Test;
import static org.junit.Assert.*;

public class BedwarsTeamTest {
    @Test public void comparesWholePlayerListPrefixesIncludingWhite() {
        Scoreboard board = new Scoreboard();
        ScorePlayerTeam first = board.createTeam("first");
        ScorePlayerTeam second = board.createTeam("second");
        assertFalse(BedwarsTeam.sameTeamPrefix(first, second));
        assertFalse(BedwarsTeam.sameTeamPrefix(first, null));
        first.setNamePrefix("\u00a7f");
        second.setNamePrefix("\u00a7f");
        assertTrue(BedwarsTeam.sameTeamPrefix(first, second));
        second.setNamePrefix("\u00a7c");
        assertFalse(BedwarsTeam.sameTeamPrefix(first, second));
        first.setNamePrefix("\u00a7c[R] ");
        second.setNamePrefix("\u00a7c[B] ");
        assertFalse(BedwarsTeam.sameTeamPrefix(first, second));
    }
}
