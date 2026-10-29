plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

dependencies {
    implementation(projects.api.combat.combatManager)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.repo)
    implementation(projects.api.weapons)
    testImplementation(libs.mockk)
    testImplementation(projects.api.combat.combatFormulas)
    testImplementation(projects.api.hitPlugin)
    testImplementation(projects.api.npcPlugin)
    testImplementation(projects.api.registry)
    testImplementation(projects.engine.routefinder)
    testImplementation(projects.engine.utilsBits)
    testImplementation(libs.guice)
    testImplementation(projects.api.invStorage)
    testImplementation(projects.api.invtx)
    testImplementation(projects.api.spells)
    testImplementation(projects.api.spellsRunes)
    testImplementation(projects.content.skills.magic.spellAttacks)
}
