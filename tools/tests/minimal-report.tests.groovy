/* Shared contract of the minimal report, exercised against each endpoint's actual embedded classes. */
Class minimal = getClass().classLoader.loadClass('MinimalReport')
Class overview = getClass().classLoader.loadClass('ConfigOverview')
Class cx = getClass().classLoader.loadClass('Cx')
int checks = 0
def verify = { boolean condition, String description ->
    assert condition : description
    checks++
}
String product = binding.getVariable('product')
String other = product == 'jira' ? 'confluence' : 'jira'
String base = product == 'jira' ? 'https://jira.example/jira/' : 'https://wiki.example/confluence'
List<List<String>> columns = minimal.columns(product)
String keyLabel = columns[0][1]
String expectedHref = product == 'jira' ? 'https://jira.example/jira/projects/APL/issues' : 'https://wiki.example/confluence/display/APL/'

Map item = [key: [value: 'APL'], name: [value: 'ART & Plattform'], lead: [value: 'Pius H\u00f6ltschi'],
            admins: [value: ['jira-admins', 'Daniel Tobler']], category: [value: 'SWEP-Streams'],
            issues: [value: '7226'], lastUpdate: [value: '30.10.2026'],
            description: [value: 'Bereich f\u00fcr ART <Plattform>'], pages: [value: '412'],
            apps: [value: ['Draw.io', 'Gliffy']], appMacros: [value: '3', state: 'partial'],
            userMacros: [state: 'notMeasured', note: 'the content index could not be read']]

/* ---- Page rendering -------------------------------------------------------- */
Map first = minimal.render(product, base, item, [:], [], '2026-11-03 10:00', '0.6')
String page = first.storage
verify(page.contains(minimal.marker(product)), 'the page carries the minimal marker')
verify(!page.contains(minimal.marker(other)), 'the page does not carry the other product\'s marker')
verify(page.contains('<a href="' + expectedHref + '">APL</a>'), 'the key links to the project or space itself')
verify(page.contains('ART &amp; Plattform'), 'values are escaped')
columns.each { verify(page.contains('<th>' + cx.esc(it[1]) + '</th>'), 'the page carries the generated column ' + it[1]) }
if (product == 'jira') {
    verify(page.contains('jira-admins, Daniel Tobler'), 'administrators are listed as users and groups')
} else {
    verify(page.contains('3 *'), 'a lower bound carries its asterisk')
    verify(page.contains('not measured: the content index could not be read'), 'an unmeasured value says so and is not zero')
    verify(page.contains('Draw.io, Gliffy'), 'used apps are listed')
}
verify(first.sections.every { it.key != minimal.COLUMN + 'name' }, 'the name is the overview identity column, not a value column')
verify(first.topics == [], 'no topic is invented')
verify(minimal.title(product, 'APL') == (product == 'jira' ? 'Jira project overview - APL' : 'Confluence space overview - APL'),
    'the default title differs from the full export title')
verify(minimal.cell(product, base, 'lead', [state: 'failed', note: 'NPE']) == '<em>unreadable: NPE</em>', 'a failed read is stated, never empty')
verify(minimal.cell(product, base, 'lead', [state: 'absent']) == '', 'an absent value is an empty cell')
verify(minimal.cell(product, base, 'key', [value: 'A B']).contains('A%20B'), 'the key is encoded into the link')

/* ---- Reading an own page back ----------------------------------------------- */
Map read = minimal.parse(page, product)
verify(read.ok == true && read.topics.isEmpty(), 'an own page without topics reads back')

/* An administrator adds two topic columns in the editor: the editor stamps macro ids and wraps cells. */
String lozenge = '<ac:structured-macro ac:name="status" ac:schema-version="1" ac:macro-id="m-1"><ac:parameter ac:name="colour">Green</ac:parameter><ac:parameter ac:name="title">KI</ac:parameter></ac:structured-macro>'
String edited = page.replace('</th></tr>', '</th><th><p><strong>Themengebiet 1</strong></p></th><th>Themengebiet 2</th><th><br/></th></tr>')
    .replace('</td></tr>', '</td><td><p>' + lozenge + ' Governance</p></td><td><p><br/></p></td><td></td></tr>')
