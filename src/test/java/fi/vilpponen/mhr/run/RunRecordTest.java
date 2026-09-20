package fi.vilpponen.mhr.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules of the loop, without a game.
 *
 * <p>Every one of these is an acceptance criterion of the run lifecycle that does not need a world
 * to be true: a run cannot start twice, a death ends a run once, a reward is never written down as
 * given until it has been given, and a reload does not turn a quit into a death or a half-built run
 * into a playable one. They live here
 * rather than in a GameTest because the rules are rules about {@link RunRecord} and nothing else,
 * and a test that boots Minecraft to ask them would be slower without proving more.
 */
class RunRecordTest {

	private static RunRecord lobby() {
		return RunRecord.NEW_SAVE;
	}

	private static RunRecord running() {
		return lobby().beginCreating(1234L, 1_000L).created();
	}

	@Nested
	@DisplayName("starting a run")
	class Starting {
		@Test
		void aNewSaveIsInTheLobbyWithNoRunBehindIt() {
			assertEquals(RunPhase.LOBBY, lobby().phase());
			assertEquals(0, lobby().runId());
			assertEquals(0, lobby().completedRuns());
			assertFalse(lobby().isRunning());
		}

		@Test
		void startingClaimsTheNextRunIdAndTheSeed() {
			RunRecord creating = lobby().beginCreating(4242L, 7_000L);

			assertEquals(RunPhase.CREATING_RUN, creating.phase());
			assertEquals(1, creating.runId());
			assertEquals(4242L, creating.seed());
			assertEquals(7_000L, creating.startedAt());
			assertFalse(creating.isRunning(), "a run being built is not a run being played");
		}

		@Test
		void aRunCannotBeStartedWhileOneIsAlreadyInProgress() {
			RunRecord running = running();

			IllegalStateException thrown =
					assertThrows(IllegalStateException.class, () -> running.beginCreating(9L, 0L));
			assertTrue(thrown.getMessage().contains("RUNNING"), thrown.getMessage());
		}

		@Test
		void aRunCannotBeStartedTwiceWhileTheFirstIsStillBeingBuilt() {
			RunRecord creating = lobby().beginCreating(1L, 0L);

			assertThrows(IllegalStateException.class, () -> creating.beginCreating(2L, 0L));
		}

		@Test
		void aRunThatCouldNotBeBuiltGoesBackToTheLobbyWithoutCountingAsPlayed() {
			RunRecord abandoned = lobby().beginCreating(1L, 0L).abandoned();

			assertEquals(RunPhase.LOBBY, abandoned.phase());
			assertEquals(0, abandoned.completedRuns());
			assertFalse(abandoned.isRunning());
		}

		@Test
		void anAbandonedRunKeepsItsIdSpent() {
			RunRecord abandoned = lobby().beginCreating(1L, 0L).abandoned();

			assertEquals(1, abandoned.runId());
			assertEquals(2, abandoned.beginCreating(2L, 0L).runId(),
					"a second attempt is a different run and must not answer to the first one's id");
		}

		@Test
		void onlyARunBeingBuiltCanBeAbandoned() {
			assertThrows(IllegalStateException.class, () -> lobby().abandoned());
			assertThrows(IllegalStateException.class, () -> running().abandoned());
		}

		@Test
		void eachRunGetsItsOwnId() {
			RunRecord first = running();
			RunRecord second = first.beginEnding().rewarded().returnedToLobby().beginCreating(99L, 0L);

			assertEquals(1, first.runId());
			assertEquals(2, second.runId());
			assertNotEquals(first.seed(), second.seed());
		}
	}

	@Nested
	@DisplayName("ending a run")
	class Ending {
		@Test
		void onlyARunInProgressCanEnd() {
			assertThrows(IllegalStateException.class, () -> lobby().beginEnding());
			assertThrows(IllegalStateException.class, () -> lobby().beginCreating(1L, 0L).beginEnding());
		}

		@Test
		void aSecondDeathFindsNoRunToEnd() {
			RunRecord ending = running().beginEnding();

			IllegalStateException thrown =
					assertThrows(IllegalStateException.class, ending::beginEnding);
			assertTrue(thrown.getMessage().contains("ENDING_RUN"), thrown.getMessage());
		}

		@Test
		void aRunIsCountedWhenItGetsBackToTheLobbyAndNotBefore() {
			RunRecord ending = running().beginEnding().rewarded();
			assertEquals(0, ending.completedRuns());

			assertEquals(1, ending.returnedToLobby().completedRuns());
		}

