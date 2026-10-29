plugins {
    id("base-conventions")
    id("integration-test-suite")
    id("game-cache-test-conventions")
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.guice)
    implementation(projects.api.areaChecker)
    implementation(projects.api.combatAccuracy)
    implementation(projects.api.combatMaxhit)
    implementation(projects.api.combat.combatCommons)
    implementation(projects.api.combat.combatWeapon)
    implementation(projects.api.config)
    implementation(projects.api.npc)
    implementation(projects.api.player)
    implementation(projects.api.random)
    implementation(projects.api.utils.utilsVars)
    implementation(projects.engine.game)
    implementation(projects.engine.map)
    implementation(projects.engine.module)
    implementation(projects.engine.plugin)
    testImplementation(libs.mockk)
    testImplementation(libs.fastutil)
    testImplementation(projects.api.registry)
    testImplementation(projects.engine.annotations)
    testImplementation(projects.engine.events)
    testImplementation(projects.engine.routefinder)
    integrationImplementation(projects.api.combat.combatCommons)
    integrationImplementation(projects.api.combat.combatWeapon)
}
