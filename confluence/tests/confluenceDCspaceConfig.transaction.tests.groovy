import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer
import groovy.transform.TypeChecked

File repo = new File(System.getProperty('repoRoot', '.'))
String source = new File(repo, 'confluence/confluenceDCspaceConfig.groovy').getText('UTF-8')
String imports = '''
import groovy.json.JsonOutput
import org.codehaus.groovy.runtime.InvokerHelper
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import java.util.regex.Matcher
import java.lang.reflect.Method
'''
int start = source.indexOf('class Pc {')
int banner = source.indexOf(' * END OF THE CONFLUENCE-FREE BLOCK', start)
assert start >= 0 && banner > start
GroovyClassLoader loader = new GroovyClassLoader(getClass().classLoader)
loader.parseClass(imports + source.substring(start, source.lastIndexOf('/*', banner)), 'ActualHelpers.groovy')
// SAL's documented public signatures; no dependency on a particular Confluence jar.
loader.parseClass('''package com.atlassian.sal.api.transaction
interface TransactionCallback<T> { T doInTransaction() }
interface TransactionTemplate { <T> T execute(TransactionCallback<T> action) }
''')
loader.parseClass(new File(repo, 'confluence/tests/fixtures/page-export-platform.groovy'))
// The values and the macro scan of the minimal report, as the real MinimalScan returns them.
loader.parseClass('''
class MinimalScan {
    static final long MACRO_BUDGET_MS = 1L
    static Map estate = [failure: null, rows: [], keys: [] as Set]
    static Map rows(Object connection, String onlyKey) { estate }
    static Map macros(long budget) { [state: 'measured', note: null, apps: [:], appMacros: [:], userMacros: [:]] }
    static void addMacros(Map values, String key, Map scan) {
        values.put('apps', [state: 'absent']); values.put('appMacros', [value: '0']); values.put('userMacros', [value: '0'])
    }
}
''')
int txStart = source.indexOf('class PageExportRejected ')
assert txStart >= 0 : 'Transaction helper start marker missing'
int txEnd = source.indexOf('/* ---- End page export transaction ---- */', txStart)
assert txEnd > txStart : 'Transaction helper end marker missing'
String helpers = source.substring(txStart, txEnd)
// New adapter is statically checked against SAL/ComponentLocator contract stubs.
CompilerConfiguration config = new CompilerConfiguration()
config.addCompilationCustomizers(new ASTTransformationCustomizer(TypeChecked))
new GroovyClassLoader(loader, config).parseClass(imports + helpers, 'CheckedTransaction.groovy')
loader.parseClass(imports + helpers, 'ActualTransaction.groovy')
String branch = source.substring(source.indexOf('    /* ---- Page export: validate'))
assert branch.trim().endsWith('}')
branch = branch.substring(0, branch.lastIndexOf('}'))
loader.parseClass(imports + '''
class ActualExportBranch {
    Object answer(Map response) { new TestResponse(response) }
    Object refuse(int status, String stage, String error) {
        new TestResponse([ok: false, written: false, status: status, stage: stage, error: error])
    }
    Object export(Map<String, Object> request) {
        long started = System.currentTimeMillis()
        Class responseClass = Object
''' + branch + '\n} }', 'ActualExportBranch.groovy')
// SAL's documented settings factory, so the export log of the actual branch can be
// exercised against a store; without a component behind it the log reports failure.
loader.parseClass('''package com.atlassian.sal.api.pluginsettings
interface PluginSettingsFactory { Object createGlobalSettings() }
''')
new GroovyShell(loader).evaluate('''
def payload = { -> [spaceKey: 'DOC', title: 'Config export Alpha', parentPageId: '', parentTitle: 'Config overview',
    space: [key: 'ALPHA', name: 'Alpha'], sections: [[kind: 'details', label: 'Details', children:
        [[kind: 'field', label: 'Status', value: 'Current', children: []]]]]] }
Class templateType = Class.forName('com.atlassian.sal.api.transaction.TransactionTemplate')
def wire = { Store store ->
    Object template = [execute: { callback -> store.transaction { callback.doInTransaction() } }].asType(templateType)
    ComponentLocator.components = [(PageManager): new PageManager(store: store),
        (PageService): new PageService(store: store), (SpaceService): new SpaceService(), (templateType): template]
}
def handler = new ActualExportBranch()
Store store = new Store()
wire(store)
Map first = handler.export(payload())
assert first.ok && first.written && first.parentAction == 'created' : first
assert first.overview.state == 'updated' : first
assert first.parentApplied == 'true' : first
assert store.commits == 1 && store.writes.every { it.transaction } : store.writes
assert ConfigOverview.read(store.byId(first.parentPageId as long).body, 'confluence', 'DOC').rows.size() == 1
store.nextRequest()
Map again = payload()
again.parentPageId = first.parentPageId
again.parentTitle = ''
Map second = handler.export(again)
assert second.ok && second.parentAction == 'found' && second.overview.state == 'updated' : second
assert store.pages.size() == 2
assert ConfigOverview.read(store.byId(first.parentPageId as long).body, 'confluence', 'DOC').rows.size() == 1
store.nextRequest()
Map another = payload()
another.title = 'Config export Beta'
another.space = [key: 'BETA', name: 'Beta']
another.parentPageId = first.parentPageId
another.parentTitle = ''
assert handler.export(another).overview.state == 'updated'
assert ConfigOverview.read(store.byId(first.parentPageId as long).body, 'confluence', 'DOC').rows.size() == 2
println 'PASS: first-run table, repeat upsert, second export and parent relationship'

/* OP-1488: the overview rows are gone, the detail pages are not. One save in the
 * transaction restores both rows; a page without the marker and a trashed export
 * are skipped with a reason, and a second rebuild writes nothing. */
long overviewId = first.parentPageId as long
store.save(new Page(version: 1, space: new Space(key: 'DOC'), title: 'Manual notes', body: '<p>Manual notes</p>'))
store.save(new Page(version: 1, space: new Space(key: 'DOC'), title: 'Config export Old', current: false,
    body: store.pages.values().find { it.title == 'Config export Alpha' }.body))
store.pages[overviewId].children = store.pages.values().findAll { it.id != overviewId }.toList()
store.pages[overviewId].body = Cx.PARENT_BODY
store.nextRequest()
int writesBefore = store.writes.size()
int commitsBefore = store.commits
Map rebuilt = handler.export([action: 'rebuild-overview', spaceKey: 'DOC', parentPageId: first.parentPageId])
assert rebuilt.ok && rebuilt.overview.state == 'updated' && rebuilt.complete == true && rebuilt.children == 4 : rebuilt
assert rebuilt.counts == [added: 2, present: 0, skipped: 2, unread: 0, unconfirmed: 0] : rebuilt
assert rebuilt.pages.find { it.title == 'Manual notes' }.reason.contains('export marker') : rebuilt
assert rebuilt.pages.find { it.title == 'Config export Old' }.reason.contains('not current') : rebuilt
assert store.writes.size() == writesBefore + 1 && store.writes.last().overview && store.writes.last().transaction : store.writes
assert store.commits == commitsBefore + 1
assert ConfigOverview.read(store.pages[overviewId].body, 'confluence', 'DOC').rows.size() == 2
store.nextRequest()
Map unchanged = handler.export([action: 'rebuild-overview', spaceKey: 'DOC', parentPageId: first.parentPageId])
assert unchanged.ok && unchanged.overview.state == 'unchanged' && unchanged.counts.present == 2 : unchanged
assert store.writes.size() == writesBefore + 1
println 'PASS: rebuild restores missing overview rows in one transactional save and then writes nothing'

Map<Long, String> committedBodies = store.pages.collectEntries { id, page -> [(id): page.body] }
store.nextRequest()
store.failCommit = true
Map failedUpdate = handler.export(again)
assert !failedUpdate.ok && !failedUpdate.written : failedUpdate
assert store.pages.collectEntries { id, page -> [(id): page.body] } == committedBodies
println 'PASS: failed commit also preserves previously committed pages'

for (String failure : ['commit', 'overview conflict', 'parent readback']) {
    Store failing = new Store(failCommit: failure == 'commit', conflictOnOverview: failure == 'overview conflict',
        hideNewParent: failure == 'parent readback')
    wire(failing)
    Map response = handler.export(payload())
    assert !response.ok && !response.written : [failure: failure, response: response]
    assert failing.pages.isEmpty() && failing.rollbacks == 1 : [failure: failure, pages: failing.pages]
}
println 'PASS: commit failure, genuine overview conflict and post-save refusal roll back without success'

Store topLevel = new Store()
wire(topLevel)
Map withoutParent = payload()
withoutParent.parentTitle = ''
Map topLevelResult = handler.export(withoutParent)
assert topLevelResult.ok && topLevelResult.overview.state == 'skipped' : topLevelResult
assert topLevel.pages.size() == 1 && topLevel.commits == 1
Store malformed = new Store()
wire(malformed)
Page parent = new Page(version: 1, space: new Space(key: 'DOC'), title: 'Config overview',
    body: '<p>Manual notes</p><ac:structured-macro ac:name="anchor"><ac:parameter ac:name="">cfcon-overview-confluence-start</ac:parameter></ac:structured-macro>')
malformed.save(parent)
malformed.writes.clear()
malformed.nextRequest()
Map separateProblem = handler.export(payload())
assert separateProblem.ok && separateProblem.written && separateProblem.overview.state == 'failed' : separateProblem
assert malformed.pages[parent.id].body == parent.body && malformed.pages.size() == 2
println 'PASS: no-parent export and pure overview validation retain their behavior'

Store guarded = new Store()
wire(guarded)
Page foreign = new Page(version: 1, space: new Space(key: 'DOC'), title: 'Config export Alpha', body: '<p>Manual text</p>')
guarded.save(foreign)
guarded.writes.clear()
Map refused = handler.export(payload())
assert !refused.ok && refused.stage == 'read' : refused
assert guarded.writes.isEmpty() && guarded.pages.size() == 1
assert guarded.pages[foreign.id].body == '<p>Manual text</p>'
ComponentLocator.components.remove(templateType)
Map unavailable = handler.export(payload())
assert !unavailable.ok && guarded.writes.isEmpty() : unavailable
Map invalid = payload()
invalid.parentPageId = '123'
assert handler.export(invalid).stage == 'validate'
assert guarded.writes.isEmpty()
println 'PASS: foreign detail/remarks guard, unavailable transaction and conflicting parent input'

/* The export log: written by the export itself, read back before it counts, and
 * never the reason an export fails. */
class FakeSettings {
    Map<String, Object> values = [:]
    boolean refuse = false
    Object get(String key) { values[key] }
    Object put(String key, Object value) {
        if (refuse) { throw new IllegalStateException('settings refused') }
        values[key] = value
    }
}
class FakeSettingsFactory {
    FakeSettings settings = new FakeSettings()
    Object createGlobalSettings() { settings }
}
Class settingsType = Class.forName('com.atlassian.sal.api.pluginsettings.PluginSettingsFactory', true, ComponentLocator.classLoader)
Store unlogged = new Store()
wire(unlogged)
Map noStore = handler.export(payload())
assert noStore.ok && noStore.written && noStore.exportLog.state == 'failed' : noStore
assert noStore.exportLog.reason.contains('no component') : noStore
Store logged = new Store()
wire(logged)
FakeSettingsFactory settingsFactory = new FakeSettingsFactory()
ComponentLocator.components[settingsType] = settingsFactory
Map loggedExport = handler.export(payload())
assert loggedExport.ok && loggedExport.exportLog.state == 'recorded' : loggedExport
Map logEntry = loggedExport.exportLog.entry
assert logEntry.key == 'ALPHA' && logEntry.product == 'confluence' && logEntry.spaceKey == 'DOC' : logEntry
assert logEntry.pageId == loggedExport.pageId && logEntry.title == 'Config export Alpha' : logEntry
assert logEntry.parentTitle == 'Config overview' && logEntry.parentPageId == loggedExport.parentPageId : logEntry
assert settingsFactory.settings.values.keySet() == ['cfcon.cfgexport.confluence.ALPHA'] as Set : settingsFactory.settings.values
assert ExportLog.read(settingsFactory.settings, 'confluence', ['ALPHA', 'BETA']).entries.keySet() == ['ALPHA'] as Set
settingsFactory.settings.refuse = true
logged.nextRequest()
Map refusedLog = handler.export(payload())
assert refusedLog.ok && refusedLog.written && refusedLog.exportLog.state == 'failed' : refusedLog
assert refusedLog.exportLog.reason.contains('settings refused') : refusedLog
Store rolledBack = new Store(failCommit: true)
wire(rolledBack)
FakeSettingsFactory untouchedSettings = new FakeSettingsFactory()
ComponentLocator.components[settingsType] = untouchedSettings
Map lostExport = handler.export(payload())
assert !lostExport.ok && !lostExport.written : lostExport
assert untouchedSettings.settings.values.isEmpty() : 'a rolled-back export must leave no log entry: ' + untouchedSettings.settings.values
println 'PASS: the export log is written after the commit, read back, and never fails the export'

/* The minimal report through the actual export branch: only the overview page, one save, topics kept. */
Store minimalStore = new Store()
wire(minimalStore)
ComponentLocator.components.remove(settingsType)
Db.factoryObject = new Object()
def spaceRow = { String key, String name, String pages -> [space: [key: key, name: name], minimal: [key: [value: key], name: [value: name],
    description: [value: 'About ' + name], category: [value: ['safe']], admins: [value: ['confluence-admins']], pages: [value: pages]]] }
MinimalScan.estate = [failure: null, rows: [spaceRow('ALPHA', 'Alpha', '12'), spaceRow('BETA', 'Beta', '3')], keys: ['ALPHA', 'BETA'] as Set]
Map minimalFirst = handler.export([action: 'minimal', spaceKey: 'DOC', parentPageId: '', parentTitle: 'Space overview', keys: ['ALPHA']])
assert minimalFirst.ok && minimalFirst.written && minimalFirst.parentAction == 'created' && minimalFirst.table.state == 'updated' : minimalFirst
assert minimalStore.pages.size() == 1 : 'the minimal report writes only the overview page'
assert minimalStore.commits == 1 && minimalStore.writes.every { it.transaction } : minimalStore.writes
long summaryId = minimalFirst.parentPageId as long
String summary = minimalStore.pages[summaryId].body
assert summary.contains(MinimalReport.PARENT_BODY) && MinimalReport.keys(summary, 'confluence') == ['ALPHA']
assert summary.contains('<th>Themengebiet 1</th><th>Themengebiet 2</th></tr>') : 'a new table starts with the default topics'
minimalStore.pages[summaryId].body = summary.replace('<td></td><td></td></tr>', '<td>KI-Governance</td><td></td></tr>')
minimalStore.nextRequest()
MinimalScan.estate.rows[0].minimal.pages = [value: '13']
Map minimalSecond = handler.export([action: 'minimal', spaceKey: 'DOC', parentPageId: minimalFirst.parentPageId, parentTitle: '', keys: ['ALPHA', 'BETA', 'NOPE']])
assert minimalSecond.ok && minimalSecond.parentAction == 'found' && minimalSecond.keys == ['ALPHA', 'BETA'] : minimalSecond
assert minimalSecond.missing == ['NOPE'] && minimalSecond.warnings.any { it.contains('NOPE') } : minimalSecond
summary = minimalStore.pages[summaryId].body
assert summary.contains('KI-Governance') && summary.contains('>13<') && MinimalReport.keys(summary, 'confluence') == ['ALPHA', 'BETA']
assert minimalStore.writes.count { it.id == summaryId } == 3 : 'create, first table, one save for both spaces'
minimalStore.nextRequest()
int pagesBefore = minimalStore.pages.size()
Map unknown = handler.export([action: 'minimal', spaceKey: 'DOC', parentPageId: '', parentTitle: 'Another overview', keys: ['NOPE']])
assert !unknown.ok && unknown.status == 404 && minimalStore.pages.size() == pagesBefore : 'unknown keys create no page'
Map noParent = handler.export([action: 'minimal', spaceKey: 'DOC', parentPageId: '', parentTitle: '', keys: ['ALPHA']])
assert !noParent.ok && noParent.status == 400 : 'the minimal report needs its overview page'
Store conflicting = new Store(conflictOnOverview: true)
wire(conflicting)
Map conflicted = handler.export([action: 'minimal', spaceKey: 'DOC', parentPageId: '', parentTitle: 'Space overview', keys: ['ALPHA']])
assert !conflicted.ok && conflicting.pages.isEmpty() && conflicting.rollbacks == 1 : 'a refused table save leaves no new overview page behind'
Db.factoryObject = null
wire(new Store())
assert handler.export([action: 'minimal', spaceKey: 'DOC', parentPageId: '', parentTitle: 'Space overview', keys: ['ALPHA']]).status == 500
println 'PASS: the minimal report writes only its overview table, in one save per run, and keeps the topics'
''', 'ExportLifecycleTests.groovy')