read = minimal.parse(edited, product)
verify(read.ok == true, 'an edited page reads back: ' + read.reason)
verify(read.topics.keySet() as List == ['Themengebiet 1', 'Themengebiet 2'], 'topics are read in page order, an empty unnamed column is dropped')
verify(read.topics['Themengebiet 1'] == '<p>' + lozenge + ' Governance</p>', 'a topic cell is carried verbatim, lozenge included')
verify(read.topics['Themengebiet 2'] == '', 'an editor-empty topic cell is empty')

Map item2 = new LinkedHashMap(item)
item2.issues = [value: '7300']
item2.category = [value: 'Neue Kategorie']
Map second = minimal.render(product, base, item2, read.topics, [], '2026-11-04 10:00', '0.6')
verify(second.storage.contains('<p>' + lozenge + ' Governance</p>'), 'the next export keeps the topic')
verify(second.topics == ['Themengebiet 1', 'Themengebiet 2'], 'the next export keeps the topic set')
verify(second.storage.contains('Neue Kategorie') && !second.storage.contains('SWEP-Streams'), 'generated values are rewritten')
verify(minimal.parse(second.storage, product).topics == read.topics, 'a rewritten page reads back to the same topics')

/* ---- One topic set --------------------------------------------------------- */
Map seeded = minimal.render(product, base, item, [:], ['Themengebiet 1', 'Themengebiet 2', 'Security'], 'now', '0.6')
verify(seeded.topics == ['Themengebiet 1', 'Themengebiet 2', 'Security'], 'a page without topics is seeded with the known set')
verify(seeded.storage.contains('<th>Security</th>'), 'a seeded topic is a column on the page')
Map mergedCase = minimal.render(product, base, item, ['themengebiet 1': 'X', 'Neu': 'Y'], ['Themengebiet 1'], 'now', '0.6')
verify(mergedCase.topics == ['Themengebiet 1', 'Neu'], 'topics match case-insensitively; a new page topic is appended')
verify(mergedCase.sections.find { it.key == minimal.TOPIC + 'Themengebiet 1' }.html == 'X', 'the page value fills the known topic')

/* ---- Fail closed ----------------------------------------------------------- */
def refusedBy = { String storage -> Map result = minimal.parse(storage, product); result.ok == false && result.reason }
verify(refusedBy(''), 'an empty page is refused')
verify(refusedBy(page.replace(minimal.marker(product), 'something else')), 'a page without the marker is refused')
verify(refusedBy(page.replace('<th>' + cx.esc(columns[2][1]) + '</th>', '<th>Renamed</th>')), 'a missing generated column is refused')
verify(refusedBy(edited.replace('<th>Themengebiet 2</th>', '<th>THEMENGEBIET 1</th>')), 'a repeated topic is refused')
verify(refusedBy(edited.replace('<th><br/></th>', '<th></th>').replace('<td></td></tr>', '<td>text</td></tr>')), 'content without a heading is refused')
verify(refusedBy(page.replace('</tr></tbody>', '</tr><tr><td>x</td></tr></tbody>')), 'a second data row is refused')
verify(refusedBy(page.replace('<td>', '<td colspan="2">')), 'merged cells are refused')
verify(refusedBy(page.replace('</td></tr>', '</td><td><table><tbody><tr><td>n</td></tr></tbody></table></td></tr>')), 'a nested table is refused')
verify(refusedBy(page + page), 'two generated tables are refused')
verify(refusedBy(page.replace('</td></tr>', '</td><td>extra</td></tr>')), 'a cell without its heading is refused')
verify(minimal.parse(page.replace('<tr><td>', '<tr><td><p>').replace('</td><td>', '</p></td><td>'), product).ok == true,
    'editor paragraphs in generated cells do not refuse the page')
verify(cx.parseRemarks(page).outcome == 'failed', 'the full export never adopts a minimal page')
verify(minimal.parse(page.replace(minimal.marker(product), cx.MARKER), product).ok == false, 'the minimal export never adopts a full page')

