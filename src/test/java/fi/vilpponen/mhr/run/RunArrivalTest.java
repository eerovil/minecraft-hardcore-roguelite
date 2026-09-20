package fi.vilpponen.mhr.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What joining does to a player, and — the part worth a test of its own — what it does not.
 *
 * <p>A stopped save is one whose phase may be unknown, and the rule there is to leave the player's
 * save alone. That is a rule about not acting, which no amount of watching a server prove: nothing
 * happening looks the same whether it was decided or forgotten. Asking the decision directly is the
 * only way to say it out loud.
 */
class RunArrivalTest {

	@Test
	@DisplayName("a stopped save never moves anybody, whatever else is true of them")
	void stoppedSavesNeverMoveAnybody() {
		// Every shape a joining player can have. None of them is a reason to touch a save whose
		// own state is in doubt — least of all the helpful-looking one, putting them in the lobby,
		// because the lobby is permanent and keeps whatever arrives in it.
		for (boolean recordUnknown : new boolean[] {false, true}) {
			for (boolean running : new boolean[] {false, true}) {
				for (boolean inARunWorld : new boolean[] {false, true}) {
					for (boolean admitted : new boolean[] {false, true}) {
						RunArrival arrival = RunArrival.decide(
								true, recordUnknown, running, inARunWorld, admitted);

						assertFalse(arrival.movesThePlayer(),
								"stopped, recordUnknown=" + recordUnknown + ", running=" + running
										+ ", inARunWorld=" + inARunWorld + ", admitted=" + admitted
										+ " gave " + arrival);
					}
				}
			}
		}
	}

	@Test
	@DisplayName("an unreadable record turns the player away rather than letting them in")
	void unknownRecordRefuses() {
		assertEquals(RunArrival.REFUSED, RunArrival.decide(true, true, false, false, false));
		assertEquals(RunArrival.REFUSED, RunArrival.decide(true, true, true, true, true));
	}

	@Test
	@DisplayName("a missing lobby leaves them where they are, because the record is still good")
	void missingLobbyExplains() {
		assertEquals(RunArrival.TOLD_AND_LEFT_ALONE, RunArrival.decide(true, false, false, false, false));
		assertEquals(RunArrival.TOLD_AND_LEFT_ALONE, RunArrival.decide(true, false, true, true, true));
	}

	@Test
	@DisplayName("no run in progress means the lobby")
	void betweenRunsMeansTheLobby() {
		assertEquals(RunArrival.TO_THE_LOBBY, RunArrival.decide(false, false, false, false, false));
		assertEquals(RunArrival.TO_THE_LOBBY, RunArrival.decide(false, false, false, true, true));
	}

	@Test
	@DisplayName("coming back to the run you were playing changes nothing")
	void theSameRunIsLeftAlone() {
		assertEquals(RunArrival.LEFT_WHERE_THEY_ARE, RunArrival.decide(false, false, true, true, true));
	}

	@Test
	@DisplayName("anything else joins the run, and so crosses its start boundary")
	void everybodyElseJoinsTheRun() {
		// In the lobby when it started.
		assertEquals(RunArrival.INTO_THE_RUN, RunArrival.decide(false, false, true, false, false));

		// The one a dimension key cannot see: logged out in the previous run, whose overworld this
		// run has replaced under the same key. In a run world, and not admitted to this one.
		assertEquals(RunArrival.INTO_THE_RUN, RunArrival.decide(false, false, true, true, false));
	}
}
