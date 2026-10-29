import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const generator = fileURLToPath(new URL('./content-progress.mjs', import.meta.url))
const START = '<!-- content-progress:start -->'
const END = '<!-- content-progress:end -->'
const GREEN = '🟢'
const RED = '🔴'
const AMBER = /[🟡🟠]/u

function fixture(t, { categories, files = {}, npcs = '', readme } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vesper-progress-test-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const put = (name, contents) => {
    const target = path.join(root, name)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, contents)
  }
  put('tools/progress/content-progress.mjs', fs.readFileSync(generator))
  put('tools/progress/features.json', JSON.stringify({ categories: categories ?? [skills()] }))
  put('tools/progress/wiki-cache.json', JSON.stringify({ pages: {}, categories: {}, fetched: '2026-01-01' }))
  put('osrs-dumps/dump.npc', npcs)
  put('README.md', readme ?? `# Fixture\n\nBefore report.\n${START}\nOld report.\n${END}\nAfter report.\n`)
  for (const [name, contents] of Object.entries(files)) put(name, contents)

  // The CLI queries git dates; use an isolated, deterministic repository rather
  // than allowing the fixture to inherit the developer's repository metadata.
  for (const args of [
    ['init', '--quiet'],
    ['-c', 'user.name=Progress fixture', '-c', 'user.email=fixture@example.invalid',
      '-c', 'commit.gpgsign=false', 'commit', '--quiet', '--allow-empty', '-m', 'Fixture'],
  ]) {
    const result = spawnSync('git', args, { cwd: root, encoding: 'utf8' })
    assert.equal(result.status, 0, result.stderr)
  }

  const run = (args = [], nodeArgs = []) => {
    const result = spawnSync(process.execPath, [
      ...nodeArgs, path.join(root, 'tools/progress/content-progress.mjs'), ...args,
    ], { cwd: root, encoding: 'utf8', timeout: 15_000 })
    assert.ifError(result.error)
    assert.equal(result.signal, null, result.stderr)
    return result
  }
  const read = (name) => fs.readFileSync(path.join(root, name), 'utf8')
  const generate = () => {
    const result = run()
    assert.equal(result.status, 0, result.stderr)
    return read('PROGRESS.md')
  }
  return { root, put, read, run, generate }
}

function skills(features = [skill('Hunter')]) {
  return { name: 'Skills', requireRuntime: true, features }
}

function skill(name, overrides = {}) {
  return {
    id: name.toLowerCase(), name, wiki: name,
    module: `content/skills/${name.toLowerCase()}`, ...overrides,
  }
}

function moduleFiles(module, files) {
  return Object.fromEntries([
    [`${module}/build.gradle.kts`, 'plugins { id("base-conventions") }\n'],
    ...Object.entries(files).map(([name, text]) => [`${module}/${name}`, text]),
  ])
}

function row(report, name) {
  const found = report.split(/\r?\n/).filter((line) => line.startsWith('| ')
    && (line.includes(`| ${name} |`) || line.includes(`[${name}](`)))
  assert.equal(found.length, 1, `Expected one feature row for ${name}:\n${report}`)
  return found[0]
}

function assertCount(report, category, numerator, denominator) {
  assert.ok(report.includes(`${category} **${numerator}/${denominator}**`), report)
}

function snapshot(root) {
  const result = {}
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === '.git') continue
      const file = path.join(dir, entry.name)
      if (entry.isDirectory()) walk(file)
      else result[path.relative(root, file)] = {
        bytes: fs.readFileSync(file).toString('base64'),
        modified: fs.statSync(file, { bigint: true }).mtimeNs.toString(),
      }
    }
  }
  walk(root)
  return result
}

function assertReadOnlyCheck(f, success, args = ['--check'], nodeArgs = []) {
  // Old timestamps detect rewriting identical bytes, even on coarse filesystems.
  for (const name of Object.keys(snapshot(f.root))) {
    fs.utimesSync(path.join(f.root, name), new Date('2000-01-01'), new Date('2000-01-01'))
  }
  const before = snapshot(f.root)
  const result = f.run(args, nodeArgs)
  if (success) assert.equal(result.status, 0, result.stderr)
  else assert.notEqual(result.status, 0, 'A stale or invalid report must fail --check')
  assert.deepEqual(snapshot(f.root), before, '--check must not create, remove or rewrite files')
  return result
}