/* ---- Overview summary ------------------------------------------------------ */
String ov = minimal.overviewProduct(product)
String parent = '<p>KEEP intro</p>'
String one = overview.upsert(parent, ov, '501', minimal.title(product, 'APL'), 'DOC', 'ART & Plattform', second.sections)
verify(one.startsWith(parent), 'content outside the summary is kept')
verify(one.contains(cx.esc(minimal.OVERVIEW_NOTE)), 'the summary says it is generated and overwritten')
verify(overview.confirmed(one, ov, '501', minimal.title(product, 'APL'), 'DOC', 'ART & Plattform', second.sections), 'the summary row is confirmed')
Map model = overview.read(one, ov, 'DOC')
verify(model.columns.collect { it.key } == (columns.findAll { it[0] != 'name' }.collect { minimal.COLUMN + it[0] } +
    [minimal.TOPIC + 'Themengebiet 1', minimal.TOPIC + 'Themengebiet 2']), 'the summary carries the page columns and topics')
verify(model.rows[0][2 + model.columns.findIndexOf { it.key == minimal.TOPIC + 'Themengebiet 1' } - 1].contains('Governance'),
    'the summary shows the topic value of the page')
verify(minimal.knownTopics(one, product, 'DOC') == ['Themengebiet 1', 'Themengebiet 2'], 'the known topic set is read from the summary')
verify(minimal.knownTopics(parent, product, 'DOC') == [], 'a parent without a summary knows no topic')
verify(!overview.confirmed(one, ov, '501', minimal.title(product, 'APL'), 'DOC', 'ART & Plattform', first.sections),
    'a row with other values is not confirmed')
Map otherItem = new LinkedHashMap(item); otherItem.key = [value: 'OLD']; otherItem.name = [value: 'Old one']
Map third = minimal.render(product, base, otherItem, [:], minimal.knownTopics(one, product, 'DOC'), 'now', '0.6')
String two = overview.upsert(one, ov, '502', minimal.title(product, 'OLD'), 'DOC', 'Old one', third.sections)
verify(overview.read(two, ov, 'DOC').rows.size() == 2, 'a second object adds a row')
verify(overview.read(two, ov, 'DOC').rows[0] == overview.read(one, ov, 'DOC').rows[0], 'the other row stays as it was')
String edit = two.replace('<ac:structured-macro ', '<ac:structured-macro ac:macro-id="x" ')
verify(overview.read(edit, ov, 'DOC').rows.size() == 2, 'editor macro ids keep the summary readable')

/* Coexistence with the full overview of the same product on the same parent. */
String full = overview.upsert(two, product, '601', 'Full export', 'DOC', 'Full', [[key: 'details', column: 'Details', label: 'Details', anchor: 'a1', count: '1 item']])
verify(overview.read(full, product, 'DOC').rows.size() == 1, 'the full overview is its own table')
verify(overview.read(full, ov, 'DOC').rows.size() == 2, 'the summary survives next to the full overview')
verify(overview.read(full.replace('<p>KEEP intro</p>', ''), ov, 'DOC').rows.size() == 2, 'the summary does not depend on surrounding text')
boolean adopted = false
try { overview.read(page, ov, 'DOC') } catch (IllegalArgumentException expected) { adopted = true }
verify(adopted, 'a minimal object page is never used as an overview')

/* ---- Objects that no longer exist ------------------------------------------ */
String marked = minimal.markGone(two, product, 'DOC', ['APL'] as Set)
verify(marked.contains(minimal.GONE) && overview.read(marked, ov, 'DOC').rows.size() == 2, 'a missing object keeps its row and is marked')
verify(overview.read(marked, ov, 'DOC').rows[0] == overview.read(two, ov, 'DOC').rows[0], 'an existing object is not touched')
verify(minimal.markGone(marked, product, 'DOC', ['APL'] as Set) == marked, 'marking is idempotent')
verify(!minimal.markGone(marked, product, 'DOC', ['APL', 'OLD'] as Set).contains(minimal.GONE), 'an object that exists again is unmarked')
verify(minimal.markGone(two, product, 'DOC', null) == two, 'without a complete key list nothing is marked')
verify(minimal.markGone(marked.replace('<ac:structured-macro ', '<ac:structured-macro ac:macro-id="y" '), product, 'DOC', ['APL', 'OLD'] as Set)
    .indexOf(minimal.GONE) < 0, 'an editor-stamped mark is still recognised')