		@Test
		void theRewardHasToBeCommittedBeforeTheLobbyIsReached() {
			RunRecord ending = running().beginEnding();

			IllegalStateException thrown =
					assertThrows(IllegalStateException.class, ending::returnedToLobby);
			assertTrue(thrown.getMessage().contains("not been rewarded"), thrown.getMessage());
		}
	}

	@Nested
	@DisplayName("committing the reward")
	class Reward {
		@Test
		void aRunThatHasJustEndedIsOwedItsReward() {
			assertTrue(running().beginEnding().rewardOutstanding());
		}

		@Test
		void committingItMarksTheRunAsPaid() {
			RunRecord paid = running().beginEnding().rewarded();

			assertFalse(paid.rewardOutstanding());
			assertEquals(paid.runId(), paid.rewardedRunId());
		}

		@Test
		void itCannotBeCommittedTwice() {
			RunRecord paid = running().beginEnding().rewarded();

			IllegalStateException thrown = assertThrows(IllegalStateException.class, paid::rewarded);
			assertTrue(thrown.getMessage().contains("already been rewarded"), thrown.getMessage());
		}

		@Test
		void aPayoutThatFailedIsStillOwed() {
			// The lifecycle only calls rewarded() once the listeners have returned, so a payout that
			// threw, or a write that failed, leaves exactly this record — and it still says owed.
			RunRecord ending = running().beginEnding();

			assertTrue(ending.rewardOutstanding());
			assertEquals(ending, ending.recovered(),
					"a reload must not decide the reward happened after all");
			assertTrue(ending.recovered().rewardOutstanding());
		}

		@Test
		void aRetryAfterAFailedPayoutCommitsOnce() {
			RunRecord owed = running().beginEnding();

			RunRecord paid = owed.rewarded();

			assertFalse(paid.rewardOutstanding());
			assertThrows(IllegalStateException.class, paid::rewarded);
			assertEquals(1, paid.returnedToLobby().completedRuns());
		}

		@Test
		void thePreviousRunsPaymentDoesNotCountForTheNextOne() {
			RunRecord secondRunEnding = running()
					.beginEnding().rewarded().returnedToLobby()
					.beginCreating(5L, 0L).created()
					.beginEnding();

			assertTrue(secondRunEnding.rewardOutstanding(),
					"run 2 is owed a reward even though run 1 was paid");
			assertEquals(1, secondRunEnding.rewardedRunId());
			assertEquals(2, secondRunEnding.runId());
		}
	}

	@Nested
	@DisplayName("what a reload means")
	class Recovery {
		@Test
		void quittingDuringARunLeavesTheRunRunning() {
			RunRecord running = running();

			assertSame(running, running.recovered(), "closing the game is not dying");
			assertTrue(running.recovered().isRunning());
			assertEquals(0, running.recovered().completedRuns());
		}

		@Test
		void aRunThatWasNeverFinishedBeingBuiltFallsBackToTheLobby() {
			RunRecord recovered = lobby().beginCreating(77L, 0L).recovered();

			assertEquals(RunPhase.LOBBY, recovered.phase());
			assertEquals(0, recovered.completedRuns());
		}

		@Test
		void theNextRunAfterAnAbandonedOneStillGetsAFreshId() {
			RunRecord recovered = lobby().beginCreating(77L, 0L).recovered();

			assertEquals(2, recovered.beginCreating(78L, 0L).runId(),
					"the abandoned run used up id 1");
		}

		@Test
		void aRunThatWasEndingIsStillEndingAndStillOwedItsReward() {
			RunRecord recovered = running().beginEnding().recovered();

			assertEquals(RunPhase.ENDING_RUN, recovered.phase());
			assertTrue(recovered.rewardOutstanding());
		}

		@Test
		void aCrashAfterTheRewardWasCommittedDoesNotPayTwice() {
			RunRecord recovered = running().beginEnding().rewarded().recovered();

			assertEquals(RunPhase.ENDING_RUN, recovered.phase());
			assertFalse(recovered.rewardOutstanding(), "the payout already happened");
			assertEquals(1, recovered.returnedToLobby().completedRuns());
		}

		@Test
		void recoveryNeverLosesTheCountOfRunsPlayed() {
			RunRecord afterTwo = running()
					.beginEnding().rewarded().returnedToLobby()
					.beginCreating(5L, 0L).created()
					.beginEnding().rewarded().returnedToLobby();

			assertEquals(2, afterTwo.completedRuns());
			assertEquals(2, afterTwo.recovered().completedRuns());
		}
	}
}