for (const [name, source] of [
  ['plugin script', 'class HunterScript : PluginScript() {}'],
  ['registered operation', 'fun register() { onOpHeld1("obj.hunting_ojibway_bird_snare") {} }'],
  ['encounter registration', 'fun register() { encounter("npc.hunting_bird") {} }'],
]) {
  test(`Skills inherits runtime requirement and credits a main ${name}`, (t) => {
    const f = fixture(t, { files: moduleFiles('content/skills/hunter', { 'src/main/kotlin/Hunter.kt': source }) })
    const report = f.generate()
    assert.ok(row(report, 'Hunter').includes(GREEN), report)
    assertCount(report, 'Skills', 1, 1)
  })
}

test('a nested skill runtime counts while main data and pack-only scaffolds remain supporting', (t) => {
  const f = fixture(t, {
    categories: [skills([skill('Magic'), skill('Hunter'), skill('Farming')])],
    files: {
      ...moduleFiles('content/skills/magic/alchemy', {
        'src/main/kotlin/Alchemy.kt': 'class AlchemyScript : PluginScript() {}',
      }),
      ...moduleFiles('content/skills/hunter/pack', {
        // A behavioural-looking reference in pack data must not become runtime.
        'src/main/kotlin/HunterPack.kt': 'val script = "PluginScript npc.hunting_bird"',
      }),
      ...moduleFiles('content/skills/farming', {
        'src/main/kotlin/PatchData.kt': 'data class PatchData(val seed: String)',
      }),
    },
  })
  const report = f.generate()
  assert.ok(row(report, 'Magic').includes(GREEN), report)
  assert.match(row(report, 'Hunter'), AMBER)
  assert.match(row(report, 'Farming'), AMBER)
  assertCount(report, 'Skills', 1, 3)
})

test('shopkeeper symbol references support skills without claiming their runtime exists', (t) => {
  const f = fixture(t, {
    categories: [skills([
      skill('Farming', { rscmPrefixes: ['farming_'] }),
      skill('Construction', { rscmPrefixes: ['poh_'] }),
    ])],
    npcs: '[farming_shopkeeper_1]\nname=Sarah\n[poh_garden_supplier]\nname=Garden supplier\n',
    files: moduleFiles('content/areas/city/shops', {
      'src/main/kotlin/Shops.kt': 'class Shops : PluginScript() {\n'
        + 'fun setup() { onOpNpc1("npc.farming_shopkeeper_1") {}; onOpNpc1("npc.poh_garden_supplier") {} }\n}',
    }),
  })
  const report = f.generate()
  assert.match(row(report, 'Farming'), AMBER)
  assert.match(row(report, 'Construction'), AMBER)
  assertCount(report, 'Skills', 0, 2)
})

for (const sourceSet of ['test', 'integration']) {
  test(`${sourceSet} modules and symbol references never count as implementation`, (t) => {
    const f = fixture(t, {
      categories: [skills([
        skill('Hunter', { rscmPrefixes: ['hunting_'] }),
        skill('Farming', { rscmPrefixes: ['farming_'] }),
      ])],
      npcs: '[hunting_bird]\nname=Bird\n[farming_shopkeeper_1]\nname=Sarah\n',
      files: {
        ...moduleFiles('content/skills/hunter', {
          [`src/${sourceSet}/kotlin/HunterTest.kt`]: 'class HunterTest : PluginScript() { val bird = "npc.hunting_bird" }',
        }),
        ...moduleFiles('content/areas/city/shops', {
          [`src/${sourceSet}/kotlin/ShopTest.kt`]: 'class ShopTest : PluginScript() { val npc = "npc.farming_shopkeeper_1" }',
        }),
      },
    })
    const report = f.generate()
    assert.ok(row(report, 'Hunter').includes(RED), report)
    assert.ok(row(report, 'Farming').includes(RED), report)
    assertCount(report, 'Skills', 0, 2)
  })
}

test('pet dialogue references do not credit a skill or a boss encounter', (t) => {
  const f = fixture(t, {
    categories: [
      skills([skill('Farming', { rscmPrefixes: ['farming_'] })]),
      { name: 'Bosses', features: [{ name: 'Garden guardian', wiki: 'Garden guardian' }] },
    ],
    npcs: '[farming_pet]\nname=Tangleroot\n[garden_guardian]\nname=Garden guardian\n',
    files: moduleFiles('content/other/pets', {
      'src/main/kotlin/PetDialogue.kt': 'class PetDialogue : PluginScript() {\n'
        + 'val farming = "npc.farming_pet"\nval guardian = "npc.garden_guardian"\n}',
    }),
  })
  const report = f.generate()
  assert.ok(row(report, 'Farming').includes(RED), report)
  assert.ok(row(report, 'Garden guardian').includes(RED), report)
  assertCount(report, 'Skills', 0, 1)
  assertCount(report, 'Bosses', 0, 1)
})