/* ---- Jira collectors, against stubs of the platform types they name ---------- */
if (product == 'jira') {
    File repo = new File(System.getProperty('repoRoot', '.'))
    String source = new File(repo, 'jira/jiraDCprojectConfig.groovy').getText('UTF-8')
    String functions = source.substring(source.indexOf('Map<String, Object> minimalRead(Closure body) {'),
        source.indexOf('/* GET ?report=minimal. With project= one project'))
    def collectors = new GroovyShell(getClass().classLoader).evaluate("""
import org.codehaus.groovy.runtime.InvokerHelper
class ApplicationUser { String displayName; String getDisplayName() { displayName } }
class ProjectCategory { String name; String getName() { name } }
class Project {
    String key; String name; Long id; ApplicationUser lead; ProjectCategory category
    String getKey() { key }; String getName() { name }; Long getId() { id }
    ApplicationUser getProjectLead() { lead }; ProjectCategory getProjectCategoryObject() { category }
}
class ProjectRole {}
class RoleActor { String descriptor; String getDescriptor() { descriptor } }
class ProjectRoleActors { Set<RoleActor> actors; Set<RoleActor> getRoleActors() { actors } }
class ProjectRoleManager { Set<RoleActor> actors; ProjectRoleActors getProjectRoleActors(ProjectRole role, Project project) { new ProjectRoleActors(actors: actors) } }
class ProjectManager { List<Project> active; List<Project> archived; List<Project> getProjectObjects() { active }; List<Project> getArchivedProjects() { archived } }
class PagerFilter { int max; PagerFilter(int max) { this.max = max } }
""" + functions + '\nreturn this')
    Class loader = collectors.class
    def type = { String name -> collectors.class.classLoader.loadClass(name) }
    def user = type('ApplicationUser').newInstance(displayName: 'Pius H\u00f6ltschi')
    def project = type('Project').newInstance(key: 'APL', name: 'ART Plattform', id: 10L, lead: user,
        category: type('ProjectCategory').newInstance(name: 'SWEP-Streams'))
    def actors = [type('RoleActor').newInstance(descriptor: 'jira-admins'), type('RoleActor').newInstance(descriptor: 'Daniel Tobler')] as Set
    def issue = new Expando(getUpdated: { -> new java.sql.Timestamp(Date.parse('yyyy-MM-dd', '2026-10-30').time) })
    def jql = []
    def search = new Expando(parseQuery: { u, String q -> jql << q; [valid: true, query: 'Q'] },
        searchOverrideSecurity: { u, q, pager -> assert pager.max == 1; new Expando(getResults: { -> [issue] }) })
    Map context = [roleManager: type('ProjectRoleManager').newInstance(actors: actors), adminRole: type('ProjectRole').newInstance(),
                   roleProblem: null, issueManager: new Expando(getIssueCountForProject: { Long id -> id == 10L ? 7226L : -1L }),
                   searchService: search, user: user]
    Map row = collectors.minimalRow(project, context)
    verify(row.key.value == 'APL' && row.name.value == 'ART Plattform', 'key and name are read')
    verify(row.lead.value == 'Pius H\u00f6ltschi', 'the lead is read by display name')
    verify(row.admins.value == ['Daniel Tobler', 'jira-admins'], 'role actors are users and groups, sorted, not expanded')
    verify(row.category.value == 'SWEP-Streams', 'the category is read')
    verify(row.issues.value == '7226', 'the issue count comes from the IssueManager call')
    verify(row.lastUpdate.value == '30.10.2026' && row.lastUpdate.state == null, 'the latest update is one search, formatted as in the requirement')
    verify(jql == ['project = "APL" ORDER BY updated DESC'], 'the search names the project and sorts by update')
    search.searchOverrideSecurity = { u, q, pager -> throw new MissingMethodException('searchOverrideSecurity', Object, [] as Object[]) }
    search.search = { u, q, pager -> new Expando(getResults: { -> [issue] }) }
    verify(collectors.minimalLastUpdate('APL', context).state == 'partial', 'a search under the administrator\'s security is a lower bound')
    search.search = { u, q, pager -> new Expando(getResults: { -> [] }) }
    verify(collectors.minimalLastUpdate('APL', context).state == 'absent', 'a project without issues has no latest update')
    context.searchService = null
    verify(collectors.minimalRow(project, context).lastUpdate.state == 'failed', 'a missing search service is unreadable, not empty')
    context.adminRole = null; context.roleProblem = 'This instance has no project role named Administrators.'
    context.issueManager = new Expando(getIssueCountForProject: { Long id -> throw new IllegalStateException('db down') })
    Map failed = collectors.minimalRow(project, context)
    verify(failed.admins.state == 'failed' && failed.admins.note.contains('Administrators'), 'a missing Administrators role is stated')
    verify(failed.issues.state == 'failed' && failed.issues.note.contains('db down'), 'a failed count is unreadable, never zero')
    project.lead = null; project.category = null
    Map empty = collectors.minimalRow(project, context)
    verify(empty.lead.state == 'absent' && empty.category.state == 'absent', 'an unset lead or category is empty, not failed')
    def projects = type('ProjectManager').newInstance(active: [project], archived: [type('Project').newInstance(key: 'ARC')])
    verify(collectors.minimalExistingKeys(projects) == ['APL', 'ARC'] as Set, 'archived projects count as existing')
    verify(collectors.minimalExistingKeys(type('ProjectManager').newInstance(active: [project], archived: null)) == null,
        'an incomplete project list marks nothing')
}

