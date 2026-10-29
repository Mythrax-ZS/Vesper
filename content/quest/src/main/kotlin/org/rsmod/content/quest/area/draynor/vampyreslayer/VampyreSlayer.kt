package org.rsmod.content.quest.area.draynor.vampyreslayer

import dev.openrune.rscm.RSCM.asRSCM
import jakarta.inject.Inject
import org.rsmod.api.invtx.invTransaction
import org.rsmod.api.invtx.select
import org.rsmod.api.player.dialogue.Dialogue
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.script.onOpHeld5
import org.rsmod.api.script.onOpNpc1
import org.rsmod.content.quest.manager.ItemRewardDisplay
import org.rsmod.content.quest.manager.QuestScript
import org.rsmod.content.quest.manager.rewards
import org.rsmod.game.entity.Player
import org.rsmod.plugin.scripts.ScriptContext

class VampyreSlayer @Inject constructor() :
    QuestScript(
        "quest_vampyreslayer",
        "varp.vampire",
        rewards { xp("stat.attack", 4825.0) },
        ItemRewardDisplay(Stake),
        questVarbit = VampyreProgress,
    ) {
    override fun ScriptContext.init() {
        check(quest.maxSteps == VampyreSlayerStage.Complete)
        onOpNpc1("npc.morgan") { startDialogue(it.npc) { morgan() } }
        onOpNpc1("npc.dr_harlow") { startDialogue(it.npc) { harlow() } }
        onOpNpc1("npc.dr_harlow_myq6") { startDialogue(it.npc) { relocatedHarlow() } }
        onOpHeld5(Stake) {
            startDialogue {
                val destroy = choice2("Yes.", true, "No.", false, title = "Destroy the stake?")
                if (destroy) access.invDel(access.inv, Stake, count = 1, slot = it.slot)
            }
        }
    }

    override fun subTitle(): String =
        "talking to <col=800000>Morgan</col> in his house in <col=800000>Draynor Village</col>."

    override fun questLog(player: ProtectedAccess): String = questJournal(player) {
        line("Morgan wants me to defeat <red>Count Draynor</red> in Draynor Manor.")
        if (player.player.vampyreStakeGiven == 0) {
            line("I should speak to <red>Dr Harlow</red> in Varrock's <red>Blue Moon Inn</red>.")
            if (quest.getQuestStage(player.player) == VampyreSlayerStage.HarlowAsked) {
                line("Dr Harlow wants a <red>beer</red> before he will help me.")
            }
        } else {
            strike("Dr Harlow explained how to stop the vampyre regenerating.")
            line("I need a <red>stake</red> and a <red>hammer</red> to finish the Count.")
            line("<red>Garlic</red> from the cupboard upstairs in Morgan's house will weaken him.")
        }
    }

    override fun completedLog(player: ProtectedAccess): String = completionJournal(player) {
        line("I defeated Count Draynor in the cellar of Draynor Manor.")
        line("Dr Harlow's stake stopped the vampyre regenerating. Morgan can rest at last.")
    }

    private suspend fun Dialogue.morgan() {
        if (quest.isQuestCompleted(player)) {
            if (player.vampyreMorganThanked == 0) {
                chatPlayer(happy, "I have some good news. Count Draynor is no more!")
                player.vampyreMorganThanked = 1
                chatNpc(happy, "He's really gone? Finally, we can live without fear! Thank you, thank you! You're a true hero!")
                chatPlayer(happy, "I'm happy to have helped.")
            } else {
                chatNpc(happy, "Once again, thank you for slaying that vampyre! You will always be a hero!")
                chatPlayer(happy, "Don't mention it.")
            }
            return
        }
        if (quest.getQuestStage(player) == VampyreSlayerStage.NotStarted) {
            chatNpc(worried, "Please help us! Count Draynor, the vampyre in the manor to the north, must be stopped.")
            if (player.combatLevel < 20) {
                mesbox("Before starting this quest, be aware that your combat level is lower than the recommended level of 20.")
            }
            val start = choice2("Yes.", true, "No.", false, title = "Start the Vampyre Slayer quest?")
            if (!start) {
                chatNpc(sad, "I understand. Perhaps another adventurer will help us.")
                return
            }
            quest.setQuestStage(access, VampyreSlayerStage.Started)
            chatNpc(happy, "Dr Harlow is a retired vampyre hunter. Look for him in the Blue Moon Inn in Varrock.")
        } else if (player.vampyreStakeGiven != 0) {
            chatNpc(worried, "Have you dealt with Count Draynor yet?")
            chatPlayer(neutral, "Not yet. Dr Harlow recommended garlic.")
            chatNpc(neutral, "There is plenty in the cupboard upstairs. Help yourself, and good luck!")
        } else {
            chatNpc(worried, "Please speak to Dr Harlow in Varrock's Blue Moon Inn. He should know how to help.")
        }
        if (choice2("Tell me more about Count Draynor.", true, "That's all I need. Thanks.", false)) {
            morganCountHistory()
        } else {
            chatNpc(happy, "Good luck, adventurer.")
        }
    }

    private suspend fun Dialogue.morganCountHistory() {
        chatNpc(neutral, "Legend says the Count came from the east, built the manor and terrorised this village.")
        chatPlayer(quiz, "Has nobody tried to stop him?")
        chatNpc(worried, "Others tried and never returned. A vampyre needs special equipment to defeat.")
        chatPlayer(quiz, "But has anyone actually seen him attack the village recently?")
        chatNpc(shifty, "Not since I moved here. Some say he hasn't left his manor for generations.")
        chatPlayer(confused, "Then why are you so determined to kill him?")
        chatNpc(worried, "What if he returns? Even our village bears his name. I'll never feel safe while he's there.")
        chatPlayer(neutral, "I'll speak to Dr Harlow and find out more.")
    }

    private suspend fun Dialogue.harlow() {
        val stage = quest.getQuestStage(player)
        if (stage == VampyreSlayerStage.NotStarted || stage == VampyreSlayerStage.Complete) {
            chatNpc(drunk, "Buy me a drink pleassh...")
            chatPlayer(neutral, "I think you've had enough.")
            return
        }
        if (stage == VampyreSlayerStage.Started) {
            chatNpc(drunk, "Buy me a drink pleassh...")
            if (!choice2("I need help dealing with a vampyre.", true, "I think you've had enough.", false)) {
                chatPlayer(neutral, "I think you've had enough.")
                return
            }
            chatPlayer(neutral, "Morgan sent me. I need to deal with Count Draynor.")
            quest.setQuestStage(access, VampyreSlayerStage.HarlowAsked)
            chatNpc(drunk, "Draynor? Buy me a beer firsht, then we'll talk.")
            return
        }
        if (player.vampyreStakeGiven == 0) {
            chatNpc(drunk, "Have yoush got me a beer?")
            val hasBeer = player.inv.contains("obj.beer")
            val firstOption = if (hasBeer) "Yes, here you go." else "I'll go and get you one."
            if (!choice2(firstOption, true, "No. I think you've had enough.", false)) {
                chatPlayer(neutral, "No. I think you've had enough.")
                chatNpc(drunk, "Then I can't help yoush.")
                return
            }
            if (!hasBeer) {
                chatPlayer(neutral, "I'll go and get you one.")
                chatNpc(drunk, "Thanksh, matey.")
                return
            }
            // Commit both sides before a suspension: cancellation cannot lose the beer or duplicate a stake.
            if (!exchangeBeer(player)) {
                access.mes("You no longer have the beer, or there isn't room for the stake.")
                return
            }
            mesbox("You give Dr Harlow a beer, and he gives you a wooden stake.")
            advice()
            harlowOptions(reminder = false)
            return
        }
        if (!player.inv.contains(Stake)) {
            chatPlayer(sad, "I don't have the stake you gave me.")
            if (!quest.isQuestInProgress(player)) return
            if (!player.inv.contains(Stake) && access.invAdd(access.inv, Stake).failure) return
            chatNpc(drunk, "Good job I've gotsh some spares. Here'sh another.")
            advice()
            harlowOptions(reminder = false)
            return
        }
        chatNpc(drunk, "Back again! Have you dealt with the vampyre yet?")
        harlowOptions(reminder = true)
    }

    private suspend fun Dialogue.harlowOptions(reminder: Boolean) {
        var previous: HarlowTopic? = if (reminder) null else HarlowTopic.Reminder
        while (true) {
            // The native follow-up menu offers the other subjects, rather than repeating the
            // explanation just given. A fresh conversation still offers all three subjects.
            val topic = when (previous) {
                null -> choice4(
                    "Remind me what I need to do.", HarlowTopic.Reminder,
                    "Has nobody seen Count Draynor?", HarlowTopic.Count,
                    "Were you really a vampyre hunter?", HarlowTopic.Career,
                    "I'll get going.", HarlowTopic.Leave,
                )
                HarlowTopic.Reminder -> choice3(
                    "Has nobody seen Count Draynor?", HarlowTopic.Count,
                    "Were you really a vampyre hunter?", HarlowTopic.Career,
                    "I'll get going.", HarlowTopic.Leave,
                )
                HarlowTopic.Count -> choice3(
                    "Remind me what I need to do.", HarlowTopic.Reminder,
                    "Were you really a vampyre hunter?", HarlowTopic.Career,
                    "I'll get going.", HarlowTopic.Leave,
                )
                HarlowTopic.Career -> choice3(
                    "Remind me what I need to do.", HarlowTopic.Reminder,
                    "Has nobody seen Count Draynor?", HarlowTopic.Count,
                    "I'll get going.", HarlowTopic.Leave,
                )
                HarlowTopic.Leave -> return
            }
            when (topic) {
                HarlowTopic.Reminder -> advice()
                HarlowTopic.Count -> {
                    chatNpc(drunk, "Adventurers go in, but they don't come back. Something must be in there.")
                    chatPlayer(quiz, "Why doesn't he leave the manor?")
                    chatNpc(drunk, "Perhaps he'sh too weak. But even a weak vampyre can be deadly.")
                }
                HarlowTopic.Career -> {
                    chatNpc(drunk, "I hunted vampyres in Morytania. Never the strongest ones, though. I couldn't hurt those.")
                    chatPlayer(worried, "What if Draynor is one of those?")
                    chatNpc(drunk, "Magic keeps vampyres east of the river. Those trapped over here are weakened.")
                    chatPlayer(quiz, "Why haven't you killed him?")
                    chatNpc(drunk, "He hasn't bothered anyone for ages. Just the occasional adventurer trying to prove themshelves.")
                }
                HarlowTopic.Leave -> {
                    chatPlayer(neutral, "I'd better get going.")
                    chatNpc(drunk, "Good luck, matey.")
                    return
                }
            }
            previous = topic
        }
    }

    private suspend fun Dialogue.relocatedHarlow() {
        // Native NPC transforms control his visibility after The Blood Moon Rises. This binding
        // supplies the relocated conversation; it does not invent a spawn or award quest progress.
        chatPlayer(quiz, "Dr Harlow? Why are you in Morytania?")
        chatNpc(drunk, "Enjoying a drink, of courshe! I heard the great vampyre had been defeated.")
        chatNpc(drunk, "I bet a shtake did the trick. With him gone, I wanted to return and shee how things have changed.")
        chatPlayer(neutral, "I see.")
    }

    private enum class HarlowTopic { Reminder, Count, Career, Leave }

    private suspend fun Dialogue.advice() {
        chatNpc(drunk, "Vampyres regenerate. When Draynor ish weak, use a hammer to drive the stake into him. General shtores sell hammers.")
        chatNpc(drunk, "Take garlic too. Vampyres don't likesh garlic.")
        chatPlayer(neutral, "I'll see if Morgan has some. Thanks.")
    }

    internal fun exchangeBeer(player: Player): Boolean {
        if (quest.getQuestStage(player) != VampyreSlayerStage.HarlowAsked || player.vampyreStakeGiven != 0) return false
        val result = player.invTransaction(player.inv) {
            val inventory = select(player.inv)
            delete { from = inventory; obj = "obj.beer".asRSCM(); strictCount = 1 }
            insert { into = inventory; obj = Stake.asRSCM(); strictCount = 1 }
        }
        if (!result.success) return false
        player.vampyreBeerGiven = 1
        player.vampyreStakeGiven = 1
        return true
    }

    internal fun settle(access: ProtectedAccess) {
        if (access.player.vampyreCompletionPending == 0) return
        quest.completeQuest(access)
        if (quest.isQuestCompleted(access.player)) access.player.vampyreCompletionPending = 0
    }
}