test('non-skill NPC behaviour still credits a real encounter outside a dedicated module', (t) => {
  const f = fixture(t, {
    categories: [{ name: 'Bosses', features: [{ name: 'Garden guardian', wiki: 'Garden guardian' }] }],
    npcs: '[garden_guardian]\nname=Garden guardian\n',
    files: moduleFiles('content/areas/garden', {
      'src/main/kotlin/Guardian.kt': 'fun register() { encounter("npc.garden_guardian") {} }',
    }),
  })
  const report = f.generate()
  assert.ok(row(report, 'Garden guardian').includes(GREEN), report)
  assertCount(report, 'Bosses', 1, 1)
})

test('engine-backed skills require main Kotlin at the configured engine path', (t) => {
  const f = fixture(t, {
    categories: [skills([
      { name: 'Attack', wiki: 'Attack', engine: 'api/combat' },
      { name: 'Defence', wiki: 'Defence', engine: 'api/missing' },
      { name: 'Strength', wiki: 'Strength', engine: 'api/test-only' },
    ])],
    files: {
      'api/combat/src/main/kotlin/Combat.kt': 'class Combat(val damage: Int)',
      'api/test-only/src/test/kotlin/CombatTest.kt': 'class CombatTest : PluginScript() {}',
    },
  })
  const report = f.generate()
  assert.ok(row(report, 'Attack').includes(GREEN), report)
  assert.ok(row(report, 'Defence').includes(RED), report)
  assert.ok(row(report, 'Strength').includes(RED), report)
  assertCount(report, 'Skills', 1, 3)
})

test('--check accepts fresh outputs without rewriting even identical bytes', (t) => {
  const f = fixture(t)
  f.generate()
  assertReadOnlyCheck(f, true)
})

for (const name of ['PROGRESS.md', 'README.md']) {
  test(`--check rejects a stale ${name} without modifying either report`, (t) => {
    const f = fixture(t)
    f.generate()
    if (name === 'PROGRESS.md') f.put(name, 'Stale progress.\n')
    else f.put(name, f.read(name).replace(START, START + '\nStale generated text.'))
    assertReadOnlyCheck(f, false)
  })

  test(`--check rejects missing ${name} without recreating it`, (t) => {
    const f = fixture(t)
    f.generate()
    fs.unlinkSync(path.join(f.root, name))
    assertReadOnlyCheck(f, false)
  })
}

for (const marker of [START, END]) {
  test(`--check rejects README without ${marker} and leaves files unchanged`, (t) => {
    const f = fixture(t)
    f.generate()
    f.put('README.md', f.read('README.md').replace(marker, ''))
    assertReadOnlyCheck(f, false)
  })
}

test('--check rejects reversed README markers without modifying files', (t) => {
  const f = fixture(t)
  f.generate()
  f.put('README.md', `# Fixture\n${END}\nMalformed report.\n${START}\nUser notes.\n`)
  assertReadOnlyCheck(f, false)
})

for (const marker of [START, END]) {
  test(`--check rejects duplicate ${marker} markers without modifying files`, (t) => {
    const f = fixture(t)
    f.generate()
    f.put('README.md', f.read('README.md') + marker + '\n')
    assertReadOnlyCheck(f, false)
  })
}

test('--check compares only the generated README block and preserves CRLF', (t) => {
  const f = fixture(t, { readme: `# Fixture\r\n${START}\r\nOld\r\n${END}\r\nUser notes.\r\n` })
  f.generate()
  assert.ok(!/(?<!\r)\n/.test(f.read('README.md')), 'Generating the block must preserve CRLF')
  f.put('README.md', f.read('README.md').replace('User notes.', 'Edited user notes.'))
  assertReadOnlyCheck(f, true)
})

for (const args of [['--check', '--fetch-wiki'], ['--fetch-wiki', '--check']]) {
  test(`${args.join(' ')} is rejected before network or file writes`, (t) => {
    const f = fixture(t)
    f.generate()
    const guard = path.join(f.root, 'forbid-network.mjs')
    const attempted = path.join(f.root, 'network-attempted')
    f.put('forbid-network.mjs', `import fs from 'node:fs'\n`
      + `globalThis.fetch = () => { fs.writeFileSync(${JSON.stringify(attempted)}, 'attempted'); throw new Error('Unexpected fetch') }\n`)
    const result = assertReadOnlyCheck(f, false, args, ['--import', guard])
    assert.ok(!fs.existsSync(attempted), 'The invalid flag combination must not call fetch')
    assert.match(result.stderr + result.stdout, /--check/)
    assert.match(result.stderr + result.stdout, /--fetch-wiki/)
  })
}