/* ---- Confluence collectors, against stubs of the platform types they name ------ */
if (product == 'confluence') {
    File repo = new File(System.getProperty('repoRoot', '.'))
    String source = new File(repo, 'confluence/confluenceDCspaceConfig.groovy').getText('UTF-8')
    String scanSource = source.substring(source.indexOf('class MinimalScan {'),
        source.indexOf('/* =============================================================================\n * REST Endpoint'))
    GroovyClassLoader platform = new GroovyClassLoader(getClass().classLoader)
    platform.parseClass('''package com.atlassian.confluence.search.v2
interface SearchQuery {}
enum Index { CONTENT }
class SearchFieldMappings {
    static final Field SPACE_KEY = new Field(name: 'spacekey')
    static class Field { String name; String getName() { name } }
}
class SearchManager {
    Map<String, List<String>> hits = [:]
    Set<String> broken = [] as Set
    List<String> asked = []
    void scan(List indexes, SearchQuery query, Set<String> fields, java.util.function.Consumer<Map<String, String[]>> consumer) {
        String name = query.name
        asked << name
        if (broken.contains(name)) { throw new IllegalStateException('index down') }
        for (String space : hits.get(name) ?: []) { consumer.accept([spacekey: [space] as String[]]) }
    }
}
''')
    platform.parseClass('''package com.atlassian.confluence.search.v2.query
class MacroUsageQuery implements com.atlassian.confluence.search.v2.SearchQuery { String name; MacroUsageQuery(String name) { this.name = name } }
''')
    platform.parseClass('''package com.atlassian.confluence.macro.browser
interface MacroMetadataSource { Object getMacroMetadata() }
class MacroMetadataManager { List entries = []; Collection getAllMacroMetadata() { entries } }
''')
    platform.parseClass('''package com.atlassian.plugin
class PluginAccessor { List plugins = []; Collection getPlugins() { plugins } }
''')
    platform.parseClass('''package com.atlassian.plugin.metadata
class PluginMetadataManager { Set system = [] as Set; boolean isSystemProvided(Object plugin) { system.contains(plugin.key) } }
''')
    platform.parseClass('''package com.atlassian.confluence.renderer
class UserMacroLibrary { Map macros = [:]; Map getMacros() { macros } }
''')
    Class scanClass = platform.parseClass('''
import groovy.json.JsonOutput
import org.codehaus.groovy.runtime.InvokerHelper
import java.sql.Connection
import com.atlassian.confluence.search.v2.Index
import com.atlassian.confluence.search.v2.SearchFieldMappings
import com.atlassian.confluence.search.v2.SearchManager
import com.atlassian.confluence.search.v2.SearchQuery
class ComponentLocator { static Map<Class, Object> components = [:]; static Object getComponent(Class type) { components.get(type) } }
class Cw { static String firstIndexValue(Map<String, String[]> document, String field) { String[] v = document?.get(field); v ? v[0] : null } }
class Scan { static final String CATEGORY_NAMESPACE = 'team'; static final String ADMIN_PERMISSION = 'SETSPACEPERMISSIONS' }
class Db {
    static Map<String, List<Map<String, String>>> answers = [:]
    static Set<String> failing = [] as Set
    static String why(Throwable error) { error.message }
    static Map<String, Object> shape(Object connection, String table, List<String> columns) { [table: table] }
    static String shapeProblem(Map<String, Object> shape) { failing.contains(shape.table) ? 'the table ' + shape.table + ' moved' : null }
    static Map<String, Object> query(Object connection, String sql, List<String> arguments, List<String> columns, int cap) {
        String hit = answers.keySet().find { sql.contains(it) }
        [rows: hit == null ? [] : answers.get(hit), truncated: false, failure: null]
    }
}
''' + scanSource, 'MinimalScanUnderTest.groovy')
    scanClass = platform.loadClass('MinimalScan')
    def type = { String name -> platform.loadClass(name) }
    Class db = platform.loadClass('Db')
    java.sql.Connection connection = [:] as java.sql.Connection
    Class locator = platform.loadClass('ComponentLocator')
    db.answers = [
        'SELECT s.spacekey, s.spacename FROM spaces s ORDER BY': [[spacekey: 'APL', spacename: 'ART Plattform'], [spacekey: 'KIGOV', spacename: 'KI Governance']],
        'JOIN bodycontent': [[spacekey: 'APL', body: '<p>Bereich f&uuml;r ART Plattform</p><p>Bestellt durch Daniel Tobler TOOLS-3169</p>']],
        'JOIN content_label': [[spacekey: 'APL', name: 'safe'], [spacekey: 'APL', name: 'art']],
        'FROM spacepermissions': [[spacekey: 'APL', permgroupname: 'confluence-administrators'],
                                  [spacekey: 'APL', permusername: 'k1', username: 'dtobler', userdisplay: 'Daniel Tobler'],
                                  [spacekey: 'APL', permusername: 'k2', username: 'nodisplay'],
                                  [spacekey: 'KIGOV', permusername: 'k3']],
        'COUNT(c.contentid)': [[spacekey: 'APL', pages: '412']],
        'SELECT s.spacekey FROM spaces s': [[spacekey: 'APL'], [spacekey: 'KIGOV']]]
    Map estate = scanClass.rows(connection, null)
    verify(estate.failure == null && estate.rows.size() == 2 && estate.keys == ['APL', 'KIGOV'] as Set, 'every space is read in one pass')
    Map apl = estate.rows[0].minimal
    verify(apl.key.value == 'APL' && apl.name.value == 'ART Plattform', 'key and name are read')
    verify(apl.description.value.startsWith('Bereich f') && !apl.description.value.contains('<p>'), 'the description is plain text')
    verify(apl.category.value == ['art', 'safe'], 'categories are the team labels by name')
    verify(apl.admins.value == ['confluence-administrators', 'Daniel Tobler', 'nodisplay'], 'administrators are groups and users, display name first')
    verify(apl.pages.value == '412', 'the page count is read')
    Map kigov = estate.rows[1].minimal
    verify(kigov.pages.value == '0' && kigov.category.state == 'absent' && kigov.admins.value == ['k3'], 'a space without pages or categories is a measured zero')
    verify(scanClass.rows(connection, 'kigov').rows*.space.key == ['KIGOV'], 'one space can be read on its own')
    db.failing = ['cwd_user'] as Set
    Map partial = scanClass.rows(connection, null)
    verify(partial.rows[0].minimal.admins.state == 'failed' && partial.rows[0].minimal.pages.value == '412', 'a moved table costs its column only')
    db.failing = ['spaces'] as Set
    verify(scanClass.rows(connection, null).failure != null && scanClass.existingKeys(connection) == null, 'an unreadable space list is a failure, not an empty estate')
    db.failing = [] as Set
    verify(scanClass.existingKeys(connection) == ['APL', 'KIGOV'] as Set, 'the existing keys are every space key')

    def search = type('com.atlassian.confluence.search.v2.SearchManager').newInstance()
    search.hits = [drawio: ['APL', 'KIGOV'], 'drawio-sketch': ['APL'], gliffy: ['APL'], 'toc': ['APL'], 'my-macro': ['KIGOV']]
    Class sourceType = type('com.atlassian.confluence.macro.browser.MacroMetadataSource')
    def descriptor = { String name -> [getMacroMetadata: { -> new Expando(getMacroName: { -> name }) }].asType(sourceType) }
    def plugins = [new Expando(key: 'com.drawio', getKey: { -> 'com.drawio' }, getName: { -> 'Draw.io' }, getModuleDescriptors: { -> [descriptor('drawio'), new Object()] }),
                   new Expando(key: 'com.gliffy', getKey: { -> 'com.gliffy' }, getName: { -> 'Gliffy' }, getModuleDescriptors: { -> [descriptor('gliffy')] }),
                   new Expando(key: 'confluence.macros.advanced', getKey: { -> 'confluence.macros.advanced' }, getName: { -> 'Advanced Macros' }, getModuleDescriptors: { -> [descriptor('toc')] })]
    def pluginMetadata = type('com.atlassian.plugin.metadata.PluginMetadataManager').newInstance(system: ['confluence.macros.advanced'] as Set)
    def catalogue = type('com.atlassian.confluence.macro.browser.MacroMetadataManager').newInstance(
        entries: [new Expando(getMacroName: { -> 'drawio-sketch' }, getPluginKey: { -> 'com.drawio' })])
    def library = type('com.atlassian.confluence.renderer.UserMacroLibrary').newInstance(macros: ['my-macro': new Expando(getName: { -> 'my-macro' }), unused: null])
    locator.components = [(type('com.atlassian.confluence.search.v2.SearchManager')): search,
        (type('com.atlassian.plugin.PluginAccessor')): type('com.atlassian.plugin.PluginAccessor').newInstance(plugins: plugins),
        (type('com.atlassian.plugin.metadata.PluginMetadataManager')): pluginMetadata,
        (type('com.atlassian.confluence.macro.browser.MacroMetadataManager')): catalogue,
        (type('com.atlassian.confluence.renderer.UserMacroLibrary')): library]
    Map macros = scanClass.macros(0L)
    verify(macros.state == 'measured' && macros.note == null, 'a complete scan is measured: ' + macros.note)
    verify(!search.asked.contains('toc'), 'macros of system-provided apps are not scanned')
    verify(search.asked.containsAll(['drawio', 'drawio-sketch', 'gliffy', 'my-macro', 'unused']), 'descriptor, catalogue and user macro names are scanned')
    verify(macros.apps.APL as List == ['Draw.io', 'Gliffy'] && macros.apps.KIGOV as List == ['Draw.io'], 'used apps per space')
    verify(macros.appMacros.APL.size() == 3 && macros.appMacros.KIGOV.size() == 1, 'distinct app macros per space')
    verify(macros.userMacros.KIGOV as List == ['my-macro'] && macros.userMacros.APL == null, 'distinct user macros per space')
    Map values = [:]
    scanClass.addMacros(values, 'APL', macros)
    verify(values.apps.value == ['Draw.io', 'Gliffy'] && values.appMacros.value == '3' && values.userMacros.value == '0', 'the three columns of one space')
    search.broken = ['gliffy'] as Set
    Map broken = scanClass.macros(0L)
    verify(broken.state == 'partial' && broken.note.contains('1 macro name(s) whose scan failed'), 'a failed name makes the figures a lower bound')
    Map lower = [:]
    scanClass.addMacros(lower, 'KIGOV', broken)
    verify(MinimalReport.cell('confluence', null, 'appMacros', lower.appMacros) == '1 *', 'a lower bound is shown with its asterisk')
    Map none = [:]
    scanClass.addMacros(none, 'APL', [state: 'failed', note: 'this Confluence offers no macro usage query'])
    verify(none.apps.state == 'notMeasured' && MinimalReport.cell('confluence', null, 'userMacros', none.userMacros).contains('not measured'),
        'a scan that cannot run is not measured, never zero')
    locator.components.remove(type('com.atlassian.confluence.search.v2.SearchManager'))
    verify(scanClass.macros(0L).state == 'failed', 'without a search manager nothing is measured')
}

println 'MINIMAL ' + product + ': ' + checks + ' checks passed'
